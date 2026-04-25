#!/bin/bash
#
# ZhikuCode 三端一键启动脚本
# 启动: Backend (Java 8080) + Python (FastAPI 8000) + Frontend (Vite 5173)
#

set -e

# ======================== 配置 ========================
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_ROOT/log"

BACKEND_PORT=8080
PYTHON_PORT=8000
FRONTEND_PORT=5173

BACKEND_LOG="$LOG_DIR/backend-console.log"
PYTHON_LOG="$LOG_DIR/python-console.log"
FRONTEND_LOG="$LOG_DIR/frontend-console.log"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ======================== 工具函数 ========================
log_info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $1"; }

# 检查端口占用，若被占用则终止占用进程
kill_port() {
    local port=$1
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        log_warn "端口 $port 被占用 (PID: $pids)，正在清理..."
        echo "$pids" | xargs kill -9 2>/dev/null || true
        sleep 1
        log_info "端口 $port 已释放"
    fi
}

# 等待端口就绪（使用 TCP 连接检测，避免 HTTP 状态码干扰）
wait_for_port() {
    local port=$1
    local name=$2
    local timeout=$3
    local elapsed=0

    while [ $elapsed -lt "$timeout" ]; do
        if nc -z localhost "$port" 2>/dev/null; then
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        printf "."
    done
    return 1
}

# ======================== 前置检查 ========================
log_step "前置检查..."

# 检查 Java
if ! command -v java &>/dev/null; then
    log_error "未找到 java，请安装 JDK 21+"
    exit 1
fi

# 检查 Node.js
if ! command -v node &>/dev/null; then
    log_error "未找到 node，请安装 Node.js"
    exit 1
fi

# 检查 Python 虚拟环境（优先 venv，回退 .venv，最后回退系统命令）
PYTHON_VENV_DIR=""
PYTHON_CMD=""
if [ -f "$PROJECT_ROOT/python-service/venv/bin/python" ]; then
    PYTHON_VENV_DIR="$PROJECT_ROOT/python-service/venv"
    PYTHON_CMD="$PYTHON_VENV_DIR/bin/python"
elif [ -f "$PROJECT_ROOT/python-service/.venv/bin/python" ]; then
    PYTHON_VENV_DIR="$PROJECT_ROOT/python-service/.venv"
    PYTHON_CMD="$PYTHON_VENV_DIR/bin/python"
elif command -v python3.11 &>/dev/null; then
    PYTHON_CMD="python3.11"
elif command -v python3 &>/dev/null; then
    PYTHON_CMD="python3"
else
    log_error "未找到 Python 虚拟环境或系统 Python，请先在 python-service/ 下创建 venv"
    exit 1
fi
log_info "Python 命令: $PYTHON_CMD ($($PYTHON_CMD --version 2>&1))"

# 确保日志目录存在
mkdir -p "$LOG_DIR"

echo ""
echo "============================================"
echo "       ZhikuCode 三端一键启动"
echo "============================================"
echo ""

# ======================== 加载环境变量 ========================
if [ -f "$PROJECT_ROOT/.env" ]; then
    log_info "加载 .env 文件..."
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
fi

# ======================== 清理端口 ========================
log_step "检查并清理端口..."
kill_port $BACKEND_PORT
kill_port $PYTHON_PORT
kill_port $FRONTEND_PORT

# ======================== 启动 Backend ========================
log_step "启动 Backend (Java Spring Boot :$BACKEND_PORT)..."
cd "$PROJECT_ROOT/backend"
# 先 clean compile 确保所有类（含 inner record）完整编译，避免残留 class 导致 NoClassDefFoundError
log_info "正在编译 Backend (clean compile)..."
./mvnw clean compile -DskipTests -q > "$BACKEND_LOG" 2>&1
if [ $? -ne 0 ]; then
    log_error "Backend 编译失败，请查看日志: $BACKEND_LOG"
    exit 1
fi
nohup ./mvnw spring-boot:run -DskipTests >> "$BACKEND_LOG" 2>&1 < /dev/null &
BACKEND_PID=$!
log_info "Backend 进程已启动 (PID: $BACKEND_PID)"

