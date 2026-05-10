// 简化版：仅订阅并打印 MESSAGE，由外部 shell 负责 POST。
const WebSocket = require('/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/.agentskills/e2e-test/node_modules/ws');

const SESSION_ID = process.argv[2];
const DURATION_MS = parseInt(process.argv[3] || '20000', 10);
if (!SESSION_ID) { console.error('usage: node ws-subscribe-msgs.cjs <sessionId> [durationMs]'); process.exit(1); }

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
        ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-msgs\ndestination:/user/queue/messages\n\n\x00`]));
        log({ ev: 'subscribed', topic: '/user/queue/messages' });
      } else if (cmd === 'MESSAGE') {
        const bodyIdx = frame.indexOf('\n\n');
        const body = frame.substring(bodyIdx + 2).replace(/\x00$/, '');
        log({ ev: 'MESSAGE', body });
      } else if (cmd) {
        log({ ev: 'frame', cmd, frame: frame.slice(0, 300) });
      }
    }
  }
});
ws.on('close', (code) => { log({ ev: 'ws_close', code }); process.exit(0); });
ws.on('error', (e) => log({ ev: 'ws_error', message: e.message }));

setTimeout(() => { try { ws.send(JSON.stringify([`DISCONNECT\n\n\x00`])); } catch (_) {} setTimeout(() => { try { ws.close(); } catch (_) {} process.exit(0); }, 200); }, DURATION_MS);
