"""
代码路径追踪器 — F40 Code Path Tracing

从 API 入口追踪完整调用链：
- 扫描项目所有 API 端点（Java Spring Boot / Python FastAPI / TS Express）
- 从指定入口正向 BFS 遍历调用图
- 对每个节点进行分层标注（controller/service/repository/database/external/utility）
- 生成层级统计

核心差异 vs ChangeImpactAnalyzer:
  - ChangeImpact 用 graph.predecessors()（反向：谁调用了被修改的代码）
  - CodePath   用 graph.successors()（正向：API 入口调用了谁）
"""

import logging
import re
import time
from collections import deque
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import networkx as nx
from pydantic import BaseModel

from .call_graph_builder import CallGraphBuilder

logger = logging.getLogger(__name__)

# ═══════════════════════════════════════════════════════════════
# Pydantic 数据模型
# ═══════════════════════════════════════════════════════════════


class APIEndpointInfo(BaseModel):
    """API 端点信息"""
    http_method: str           # GET | POST | PUT | DELETE | PATCH
    path: str                  # "/api/users/{id}"
    handler_function: str
    handler_class: str
    file_path: str
    line_number: int
    language: str
    parameters: List[dict] = []


class PathNode(BaseModel):
    """路径追踪中的节点"""
    id: str
    name: str
    class_name: str
    file_path: str
    line_range: List[int]
    layer: str                 # controller | service | repository | database | external | utility
    node_type: str
    annotations: List[str] = []
    parameters: List[dict] = []
    return_type: str = "void"


class PathEdge(BaseModel):
    """路径追踪中的边"""
    source: str
    target: str
    call_type: str = "method_call"
    parameter_mapping: Optional[Dict[str, str]] = None


class LayerInfo(BaseModel):
    """层级统计信息"""
    layer: str
    node_count: int
    description: str


class CodePathResult(BaseModel):
    """代码路径追踪结果"""
    nodes: List[PathNode]
    edges: List[PathEdge]
    layers: List[LayerInfo]
    entry_node: Optional[str] = None
    total_depth: int = 0
    analysis_time_ms: float = 0.0
    warnings: List[str] = []


# ═══════════════════════════════════════════════════════════════
# 层级分类常量
# ═══════════════════════════════════════════════════════════════

# node_type → layer 直接映射
_TYPE_TO_LAYER: Dict[str, str] = {
    "api": "controller",
    "service": "service",
    "repository": "repository",
    "scheduler": "service",
    "config": "utility",
}

# 关键字检测（按优先级排序）
_KEYWORD_LAYER_PATTERNS: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"(?i)controller"), "controller"),
    (re.compile(r"(?i)resource"), "controller"),
    (re.compile(r"(?i)endpoint"), "controller"),
    (re.compile(r"(?i)service"), "service"),
    (re.compile(r"(?i)repository"), "repository"),
    (re.compile(r"(?i)dao"), "repository"),
    (re.compile(r"(?i)mapper"), "repository"),
    (re.compile(r"(?i)client"), "external"),
    (re.compile(r"(?i)http"), "external"),
    (re.compile(r"(?i)gateway"), "external"),
    (re.compile(r"(?i)database"), "database"),
    (re.compile(r"(?i)datasource"), "database"),
    (re.compile(r"(?i)jdbc"), "database"),
]

# 路径模式检测
_PATH_LAYER_PATTERNS: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"/controllers?/", re.IGNORECASE), "controller"),
    (re.compile(r"/routers?/", re.IGNORECASE), "controller"),
    (re.compile(r"/services?/", re.IGNORECASE), "service"),
    (re.compile(r"/repositor(?:y|ies)/", re.IGNORECASE), "repository"),
    (re.compile(r"/dao/", re.IGNORECASE), "repository"),
    (re.compile(r"/mappers?/", re.IGNORECASE), "repository"),
    (re.compile(r"/clients?/", re.IGNORECASE), "external"),
    (re.compile(r"/utils?/", re.IGNORECASE), "utility"),
    (re.compile(r"/helpers?/", re.IGNORECASE), "utility"),
]

