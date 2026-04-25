#!/bin/bash
#
# ZhikuCode 三端一键停止脚本
#

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$PROJECT_ROOT/.service-pids"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }

kill_port() {
    local port=$1
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "$pids" | xargs kill -9 2>/dev/null || true
        log_info "已停止端口 $port 上的进程 (PID: $pids)"
    fi
}

echo ""
echo "============================================"
echo "       ZhikuCode 三端停止"
echo "============================================"
echo ""

# 通过 PID 文件停止
if [ -f "$PID_FILE" ]; then
    source "$PID_FILE"
    [ -n "$BACKEND_PID" ]  && kill "$BACKEND_PID"  2>/dev/null && log_info "Backend 已停止 (PID: $BACKEND_PID)"
    [ -n "$PYTHON_PID" ]   && kill "$PYTHON_PID"   2>/dev/null && log_info "Python 已停止 (PID: $PYTHON_PID)"
    [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID"  2>/dev/null && log_info "Frontend 已停止 (PID: $FRONTEND_PID)"
    rm -f "$PID_FILE"
fi

# 兜底：通过端口清理
sleep 1
kill_port 8080
kill_port 8000
kill_port 5173

# 清理 Backend 编译产物，防止残留 class 导致 NoClassDefFoundError
if [ -d "$PROJECT_ROOT/backend/target" ]; then
    rm -rf "$PROJECT_ROOT/backend/target"
    log_info "Backend target 目录已清理"
fi

echo ""
log_info "所有服务已停止"
echo ""
