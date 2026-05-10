#!/usr/bin/env bash
# v9.3 Swarm 创建性能
set -euo pipefail
N=${N:-20}
OUT=${OUT:-docs/test-results/v9.3/perf/swarm-create-samples.tsv}
mkdir -p "$(dirname "$OUT")"
echo -e "idx\thttp\trtt_ms" > "$OUT"
for i in $(seq 1 "$N"); do
  line=$(curl -sS -o /dev/null -w "%{http_code}\t%{time_total}" -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"perf-swarm-$i\",\"teamName\":\"perf-team-$i\",\"maxWorkers\":4}" \
    http://localhost:8080/api/swarm || echo "000\t0")
  http=$(echo "$line" | cut -f1)
  t=$(echo "$line" | cut -f2)
  rtt_ms=$(awk -v t="$t" 'BEGIN{printf "%.3f", t*1000}')
  echo -e "${i}\t${http}\t${rtt_ms}" >> "$OUT"
  echo "[$i/$N] http=$http rtt=${rtt_ms}ms"
done
echo "DONE → $OUT"
