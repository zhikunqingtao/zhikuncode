#!/bin/bash
# Open a temporary, authenticated browser entry to existing local services.
# Does not restart or stop the application. Personal, single-user use only.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_FILE="$SCRIPT_DIR/mobile-access.Caddyfile"
RUNTIME_DIR="$SCRIPT_DIR/.runtime"
AUTH_FILE="$RUNTIME_DIR/auth.env"
STATIC_ROOT="$PROJECT_ROOT/frontend/dist"
CHECK_ONLY=0
FORCE_BUILD=0

usage() {
    cat <<'HELP'
用法: bash scripts/mobile-access/start-mobile.sh [--check | --build] [--help]

首次安装（macOS）: brew install cloudflared caddy
先按项目 README 启动本机服务，已运行的服务无需重启。
再运行本脚本，用手机打开打印的 HTTPS 网址并输入生成的凭据。

默认     托管 frontend/dist 生产构建；缺少构建时自动执行 npm run build
--build  重新构建前端，再开放手机入口（前端代码更新后使用）
--check  只读检查依赖、生产构建、配置和本地服务；不构建、不写凭据、不启动入口
Ctrl+C   关闭本次入口，保留 Java/Python/前端本机服务

监听端口: MOBILE_ENTRY_PORT（默认 15173，仅绑定 127.0.0.1）
运行数据: scripts/mobile-access/.runtime/（已忽略，不要提交或外发）
换密码: 先 Ctrl+C 关闭旧入口，再删除 .runtime/auth.env，重跑脚本。

权限边界: 持有凭据的人可操作本机项目和 Agent；此入口不是文件/命令沙箱。
公司设备应使用经批准的远程接入方式，详情见 docs/mobile-access.md。
HELP
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --check) CHECK_ONLY=1 ;;
        --build) FORCE_BUILD=1 ;;
        *) usage >&2; exit 2 ;;
    esac
    shift
done
if [ "$CHECK_ONLY" = 1 ] && [ "$FORCE_BUILD" = 1 ]; then
    echo "--check 与 --build 不能同时使用：检查模式不会执行构建。" >&2
    exit 2
fi

MOBILE_ENTRY_PORT="${MOBILE_ENTRY_PORT:-15173}"
if ! [[ "$MOBILE_ENTRY_PORT" =~ ^[0-9]{1,5}$ ]] \
        || [ "$((10#$MOBILE_ENTRY_PORT))" -lt 1 ] \
        || [ "$((10#$MOBILE_ENTRY_PORT))" -gt 65535 ]; then
    echo "MOBILE_ENTRY_PORT 必须是 1–65535 的端口号。" >&2
    exit 2
fi
MOBILE_ENTRY_PORT="$((10#$MOBILE_ENTRY_PORT))"
export MOBILE_ENTRY_PORT
export MOBILE_STATIC_ROOT="$STATIC_ROOT"
ENTRY_URL="http://127.0.0.1:$MOBILE_ENTRY_PORT"

for dependency in caddy cloudflared curl lsof openssl; do
    if ! command -v "$dependency" >/dev/null 2>&1; then
        echo "缺少命令: $dependency。macOS 可先执行 brew install cloudflared caddy。" >&2
        exit 1
    fi
done

# Refuse before a build, credential generation or runtime-directory creation.
# In particular, deleting auth.env does not revoke a still-running Caddy.
if lsof -nP -iTCP:"$MOBILE_ENTRY_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    if [ "$CHECK_ONLY" = 1 ]; then
        echo "提示：$MOBILE_ENTRY_PORT 已被占用；正式启动前需关闭旧入口或另选端口。"
    else
        echo "$MOBILE_ENTRY_PORT 已被占用。请使用原入口，或先在原终端按 Ctrl+C 后重跑。" >&2
        exit 1
    fi
fi

for endpoint in \
    http://127.0.0.1:8080/actuator/health \
    http://127.0.0.1:8000/api/health; do
    if ! curl --noproxy '*' -fsS --max-time 5 "$endpoint" >/dev/null; then
        echo "本地服务尚未就绪: $endpoint。请先按项目 README 启动服务。" >&2
        exit 1
    fi
done

