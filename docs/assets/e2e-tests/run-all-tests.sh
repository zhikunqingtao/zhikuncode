#!/bin/bash
# ============================================================
# ZhikunCode E2E 全模块串行测试执行脚本
# 版本: v9.0 | 日期: 2026-05-06
# 用途: CI/CD 集成或本地一键执行全部 E2E 测试
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
EVIDENCE_DIR="$SCRIPT_DIR/../e2e-evidence"
LOGS_DIR="$EVIDENCE_DIR/logs"
RESULTS_FILE="$SCRIPT_DIR/test-results.json"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 结果统计
TOTAL_MODULES=0
PASSED_MODULES=0
FAILED_MODULES=0
RESULTS_JSON="[]"

# ============================================================
# 工具函数
# ============================================================

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

check_service() {
    local name="$1"
    local url="$2"
    local timeout="${3:-5}"
    if curl -sf --max-time "$timeout" "$url" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

append_result() {
    local module_id="$1"
    local module_name="$2"
    local status="$3"
    local duration="$4"
    local details="$5"
    RESULTS_JSON=$(echo "$RESULTS_JSON" | python3 -c "
import json, sys
data = json.load(sys.stdin)
data.append({
    'module': $module_id,
    'name': '$module_name',
    'status': '$status',
    'duration_ms': $duration,
    'details': '$details'
})
print(json.dumps(data))
")
}

# ============================================================
# 环境检查
# ============================================================

echo "============================================================"
echo " ZhikunCode E2E Test Suite - Full Execution"
echo " 时间: $TIMESTAMP"
echo "============================================================"
echo ""

log_info "检查三端服务状态..."

BACKEND_OK=false
PYTHON_OK=false
FRONTEND_OK=false

if check_service "Backend" "http://localhost:8080/api/health"; then
    log_pass "Backend (Java Spring Boot) - :8080 运行中"
    BACKEND_OK=true
else
    log_fail "Backend (Java Spring Boot) - :8080 未响应"
fi

if check_service "Python" "http://localhost:8000/health"; then
    log_pass "Python (FastAPI) - :8000 运行中"
    PYTHON_OK=true
else
    log_fail "Python (FastAPI) - :8000 未响应"
fi

if check_service "Frontend" "http://localhost:5173"; then
    log_pass "Frontend (Vite) - :5173 运行中"
    FRONTEND_OK=true
else
    log_fail "Frontend (Vite) - :5173 未响应"
fi

echo ""

if [[ "$BACKEND_OK" != "true" ]] || [[ "$PYTHON_OK" != "true" ]] || [[ "$FRONTEND_OK" != "true" ]]; then
    log_fail "三端服务未全部运行，请先执行 start.sh 启动服务"
    echo ""
    echo "  使用方法: cd $PROJECT_ROOT && ./start.sh"
    echo ""
    exit 1
fi

log_info "所有服务就绪，开始执行测试..."
echo ""

# 确保日志目录存在
mkdir -p "$LOGS_DIR"

# ============================================================
# 模块执行
# ============================================================

run_node_module() {
    local module_id="$1"
    local module_name="$2"
    local script_file="$3"
    local log_file="$4"

    TOTAL_MODULES=$((TOTAL_MODULES + 1))
    echo "------------------------------------------------------------"
    log_info "Module $module_id: $module_name"

    local start_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")

    if node "$SCRIPT_DIR/$script_file" > "$LOGS_DIR/$log_file" 2>&1; then
        local end_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")
        local duration=$((end_time - start_time))
        log_pass "Module $module_id 完成 (${duration}ms)"
        PASSED_MODULES=$((PASSED_MODULES + 1))
        append_result "$module_id" "$module_name" "PASS" "$duration" ""
    else
        local end_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")
        local duration=$((end_time - start_time))
        log_fail "Module $module_id 失败 (${duration}ms) - 详见 $LOGS_DIR/$log_file"
        FAILED_MODULES=$((FAILED_MODULES + 1))
        append_result "$module_id" "$module_name" "FAIL" "$duration" "See $log_file"
    fi
}

run_shell_module() {
    local module_id="$1"
    local module_name="$2"
    local script_file="$3"
    local log_file="$4"

    TOTAL_MODULES=$((TOTAL_MODULES + 1))
    echo "------------------------------------------------------------"
    log_info "Module $module_id: $module_name"

    local start_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")

    if bash "$SCRIPT_DIR/$script_file" > "$LOGS_DIR/$log_file" 2>&1; then
        local end_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")
        local duration=$((end_time - start_time))
        log_pass "Module $module_id 完成 (${duration}ms)"
        PASSED_MODULES=$((PASSED_MODULES + 1))
        append_result "$module_id" "$module_name" "PASS" "$duration" ""
    else
        local end_time=$(date +%s%3N 2>/dev/null || python3 -c "import time; print(int(time.time()*1000))")
        local duration=$((end_time - start_time))
        log_fail "Module $module_id 失败 (${duration}ms) - 详见 $LOGS_DIR/$log_file"
        FAILED_MODULES=$((FAILED_MODULES + 1))
        append_result "$module_id" "$module_name" "FAIL" "$duration" "See $log_file"
    fi
}

# --- 串行执行所有模块 ---

run_node_module 2 "REST API 基础功能 (33端点)" \
    "module02-rest-api-test.mjs" "module02-rest-api.log"

run_node_module 3 "WebSocket STOMP 通信 (8场景)" \
    "module03-websocket-test.mjs" "module03-websocket.log"

run_node_module "4-6" "Agent/工具/权限 (25用例)" \
    "module04-06-agent-tool-permission-test.mjs" "module04-06-agent-tool-permission.log"

run_node_module "7-10" "LLM/记忆/技能/插件/MCP (32用例)" \
    "module07-10-llm-memory-skill-plugin-test.mjs" "module07-10-llm-memory-skill-plugin.log"

run_node_module 11 "多Agent协作 (6用例)" \
    "module11-multi-agent-test.mjs" "module11-multi-agent.log"

run_node_module 12 "Python 服务 (15用例)" \
    "module12-python-service-test.mjs" "module12-python-service.log"

run_node_module 14 "文件历史与补充API (11用例)" \
    "module14-file-extra-test.mjs" "module14-file-extra.log"

run_node_module 15 "CLI aica (11用例)" \
    "module15-cli-test.mjs" "module15-cli.log"

run_node_module 16 "可视化功能 (19用例)" \
    "module16-visualization-test.mjs" "module16-visualization.log"

run_node_module "17-21" "高级可视化 (68用例)" \
    "module17-21-advanced-vis-test.mjs" "module17-21-advanced-vis.log"

run_shell_module 22 "单元测试体系 (pytest+vitest+junit+playwright)" \
    "module22-unit-tests.sh" "module22-unit-tests.log"

# ============================================================
# 结果汇总
# ============================================================

echo ""
echo "============================================================"
echo " 测试执行完毕"
echo "============================================================"
echo ""
echo "  总模块数: $TOTAL_MODULES"
echo -e "  ${GREEN}通过: $PASSED_MODULES${NC}"
if [[ $FAILED_MODULES -gt 0 ]]; then
    echo -e "  ${RED}失败: $FAILED_MODULES${NC}"
fi
echo ""
echo "  日志目录: $LOGS_DIR"
echo "  结果文件: $RESULTS_FILE"
echo ""

# 生成 JSON 结果
cat > "$RESULTS_FILE" << EOF
{
  "timestamp": "$TIMESTAMP",
  "version": "v9.0",
  "summary": {
    "total_modules": $TOTAL_MODULES,
    "passed": $PASSED_MODULES,
    "failed": $FAILED_MODULES
  },
  "modules": $RESULTS_JSON
}
EOF

log_info "结果已写入 $RESULTS_FILE"

# 退出码
if [[ $FAILED_MODULES -gt 0 ]]; then
    exit 1
else
    log_pass "全部模块通过！"
    exit 0
fi
