// v9.3 WS 性能探针
// 测两件事：
//  a) WS握手 = socket_open → sockjs_o → STOMP CONNECTED 的时间
//  b) slash_command_rtt = SEND /app/command → 收到 /user/queue/messages 回执（visualization envelope）
// 采样 N 次，输出 TSV：idx, handshake_ms, slash_rtt_ms
const WebSocket = require('/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/.agentskills/e2e-test/node_modules/ws');
const fs = require('fs');

const N = parseInt(process.argv[2] || '30', 10);
const OUT = process.argv[3] || 'docs/test-results/v9.3/perf/ws-samples.tsv';
const COMMAND = 'visualize';
const ARGS = 'text hello-perf';

fs.writeFileSync(OUT, 'idx\thandshake_ms\tslash_rtt_ms\tstatus\n');

function once(idx) {
  return new Promise((resolve) => {
    const SESSION_ID = `perf-${idx}-${Math.random().toString(36).slice(2, 8)}`;
    const RAND_SID = Math.random().toString(36).substring(2, 10);
    const URL = `ws://localhost:8080/ws/000/${RAND_SID}/websocket`;
    const ws = new WebSocket(URL);
    const marks = {};
    let done = false;
    const finalize = (status) => {
      if (done) return; done = true;
      const hs = (marks.connected && marks.open) ? (marks.connected - marks.open) : -1;
      const rtt = (marks.msg && marks.send) ? (marks.msg - marks.send) : -1;
      fs.appendFileSync(OUT, `${idx}\t${hs.toFixed(3)}\t${rtt.toFixed(3)}\t${status}\n`);
      try { ws.close(); } catch (_) {}
      resolve({ idx, hs, rtt, status });
    };
    const timer = setTimeout(() => finalize('timeout'), 8000);
    ws.on('open', () => { marks.open = performance.now(); });
    ws.on('message', (raw) => {
      const s = raw.toString();
      const c = s.charAt(0);
      if (c === 'o') {
        ws.send(JSON.stringify([`CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nX-Session-Id:${SESSION_ID}\n\n\x00`]));
      } else if (c === 'a') {
        let arr; try { arr = JSON.parse(s.substring(1)); } catch (_) { return; }
        for (const fr of arr) {
          const cmd = fr.split('\n')[0];
          if (cmd === 'CONNECTED') {
            marks.connected = performance.now();
            ws.send(JSON.stringify([`SUBSCRIBE\nid:sub-msgs\ndestination:/user/queue/messages\n\n\x00`]));
            const bind = JSON.stringify({ sessionId: SESSION_ID });
            ws.send(JSON.stringify([`SEND\ndestination:/app/bind-session\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(bind)}\n\n${bind}\x00`]));
            setTimeout(() => {
              const payload = JSON.stringify({ command: COMMAND, args: ARGS });
              marks.send = performance.now();
              ws.send(JSON.stringify([`SEND\ndestination:/app/command\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(payload)}\n\n${payload}\x00`]));
            }, 120);
          } else if (cmd === 'MESSAGE') {
            if (!marks.msg) { marks.msg = performance.now(); clearTimeout(timer); setTimeout(() => finalize('ok'), 20); }
          } else if (cmd === 'ERROR') {
            clearTimeout(timer); finalize('stomp_error');
          }
        }
      }
    });
    ws.on('error', () => finalize('ws_error'));
    ws.on('close', () => { if (!done) finalize('closed_early'); });
  });
}

(async () => {
  for (let i = 1; i <= N; i++) {
    const r = await once(i);
    console.log(`[${i}/${N}] hs=${r.hs?.toFixed?.(2)}ms rtt=${r.rtt?.toFixed?.(2)}ms status=${r.status}`);
    await new Promise(res => setTimeout(res, 80));
  }
  console.log('DONE → ' + OUT);
  process.exit(0);
})();
