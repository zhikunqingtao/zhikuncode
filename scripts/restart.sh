#!/bin/bash
# restart.sh — 一键重启全栈服务 (后端 + 前端 + Python)
# 用法:
#   ./scripts/restart.sh          # 重启所有服务
#   ./scripts/restart.sh backend  # 只重启后端
#   ./scripts/restart.sh frontend # 只重启前端
#   ./scripts/restart.sh python   # 只重启 Python
#   ./scripts/restart.sh kill     # 只杀进程不启动

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-all}"

# ─── 颜色输出 ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }
info() { echo -e "${CYAN}[→]${NC} $1"; }

# ─── 环境变量 ───
export JAVA_HOME="${JAVA_HOME:-/Users/guoqingtao/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home}"
BACKEND_PORT=8080
FRONTEND_PORT=5173
PYTHON_PORT=8000

# ─── 杀进程函数 ───
kill_port() {
    local port=$1
    local name=$2
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "$pids" | xargs kill -9 2>/dev/null || true
        log "已杀掉 $name (port $port, PIDs: $(echo $pids | tr '\n' ' '))"
        sleep 1
    else
        info "$name 未运行 (port $port)"
    fi
}

# ─── 杀掉所有服务 ───
kill_all() {
    echo ""
    info "停止现有服务..."
    kill_port $BACKEND_PORT  "后端 (Java)"
    kill_port $FRONTEND_PORT "前端 (Vite)"
    kill_port $PYTHON_PORT   "Python 服务"
}

kill_backend()  { kill_port $BACKEND_PORT  "后端 (Java)"; }
kill_frontend() { kill_port $FRONTEND_PORT "前端 (Vite)"; }
kill_python()   { kill_port $PYTHON_PORT   "Python 服务"; }

# ─── 启动后端 ───
start_backend() {
    info "编译后端..."
    cd "$PROJECT_ROOT/backend"
    ./mvnw compile -q 2>&1 | tail -5
    if [ ${PIPESTATUS[0]} -ne 0 ]; then
        err "后端编译失败！"
        return 1
    fi
    log "后端编译成功"

    info "启动后端 (port $BACKEND_PORT)..."
    nohup ./mvnw spring-boot:run -q > "$PROJECT_ROOT/log/backend-console.log" 2>&1 &
    local pid=$!
    
    # 等待启动
    local count=0
    while [ $count -lt 30 ]; do
        if curl -sf "http://localhost:$BACKEND_PORT/api/health" >/dev/null 2>&1; then
            log "后端已启动 (PID: $pid, port $BACKEND_PORT)"
            return 0
        fi
        # 检查进程是否还活着
        if ! kill -0 $pid 2>/dev/null; then
            err "后端启动失败！查看日志: $PROJECT_ROOT/log/backend-console.log"
            return 1
        fi
        count=$((count + 1))
        sleep 1
    done
    
    # 30秒超时但进程还活着，可能只是健康检查端点没实现
    if kill -0 $pid 2>/dev/null; then
        warn "后端健康检查超时，但进程仍在运行 (PID: $pid)"
        log "后端已启动 (PID: $pid, port $BACKEND_PORT)"
    else
        err "后端启动超时！查看日志: $PROJECT_ROOT/log/backend-console.log"
        return 1
    fi
}

# ─── 启动前端 ───
start_frontend() {
    info "启动前端 (port $FRONTEND_PORT)..."
    cd "$PROJECT_ROOT/frontend"
    nohup npx vite --host 0.0.0.0 --port $FRONTEND_PORT > "$PROJECT_ROOT/log/frontend-console.log" 2>&1 &
    local pid=$!
    sleep 2
    
    if kill -0 $pid 2>/dev/null; then
        log "前端已启动 (PID: $pid, port $FRONTEND_PORT)"
    else
        err "前端启动失败！查看日志: $PROJECT_ROOT/log/frontend-console.log"
        return 1
    fi
}

# ─── 启动 Python ───
start_python() {
    if [ ! -d "$PROJECT_ROOT/python-service" ]; then
        warn "python-service 目录不存在，跳过"
        return 0
    fi
    
    info "启动 Python 服务 (port $PYTHON_PORT)..."
    cd "$PROJECT_ROOT/python-service"
    
    # 如果有 venv 则使用
    local python_bin="python3"
    if [ -f ".venv/bin/python" ]; then
        python_bin=".venv/bin/python"
    fi
    
    nohup $python_bin -m uvicorn main:app --host 0.0.0.0 --port $PYTHON_PORT --reload > "$PROJECT_ROOT/log/python-console.log" 2>&1 &
    local pid=$!
    sleep 2
    
    if kill -0 $pid 2>/dev/null; then
        log "Python 服务已启动 (PID: $pid, port $PYTHON_PORT)"
    else
        warn "Python 服务启动失败（可能无 main:app），跳过"
    fi
}

# ─── 显示状态 ───
show_status() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
    echo -e "${CYAN}   AI Code Assistant — 服务状态${NC}"
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
    
    # 获取局域网 IP
    local lan_ip
    lan_ip=$(ipconfig getifaddr en0 2>/dev/null || echo "unknown")
    
    echo -e "  前端:   ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
    echo -e "  后端:   ${GREEN}http://localhost:$BACKEND_PORT${NC}"
    echo -e "  Python: ${GREEN}http://localhost:$PYTHON_PORT${NC}"
    echo ""
    echo -e "  手机:   ${YELLOW}http://${lan_ip}:$FRONTEND_PORT${NC}"
    echo ""
    echo -e "  日志:   ${CYAN}$PROJECT_ROOT/log/${NC}"
    echo -e "          app.log | error.log | backend-console.log | frontend-console.log"
    echo ""
    echo -e "  ${YELLOW}按 Ctrl+C 不会停止后台服务，需运行: ./scripts/restart.sh kill${NC}"
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
}

# ─── 主逻辑 ───
echo -e "${CYAN}═══ AI Code Assistant 全栈重启脚本 ═══${NC}"

case "$TARGET" in
    all)
        kill_all
        start_backend
        start_frontend
        start_python
        show_status
        ;;
    backend)
        kill_backend
        start_backend
        ;;
    frontend)
        kill_frontend
        start_frontend
        ;;
    python)
        kill_python
        start_python
        ;;
    kill)
        kill_all
        log "所有服务已停止"
        ;;
    status)
        echo ""
        info "端口占用情况:"
        for p in $BACKEND_PORT $FRONTEND_PORT $PYTHON_PORT; do
            pids=$(lsof -ti:"$p" 2>/dev/null || true)
            if [ -n "$pids" ]; then
                log "port $p: 运行中 (PIDs: $(echo $pids | tr '\n' ' '))"
            else
                warn "port $p: 未运行"
            fi
        done
        ;;
    *)
        echo "用法: $0 {all|backend|frontend|python|kill|status}"
        exit 1
        ;;
esac
