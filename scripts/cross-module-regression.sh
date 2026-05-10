#!/usr/bin/env bash
# Task 3-5 跨模块回归测试（对应 §6.2 轮次 8）
# 引用：docs/Task3-5差异化升级功能测试方案.md §11.3
# 用法：./scripts/cross-module-regression.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

STAMP=$(date +%Y%m%d-%H%M%S)
LOG_DIR="docs/test-results/logs/task3-5/regression-${STAMP}"
mkdir -p "$LOG_DIR"

trap 'echo "❌ 中断，日志在 ${LOG_DIR}"; exit 1' INT TERM ERR

echo "==> [1/5] 前置自检"
./scripts/task3-5-precheck.sh | tee "$LOG_DIR/01-precheck.log"

echo ""
echo "==> [2/5] 后端编译门 + 单元"
(cd backend && ./mvnw -q test -DskipITs) 2>&1 | tee "$LOG_DIR/02-backend.log"

echo ""
echo "==> [3/5] 前端编译门 + 单元"
(cd frontend && npm run test -- --run) 2>&1 | tee "$LOG_DIR/03-frontend.log"

echo ""
echo "==> [4/5] Python 编译门 + 单元"
(cd python-service && python -m pytest -q) 2>&1 | tee "$LOG_DIR/04-python.log"

echo ""
echo "==> [5/5] 跨模块 E2E（TC-X-01..04 + P0 红线）"
(cd frontend && npx playwright test e2e/task3-5-differential-upgrade.spec.ts \
    --grep 'TC-T3-SEC-01|TC-T4-E2E-01|TC-T5-SEC-03|TC-T5-SEC-04|TC-X-01|TC-X-02') \
    2>&1 | tee "$LOG_DIR/05-e2e.log" || echo "⚠️ E2E 部分失败，详见日志"

# 自动归档日志快照
./scripts/archive-logs.sh regression-${STAMP}

echo ""
echo "========================================"
echo "✅ 跨模块回归执行完成"
echo "   日志：$LOG_DIR"
echo "========================================"
