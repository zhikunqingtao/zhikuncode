#!/usr/bin/env python3
"""Merge original 50 + retry1 (16) + retry2 (7) → final 50 results"""
import json

ORIG = "/data/swe-bench/qwen3.6-max-preview.zhikuncode_eval_50.json"
RETRY1 = "/data/swe-bench/qwen3.6-max-preview.zhikuncode_eval_50_retry.json"
RETRY2 = "/data/swe-bench/qwen3.6-max-preview.zhikuncode_eval_50_retry2.json"
MERGED = "/data/swe-bench/qwen3.6-max-preview.zhikuncode_eval_50_FINAL.json"

with open(ORIG) as f:
    orig = json.load(f)
with open(RETRY1) as f:
    r1 = json.load(f)
with open(RETRY2) as f:
    r2 = json.load(f)

# Merge logic: prefer the latest retry's outcome for any instance ID
# Final 50 = original instances NOT in any retry + retry1 results NOT in retry2 + retry2 results

retry2_ids = set(r2["submitted_ids"])
retry1_ids = set(r1["submitted_ids"])

# Build final resolved/unresolved/error sets
def categorize(report):
    return {
        "resolved": set(report.get("resolved_ids", [])),
        "unresolved": set(report.get("unresolved_ids", [])),
        "error": set(report.get("error_ids", [])),
        "completed": set(report.get("completed_ids", [])),
    }

o = categorize(orig)
r1c = categorize(r1)
r2c = categorize(r2)

# Final per-instance outcome: retry2 > retry1 > original
final_resolved, final_unresolved, final_error = set(), set(), set()

for inst in orig["submitted_ids"]:
    if inst in retry2_ids:
        src = r2c
    elif inst in retry1_ids:
        src = r1c
    else:
        src = o
    if inst in src["resolved"]:
        final_resolved.add(inst)
    elif inst in src["unresolved"]:
        final_unresolved.add(inst)
    elif inst in src["error"]:
        final_error.add(inst)
    else:
        # Shouldn't happen, but fallback
        final_error.add(inst)

total = len(orig["submitted_ids"])
merged_report = {
    "total_instances": total,
    "submitted_instances": total,
    "completed_instances": len(final_resolved) + len(final_unresolved),
    "resolved_instances": len(final_resolved),
    "unresolved_instances": len(final_unresolved),
    "empty_patch_instances": 0,
    "error_instances": len(final_error),
    "completed_ids": sorted(final_resolved | final_unresolved),
    "resolved_ids": sorted(final_resolved),
    "unresolved_ids": sorted(final_unresolved),
    "error_ids": sorted(final_error),
    "submitted_ids": sorted(orig["submitted_ids"]),
    "schema_version": 2,
}

with open(MERGED, "w") as f:
    json.dump(merged_report, f, ensure_ascii=False, indent=2)

resolve_rate = len(final_resolved) / total * 100
print(f"==== FINAL MERGED REPORT ====")
print(f"Total       : {total}")
print(f"Resolved    : {len(final_resolved)}  ({resolve_rate:.1f}%)")
print(f"Unresolved  : {len(final_unresolved)}")
print(f"Error       : {len(final_error)}")
print(f"")
print(f"==== ORIGINAL vs FINAL ====")
print(f"Original resolved: {len(o['resolved'])} ({len(o['resolved'])/total*100:.1f}%)")
print(f"Final resolved   : {len(final_resolved)} ({resolve_rate:.1f}%)")
print(f"Improvement      : +{len(final_resolved) - len(o['resolved'])} resolved ({resolve_rate - len(o['resolved'])/total*100:.1f} pp)")
print(f"")
print(f"==== RETRY DETAILS ====")
print(f"Retry1 (16): resolved={len(r1c['resolved'])}, unresolved={len(r1c['unresolved'])}, error={len(r1c['error'])}")
print(f"Retry2 (7) : resolved={len(r2c['resolved'])}, unresolved={len(r2c['unresolved'])}, error={len(r2c['error'])}")
print(f"")
print(f"New resolved by retry: {sorted(final_resolved - o['resolved'])}")
print(f"Remaining errors: {sorted(final_error)}")
print(f"")
print(f"Report saved to: {MERGED}")
