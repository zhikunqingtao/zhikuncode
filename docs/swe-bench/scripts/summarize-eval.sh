#!/bin/bash
# 汇总 SWE-bench 评测结果并打包
set -e

REPORT_DIR="/root/logs/run_evaluation/zhikuncode_eval/qwen3.6-max-preview"
OUTPUT_DIR="/data/swe-bench/final-results"

mkdir -p "$OUTPUT_DIR"

echo "=========================================="
echo "  SWE-bench 评测结果汇总"
echo "=========================================="
echo ""

# 统计每个 instance 的 resolved 状态
echo "## 详细结果"
echo ""
printf "%-35s %-12s %s\n" "Instance ID" "Resolved" "Run Status"
printf "%-35s %-12s %s\n" "-----------" "--------" "----------"

RESOLVED_COUNT=0
UNRESOLVED_COUNT=0
ERROR_COUNT=0
RESOLVED_LIST=""
UNRESOLVED_LIST=""

for report in $(find "$REPORT_DIR" -name "report.json" | sort); do
    INSTANCE_ID=$(basename $(dirname "$report"))
    if [ -f "$report" ]; then
        RESOLVED=$(python3 -c "import json; d=json.load(open('$report'))[next(iter(json.load(open('$report'))))]; print(d.get('resolved', False))" 2>/dev/null || echo "ERROR")
        if [ "$RESOLVED" = "True" ]; then
            STATUS="✅ PASS"
            RESOLVED_COUNT=$((RESOLVED_COUNT + 1))
            RESOLVED_LIST="$RESOLVED_LIST  - $INSTANCE_ID\n"
        elif [ "$RESOLVED" = "False" ]; then
            STATUS="❌ FAIL"
            UNRESOLVED_COUNT=$((UNRESOLVED_COUNT + 1))
            UNRESOLVED_LIST="$UNRESOLVED_LIST  - $INSTANCE_ID\n"
        else
            STATUS="⚠️ ERROR"
            ERROR_COUNT=$((ERROR_COUNT + 1))
        fi
        printf "%-35s %-12s %s\n" "$INSTANCE_ID" "$RESOLVED" "$STATUS"
    fi
done

TOTAL=$((RESOLVED_COUNT + UNRESOLVED_COUNT + ERROR_COUNT))

echo ""
echo "## 统计汇总"
echo ""
echo "  总评测数:       $TOTAL"
echo "  通过 (Resolved): $RESOLVED_COUNT"
echo "  未通过:          $UNRESOLVED_COUNT"
echo "  错误:            $ERROR_COUNT"
if [ "$TOTAL" -gt 0 ]; then
    RATE=$(python3 -c "print(f'{$RESOLVED_COUNT*100/$TOTAL:.1f}')")
    echo ""
    echo "  ✨ Resolve Rate: $RATE% ($RESOLVED_COUNT/$TOTAL)"
fi

echo ""
echo "## ✅ 通过的 instance"
echo -e "$RESOLVED_LIST"
echo ""
echo "## ❌ 未通过的 instance"
echo -e "$UNRESOLVED_LIST"

# 保存汇总报告
SUMMARY_FILE="$OUTPUT_DIR/eval_summary.txt"
{
    echo "SWE-bench 评测结果汇总"
    echo "评测时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Run ID: zhikuncode_eval"
    echo "Model: qwen3.6-max-preview"
    echo "Dataset: SWE-bench Lite (15 valid patches)"
    echo ""
    echo "Resolve Rate: $RATE% ($RESOLVED_COUNT/$TOTAL)"
    echo "  通过: $RESOLVED_COUNT"
    echo "  未通过: $UNRESOLVED_COUNT"
    echo "  错误: $ERROR_COUNT"
    echo ""
    echo "## 通过的 instance:"
    echo -e "$RESOLVED_LIST"
    echo "## 未通过的 instance:"
    echo -e "$UNRESOLVED_LIST"
} > "$SUMMARY_FILE"

# 打包所有结果
echo ""
echo "## 打包评测结果..."
cp -r "$REPORT_DIR" "$OUTPUT_DIR/reports"
cd "$OUTPUT_DIR"
tar -czf /root/eval-results.tar.gz reports eval_summary.txt
echo "✅ 已打包到: /root/eval-results.tar.gz"
ls -lh /root/eval-results.tar.gz
