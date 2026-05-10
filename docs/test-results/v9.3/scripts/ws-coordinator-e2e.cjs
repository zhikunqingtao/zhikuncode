// 通过 Node 直连 SockJS + STOMP，订阅 /user/queue/coordinator/{sid} 并等待事件
const WebSocket = require('/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/.agentskills/e2e-test/node_modules/ws');

const SESSION_ID = process.argv[2] || 'c921acbb-eed5-4f2a-ade5-5653a6340cf1';
const RAND_SID = Math.random().toString(36).substring(2, 10);
const URL = `ws://localhost:8080/ws/000/${RAND_SID}/websocket`;

const events = [];
const t0 = Date.now();
const ts = () => (Date.now() - t0) + 'ms';

console.log(JSON.stringify({ action: 'connect', url: URL, sessionId: SESSION_ID }));

const ws = new WebSocket(URL);

ws.on('open', () => {
  events.push({ t: ts(), ev: 'ws_open' });
  console.log(JSON.stringify({ t: ts(), ev: 'ws_open' }));
});

ws.on('message', (raw) => {
  const msg = raw.toString();
  const first = msg.charAt(0);
  if (first === 'o') {
    events.push({ t: ts(), ev: 'sockjs_open' });
    console.log(JSON.stringify({ t: ts(), ev: 'sockjs_open' }));
    // 发送 STOMP CONNECT
    const connect = `CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nX-Session-Id:${SESSION_ID}\n\n\x00`;
    ws.send(JSON.stringify([connect]));
    events.push({ t: ts(), ev: 'stomp_connect_sent' });
  } else if (first === 'h') {
    events.push({ t: ts(), ev: 'heartbeat' });
  } else if (first === 'a') {
    const arr = JSON.parse(msg.substring(1));
    for (const frame of arr) {
      const cmd = frame.split('\n')[0];
      events.push({ t: ts(), ev: 'stomp_frame', cmd, frame: frame.slice(0, 200) });
      console.log(JSON.stringify({ t: ts(), ev: 'stomp_frame', cmd, frame: frame.slice(0, 300) }));
      if (cmd === 'CONNECTED') {
        // 订阅 coordinator topic
        const subId = 'sub-' + Math.random().toString(36).slice(2, 8);
        const sub = `SUBSCRIBE\nid:${subId}\ndestination:/user/queue/coordinator/${SESSION_ID}\n\n\x00`;
        ws.send(JSON.stringify([sub]));
        events.push({ t: ts(), ev: 'sub_sent', destination: `/user/queue/coordinator/${SESSION_ID}`, id: subId });
        console.log(JSON.stringify({ t: ts(), ev: 'sub_sent', destination: `/user/queue/coordinator/${SESSION_ID}` }));
        // 同时订阅 session list 和 assistant 流验证多订阅健康
        const sub2 = `SUBSCRIBE\nid:sub-slist\ndestination:/user/queue/sessions\n\n\x00`;
        ws.send(JSON.stringify([sub2]));
        events.push({ t: ts(), ev: 'sub_sent', destination: '/user/queue/sessions', id: 'sub-slist' });
      }
    }
  } else if (first === 'c') {
    events.push({ t: ts(), ev: 'sockjs_close', payload: msg });
    console.log(JSON.stringify({ t: ts(), ev: 'sockjs_close', payload: msg }));
  }
});

ws.on('close', (code, reason) => {
  events.push({ t: ts(), ev: 'ws_close', code, reason: reason.toString() });
  console.log(JSON.stringify({ t: ts(), ev: 'ws_close', code }));
  console.log('FINAL=' + JSON.stringify({ status: 'done', events }));
});

ws.on('error', (e) => {
  events.push({ t: ts(), ev: 'ws_error', message: e.message });
  console.log(JSON.stringify({ t: ts(), ev: 'ws_error', message: e.message }));
});

// 运行 15s 后主动关闭（给 heartbeat 与可能的事件推送留余地）
setTimeout(() => {
  try {
    // 发送 DISCONNECT
    ws.send(JSON.stringify([`DISCONNECT\n\n\x00`]));
  } catch (_) {}
  setTimeout(() => { try { ws.close(); } catch (_) {} }, 200);
}, 15000);

setTimeout(() => {
  console.log('TIMEOUT_EXIT=' + JSON.stringify({ status: 'timeout', events }));
  process.exit(0);
}, 18000);
