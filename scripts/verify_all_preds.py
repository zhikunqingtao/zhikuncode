#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证 all_preds.jsonl 完整性与合规性
"""
import json
import os
import statistics
import sys
from collections import Counter

PRED_PATH = "docs/swe-bench/20260531/all_preds.jsonl"
LITE_PATH = "swe-bench/data/swe-bench-lite.json"
EXPECTED_MODEL = "qwen3.7-max"
EXPECTED_COUNT = 300
REQUIRED_FIELDS = ("instance_id", "model_name_or_path", "model_patch")


def hr(title=""):
    print("=" * 78)
    if title:
        print(title)
        print("=" * 78)


def main():
    report = {"checks": [], "issues": []}

    def add_check(name, passed, detail=""):
        status = "PASS" if passed else "FAIL"
        report["checks"].append((name, status, detail))
        print(f"[{status}] {name}" + (f" -- {detail}" if detail else ""))
        if not passed:
            report["issues"].append((name, detail))

    if not os.path.exists(PRED_PATH):
        print(f"[FATAL] 找不到文件 {PRED_PATH}")
        sys.exit(2)
    if not os.path.exists(LITE_PATH):
        print(f"[FATAL] 找不到文件 {LITE_PATH}")
        sys.exit(2)

    # 读取并校验 JSON 格式
    hr("读取 all_preds.jsonl")
    records = []
    bad_json_lines = []
    with open(PRED_PATH, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip():
                bad_json_lines.append((idx, "空行"))
                continue
            try:
                obj = json.loads(line)
                records.append((idx, obj))
            except json.JSONDecodeError as e:
                bad_json_lines.append((idx, str(e)))
    print(f"读取成功: {len(records)} 条; JSON 异常: {len(bad_json_lines)}")

    # 1. 行数验证
    hr("1. 行数验证")
    line_count = len(records) + len(bad_json_lines)
    add_check("行数恰好 300", line_count == EXPECTED_COUNT,
              f"实际行数={line_count}")

    # 7. JSON 格式（顺带先做）
    hr("7. JSON 格式合法")
    add_check("每行都是合法 JSON", len(bad_json_lines) == 0,
              f"非法行={len(bad_json_lines)}; 示例={bad_json_lines[:3]}")

    # 2. 字段完整性
    hr("2. 字段完整性")
    missing_field_records = []
    for idx, obj in records:
        miss = [k for k in REQUIRED_FIELDS if k not in obj]
        if miss:
            missing_field_records.append((idx, miss))
    add_check("所有记录含必需字段", not missing_field_records,
              f"缺失记录数={len(missing_field_records)}; 示例={missing_field_records[:3]}")

    # 3. instance_id 覆盖率
    hr("3. instance_id 覆盖率")
    lite_data = []
    with open(LITE_PATH, "r", encoding="utf-8") as f:
        head = f.read(1)
        f.seek(0)
        if head == "[":
            obj = json.load(f)
            if isinstance(obj, dict) and "instances" in obj:
                obj = obj["instances"]
            lite_data = obj
        else:
            # JSONL
            for ln in f:
                ln = ln.strip()
                if ln:
                    lite_data.append(json.loads(ln))
    lite_ids = {item["instance_id"] for item in lite_data if "instance_id" in item}
    print(f"swe-bench-lite 总实例数: {len(lite_ids)}")

    pred_ids = [obj.get("instance_id") for _, obj in records if "instance_id" in obj]
    pred_id_set = set(pred_ids)
    missing_in_preds = lite_ids - pred_id_set
    extra_in_preds = pred_id_set - lite_ids
    add_check("instance_id 完全匹配 swe-bench-lite",
              not missing_in_preds and not extra_in_preds,
              f"遗漏={len(missing_in_preds)}, 多余={len(extra_in_preds)}")
    if missing_in_preds:
        print("  遗漏示例:", list(sorted(missing_in_preds))[:5])
    if extra_in_preds:
        print("  多余示例:", list(sorted(extra_in_preds))[:5])

    # 4. 无重复
    hr("4. 无重复 instance_id")
    counter = Counter(pred_ids)
    dup = {k: v for k, v in counter.items() if v > 1}
    add_check("无重复 instance_id", not dup,
              f"重复条目数={len(dup)}; 示例={list(dup.items())[:3]}")

    # 5. model_name_or_path 一致性
    hr("5. model_name_or_path 一致性")
    model_names = Counter(obj.get("model_name_or_path") for _, obj in records)
    print(f"model_name_or_path 分布: {dict(model_names)}")
    only_one = len(model_names) == 1
    correct_value = list(model_names.keys())[0] == EXPECTED_MODEL if only_one else False
    add_check(
        f"所有 model_name_or_path 都为 '{EXPECTED_MODEL}'",
        only_one and correct_value,
        f"distinct={len(model_names)}, value={list(model_names.keys())}",
    )

    # 6. model_patch 有效性
    hr("6. model_patch 有效性")
    empty_patches = []
    invalid_prefix = []
    line_counts = []
    for idx, obj in records:
        patch = obj.get("model_patch", "")
        if not isinstance(patch, str) or len(patch) == 0:
            empty_patches.append((idx, obj.get("instance_id")))
            continue
        # 允许行首空白后再判断
        stripped = patch.lstrip()
        if not (stripped.startswith("diff --git") or stripped.startswith("---")):
            invalid_prefix.append((idx, obj.get("instance_id"),
                                   patch[:60].replace("\n", "\\n")))
        line_counts.append(patch.count("\n") + (0 if patch.endswith("\n") else 1))

    add_check("所有 patch 非空", not empty_patches,
              f"空 patch 数={len(empty_patches)}; 示例={empty_patches[:5]}")
    add_check("所有 patch 以 'diff --git' 或 '---' 开头",
              not invalid_prefix,
              f"非法前缀数={len(invalid_prefix)}; 示例={invalid_prefix[:3]}")

    # 行数分布
    if line_counts:
        mn, mx = min(line_counts), max(line_counts)
        avg = sum(line_counts) / len(line_counts)
        med = statistics.median(line_counts)
        print(f"patch 行数分布: min={mn}, max={mx}, mean={avg:.2f}, median={med}")
        report["patch_line_stats"] = {
            "min": mn, "max": mx, "mean": round(avg, 2), "median": med,
            "count": len(line_counts),
        }
    else:
        print("无可统计 patch")

    # 汇总
    hr("汇总报告")
    total = len(report["checks"])
    passed = sum(1 for _, s, _ in report["checks"] if s == "PASS")
    failed = total - passed
    print(f"检查项总数: {total}; 通过: {passed}; 失败: {failed}")
    if failed:
        print("失败项:")
        for name, detail in report["issues"]:
            print(f"  - {name}: {detail}")
    print()
    print("结论: " + ("✅ 全部通过" if failed == 0 else f"❌ 存在 {failed} 项问题"))
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
