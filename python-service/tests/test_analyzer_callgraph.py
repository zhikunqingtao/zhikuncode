"""
TC-PY-ANALYZER-004: Python 文件调用图构建
创建含函数调用的 .py 文件，验证调用图节点和边的正确性

适配说明：
- CallGraphBuilder.build() 是同步方法，返回 nx.DiGraph
- 节点通过 graph.nodes(data=True) 访问，属性存储在 data dict 中
"""
import pytest
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from analyzers.call_graph_builder import CallGraphBuilder


@pytest.mark.timeout(30)
def test_call_graph_python_files(tmp_path):
    """TC-PY-ANALYZER-004: Python 文件调用图构建"""
    # 准备：创建具有明确调用关系的 Python 文件
    (tmp_path / "math_utils.py").write_text('''
def add(a, b):
    return a + b

def multiply(a, b):
    result = 0
    for _ in range(b):
        result = add(result, a)
    return result
''')
    (tmp_path / "calculator.py").write_text('''
from math_utils import add, multiply

class Calculator:
    def compute(self, op, a, b):
        if op == "+":
            return add(a, b)
        elif op == "*":
            return multiply(a, b)
''')

    # 执行：构建调用图（同步方法）
    builder = CallGraphBuilder()
    graph = builder.build(project_root=str(tmp_path))

    # 验证：节点包含关键函数
    node_names = [data.get("name", node_id) for node_id, data in graph.nodes(data=True)]
    assert any("add" in name for name in node_names), f"节点中未找到 'add'，实际节点: {node_names}"
    assert any("multiply" in name for name in node_names), f"节点中未找到 'multiply'，实际节点: {node_names}"
    assert any("compute" in name or "Calculator" in name for name in node_names), \
        f"节点中未找到 'compute' 或 'Calculator'，实际节点: {node_names}"

    # 验证：边包含调用关系
    assert len(graph.edges) >= 2, \
        f"期望至少 2 条边 (multiply->add, compute->add, compute->multiply)，实际: {len(graph.edges)}"
