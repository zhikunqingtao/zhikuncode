#!/usr/bin/env python3
"""v9.3 通用 TSV 百分位聚合。"""
import csv
import sys
from pathlib import Path
from statistics import mean


def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * (p / 100.0)
    f = int(k); c = min(f + 1, len(vals) - 1)
    return vals[f] if f == c else vals[f] + (vals[c] - vals[f]) * (k - f)


def summarize(label, path: Path, col: str, http_col: str = None):
    with path.open() as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    vals = []
    okn = 0
    for r in rows:
        try:
            v = float(r[col])
            if v < 0:
                continue
            vals.append(v)
            if http_col and str(r.get(http_col, "")).startswith("2"):
                okn += 1
            elif r.get("status") == "ok":
                okn += 1
        except Exception:
            continue
    vals.sort()
    print(
        f"| {label} | {len(vals)} | {okn} | "
        f"{pct(vals,50):.2f} | {pct(vals,95):.2f} | {pct(vals,99):.2f} | "
        f"{min(vals):.2f} | {max(vals):.2f} | {mean(vals):.2f} |"
    )


if __name__ == "__main__":
    base = Path("docs/test-results/v9.3/perf")
    print("| metric | N | ok | p50 | p95 | p99 | min | max | mean |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    summarize("ws_handshake_ms", base / "ws-samples.tsv", "handshake_ms")
    summarize("ws_slash_rtt_ms", base / "ws-samples.tsv", "slash_rtt_ms")
    summarize(
        "browser_snapshot_all_ms",
        base / "browser-snap-samples.tsv", "rtt_ms", http_col="http",
    )
    # 热路径（去除第 1 次冷启动）
    warm_path = base / "browser-snap-warm.tsv"
    if (base / "browser-snap-samples.tsv").exists():
        with (base / "browser-snap-samples.tsv").open() as src, warm_path.open("w") as dst:
            lines = src.readlines()
            dst.write(lines[0])
            for ln in lines[2:]:  # 跳过 header + 第一条冷启动
                dst.write(ln)
        summarize(
            "browser_snapshot_warm_ms", warm_path, "rtt_ms", http_col="http"
        )
    summarize(
        "swarm_create_ms", base / "swarm-create-samples.tsv", "rtt_ms", http_col="http"
    )