if [ "$CHECK_ONLY" = 1 ]; then
    if [ ! -f "$STATIC_ROOT/index.html" ]; then
        echo "缺少 frontend/dist/index.html；请运行 npm --prefix frontend run build 后再检查。" >&2
        exit 1
    fi
    # Only in-memory configuration-validation values; no runtime credentials
    # are read or persisted, and no listener or public tunnel is started.
    CHECK_HASH="$(caddy hash-password --plaintext configuration-validation-only)"
    MOBILE_AUTH_USERNAME=config-check MOBILE_AUTH_PASSWORD_HASH="$CHECK_HASH" \
        caddy validate --config "$CONFIG_FILE" --adapter caddyfile
    echo "依赖、生产构建、转发配置和本地服务检查通过；未启动入口或创建运行数据。"
    exit 0
fi

if [ "$FORCE_BUILD" = 1 ] || [ ! -f "$STATIC_ROOT/index.html" ]; then
    if ! command -v npm >/dev/null 2>&1; then
        echo "构建前端需要 npm，请先安装项目 README 要求的 Node.js。" >&2
        exit 1
    fi
    echo "正在构建前端生产包……"
    npm --prefix "$PROJECT_ROOT/frontend" run build
fi
if [ ! -f "$STATIC_ROOT/index.html" ]; then
    echo "未找到 frontend/dist/index.html，前端构建失败。" >&2
    exit 1
fi

mkdir -p "$RUNTIME_DIR/log"
chmod 700 "$RUNTIME_DIR"
if [ ! -f "$AUTH_FILE" ]; then
    echo "首次运行：正在生成本机专用的入口凭据……"
    NEW_PASSWORD="$(openssl rand -hex 16)"
    NEW_HASH="$(caddy hash-password --plaintext "$NEW_PASSWORD")"
    {
        printf "MOBILE_AUTH_USERNAME='zhikun'\n"
        printf "MOBILE_AUTH_PASSWORD='%s'\n" "$NEW_PASSWORD"
        printf "MOBILE_AUTH_PASSWORD_HASH='%s'\n" "$NEW_HASH"
    } >"$AUTH_FILE"
fi
chmod 600 "$AUTH_FILE"
# This locally generated file is executable shell syntax. Do not replace it
# with a downloaded auth.env or use credentials from someone else's machine.
# shellcheck disable=SC1090
. "$AUTH_FILE"
: "${MOBILE_AUTH_USERNAME:?auth.env 缺少用户名}" \
  "${MOBILE_AUTH_PASSWORD:?auth.env 缺少密码}" \
  "${MOBILE_AUTH_PASSWORD_HASH:?auth.env 缺少密码哈希}"
export MOBILE_AUTH_USERNAME MOBILE_AUTH_PASSWORD_HASH
caddy validate --config "$CONFIG_FILE" --adapter caddyfile

RUN_DIR=$(mktemp -d "$RUNTIME_DIR/log/mobile-access.XXXXXX")
CADDY_LOG="$RUN_DIR/caddy.log"
TUNNEL_LOG="$RUN_DIR/cloudflared.log"
CADDY_PID=""
TUNNEL_PID=""
CAFFEINATE_PID=""
cleanup() {
    local pid
    for pid in "$TUNNEL_PID" "$CADDY_PID" "$CAFFEINATE_PID"; do
        if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
    done
    for pid in "$TUNNEL_PID" "$CADDY_PID" "$CAFFEINATE_PID"; do
        if [ -n "$pid" ]; then wait "$pid" 2>/dev/null || true; fi
    done
    echo "本次入口已关闭。本机应用服务保持运行。诊断日志: $RUN_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

caddy run --config "$CONFIG_FILE" --adapter caddyfile >"$CADDY_LOG" 2>&1 &
CADDY_PID=$!
relay_ready=false
for ((attempt=0; attempt<15; attempt++)); do
    if ! kill -0 "$CADDY_PID" 2>/dev/null; then break; fi
    if curl --noproxy '*' -fsS --max-time 2 \
        -u "$MOBILE_AUTH_USERNAME:$MOBILE_AUTH_PASSWORD" \
        "$ENTRY_URL/" >/dev/null 2>&1; then
        relay_ready=true
        break
    fi
    sleep 1
done
if [ "$relay_ready" != true ]; then
    echo "本地入口启动失败，请查看 $CADDY_LOG。" >&2
    exit 1
fi

# Prove authentication covers static content, REST and SockJS before exposure.
for route in / /api/auth/status /ws/info; do
    UNAUTH_STATUS=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        --max-time 5 "$ENTRY_URL$route")
    if [ "$UNAUTH_STATUS" != "401" ]; then
        echo "入口认证检查失败: $route 返回 $UNAUTH_STATUS（预期 401）；不开放公网入口。" >&2
        exit 1
    fi