# ======================== 启动 Python ========================
log_step "启动 Python 服务 (FastAPI :$PYTHON_PORT)..."
cd "$PROJECT_ROOT/python-service"
nohup env PYTHONPATH=./src "$PYTHON_CMD" -m uvicorn src.main:app \
    --host 0.0.0.0 --port $PYTHON_PORT --reload > "$PYTHON_LOG" 2>&1 < /dev/null &
PYTHON_PID=$!
log_info "Python 进程已启动 (PID: $PYTHON_PID)"

# ======================== 启动 Frontend ========================
log_step "启动 Frontend (Vite :$FRONTEND_PORT)..."
cd "$PROJECT_ROOT/frontend"
nohup npm run dev > "$FRONTEND_LOG" 2>&1 < /dev/null &
FRONTEND_PID=$!
log_info "Frontend 进程已启动 (PID: $FRONTEND_PID)"

# ======================== 健康检查 ========================
echo ""
log_step "等待服务就绪..."

# 等待 Backend（最长 180 秒，冷启动编译+MCP连接较慢）
printf "  Backend  "
if wait_for_port $BACKEND_PORT "Backend" 180; then
    echo -e " ${GREEN}✔ 就绪${NC}"
else
    echo -e " ${RED}✘ 超时${NC}"
    log_error "Backend 启动失败，请查看日志: $BACKEND_LOG"
fi

# 等待 Python（最长 30 秒）
printf "  Python   "
if wait_for_port $PYTHON_PORT "Python" 30; then
    echo -e " ${GREEN}✔ 就绪${NC}"
else
    echo -e " ${RED}✘ 超时${NC}"
    log_error "Python 启动失败，请查看日志: $PYTHON_LOG"
fi

# 等待 Frontend（最长 30 秒）
printf "  Frontend "
if wait_for_port $FRONTEND_PORT "Frontend" 30; then
    echo -e " ${GREEN}✔ 就绪${NC}"
else
    echo -e " ${RED}✘ 超时${NC}"
    log_error "Frontend 启动失败，请查看日志: $FRONTEND_LOG"
fi

# ======================== 健康详情 ========================
echo ""
log_step "服务状态详情:"

BACKEND_HEALTH=$(curl -s "http://localhost:$BACKEND_PORT/api/health" 2>/dev/null || echo "不可达")
echo -e "  Backend  : ${GREEN}http://localhost:$BACKEND_PORT${NC}"
echo "             $BACKEND_HEALTH" | head -1

PYTHON_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PYTHON_PORT/docs" 2>/dev/null || echo "000")
if [ "$PYTHON_STATUS" = "200" ]; then
    echo -e "  Python   : ${GREEN}http://localhost:$PYTHON_PORT${NC}  (docs: /docs)"
else
    echo -e "  Python   : ${RED}http://localhost:$PYTHON_PORT (HTTP $PYTHON_STATUS)${NC}"
fi

FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$FRONTEND_PORT/" 2>/dev/null || echo "000")
if [ "$FRONTEND_STATUS" = "200" ]; then
    echo -e "  Frontend : ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
else
    echo -e "  Frontend : ${RED}http://localhost:$FRONTEND_PORT (HTTP $FRONTEND_STATUS)${NC}"
fi

# ======================== 保存 PID 用于停止 ========================
PID_FILE="$PROJECT_ROOT/.service-pids"
echo "BACKEND_PID=$BACKEND_PID" > "$PID_FILE"
echo "PYTHON_PID=$PYTHON_PID" >> "$PID_FILE"
echo "FRONTEND_PID=$FRONTEND_PID" >> "$PID_FILE"

echo ""
echo "============================================"
echo "  日志文件:"
echo "    Backend  : $BACKEND_LOG"
echo "    Python   : $PYTHON_LOG"
echo "    Frontend : $FRONTEND_LOG"
echo ""
echo "  停止所有服务: ./stop.sh"
echo "============================================"
echo ""

# 保持脚本运行，等待 Ctrl+C 退出
cleanup() {
    echo ""
    log_warn "正在停止所有服务..."
    kill $BACKEND_PID $PYTHON_PID $FRONTEND_PID 2>/dev/null || true
    # 确保端口释放
    sleep 1
    kill_port $BACKEND_PORT
    kill_port $PYTHON_PORT
    kill_port $FRONTEND_PORT
    log_info "所有服务已停止"
    rm -f "$PID_FILE"
    exit 0
}

trap cleanup SIGINT SIGTERM

log_info "按 Ctrl+C 停止所有服务"
wait
