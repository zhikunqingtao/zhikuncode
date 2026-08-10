#!/bin/bash
# 王者峡谷 Web 版启动器——自动挑选空闲端口，避免与本机其他服务冲突
cd "$(dirname "$0")"
PORT=""
for p in 9201 9202 9203 9301 9302 9417 9418 9419; do
  if ! lsof -iTCP:$p -sTCP:LISTEN -P -n >/dev/null 2>&1; then PORT=$p; break; fi
done
if [ -z "$PORT" ]; then
  PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
fi
echo "启动王者峡谷: http://localhost:$PORT"
( sleep 1; open "http://localhost:$PORT" ) &
python3 -m http.server "$PORT"