# HTTP method 从注解中提取的映射
_ANNOTATION_TO_HTTP_METHOD: Dict[str, str] = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    "RequestMapping": "GET",  # 默认，实际可能需要解析 method 属性
    # Python FastAPI
    "router.get": "GET",
    "router.post": "POST",
    "router.put": "PUT",
    "router.delete": "DELETE",
    "router.patch": "PATCH",
    "app.get": "GET",
    "app.post": "POST",
    "app.put": "PUT",
    "app.delete": "DELETE",
    "app.patch": "PATCH",
}

# 层级描述映射
_LAYER_DESCRIPTIONS: Dict[str, str] = {
    "controller": "API 控制层 — 接收请求、参数校验、路由分发",
    "service": "业务逻辑层 — 核心业务处理、事务管理",
    "repository": "数据访问层 — 数据库查询、持久化操作",
    "database": "数据库层 — 底层数据存储访问",
    "external": "外部调用层 — HTTP 客户端、第三方服务调用",
    "utility": "工具层 — 通用工具类、辅助函数",
}

# ═══════════════════════════════════════════════════════════════
# 调用图缓存（模块级，多次调用复用）
# ═══════════════════════════════════════════════════════════════

_tracer_graph_cache: Dict[str, object] = {
    "graph": None,
    "key": None,
    "timestamp": 0.0,
}
_TRACER_CACHE_TTL = 300  # 5 minutes


# ═══════════════════════════════════════════════════════════════
# CodePathTracer
# ═══════════════════════════════════════════════════════════════


