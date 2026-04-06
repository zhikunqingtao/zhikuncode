#!/bin/bash
# SPEC 执行流水线驱动脚本
# 用法: ./scripts/run_pipeline.sh [命令]
#
# 命令:
#   split    - 拆分 SPEC.md 为片段文件
#   gen      - 生成所有轮次的 prompt 文件
#   all      - 执行 split + gen
#   status   - 显示当前执行状态

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(dirname "$SCRIPT_DIR")"

cd "$WORKSPACE"

case "${1:-all}" in
  split)
    echo "=== Step 1: 拆分 SPEC.md ==="
    python3 scripts/split_spec.py
    echo ""
    echo "拆分完成。片段文件在 spec_sections/ 目录。"
    ;;
  gen)
    echo "=== Step 2: 生成 Prompt 文件 ==="
    python3 scripts/gen_prompts.py
    echo ""
    echo "Prompt 生成完成。文件在 prompts/ 目录。"
    echo ""
    echo "下一步："
    echo "  1. 打开 prompts/_CHECKLIST.md 查看执行清单"
    echo "  2. 从 Round 01 开始，将 prompt 文件内容复制到 Qoder 执行"
    echo "  3. 或直接对 Qoder 说: '请读取 prompts/round_01_三项目初始化.md 并执行'"
    ;;
  all)
    echo "=== SPEC 自动化流水线 ==="
    echo ""
    echo "Step 1: 拆分 SPEC.md..."
    python3 scripts/split_spec.py
    echo ""
    echo "Step 2: 生成 Prompt 文件..."
    python3 scripts/gen_prompts.py
    echo ""
    echo "========================================="
    echo "  流水线初始化完成！"
    echo "========================================="
    echo ""
    echo "产物清单："
    echo "  spec_sections/  - SPEC 片段文件 ($(find spec_sections -name '*.md' | wc -l | tr -d ' ') 个)"
    echo "  prompts/        - Prompt 文件 ($(find prompts -name 'round_*.md' | wc -l | tr -d ' ') 轮)"
    echo "  pipeline.json   - 流水线定义"
    echo ""
    echo "开始执行："
    echo "  查看清单: cat prompts/_CHECKLIST.md"
    echo "  执行 R01: 对 Qoder 说 '请读取 prompts/round_01_三项目初始化.md 并执行'"
    ;;
  status)
    echo "=== 执行状态 ==="
    if [ -f prompts/_CHECKLIST.md ]; then
      # 统计已完成和未完成
      total=$(grep -c '^\- \[' prompts/_CHECKLIST.md || true)
      done=$(grep -c '^\- \[x\]' prompts/_CHECKLIST.md || true)
      echo "总轮次: $total"
      echo "已完成: $done"
      echo "剩余:   $((total - done))"
      echo ""
      echo "未完成轮次:"
      grep '^\- \[ \]' prompts/_CHECKLIST.md | head -5
    else
      echo "尚未生成 prompt 文件。请先运行: ./scripts/run_pipeline.sh all"
    fi
    ;;
  *)
    echo "用法: $0 {split|gen|all|status}"
    exit 1
    ;;
esac
