// WS E2E：CONNECT → SUBSCRIBE /user/queue/messages → SEND /app/command (SlashCommandPayload) → 观测 visualization 消息
const WebSocket = require('/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/.agentskills/e2e-test/node_modules/ws');

const SESSION_ID = process.argv[2];
const COMMAND = process.argv[3] || 'visualize';
const ARGS = process.argv[4] || 'mermaid graph TD; A-->B; B-->C;';
const DURATION_MS = parseInt(process.argv[5] || '15000', 10);
if (!SESSION_ID) { console.error('usage: node ws-slash-visualize.cjs <sessionId> [command] [args] [durationMs]'); process.exit(1); }

const RAND_SID = Math.random().toString(36).substring(2, 10);
const URL = `ws://localhost:8080/ws/000/${RAND_SID}/websocket`;

const t0 = Date.now();
const ts = () => (Date.now() - t0) + 'ms';
function log(o) { process.stdout.write(JSON.stringify({ t: ts(), ...o }) + '\n'); }

const ws = new WebSocket(URL);

ws.on('open', () => log({ ev: 'ws_open' }));
ws.on('message', (raw) => {
  const msg = raw.toString();
  const first = msg.charAt(0);
  if (first === 'o') {
    log({ ev: 'sockjs_open' });
    ws.send(JSON.stringify([`CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nX-Session-Id:${SESSION_ID}\n\n\x00`]));
  } else if (first === 'a') {
    let arr;
    try { arr = JSON.parse(msg.substring(1)); } catch (_) { return; }
    for (const frame of arr) {
      const cmd = frame.split('\n')[0];
      if (cmd === 'CONNECTED') {
        log({ ev: 'CONNECTED', frame: frame.slice(0, 300) });
        // 1) 订阅 /user/queue/messages
        ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-msgs\ndestination:/user/queue/messages\n\n\x00`]));
        log({ ev: 'subscribed', topic: '/user/queue/messages' });
        // 2) 也订阅 /user/queue/errors（便于看错误）
        ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-errs\ndestination:/user/queue/errors\n\n\x00`]));
        // 3) 先 bind-session（WS 侧将 principal 和 sessionId 绑定）
        const bindBody = JSON.stringify({ sessionId: SESSION_ID });
        const bindFrame = `SEND\ndestination:/app/bind-session\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(bindBody)}\n\n${bindBody}\x00`;
        ws.send(JSON.stringify([bindFrame]));
        log({ ev: 'bind_session_sent' });
        // 4) 500ms 后发 slash command
        setTimeout(() => {
          const payload = JSON.stringify({ command: COMMAND, args: ARGS });
          const sendFrame = `SEND\ndestination:/app/command\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(payload)}\n\n${payload}\x00`;
          ws.send(JSON.stringify([sendFrame]));
          log({ ev: 'slash_command_sent', command: COMMAND, args: ARGS.slice(0, 120) });
        }, 500);
      } else if (cmd === 'MESSAGE') {
        const bodyIdx = frame.indexOf('\n\n');
        const headers = frame.substring(0, bodyIdx);
        const body = frame.substring(bodyIdx + 2).replace(/\x00$/, '');
        log({ ev: 'MESSAGE', headers: headers.slice(0, 200), body: body.slice(0, 800) });
      } else if (cmd === 'ERROR') {
        log({ ev: 'ERROR', frame: frame.slice(0, 500) });
      } else if (cmd) {
        log({ ev: 'frame', cmd, frame: frame.slice(0, 200) });
      }
    }
  }
});
ws.on('close', (code) => { log({ ev: 'ws_close', code }); process.exit(0); });
ws.on('error', (e) => log({ ev: 'ws_error', message: e.message }));

setTimeout(() => { try { ws.send(JSON.stringify([`DISCONNECT\n\n\x00`])); } catch (_) {} setTimeout(() => { try { ws.close(); } catch (_) {} process.exit(0); }, 200); }, DURATION_MS);
