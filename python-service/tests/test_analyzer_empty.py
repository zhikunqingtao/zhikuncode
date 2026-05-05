"""
TC-PY-ANALYZER-003: 空结果处理
修改的文件不在调用图中，验证返回空 impact_nodes
"""
import pytest
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from analyzers.change_impact_analyzer import ChangeImpactAnalyzer


@pytest.mark.asyncio
@pytest.mark.timeout(30)
async def test_change_impact_empty_result(tmp_path):
    """TC-PY-ANALYZER-003: 空结果处理"""
    # 准备：创建孤立文件（无调用关系）
    (tmp_path / "isolated.py").write_text('''
def standalone_func():
    return 42
''')
    (tmp_path / "other.py").write_text('''
def unrelated():
    return "hello"
''')

    # 执行：分析孤立文件的变更影响
    analyzer = ChangeImpactAnalyzer()
    result = await analyzer.analyze(
        file_path=str(tmp_path / "isolated.py"),
        changed_lines=[2],
        project_root=str(tmp_path),
        depth=3,
    )

    # 验证：返回结果不为 null，但 impact_nodes 为空或仅包含自身
    assert result is not None
    assert result.summary.indirect_count == 0
