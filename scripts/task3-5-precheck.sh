#!/usr/bin/env bash
# Task 3-5 差异化升级测试前置自检
# 引用：docs/Task3-5差异化升级功能测试方案.md §11.1
# 用法：./scripts/task3-5-precheck.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PASS=0
FAIL=0
WARN=0

check() {
    local name="$1"; shift
    if "$@" >/dev/null 2>&1; then
        echo "  ✅ $name"
        PASS=$((PASS + 1))
    else
        echo "  ❌ $name"
        FAIL=$((FAIL + 1))
    fi
}

warn() {
    echo "  ⚠️  $1"
    WARN=$((WARN + 1))
}

echo "==> 1. 运行时版本检查"
check "Node >= 22"        bash -c 'node -v | grep -E "^v2[2-9]"'
check "JDK 21"            bash -c 'java -version 2>&1 | grep -E "21\."'
check "Python 3.11"       bash -c 'python3 --version | grep -E "3\.11"'

echo ""
echo "==> 2. Playwright"
if [ -d frontend/node_modules/@playwright ]; then
    check "Playwright 已安装" test -d frontend/node_modules/@playwright
else
    warn "frontend/node_modules/@playwright 不存在，首次运行需 npm ci + npx playwright install chromium"
fi

echo ""
echo "==> 3. Python 依赖"
if [ -d python-service/.venv ] || [ -d python-service/venv ]; then
    check "Python venv 存在" bash -c 'test -d python-service/.venv -o -d python-service/venv'
else
    warn "python-service venv 不存在，需 pip install -e ."
fi

echo ""
echo "==> 4. 测试产物目录"
mkdir -p docs/test-results/screenshots/visualization
mkdir -p docs/test-results/logs/task3-5
mkdir -p docs/test-results/coverage
mkdir -p docs/test-results/bugs
echo "  ✅ docs/test-results/ 目录结构就绪"
PASS=$((PASS + 1))

echo ""
echo "==> 5. 环境变量"
if [ -n "${DASHSCOPE_API_KEY:-}" ]; then
    echo "  ✅ DASHSCOPE_API_KEY 已设置"
    PASS=$((PASS + 1))
else
    warn "DASHSCOPE_API_KEY 未设置，Task 4 LLM 路径将走 mock"
fi

echo ""
echo "==> 6. Chrome 可用性（可选，未装则降级 bundled）"
if [ -f "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]; then
    echo "  ✅ 系统 Chrome 可用（BROWSER_CHANNEL=chrome 生效）"
    PASS=$((PASS + 1))
else
    warn "系统 Chrome 不存在，将使用 Playwright bundled chromium"
fi

echo ""
echo "==> 7. 三端端口占用检查（8080 / 8000 / 5173）"
for port in 8080 8000 5173; do
    if lsof -nP -iTCP:${port} -sTCP:LISTEN >/dev/null 2>&1; then
        warn "端口 ${port} 已被占用，执行前建议 ./stop.sh"
    else
        echo "  ✅ 端口 ${port} 空闲"
        PASS=$((PASS + 1))
    fi
done

echo ""
echo "========================================"
echo "  PASS: $PASS   WARN: $WARN   FAIL: $FAIL"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    echo "❌ 前置检查未通过，修复后再执行测试"
    exit 1
fi

echo "✅ 前置检查通过（警告项不阻塞，但请关注）"
