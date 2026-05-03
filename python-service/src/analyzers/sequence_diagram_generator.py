"""
时序图生成器 — 从项目代码生成 Mermaid 时序图

基于 CallGraphBuilder 的调用图，从 API 入口点出发，提取调用链并映射到
Mermaid sequenceDiagram 语法。

- 支持 Java (Spring Boot) 和 Python (FastAPI) 项目
- 自动识别 Controller / Service / Repository 等参与者角色
- 循环调用检测与截断
- 置信度评分
"""

import logging
import re
import time
from collections import deque
from typing import Dict, List, Optional, Set, Tuple

import networkx as nx

from .call_graph_builder import CallGraphBuilder
from .diagram_models import DiagramMetadata, DiagramResult

logger = logging.getLogger(__name__)

# 参与者角色映射规则：路径关键词 → 角色名
_ROLE_PATTERNS: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"(?i)controller"), "Controller"),
    (re.compile(r"(?i)resource"), "Controller"),
    (re.compile(r"(?i)endpoint"), "Controller"),
    (re.compile(r"(?i)service"), "Service"),
    (re.compile(r"(?i)repository"), "Repository"),
    (re.compile(r"(?i)dao"), "Repository"),
    (re.compile(r"(?i)mapper"), "Repository"),
    (re.compile(r"(?i)client"), "External"),
    (re.compile(r"(?i)gateway"), "External"),
    (re.compile(r"(?i)config"), "Config"),
    (re.compile(r"(?i)util"), "Utility"),
    (re.compile(r"(?i)helper"), "Utility"),
]

# API 入口注解/装饰器识别
_JAVA_API_ANNOTATIONS = {
    "@GetMapping", "@PostMapping", "@PutMapping",
    "@DeleteMapping", "@PatchMapping", "@RequestMapping",
}

_PYTHON_ROUTE_DECORATORS = {
    "app.get", "app.post", "app.put", "app.delete", "app.patch",
    "router.get", "router.post", "router.put", "router.delete", "router.patch",
}


def _escape_mermaid(text: str) -> str:
    """转义 Mermaid 语法中的特殊字符"""
    text = text.replace("(", "#40;").replace(")", "#41;")
    text = text.replace("{", "#123;").replace("}", "#125;")
    text = text.replace("[", "#91;").replace("]", "#93;")
    text = text.replace("<", "#60;").replace(">", "#62;")
    return text