class CodePathTracer:
    """代码路径追踪器 — 从 API 入口追踪完整调用链"""

    def __init__(self, project_root: str):
        self._project_root = project_root
        self._builder = CallGraphBuilder()
        self._graph: Optional[nx.DiGraph] = None

    # ── 公共 API ──────────────────────────────────────────

    def scan_api_endpoints(self, languages: Optional[List[str]] = None) -> List[APIEndpointInfo]:
        """
        扫描项目所有 API 端点。

        Args:
            languages: 指定语言过滤，如 ["java", "python"]，None 表示全部

        Returns:
            APIEndpointInfo 列表
        """
        self._ensure_graph(languages=languages)
        if self._graph is None:
            return []

        endpoints: List[APIEndpointInfo] = []

        for node_id, attrs in self._graph.nodes(data=True):
            node_type = attrs.get("type", "")
            if node_type != "api":
                continue

            # 语言过滤
            lang = attrs.get("language", "unknown")
            if languages and lang not in languages:
                continue

            # 提取 HTTP method 和 path
            http_method, path = self._extract_http_info(node_id, attrs)

            # 提取类名
            name = attrs.get("name", "")
            class_name = self._extract_class_name(node_id)

            # 提取参数
            parameters = self._extract_parameters(node_id, attrs)

            line_range = attrs.get("line_range", [0, 0])
            line_number = line_range[0] if isinstance(line_range, (list, tuple)) and line_range else 0

            endpoints.append(APIEndpointInfo(
                http_method=http_method,
                path=path,
                handler_function=name,
                handler_class=class_name,
                file_path=attrs.get("file_path", ""),
                line_number=line_number,
                language=lang,
                parameters=parameters,
            ))

        # 按 path 排序以保证输出稳定性
        endpoints.sort(key=lambda e: (e.path, e.http_method))
        return endpoints

    def trace_code_path(
        self,
        entry_file: str,
        entry_function: str,
        max_depth: int = 10,
    ) -> CodePathResult:
        """
        追踪指定 API 的完整代码路径。

        Args:
            entry_file: 入口方法所在文件路径
            entry_function: 入口方法名
            max_depth: 最大追踪深度

        Returns:
            CodePathResult 包含 nodes, edges, layers
        """
        start_time = time.monotonic()
        warnings: List[str] = []
        max_depth = max(1, min(20, max_depth))

        # 1. 构建/获取缓存的调用图
        self._ensure_graph()
        if self._graph is None:
            return self._empty_result(warnings=["调用图构建失败"])

        # 2. 定位入口节点（模糊匹配）
        entry_node = self._locate_entry_node(entry_file, entry_function)
        if entry_node is None:
            warnings.append(
                f"未找到匹配的入口节点: file={entry_file}, function={entry_function}"
            )
            return self._empty_result(warnings=warnings)

        # 3. 正向 BFS 遍历
        path_nodes, path_edges, actual_depth = self._forward_bfs(entry_node, max_depth)

        # 4. 生成层级统计
        layers = self._compute_layer_stats(path_nodes)

        elapsed = (time.monotonic() - start_time) * 1000

        return CodePathResult(
            nodes=path_nodes,
            edges=path_edges,
            layers=layers,
            entry_node=entry_node,
            total_depth=actual_depth,
            analysis_time_ms=round(elapsed, 1),
            warnings=warnings,
        )

    # ── 调用图构建 ────────────────────────────────────────

    def _ensure_graph(self, languages: Optional[List[str]] = None) -> None:
        """构建调用图（带缓存）"""
        global _tracer_graph_cache

        now = time.time()
        cache_key = self._project_root

        if (
            _tracer_graph_cache["key"] == cache_key
            and _tracer_graph_cache["graph"] is not None
            and (now - _tracer_graph_cache["timestamp"]) < _TRACER_CACHE_TTL
        ):
            self._graph = _tracer_graph_cache["graph"]  # type: ignore[assignment]
            return

        logger.info("Building call graph for CodePathTracer: %s", self._project_root)
        self._graph = self._builder.build(
            self._project_root, languages=languages, skip_tests=True
        )

        _tracer_graph_cache["graph"] = self._graph
        _tracer_graph_cache["key"] = cache_key
        _tracer_graph_cache["timestamp"] = now

        logger.info(
            "Call graph built: %d nodes, %d edges",
            self._graph.number_of_nodes(),
            self._graph.number_of_edges(),
        )

    # ── 入口节点定位 ──────────────────────────────────────

    def _locate_entry_node(self, entry_file: str, entry_function: str) -> Optional[str]:
        """
        模糊匹配定位入口节点。

        匹配策略（按优先级）：
          1. 文件路径包含 entry_file 且方法名精确匹配
          2. 文件路径包含 entry_file 且方法名模糊匹配（包含）
          3. 仅方法名精确匹配
          4. 仅方法名模糊匹配
        """
        if self._graph is None:
            return None

        entry_file_norm = entry_file.replace("\\", "/").lower()
        entry_func_lower = entry_function.lower()

        candidates: List[Tuple[str, int]] = []  # (node_id, priority)

        for node_id, attrs in self._graph.nodes(data=True):
            node_file = attrs.get("file_path", "").replace("\\", "/").lower()
            node_name = attrs.get("name", "").lower()

            file_match = entry_file_norm in node_file or node_file.endswith(entry_file_norm)

            if file_match and node_name == entry_func_lower:
                # 最高优先级：文件 + 方法名精确匹配
                candidates.append((node_id, 0))
            elif file_match and entry_func_lower in node_name:
                # 文件匹配 + 方法名模糊匹配
                candidates.append((node_id, 1))
            elif node_name == entry_func_lower:
                # 仅方法名精确匹配
                candidates.append((node_id, 2))
            elif entry_func_lower in node_name:
                # 仅方法名模糊匹配
                candidates.append((node_id, 3))
            # 也尝试从 node_id 中匹配（node_id 通常为 module.class.method 格式）
            elif entry_func_lower in node_id.lower():
                candidates.append((node_id, 4))

        if not candidates:
            return None

        # 按优先级排序，优先选择 API 类型的节点
        candidates.sort(key=lambda c: (c[1], 0 if self._graph.nodes.get(c[0], {}).get("type") == "api" else 1))
        return candidates[0][0]

    # ── 正向 BFS ──────────────────────────────────────────

    def _forward_bfs(
        self,
        start_node: str,
        max_depth: int,
    ) -> Tuple[List[PathNode], List[PathEdge], int]:
        """
        正向 BFS — 从 API 入口沿 graph.successors() 遍历。

        关键差异 vs ChangeImpactAnalyzer._bfs_impact:
          - ChangeImpact 用 graph.predecessors()（反向：谁调用了被修改的代码）
          - CodePath    用 graph.successors()（正向：API 入口调用了谁）

        Returns:
            (path_nodes, path_edges, max_depth_reached)
        """
        if self._graph is None:
            return [], [], 0

        visited: Set[str] = {start_node}
        queue: deque = deque()  # (node_id, depth)
        path_nodes: List[PathNode] = []
        path_edges: List[PathEdge] = []
        max_depth_reached = 0

        # 将起始节点添加到结果
        start_attrs = self._graph.nodes.get(start_node, {})
        path_nodes.append(self._build_path_node(start_node, start_attrs))

        # 将起始节点的所有后继入队
        for successor in self._graph.successors(start_node):
            if successor not in visited:
                visited.add(successor)
                queue.append((successor, 1))
                edge_data = self._graph.edges.get((start_node, successor), {})
                path_edges.append(PathEdge(
                    source=start_node,
                    target=successor,
                    call_type=edge_data.get("type", "method_call"),
                ))

        # BFS 遍历
        while queue:
            current_id, current_depth = queue.popleft()

            if current_depth > max_depth:
                continue

            max_depth_reached = max(max_depth_reached, current_depth)

            attrs = self._graph.nodes.get(current_id, {})
            path_nodes.append(self._build_path_node(current_id, attrs))

            # 继续遍历后继节点
            if current_depth < max_depth:
                for successor in self._graph.successors(current_id):
                    if successor not in visited:
                        visited.add(successor)
                        queue.append((successor, current_depth + 1))
                        edge_data = self._graph.edges.get((current_id, successor), {})
                        path_edges.append(PathEdge(
                            source=current_id,
                            target=successor,
                            call_type=edge_data.get("type", "method_call"),
                        ))

        return path_nodes, path_edges, max_depth_reached

    # ── 节点构建 ──────────────────────────────────────────

    def _build_path_node(self, node_id: str, attrs: dict) -> PathNode:
        """从图节点属性构建 PathNode"""
        name = attrs.get("name", node_id.split(".")[-1])
        class_name = self._extract_class_name(node_id)
        layer = self._classify_layer(node_id, attrs)
        parameters = self._extract_parameters(node_id, attrs)
        annotations = self._extract_annotations(node_id, attrs)

        return PathNode(
            id=node_id,
            name=name,
            class_name=class_name,
            file_path=attrs.get("file_path", ""),
            line_range=attrs.get("line_range", [0, 0]),
            layer=layer,
            node_type=attrs.get("type", "function"),
            annotations=annotations,
            parameters=parameters,
            return_type=attrs.get("return_type", "void"),
        )

    # ── 分层分类 ──────────────────────────────────────────

    def _classify_layer(self, node_id: str, node_data: dict) -> str:
        """
        四优先级分层分类规则：
          1. node_type 直接映射 (api->controller, service->service, ...)
          2. 关键字检测 (含 'dao'/'mapper' -> repository, 含 'client'/'http' -> external)
          3. 路径模式 (/controller/ -> controller, /service/ -> service)
          4. 默认 -> utility
        """
        node_type = node_data.get("type", "")

        # 规则 1: node_type 直接映射
        if node_type in _TYPE_TO_LAYER:
            return _TYPE_TO_LAYER[node_type]

        # 规则 2: 关键字检测 — 检查 node_id 和 name
        combined_text = f"{node_id} {node_data.get('name', '')}"
        for pattern, layer in _KEYWORD_LAYER_PATTERNS:
            if pattern.search(combined_text):
                return layer

        # 规则 3: 路径模式 — 检查 file_path
        file_path = node_data.get("file_path", "")
        for pattern, layer in _PATH_LAYER_PATTERNS:
            if pattern.search(file_path):
                return layer

        # 规则 4: 默认
        return "utility"

    # ── HTTP 信息提取 ─────────────────────────────────────

    @staticmethod
    def _extract_http_info(node_id: str, attrs: dict) -> Tuple[str, str]:
        """
        从节点属性中提取 HTTP method 和 path。

        解析来源：
          - Java: 注解如 @GetMapping("/api/users")
          - Python: 装饰器如 @router.get("/api/users")
          - 节点 ID 中可能包含路径信息
        """
        http_method = "GET"
        path = ""

        # 尝试从 node_id 推断方法名对应的路径
        name = attrs.get("name", "")
        file_path = attrs.get("file_path", "")

        # 检查节点名称中是否包含 HTTP 方法线索
        name_lower = name.lower()
        if name_lower.startswith("create") or name_lower.startswith("add"):
            http_method = "POST"
        elif name_lower.startswith("update") or name_lower.startswith("modify"):
            http_method = "PUT"
        elif name_lower.startswith("delete") or name_lower.startswith("remove"):
            http_method = "DELETE"
        elif name_lower.startswith("get") or name_lower.startswith("find") or name_lower.startswith("list"):
            http_method = "GET"

        # 从 node_id 推断路径（如 module.Controller.getUsers → /users）
        parts = node_id.split(".")
        if len(parts) >= 2:
            # 使用方法名生成猜测路径
            method_part = parts[-1]
            # 去掉常见前缀
            for prefix in ("get", "create", "update", "delete", "find", "list", "add", "remove"):
                if method_part.lower().startswith(prefix):
                    remaining = method_part[len(prefix):]
                    if remaining:
                        # CamelCase → kebab-case 转换
                        path_part = re.sub(r'(?<!^)(?=[A-Z])', '-', remaining).lower()
                        path = f"/api/{path_part}"
                        break

        if not path:
            path = f"/api/{name}"

        return http_method, path

    # ── 参数提取 ──────────────────────────────────────────

    @staticmethod
    def _extract_parameters(node_id: str, node_data: dict) -> List[dict]:
        """从节点数据中提取方法参数列表"""
        params = node_data.get("parameters", [])
        if isinstance(params, list):
            return params
        return []

    # ── 注解提取 ──────────────────────────────────────────

    @staticmethod
    def _extract_annotations(node_id: str, node_data: dict) -> List[str]:
        """从节点数据中提取注解/装饰器列表"""
        annotations = node_data.get("annotations", [])
        if isinstance(annotations, list):
            return annotations
        return []

    # ── 类名提取 ──────────────────────────────────────────

    @staticmethod
    def _extract_class_name(node_id: str) -> str:
        """从 node_id 中提取类名（倒数第二段）"""
        parts = node_id.split(".")
        if len(parts) >= 3:
            return parts[-2]
        elif len(parts) == 2:
            return parts[0]
        return ""

    # ── 层级统计 ──────────────────────────────────────────

    @staticmethod
    def _compute_layer_stats(nodes: List[PathNode]) -> List[LayerInfo]:
        """生成层级统计信息"""
        layer_counts: Dict[str, int] = {}
        for node in nodes:
            layer_counts[node.layer] = layer_counts.get(node.layer, 0) + 1

        # 按固定顺序输出
        layer_order = ["controller", "service", "repository", "database", "external", "utility"]
        layers: List[LayerInfo] = []

        for layer in layer_order:
            count = layer_counts.get(layer, 0)
            if count > 0:
                layers.append(LayerInfo(
                    layer=layer,
                    node_count=count,
                    description=_LAYER_DESCRIPTIONS.get(layer, layer),
                ))

        # 补充未在预定义顺序中的层
        for layer, count in layer_counts.items():
            if layer not in layer_order and count > 0:
                layers.append(LayerInfo(
                    layer=layer,
                    node_count=count,
                    description=_LAYER_DESCRIPTIONS.get(layer, layer),
                ))

        return layers

    # ── 工具方法 ──────────────────────────────────────────

    @staticmethod
    def _empty_result(warnings: Optional[List[str]] = None) -> CodePathResult:
        """返回空结果"""
        return CodePathResult(
            nodes=[],
            edges=[],
            layers=[],
            warnings=warnings or [],
        )
