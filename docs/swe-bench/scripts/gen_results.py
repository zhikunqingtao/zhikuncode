#!/usr/bin/env python3
import json
import os
from datetime import datetime
from pathlib import Path

# 脚本所在目录: <project_root>/docs/swe-bench/scripts
SCRIPT_DIR = Path(__file__).resolve().parent
# 项目根目录: 上溯三级 (scripts -> swe-bench -> docs -> project_root)
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent

# 数据集默认与脚本同目录，可通过环境变量 SWE_BENCH_DATASET 覆盖
DATASET = os.environ.get(
    "SWE_BENCH_DATASET",
    str(SCRIPT_DIR / "swe-bench-lite-full.jsonl"),
)
# 提交目录默认在项目根 swe-bench/leaderboard_workspace 下，可通过 SWE_BENCH_SUBMISSION 覆盖
SUBMISSION = Path(os.environ.get(
    "SWE_BENCH_SUBMISSION",
    str(PROJECT_ROOT / "swe-bench" / "leaderboard_workspace" / "experiments" / "evaluation" / "lite" / "20260520_zhikuncode"),
))

instances = []
with open(DATASET) as f:
    for line in f:
        line = line.strip()
        if line:
            instances.append(json.loads(line))
print(f"Loaded {len(instances)} dataset instances")

resolved_by_repo = {}
resolved_by_time = {}
for inst in instances:
    repo = inst["repo"]
    year = datetime.fromisoformat(inst["created_at"].rstrip("Z")).year
    resolved_by_repo.setdefault(repo, {"resolved": 0, "total": 0})
    resolved_by_time.setdefault(year, {"resolved": 0, "total": 0})

no_generation = []
no_logs = []
resolved = []
logs_dir = SUBMISSION / "logs"

for inst in instances:
    iid = inst["instance_id"]
    repo = inst["repo"]
    year = datetime.fromisoformat(inst["created_at"].rstrip("Z")).year
    resolved_by_repo[repo]["total"] += 1
    resolved_by_time[year]["total"] += 1

    pred_folder = logs_dir / iid
    if not pred_folder.exists():
        no_generation.append(iid); continue
    if not (pred_folder / "patch.diff").exists():
        no_generation.append(iid); continue
    if not (pred_folder / "test_output.txt").exists() or not (pred_folder / "report.json").exists():
        no_logs.append(iid); continue
    try:
        with open(pred_folder / "report.json") as f:
            report = json.load(f)
        info = report.get(iid, {})
        if info.get("resolved", False):
            resolved.append(iid)
            resolved_by_repo[repo]["resolved"] += 1
            resolved_by_time[year]["resolved"] += 1
    except Exception as e:
        print(f"WARN: {iid}: {e}")
        no_logs.append(iid)

results_dir = SUBMISSION / "results"
results_dir.mkdir(exist_ok=True)
with open(results_dir / "results.json", "w") as f:
    json.dump({
        "no_generation": sorted(set(no_generation)),
        "no_logs": sorted(set(no_logs)),
        "resolved": sorted(set(resolved)),
    }, f, indent=2)
rbr = {k: resolved_by_repo[k] for k in sorted(resolved_by_repo)}
rbt = {str(k): resolved_by_time[k] for k in sorted(resolved_by_time)}
with open(results_dir / "resolved_by_repo.json", "w") as f:
    json.dump(rbr, f, indent=2)
with open(results_dir / "resolved_by_time.json", "w") as f:
    json.dump(rbt, f, indent=2)

total = len(instances)
rate = round(len(resolved) * 100.0 / total, 2)
print("="*50)
print(f"Resolved {len(resolved)} / {total} ({rate}%)")
print(f"no_generation: {len(no_generation)}, no_logs: {len(no_logs)}")
print("="*50)
for r, v in rbr.items():
    print(f"  {r}: {v['resolved']}/{v['total']}")
print("---")
for y, v in rbt.items():
    print(f"  {y}: {v['resolved']}/{v['total']}")
