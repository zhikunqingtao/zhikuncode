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

# 通过 PID 文件停止（java -jar 启动方式，PID 就是 JVM 本身，无 fork 问题）
if [ -f "$PID_FILE" ]; then
    source "$PID_FILE"
    [ -n "$BACKEND_PID" ]  && kill "$BACKEND_PID"  2>/dev/null && log_info "Backend 已停止 (PID: $BACKEND_PID)"
    [ -n "$PYTHON_PID" ]   && kill "$PYTHON_PID"   2>/dev/null && log_info "Python 已停止 (PID: $PYTHON_PID)"
    [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID"  2>/dev/null && log_info "Frontend 已停止 (PID: $FRONTEND_PID)"
    rm -f "$PID_FILE"
fi

# 兖底：通过端口清理
sleep 1
kill_port 8080
kill_port 8000
kill_port 5173

# 等待进程完全退出并释放文件句柄
sleep 2

# 清理 Agent 残留的 git worktree（避免 VS Code 源代码管理面板显示大量虚假变更）
agent_worktrees=$(git -C "$PROJECT_ROOT" worktree list --porcelain 2>/dev/null | grep '^worktree ' | grep -v "$PROJECT_ROOT" | sed 's/^worktree //')
if [ -n "$agent_worktrees" ]; then
    while IFS= read -r wt; do
        git -C "$PROJECT_ROOT" worktree remove --force "$wt" 2>/dev/null && log_info "已清理 Agent worktree: $wt"
    done <<< "$agent_worktrees"
    # 清理对应的 agent 临时分支
    git -C "$PROJECT_ROOT" branch --list 'agent-*' | xargs -r git -C "$PROJECT_ROOT" branch -D 2>/dev/null && log_info "已清理 Agent 临时分支"
else
    log_info "无残留 Agent worktree"
fi

# 清理 Backend 编译产物（可选，java -jar 模式下已不会锁定 target）
if [ -d "$PROJECT_ROOT/backend/target" ]; then
    rm -rf "$PROJECT_ROOT/backend/target" 2>/dev/null
    if [ -d "$PROJECT_ROOT/backend/target" ]; then
        log_warn "Backend target 目录清理不完整，将在下次启动时清理"
    else
        log_info "Backend target 目录已清理"
    fi
fi

echo ""
log_info "所有服务已停止"
echo ""
