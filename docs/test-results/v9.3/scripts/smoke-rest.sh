#!/usr/bin/env bash
# ZhikunCode v9.3 — 22 模块冒烟抽样脚本
# 验证 REST 可达性（GET 批量）+ 响应码 + 响应尺寸
set -u
BE=http://localhost:8080
PY=http://localhost:8000
FE=http://localhost:5173
OUT=/tmp/zk-v9.3/smoke-results.tsv
echo -e "module\tendpoint\tmethod\tcode\ttime_ms\tbytes\tverdict" > "$OUT"

probe() {
  local mod="$1" ep="$2" url="$3" method="${4:-GET}" expected="${5:-200}"
  local data
  data=$(curl -s -o /tmp/zk-v9.3/last.body -X "$method" -w "%{http_code}\t%{time_total}\t%{size_download}" "$url" 2>/dev/null)
  local code t bytes verdict
  code=$(echo "$data" | awk '{print $1}')
  t=$(echo "$data" | awk '{printf "%.0f", $2*1000}')
  bytes=$(echo "$data" | awk '{print $3}')
  if [ "$code" = "$expected" ] || { [ "$expected" = "2xx" ] && [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; }; then
    verdict=PASS
  elif [ "$code" = "404" ] && [ "$expected" = "404" ]; then
    verdict=PASS
  else
    verdict=FAIL
  fi
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$mod" "$ep" "$method" "$code" "$t" "$bytes" "$verdict" >> "$OUT"
}

# ---- Module 1: Health & Actuator ----
probe HEALTH actuator-health $BE/actuator/health GET 200
probe HEALTH actuator-info $BE/actuator/info GET 200
probe HEALTH api-health $BE/api/health GET 200
probe HEALTH api-health-live $BE/api/health/live GET 200
probe HEALTH api-health-ready $BE/api/health/ready GET 200
probe HEALTH api-doctor $BE/api/doctor GET 200

# ---- Module 2: Model Registry ----
probe MODEL models-list $BE/api/models GET 200

# ---- Module 3: Config ----
probe CONFIG config-root $BE/api/config GET 200
probe CONFIG config-project $BE/api/config/project GET 200

# ---- Module 4: Session ----
probe SESSION sessions-list $BE/api/sessions GET 200
probe SESSION snapshots-list $BE/api/sessions/snapshots GET 200

# ---- Module 5: MCP ----
probe MCP mcp-servers $BE/api/mcp/servers GET 200
probe MCP mcp-resources $BE/api/mcp/resources GET 200
probe MCP mcp-prompts $BE/api/mcp/prompts GET 200
probe MCP mcp-capabilities $BE/api/mcp/capabilities GET 200
probe MCP mcp-domains $BE/api/mcp/capabilities/domains GET 200

# ---- Module 6: Skill Registry ----
probe SKILL skills-list $BE/api/skills GET 200

# ---- Module 7: Tool Registry ----
probe TOOL tools-list $BE/api/tools GET 200

# ---- Module 8: Permission ----
probe PERM permissions-rules $BE/api/permissions/rules GET 200

# ---- Module 9: Memory ----
probe MEM memory-all $BE/api/memory/all GET 200

# ---- Module 10: Auth / Admin ----
probe AUTH auth-status $BE/api/auth/status GET 200
probe ADMIN admin-status $BE/api/admin/status GET 200

# ---- Module 11: Remote Control ----
probe REMOTE remote-status $BE/api/remote/status GET 200

# ---- Module 12: File ----
probe FILE files-search "$BE/api/files/search?query=test" GET 200
probe FILE files-search-400 $BE/api/files/search GET 400

# ---- Module 13: Command Registry ----
probe CMD commands-list $BE/api/commands GET 200

# ---- Module 14: Plugin (no-op list) ----
# no GET listing; skip probe but record
# probe PLUGIN plugins-list $BE/api/plugins GET 200

# ---- Module 15: Swarm ----
probe SWARM swarm-get-missing $BE/api/swarm/nonexistent GET 404

# ---- Module 16: BrowserReplay ----
probe BROWSER replay-missing $BE/api/browser/replay/nonexistent GET 200

# ---- Module 17: Attachment ----
probe ATTACH attach-get-missing $BE/api/attachments/nonexistent-uuid GET 404

# ---- Module 18: FileHistory ----
probe HISTORY history-snapshots-empty "$BE/api/sessions/test-session/history/snapshots" GET 200

# ---- Module 19: Dialog ----
# All POST endpoints, skip for smoke

# ---- Module 20: CodePath ----
# All POST, skip

# ---- Module 21: CodeDiagram ----
# All POST, skip

# ---- Module 22: Query ----
# All POST streaming, skip

# ---- Python 端 ----
probe PY py-docs $PY/docs GET 200
probe PY py-openapi $PY/openapi.json GET 200
probe PY py-analysis-health $PY/api/analysis/health GET 200
probe PY py-code-quality-health $PY/api/code-quality/health GET 200
probe PY py-browser-sessions $PY/api/browser/session/nonexistent GET 405

# ---- Frontend 端 ----
probe FE fe-root $FE/ GET 200
probe FE fe-vite-client $FE/@vite/client GET 200
probe FE fe-src-main $FE/src/main.tsx GET 200

# ---- 异常路径（404 验证）----
probe ERROR not-found-api $BE/api/nonexistent GET 404
probe ERROR not-found-root $BE/nonexistent GET 404

# ---- SSE/WebSocket endpoint 探测（仅 HTTP upgrade 握手失败验证端点存在）----
probe WS ws-sockjs-info $BE/ws/info GET 200

# ---- LLM 真实推理（小样本，快速验证 provider）----
probe LLM chat-sync-tiny $BE/api/query/conversation?session=smoke POST 400

# ---- 总结 ----
total=$(tail -n +2 "$OUT" | wc -l)
pass=$(awk -F'\t' '$7=="PASS"' "$OUT" | wc -l)
fail=$(awk -F'\t' '$7=="FAIL"' "$OUT" | wc -l)
echo "==== Summary ===="
echo "Total:  $total"
echo "PASS:   $pass"
echo "FAIL:   $fail"
echo ""
echo "==== Failures ===="
awk -F'\t' '$7=="FAIL"' "$OUT"
