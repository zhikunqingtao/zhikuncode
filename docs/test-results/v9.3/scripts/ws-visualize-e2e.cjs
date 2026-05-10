// WS 订阅 /user/queue/messages，然后通过 REST 发送 /visualize slash command，观测 visualization 独立消息推送。
const WebSocket = require('/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/.agentskills/e2e-test/node_modules/ws');
const http = require('http');

const SESSION_ID = process.argv[2];
if (!SESSION_ID) { console.error('usage: node ws-visualize-e2e.cjs <sessionId>'); process.exit(1); }
const VIZ_PAYLOAD = process.argv[3] || '/visualize mermaid graph TD; A-->B; B-->C;';

const RAND_SID = Math.random().toString(36).substring(2, 10);
const URL = `ws://localhost:8080/ws/000/${RAND_SID}/websocket`;

const t0 = Date.now();
const ts = () => (Date.now() - t0) + 'ms';
const events = [];

function log(o) { console.log(JSON.stringify({ t: ts(), ...o })); events.push({ t: ts(), ...o }); }

const ws = new WebSocket(URL);
let restSent = false;

function postJson(path, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({
      hostname: 'localhost', port: 8080, path,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
    }, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => resolve({ code: res.statusCode, body: buf }));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

ws.on('open', () => log({ ev: 'ws_open' }));

ws.on('message', async (raw) => {
  const msg = raw.toString();
  const first = msg.charAt(0);
  if (first === 'o') {
    log({ ev: 'sockjs_open' });
    ws.send(JSON.stringify([`CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nX-Session-Id:${SESSION_ID}\n\n\x00`]));
  } else if (first === 'a') {
    const arr = JSON.parse(msg.substring(1));
    for (const frame of arr) {
      const cmd = frame.split('\n')[0];
      log({ ev: 'stomp_frame', cmd, frame: frame.slice(0, 400) });
      if (cmd === 'CONNECTED') {
        ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-msgs\ndestination:/user/queue/messages\n\n\x00`]));
        ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-elicit\ndestination:/user/queue/elicitation\n\n\x00`]));
        log({ ev: 'subscribed', topics: ['/user/queue/messages', '/user/queue/elicitation'] });
        // 等 200ms 再发 REST（确保后端已完成订阅绑定）
        if (!restSent) {
          restSent = true;
          setTimeout(async () => {
            try {
              const res = await postJson('/api/query/conversation', {
                sessionId: SESSION_ID,
                message: VIZ_PAYLOAD,
                model: 'qwen3.6-plus'
              });
              log({ ev: 'rest_post', code: res.code, preview: res.body.slice(0, 300) });
            } catch (e) {
              log({ ev: 'rest_post_error', message: e.message });
            }
          }, 300);
        }
      } else if (cmd === 'MESSAGE') {
        const bodyIdx = frame.indexOf('\n\n');
        const headers = frame.substring(0, bodyIdx);
        const body = frame.substring(bodyIdx + 2).replace(/\x00$/, '');
        log({ ev: 'MESSAGE', headers, body: body.slice(0, 500) });
      }
    }
  } else if (first === 'c') {
    log({ ev: 'sockjs_close', payload: msg });
  }
});

ws.on('close', (code) => {
  log({ ev: 'ws_close', code });
  console.log('FINAL=' + JSON.stringify({ status: 'done', eventsCount: events.length }));
});

ws.on('error', (e) => log({ ev: 'ws_error', message: e.message }));

setTimeout(() => { try { ws.send(JSON.stringify([`DISCONNECT\n\n\x00`])); } catch (_) {} setTimeout(() => { try { ws.close(); } catch (_) {} process.exit(0); }, 200); }, 25000);
