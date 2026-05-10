#!/usr/bin/env python3
"""
Task 3-5 大页面 fixture 生成器
引用：docs/Task3-5差异化升级功能测试方案.md §11.13（TC-T5-EX-03 离线替代）

用法：python3 python-service/tests/fixtures/snapshot_pages/_generate_large_page.py
产出：同目录 large_page_10k.html（约 10000 DOM 节点）
"""
import os

OUTPUT = os.path.join(os.path.dirname(__file__), "large_page_10k.html")

HEADER = """<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>Large Page 10k</title></head>
<body>
<h1>Large Page Fixture (Generated)</h1>
"""

FOOTER = """
</body>
</html>
"""


def main() -> None:
    rows = []
    # 每轮产出 5 个节点 (div + button + input + a + span)，共 2000 轮 ≈ 10000 节点
    for i in range(2000):
        rows.append(
            f'<div data-row="{i}">'
            f'<button aria-label="Btn {i}">B{i}</button>'
            f'<input type="text" name="field-{i}" placeholder="Field {i}">'
            f'<a href="#row-{i}">Link {i}</a>'
            f'<span>Text {i}</span>'
            f'</div>'
        )
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write(HEADER)
        f.write("\n".join(rows))
        f.write(FOOTER)
    print(f"Generated {OUTPUT} with ~10000 nodes")


if __name__ == "__main__":
    main()
