"""
图表生成共享数据模型

为 SequenceDiagramGenerator 和 FlowChartGenerator 提供统一的返回结构。
"""

from dataclasses import dataclass, field
from typing import List


@dataclass
class DiagramMetadata:
    """图表元数据"""
    nodes_count: int
    edges_count: int
    languages_analyzed: List[str]
    analysis_time_ms: float


@dataclass
class DiagramResult:
    """图表生成结果"""
    diagram_type: str           # "sequence" | "flowchart"
    mermaid_syntax: str
    confidence_score: float     # 0-1
    metadata: DiagramMetadata
    warnings: List[str] = field(default_factory=list)
