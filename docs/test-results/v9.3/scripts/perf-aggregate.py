#!/usr/bin/env python3
"""v9.3 性能聚合 - 从 TSV 计算 p50/p95/p99/min/max/mean。"""
import csv
import sys
from pathlib import Path
from statistics import mean


def percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def agg(path: Path, out: Path):
    buckets = {}
    with path.open() as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            key = row["endpoint"]
            try:
                rtt = float(row["rtt_ms"])
            except Exception:
                continue
            http = row["http"]
            buckets.setdefault(key, {"rtt": [], "http": []})
            buckets[key]["rtt"].append(rtt)
            buckets[key]["http"].append(http)

    lines = []
    lines.append(
        "| endpoint | N | ok | p50 | p95 | p99 | min | max | mean |"
    )
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for name in sorted(buckets):
        v = buckets[name]
        rtts = sorted(v["rtt"])
        ok = sum(1 for h in v["http"] if h.startswith("2"))
        lines.append(
            f"| {name} | {len(rtts)} | {ok} |"
            f" {percentile(rtts,50):.2f} | {percentile(rtts,95):.2f} |"
            f" {percentile(rtts,99):.2f} | {min(rtts):.2f} |"
            f" {max(rtts):.2f} | {mean(rtts):.2f} |"
        )
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"summary saved: {out}")
    print("\n".join(lines))


if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "docs/test-results/v9.3/perf/rest-samples.tsv"
    )
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_suffix(".summary.md")
    agg(src, dst)