done
WRONG_PASSWORD_STATUS=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
    --max-time 5 -u "$MOBILE_AUTH_USERNAME:${MOBILE_AUTH_PASSWORD}-invalid" \
    "$ENTRY_URL/")
if [ "$WRONG_PASSWORD_STATUS" != "401" ]; then
    echo "错误密码检查失败（返回 $WRONG_PASSWORD_STATUS，预期 401）；不开放公网入口。" >&2
    exit 1
fi

# Exercise public Origin compatibility and rejection, still entirely locally.
for route in /api/auth/status /ws/info; do
    if ! curl --noproxy '*' -fsS --max-time 5 \
        -u "$MOBILE_AUTH_USERNAME:$MOBILE_AUTH_PASSWORD" \
        -H 'Host: mobile-check.example.com' \
        -H 'Origin: https://mobile-check.example.com' \
        "$ENTRY_URL$route" >/dev/null; then
        echo "公网来源兼容检查失败: $route；不开放公网入口。" >&2
        exit 1
    fi
done
for origin in https://other.example.com null http://mobile-check.example.com \
    https://mobile-check.example.com:444; do
    ORIGIN_STATUS=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        --max-time 5 -u "$MOBILE_AUTH_USERNAME:$MOBILE_AUTH_PASSWORD" \
        -H 'Host: mobile-check.example.com' -H "Origin: $origin" \
        "$ENTRY_URL/ws/info")
    if [ "$ORIGIN_STATUS" != "403" ]; then
        echo "Origin 校验失败（返回 $ORIGIN_STATUS，预期 403）；不开放公网入口。" >&2
        exit 1
    fi
done
echo "入口自检通过：匿名请求 401，同源可访问，外站/错误协议或端口来源 403。"

# Do not consume or change a user's named-tunnel configuration. HTTP/2 avoids
# requiring UDP connectivity; Quick Tunnel still requires outbound TCP 7844.
printf '{}\n' >"$RUN_DIR/cloudflared.yml"
cloudflared tunnel --config "$RUN_DIR/cloudflared.yml" \
    --no-autoupdate --protocol http2 \
    --url "$ENTRY_URL" >"$TUNNEL_LOG" 2>&1 &
TUNNEL_PID=$!
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w "$$" >/dev/null 2>&1 &
    CAFFEINATE_PID=$!
fi

echo "正在申请临时 HTTPS 网址并连接隧道，最长等待约 90 秒……"
PUBLIC_URL=""
tunnel_ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if ! kill -0 "$TUNNEL_PID" 2>/dev/null || ! kill -0 "$CADDY_PID" 2>/dev/null; then break; fi
    PUBLIC_URL=$(sed -nE '/https:\/\/[a-z0-9-]+\.trycloudflare\.com/ { s#.*(https://[a-z0-9-]+\.trycloudflare\.com).*#\1#; p; q; }' "$TUNNEL_LOG")
    if [ -n "$PUBLIC_URL" ] && grep -q 'Registered tunnel connection' "$TUNNEL_LOG"; then
        tunnel_ready=true
        break
    fi
    sleep 1
done
if [ "$tunnel_ready" != true ]; then
    echo "隧道未能连接。检查出站 TCP 7844 和诊断日志: $TUNNEL_LOG。" >&2
    exit 1
fi

printf '%s\n' "$PUBLIC_URL" >"$RUN_DIR/url.txt"
cat <<INFO

手机浏览器打开: $PUBLIC_URL
用户名: $MOBILE_AUTH_USERNAME
密码:   $MOBILE_AUTH_PASSWORD
凭据文件: $AUTH_FILE（权限 600，不要提交、截图或外发）
当次网址: $RUN_DIR/url.txt

请用手机蜂窝网络验证页面、消息发送、流式回复及审批。
新会话默认完全访问；需要人工审批时，请先切换为标准授权。
保持本终端运行、电脑联网且不休眠；macOS 防空闲休眠不能防合盖或主动睡眠。
Ctrl+C 关闭本次公网入口；下次启动可能换新网址。
INFO

while kill -0 "$TUNNEL_PID" 2>/dev/null && kill -0 "$CADDY_PID" 2>/dev/null; do
    sleep 2
done
echo "入口或隧道进程已退出，请查看诊断日志: $RUN_DIR。" >&2
exit 1
