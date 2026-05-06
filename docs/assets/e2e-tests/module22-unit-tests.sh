#!/bin/bash
# Module 22: 单元测试体系执行脚本
# 覆盖: 84 用例 / 277 方法
# 执行顺序: Python → Vitest → JUnit → Playwright

set -e
PROJECT_ROOT="/Users/guoqingtao/Desktop/dev/code/zhikuncode"
LOG_DIR="$PROJECT_ROOT/docs/assets/e2e-evidence/logs"
mkdir -p "$LOG_DIR"

echo "=========================================="
echo "Module 22: 单元测试体系执行"
echo "=========================================="
echo ""

# 1. Python pytest (6 用例 / 29 方法)
echo "[1/4] Python pytest..."
cd "$PROJECT_ROOT/python-service"
./venv/bin/python -m pytest tests/ -v 2>&1 | tee "$LOG_DIR/module22-python-pytest.log"
echo ""

# 2. 前端 Vitest (18 用例 / 35 方法)
echo "[2/4] Frontend Vitest..."
cd "$PROJECT_ROOT/frontend"
npx vitest run 2>&1 | tee "$LOG_DIR/module22-frontend-vitest.log"
echo ""

# 3. 后端 JUnit 5 (47 用例 / 176 方法)
echo "[3/4] Backend JUnit 5..."
cd "$PROJECT_ROOT/backend"
./mvnw test -pl . 2>&1 | tee "$LOG_DIR/module22-backend-junit.log"
echo ""

# 4. 前端 Playwright E2E (10 用例 / 26 子测试)
echo "[4/4] Frontend Playwright E2E..."
cd "$PROJECT_ROOT/frontend"
npx playwright test --timeout=120000 2>&1 | tee "$LOG_DIR/module22-frontend-playwright.log"
echo ""

echo "=========================================="
echo "所有测试执行完毕，日志保存在: $LOG_DIR"
echo "=========================================="