class SequenceDiagramGenerator:
    """从项目代码生成 Mermaid 时序图"""

    def __init__(self, project_root: str):
        """
        初始化时序图生成器。

        Args:
            project_root: 项目根目录路径
        """
        self._project_root = project_root
        self._builder = CallGraphBuilder()
        self._graph: Optional[nx.DiGraph] = None

    # ── 公共 API ──────────────────────────────────────────

    def generate(
        self,
        target: str,
        depth: int = 3,
        include_tests: bool = False,
    ) -> DiagramResult:
        """
        生成指定 API 端点的时序图。

        Args:
            target: API 路径（如 /api/sessions/create）或方法签名
                    （如 SessionController.createSession）
            depth: 追踪深度 (1-5)，默认 3
            include_tests: 是否包含测试文件

        Returns:
            DiagramResult 包含 mermaid_syntax, confidence_score, metadata, warnings
        """
        start_time = time.monotonic()
        warnings: List[str] = []
        depth = max(1, min(5, depth))

        try:
            # 1. 构建调用图
            self._ensure_graph(skip_tests=not include_tests)

            # 2. 定位入口节点
            entry_nodes = self._find_entry_nodes(target)
            if not entry_nodes:
                elapsed = (time.monotonic() - start_time) * 1000
                warnings.append(f"未找到匹配 '{target}' 的 API 入口节点")
                return DiagramResult(
                    diagram_type="sequence",
                    mermaid_syntax="sequenceDiagram\n    Note over Client: 未找到匹配的入口节点",
                    confidence_score=0.0,
                    metadata=DiagramMetadata(
                        nodes_count=0, edges_count=0,
                        languages_analyzed=self._detected_languages(),
                        analysis_time_ms=elapsed,
                    ),
                    warnings=warnings,
                )

            # 3. 提取调用链
            call_chains, cycle_warnings = self._extract_call_chains(
                entry_nodes, depth
            )
            warnings.extend(cycle_warnings)

            # 4. 识别参与者
            participants = self._identify_participants(call_chains)

            # 5. 生成 Mermaid 语法
            mermaid = self._render_mermaid(participants, call_chains)

            # 6. 计算置信度
            confidence = self._compute_confidence(
                call_chains, participants, cycle_warnings
            )

            elapsed = (time.monotonic() - start_time) * 1000
            nodes_in_chains = set()
            edges_count = 0
            for chain in call_chains:
                for caller, callee, _ in chain:
                    nodes_in_chains.add(caller)
                    nodes_in_chains.add(callee)
                    edges_count += 1

            return DiagramResult(
                diagram_type="sequence",
                mermaid_syntax=mermaid,
                confidence_score=round(confidence, 2),
                metadata=DiagramMetadata(
                    nodes_count=len(nodes_in_chains),
                    edges_count=edges_count,
                    languages_analyzed=self._detected_languages(),
                    analysis_time_ms=round(elapsed, 1),
                ),
                warnings=warnings,
            )

        except Exception as e:
            elapsed = (time.monotonic() - start_time) * 1000
            logger.error("时序图生成失败: %s", e, exc_info=True)
            warnings.append(f"生成过程出错: {e}")
            return DiagramResult(
                diagram_type="sequence",
                mermaid_syntax="sequenceDiagram\n    Note over Client: 生成失败",
                confidence_score=0.0,
                metadata=DiagramMetadata(
                    nodes_count=0, edges_count=0,
                    languages_analyzed=[],
                    analysis_time_ms=round(elapsed, 1),
                ),
                warnings=warnings,
            )

    # ── 调用图构建 ────────────────────────────────────────

    def _ensure_graph(self, skip_tests: bool = True) -> None:
        """构建调用图（如果尚未构建）"""
        if self._graph is None:
            self._graph = self._builder.build(
                self._project_root, skip_tests=skip_tests,
            )
            logger.info(
                "调用图构建完成: %d 节点, %d 边",
                self._graph.number_of_nodes(),
                self._graph.number_of_edges(),
            )

    def _detected_languages(self) -> List[str]:
        """返回调用图中检测到的编程语言"""
        if self._graph is None:
            return []
        languages: Set[str] = set()
        for _, attrs in self._graph.nodes(data=True):
            lang = attrs.get("language")
            if lang:
                languages.add(lang)
        return sorted(languages)

    # ── 入口节点定位 ──────────────────────────────────────

    def _find_entry_nodes(self, target: str) -> List[str]:
        """
        从调用图中查找匹配 target 的 API 入口节点。

        匹配策略（按优先级）：
          1. 节点 ID 包含 target（完全或部分匹配）
          2. 节点名称匹配 target 中的方法名部分
          3. API 路径匹配（从注解/装饰器中提取的路径）
        """
        if self._graph is None:
            return []

        target_lower = target.lower().strip("/")
        # 如果 target 是方法签名，提取方法名
        method_name = target.split(".")[-1] if "." in target else target

        candidates: List[Tuple[str, int]] = []  # (node_id, priority)

        for node_id, attrs in self._graph.nodes(data=True):
            node_type = attrs.get("type", "")
            node_name = attrs.get("name", "")
            node_id_lower = node_id.lower()

            # 策略 1: 节点 ID 完全包含 target
            if target_lower in node_id_lower:
                priority = 0 if node_type == "api" else 2
                candidates.append((node_id, priority))
                continue

            # 策略 2: 方法名匹配
            if node_name.lower() == method_name.lower():
                priority = 1 if node_type == "api" else 3
                candidates.append((node_id, priority))
                continue

            # 策略 3: API 路径匹配（在节点 ID 中搜索路径片段）
            if "/" in target:
                path_parts = [p for p in target.split("/") if p]
                if path_parts and any(
                    part.lower() in node_id_lower for part in path_parts[-2:]
                ):
                    if node_type == "api":
                        candidates.append((node_id, 1))

        # 按优先级排序，取最优匹配
        candidates.sort(key=lambda x: x[1])
        seen: Set[str] = set()
        result: List[str] = []
        for nid, _ in candidates:
            if nid not in seen:
                seen.add(nid)
                result.append(nid)
            if len(result) >= 3:  # 最多 3 个入口
                break

        return result

    # ── 调用链提取 ────────────────────────────────────────

    def _extract_call_chains(
        self,
        entry_nodes: List[str],
        max_depth: int,
    ) -> Tuple[List[List[Tuple[str, str, str]]], List[str]]:
        """
        从入口节点 BFS 遍历，提取调用链。

        Returns:
            (call_chains, warnings)
            call_chains: 每条链为 [(caller, callee, edge_type), ...]
            warnings: 循环截断等警告
        """
        if self._graph is None:
            return [], []

        all_chains: List[List[Tuple[str, str, str]]] = []
        warnings: List[str] = []
        global_visited: Set[str] = set()

        for entry in entry_nodes:
            chain: List[Tuple[str, str, str]] = []
            visited: Set[str] = {entry}
            queue: deque = deque()  # (current_node, depth)

            # 入队：entry 的所有后继
            for successor in self._graph.successors(entry):
                edge_data = self._graph.edges.get((entry, successor), {})
                edge_type = edge_data.get("type", "call")
                queue.append((entry, successor, edge_type, 1))

            while queue:
                caller, callee, edge_type, current_depth = queue.popleft()

                if current_depth > max_depth:
                    continue

                # 循环检测
                if callee in visited:
                    warnings.append(
                        f"循环调用被截断: {self._short_name(caller)} → "
                        f"{self._short_name(callee)}"
                    )
                    continue

                visited.add(callee)
                chain.append((caller, callee, edge_type))

                # 继续遍历 callee 的后继
                if current_depth < max_depth:
                    for next_callee in self._graph.successors(callee):
                        next_edge = self._graph.edges.get(
                            (callee, next_callee), {}
                        )
                        queue.append((
                            callee,
                            next_callee,
                            next_edge.get("type", "call"),
                            current_depth + 1,
                        ))

            if chain:
                all_chains.append(chain)
            global_visited.update(visited)

        return all_chains, warnings

    # ── 参与者识别 ────────────────────────────────────────

    def _identify_participants(
        self,
        call_chains: List[List[Tuple[str, str, str]]],
    ) -> Dict[str, str]:
        """
        根据节点 ID / 文件路径分类参与者角色。

        Returns:
            {node_id: role_name} 映射
        """
        if self._graph is None:
            return {}

        participants: Dict[str, str] = {}
        all_nodes: Set[str] = set()

        for chain in call_chains:
            for caller, callee, _ in chain:
                all_nodes.add(caller)
                all_nodes.add(callee)

        for node_id in all_nodes:
            attrs = self._graph.nodes.get(node_id, {})
            node_type = attrs.get("type", "")
            file_path = attrs.get("file_path", "")

            # 优先使用调用图中的节点类型
            if node_type == "api":
                participants[node_id] = "Controller"
                continue
            if node_type == "service":
                participants[node_id] = "Service"
                continue
            if node_type == "repository":
                participants[node_id] = "Repository"
                continue
            if node_type == "scheduler":
                participants[node_id] = "Scheduler"
                continue
            if node_type == "config":
                participants[node_id] = "Config"
                continue

            # 用路径关键词匹配
            combined = f"{node_id} {file_path}"
            role = self._classify_by_pattern(combined)
            participants[node_id] = role

        return participants

    @staticmethod
    def _classify_by_pattern(text: str) -> str:
        """通过路径/ID 模式匹配角色"""
        for pattern, role in _ROLE_PATTERNS:
            if pattern.search(text):
                return role
        return "Component"

    # ── Mermaid 渲染 ──────────────────────────────────────

    def _render_mermaid(
        self,
        participants: Dict[str, str],
        call_chains: List[List[Tuple[str, str, str]]],
    ) -> str:
        """将参与者和调用链渲染为 Mermaid sequenceDiagram 语法"""
        lines: List[str] = ["sequenceDiagram"]

        # 按角色分组，生成 participant 声明
        role_order = [
            "Controller", "Service", "Repository", "External",
            "Config", "Scheduler", "Utility", "Component",
        ]
        declared: Dict[str, str] = {}  # alias -> role
        alias_map: Dict[str, str] = {}  # node_id -> alias
        alias_counter: Dict[str, int] = {}

        # 构建 alias 映射（同角色多实例时添加序号）
        role_nodes: Dict[str, List[str]] = {}
        for node_id, role in participants.items():
            role_nodes.setdefault(role, []).append(node_id)

        for role in role_order:
            nodes = role_nodes.get(role, [])
            nodes.sort()
            for i, node_id in enumerate(nodes):
                short = self._short_name(node_id)
                if len(nodes) == 1:
                    alias = role[0].upper()
                    if alias in declared:
                        alias = f"{role[0].upper()}{role[1].lower()}"
                else:
                    alias = f"{role[0].upper()}{i + 1}"

                # 去重 alias
                base_alias = alias
                counter = 1
                while alias in declared:
                    alias = f"{base_alias}_{counter}"
                    counter += 1

                declared[alias] = role
                alias_map[node_id] = alias
                label = _escape_mermaid(short)
                lines.append(f"    participant {alias} as {label}")

        # 生成调用消息
        for chain in call_chains:
            for caller, callee, edge_type in chain:
                caller_alias = alias_map.get(caller, "?")
                callee_alias = alias_map.get(callee, "?")

                if caller_alias == callee_alias:
                    continue  # 跳过自调用

                callee_name = _escape_mermaid(self._short_name(callee))

                if edge_type == "dependency":
                    # 虚线箭头表示依赖
                    lines.append(
                        f"    {caller_alias}-->>+{callee_alias}: {callee_name}"
                    )
                    lines.append(
                        f"    {callee_alias}-->>-{caller_alias}: return"
                    )
                else:
                    # 实线箭头表示调用
                    lines.append(
                        f"    {caller_alias}->>+{callee_alias}: {callee_name}"
                    )
                    lines.append(
                        f"    {callee_alias}-->>-{caller_alias}: return"
                    )

        return "\n".join(lines)

    # ── 置信度计算 ────────────────────────────────────────

    def _compute_confidence(
        self,
        call_chains: List[List[Tuple[str, str, str]]],
        participants: Dict[str, str],
        cycle_warnings: List[str],
    ) -> float:
        """
        计算时序图的置信度评分 (0-1)。

        评分规则:
          - 基线: 0.5
          - 所有节点都有明确的方法签名: +0.2
          - 调用链无循环截断: +0.1
          - 所有参与者都有明确角色: +0.1
          - 没有 "unknown" 类型的调用: +0.1
        """
        score = 0.5

        # 检查方法签名
        all_nodes: Set[str] = set()
        has_unknown_calls = False
        for chain in call_chains:
            for caller, callee, edge_type in chain:
                all_nodes.add(caller)
                all_nodes.add(callee)
                if edge_type == "unknown":
                    has_unknown_calls = True

        all_have_signature = all("." in nid for nid in all_nodes)
        if all_have_signature and all_nodes:
            score += 0.2

        # 无循环截断
        if not cycle_warnings:
            score += 0.1

        # 所有参与者都有明确角色
        all_have_roles = all(
            role != "Component" for role in participants.values()
        )
        if all_have_roles and participants:
            score += 0.1

        # 无 unknown 调用
        if not has_unknown_calls:
            score += 0.1

        return min(1.0, score)

    # ── 工具方法 ──────────────────────────────────────────

    @staticmethod
    def _short_name(node_id: str) -> str:
        """从节点 ID 提取简短名称（最后两段）"""
        parts = node_id.split(".")
        if len(parts) >= 2:
            return f"{parts[-2]}.{parts[-1]}"
        return parts[-1] if parts else node_id
