#!/usr/bin/env python3
"""SWE-bench Lite submission compliance verification."""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
try:
    import yaml
except ImportError:
    yaml = None

ROOT = Path(__file__).parent
PREDS = ROOT / "all_preds.jsonl"
TRAJS = ROOT / "trajs"
LOGS = ROOT / "logs"
META = ROOT / "metadata.yaml"
README = ROOT / "README.md"

REQUIRED = {"instance_id", "model_name_or_path", "model_patch"}
EXPECTED_MODEL = "zhikuncode"
EXPECTED_RESOLVED = 139
EXPECTED_TOTAL = 300
EXPECTED_REPORTS_MIN = 251

results = []
def check(n, ok, d):
    results.append((n, ok, d))
    print(f"[{'PASS' if ok else 'FAIL'}] {n}: {d}")

# 1
preds, errs = [], []
for i, line in enumerate(PREDS.read_text(encoding='utf-8').splitlines(), 1):
    if not line.strip(): continue
    try: o = json.loads(line)
    except Exception as e: errs.append(f"L{i}:{e}"); continue
    miss = REQUIRED - set(o.keys())
    if miss: errs.append(f"L{i} miss {miss}")
    preds.append(o)
ok1 = len(preds) == EXPECTED_TOTAL and not errs
check("1. all_preds.jsonl 300行+必需字段", ok1, f"行数={len(preds)} 错={len(errs)}")

# 2
trajs = sorted(p.name for p in TRAJS.glob("*.md"))
ok2 = len(trajs) == EXPECTED_TOTAL
check("2. trajs/ 文件数", ok2, f"{len(trajs)} .md 文件")

# 3
log_dirs = [p for p in LOGS.iterdir() if p.is_dir()]
rep = sum(1 for d in log_dirs if (d/"report.json").exists())
pat = sum(1 for d in log_dirs if (d/"patch.diff").exists())
rl = sum(1 for d in log_dirs if (d/"run_instance.log").exists())
to = sum(1 for d in log_dirs if (d/"test_output.txt").exists())
ok3 = rep >= EXPECTED_REPORTS_MIN
check("3. logs/ 评测日志", ok3, f"目录={len(log_dirs)} report.json={rep} patch.diff={pat} run_instance.log={rl} test_output.txt={to}")

# 4
meta_ok = False
detail = ""
try:
    raw = META.read_text(encoding='utf-8')
    m = yaml.safe_load(raw) if yaml else None
    if m and isinstance(m, dict):
        keys = set(m.keys())
        req = {"info","model","org","os_model","os_system","system","tags"}
        miss = req - keys
        meta_ok = not miss
        detail = f"keys={sorted(keys)} miss={sorted(miss) if miss else '无'}"
    else:
        meta_ok = bool(raw.strip())
        detail = "PyYAML 不可用"
except Exception as e:
    detail = f"err:{e}"
check("4. metadata.yaml 格式", meta_ok, detail)

# 5
txt = README.read_text(encoding='utf-8') if README.exists() else ""
nums = ["46.3","139","300"]
words = ["合规","Resolve"]
hn = [k for k in nums if k in txt]
hw = [k for k in words if k in txt]
ok5 = len(hn) == 3 and bool(hw)
check("5. README.md 内容", ok5, f"长度={len(txt)} 数字命中={hn} 关键词命中={hw}")

# 6
mc = Counter(p.get("model_name_or_path","<MISS>") for p in preds)
ok6 = len(mc) == 1 and EXPECTED_MODEL in mc
check("6. model_name_or_path 一致性", ok6, f"分布={dict(mc)}")

# 7
resolved = unresolved = parse_err = 0
for d in log_dirs:
    rp = d/"report.json"
    if not rp.exists(): continue
    try:
        r = json.loads(rp.read_text(encoding='utf-8'))
        ir = False
        if isinstance(r, dict):
            if "resolved" in r: ir = bool(r["resolved"])
            else:
                for v in r.values():
                    if isinstance(v, dict) and "resolved" in v:
                        ir = bool(v["resolved"]); break
        if ir: resolved += 1
        else: unresolved += 1
    except Exception: parse_err += 1
ok7 = resolved == EXPECTED_RESOLVED
check("7. resolved 数量", ok7, f"resolved={resolved} (expect {EXPECTED_RESOLVED}) unresolved={unresolved} parse_err={parse_err}")

# 8
ids = [p.get("instance_id") for p in preds]
ic = Counter(ids)
dup = {k:v for k,v in ic.items() if v>1}
ok8 = not dup
check("8. instance_id 唯一性", ok8, f"unique={len(set(ids))}/{len(ids)} dup={dup if dup else '无'}")

# cross
pid = set(ids); tid = {f[:-3] for f in trajs}; lid = {d.name for d in log_dirs}
print()
print("==== 交叉一致性 ====")
print(f"preds∩trajs={len(pid&tid)} preds-only={len(pid-tid)} trajs-only={len(tid-pid)}")
print(f"preds∩logs ={len(pid&lid)} preds-only={len(pid-lid)} logs-only ={len(lid-pid)}")

print()
print("==== 总览 ====")
passed = sum(1 for _,ok,_ in results if ok)
print(f"通过 {passed}/{len(results)}")
for n,ok,_ in results:
    print(f"  - [{'PASS' if ok else 'FAIL'}] {n}")
sys.exit(0 if passed == len(results) else 1)
