#!/usr/bin/env bash
# v9.3 性能探针 - REST 同步调用
# 每个端点采样 N=30，输出 p50/p95/p99/min/max/mean (ms)
set -euo pipefail
N=${N:-30}
OUT=${OUT:-docs/test-results/v9.3/perf/rest-samples.tsv}
mkdir -p "$(dirname "$OUT")"

endpoints=(
  "backend_actuator_health|GET|http://localhost:8080/actuator/health"
  "backend_api_skills|GET|http://localhost:8080/api/skills"
  "backend_api_models|GET|http://localhost:8080/api/models"
  "backend_api_commands|GET|http://localhost:8080/api/commands"
  "backend_api_mcp_servers|GET|http://localhost:8080/api/mcp/servers"
  "backend_api_swarm_list|GET|http://localhost:8080/api/swarm"
  "backend_api_config|GET|http://localhost:8080/api/config"
  "backend_api_tools|GET|http://localhost:8080/api/tools"
  "backend_api_plugins|GET|http://localhost:8080/api/plugins"
  "backend_api_memory|GET|http://localhost:8080/api/memory"
  "backend_api_admin_status|GET|http://localhost:8080/api/admin/status"
  "python_api_health|GET|http://localhost:8000/api/health"
  "python_api_health_capabilities|GET|http://localhost:8000/api/health/capabilities"
  "frontend_index|GET|http://localhost:5173/"
)

echo -e "endpoint\tmethod\turl\tidx\thttp\trtt_ms" > "$OUT"
for entry in "${endpoints[@]}"; do
  IFS='|' read -r name method url <<<"$entry"
  for i in $(seq 1 "$N"); do
    line=$(curl -sS -o /dev/null -w "%{http_code}\t%{time_total}" -X "$method" "$url" || echo "000\t0")
    http=$(echo "$line" | cut -f1)
    t=$(echo "$line" | cut -f2)
    rtt_ms=$(awk -v t="$t" 'BEGIN{printf "%.3f", t*1000}')
    echo -e "${name}\t${method}\t${url}\t${i}\t${http}\t${rtt_ms}" >> "$OUT"
  done
done
echo "samples saved: $OUT"
