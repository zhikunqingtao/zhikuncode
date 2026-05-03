"""
调用图构建器 — F33 Change Impact Analysis

遍历项目源文件，提取函数/类/模块之间的调用关系，构建 networkx 有向图。
- Python: LibCST 精准解析 (confidence=high)
- Java: tree-sitter 启发式 + Spring Boot 注解识别 (confidence=medium)
- TypeScript: tree-sitter 启发式 (confidence=low)
"""

import logging
import signal
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import networkx as nx

logger = logging.getLogger(__name__)

# 需要跳过的目录
SKIP_DIRS: Set[str] = {
    "node_modules", "__pycache__", ".git", "venv", ".venv",
    "target", "dist", "build", ".tox", ".mypy_cache", ".pytest_cache",
    "egg-info", ".eggs",
}

# 测试文件 glob patterns
TEST_PATTERNS: Set[str] = {
    "*_test.py", "test_*.py", "*Test.java", "*Tests.java",
    "*.spec.ts", "*.spec.tsx", "*.test.ts", "*.test.tsx",
}

# 单文件解析超时 (秒)
_PARSE_TIMEOUT = 5


@dataclass
class CallGraphNode:
    """调用图中的节点"""
    id: str                # 唯一标识，如 "module.class.method"
    name: str
    type: str              # 'api' | 'service' | 'repository' | 'scheduler' | 'config' | 'function' | 'class'
    file_path: str
    line_range: Tuple[int, int]
    language: str
    confidence: str = "medium"  # 'high' | 'medium' | 'low'


class CallGraphBuilder:
    """项目级调用图构建器"""

    def __init__(self):
        self.graph: nx.DiGraph = nx.DiGraph()
        self._nodes: Dict[str, CallGraphNode] = {}

    def build(
        self,
        project_root: str,
        languages: Optional[List[str]] = None,
        skip_tests: bool = True,
    ) -> nx.DiGraph:
        """构建项目调用图"""
        root = Path(project_root)
        if not root.is_dir():
            logger.warning("Project root does not exist: %s", project_root)
            return self.graph

        if languages is None:
            languages = ["python", "java", "typescript"]

        if "python" in languages:
            self._build_python(root, skip_tests)
        if "java" in languages:
            self._build_java(root, skip_tests)
        if "typescript" in languages:
            self._build_typescript(root, skip_tests)

        return self.graph

    # ── 节点管理 ────────────────────────────────────────────

    def _add_node(self, node: CallGraphNode) -> None:
        if node.id not in self._nodes:
            self._nodes[node.id] = node
            self.graph.add_node(node.id, **{
                "name": node.name,
                "type": node.type,
                "file_path": node.file_path,
                "line_range": list(node.line_range),
                "language": node.language,
                "confidence": node.confidence,
            })

    def _add_edge(self, source: str, target: str, edge_type: str = "call") -> None:
        if source in self._nodes and target in self._nodes:
            self.graph.add_edge(source, target, type=edge_type, weight=1.0)

    # ── 文件遍历 ───────────────────────────────────────────

    def _iter_files(self, root: Path, extensions: Set[str], skip_tests: bool):
        """递归遍历项目文件，跳过排除目录和测试文件"""
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix not in extensions:
                continue
            # 跳过排除目录
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            # 跳过测试文件
            if skip_tests and self._is_test_file(path):
                continue
            yield path

    @staticmethod
    def _is_test_file(path: Path) -> bool:
        name = path.name
        if name.startswith("test_") or name.endswith("_test.py"):
            return True
        if name.endswith(("Test.java", "Tests.java")):
            return True
        if name.endswith((".spec.ts", ".spec.tsx", ".test.ts", ".test.tsx")):
            return True
        return False

    @staticmethod
    def _module_name_from_path(path: Path, root: Path) -> str:
        """从文件路径生成模块名"""
        try:
            rel = path.relative_to(root)
        except ValueError:
            rel = path
        parts = list(rel.with_suffix("").parts)
        return ".".join(parts)

    # ── Python (LibCST) ───────────────────────────────────

    def _build_python(self, root: Path, skip_tests: bool) -> None:
        """使用 LibCST 解析 Python 文件"""
        try:
            import libcst as cst
            from libcst.metadata import MetadataWrapper, PositionProvider
            _has_metadata = True
        except ImportError:
            try:
                import libcst as cst
                _has_metadata = False
            except ImportError:
                logger.warning("libcst not installed, skipping Python analysis")
                return

        for path in self._iter_files(root, {".py"}, skip_tests):
            try:
                code = path.read_text(encoding="utf-8", errors="replace")
                module_name = self._module_name_from_path(path, root)
                tree = cst.parse_module(code)
                if _has_metadata:
                    try:
                        wrapper = MetadataWrapper(tree)
                        positions = wrapper.resolve(PositionProvider)
                        visitor = _PythonCallGraphVisitor(module_name, str(path))
                        visitor._positions = positions
                        tree.visit(visitor)
                    except Exception:
                        visitor = _PythonCallGraphVisitor(module_name, str(path))
                        tree.visit(visitor)
                else:
                    visitor = _PythonCallGraphVisitor(module_name, str(path))
                    tree.visit(visitor)
                self._ingest_python_results(visitor, str(path), module_name)
            except Exception as e:
                logger.warning("Failed to parse Python file %s: %s", path, e)

    def _ingest_python_results(self, visitor: "_PythonCallGraphVisitor",
                               file_path: str, module_name: str) -> None:
        """将 LibCST 访问结果注入图"""
        # 注册函数/方法节点
        for func in visitor.functions:
            node_id = f"{module_name}.{func['qualified_name']}"
            node_type = func.get("node_type", "function")
            self._add_node(CallGraphNode(
                id=node_id, name=func["name"], type=node_type,
                file_path=file_path,
                line_range=(func["line_start"], func["line_end"]),
                language="python", confidence="high",
            ))

        # 注册类节点
        for cls in visitor.classes:
            node_id = f"{module_name}.{cls['name']}"
            self._add_node(CallGraphNode(
                id=node_id, name=cls["name"], type="class",
                file_path=file_path,
                line_range=(cls["line_start"], cls["line_end"]),
                language="python", confidence="high",
            ))

        # 添加调用边
        for caller, callee in visitor.calls:
            caller_id = f"{module_name}.{caller}" if caller else module_name
            # 尝试匹配已知节点
            callee_candidates = [
                nid for nid in self._nodes if nid.endswith(f".{callee}")
            ]
            for target_id in callee_candidates:
                self._add_edge(caller_id, target_id, "call")

        # 添加 import 依赖边
        for from_module, imported in visitor.imports:
            target_candidates = [
                nid for nid in self._nodes
                if nid.startswith(from_module) or nid.endswith(f".{imported}")
            ]
            source_id = module_name
            for target_id in target_candidates:
                self._add_edge(source_id, target_id, "dependency")

    # ── Java (tree-sitter) ────────────────────────────────

    def _build_java(self, root: Path, skip_tests: bool) -> None:
        """使用 tree-sitter 解析 Java 文件"""
        try:
            from services.tree_sitter_service import TreeSitterService
        except ImportError:
            logger.warning("TreeSitterService not available, skipping Java analysis")
            return

        ts = TreeSitterService()
        if not ts.is_available():
            logger.warning("tree-sitter not available, skipping Java analysis")
            return

        for path in self._iter_files(root, {".java"}, skip_tests):
            try:
                code = path.read_text(encoding="utf-8", errors="replace")
                module_name = self._module_name_from_path(path, root)
                ast_root = ts.parse(code, "java")
                self._extract_java_nodes(ast_root, code, str(path), module_name)
            except Exception as e:
                logger.warning("Failed to parse Java file %s: %s", path, e)

    def _extract_java_nodes(self, ast_root, code: str, file_path: str,
                            module_name: str) -> None:
        """从 Java AST 提取节点和调用关系"""
        self._walk_java(ast_root, code, file_path, module_name, parent_class=None)

    def _walk_java(self, node, code: str, file_path: str, module_name: str,
                   parent_class: Optional[str]) -> None:
        """递归遍历 Java AST"""
        if node.type == "class_declaration":
            name_node = node.child_by_field_name("name")
            if name_node:
                class_name = code[name_node.start_byte:name_node.end_byte]
                node_type = self._detect_java_class_type(node, code)
                node_id = f"{module_name}.{class_name}"
                self._add_node(CallGraphNode(
                    id=node_id, name=class_name, type=node_type,
                    file_path=file_path,
                    line_range=(node.start_point[0] + 1, node.end_point[0] + 1),
                    language="java", confidence="medium",
                ))
                for child in node.children:
                    self._walk_java(child, code, file_path, module_name,
                                    parent_class=class_name)
                return

        if node.type == "method_declaration":
            name_node = node.child_by_field_name("name")
            if name_node:
                method_name = code[name_node.start_byte:name_node.end_byte]
                qualified = f"{parent_class}.{method_name}" if parent_class else method_name
                node_id = f"{module_name}.{qualified}"
                node_type = self._detect_java_method_type(node, code, parent_class)
                self._add_node(CallGraphNode(
                    id=node_id, name=method_name, type=node_type,
                    file_path=file_path,
                    line_range=(node.start_point[0] + 1, node.end_point[0] + 1),
                    language="java", confidence="medium",
                ))
                # 提取方法内调用
                self._extract_java_calls(node, code, node_id, module_name)
                return

        for child in node.children:
            self._walk_java(child, code, file_path, module_name, parent_class)

    def _detect_java_class_type(self, node, code: str) -> str:
        """通过 Spring Boot 注解识别 Java 类类型"""
        # 检查 modifiers 子节点中的注解
        for child in node.children:
            if child.type == "modifiers":
                text = code[child.start_byte:child.end_byte]
                if "@RestController" in text or "@Controller" in text:
                    return "api"
                if "@Service" in text:
                    return "service"
                if "@Repository" in text or "@Mapper" in text:
                    return "repository"
                if "@Configuration" in text:
                    return "config"
                if "@Component" in text:
                    return "service"
        return "class"

    def _detect_java_method_type(self, node, code: str,
                                 parent_class: Optional[str]) -> str:
        """检测 Java 方法是否为 API 端点或定时任务"""
        for child in node.children:
            if child.type == "modifiers":
                text = code[child.start_byte:child.end_byte]
                if any(a in text for a in (
                    "@GetMapping", "@PostMapping", "@PutMapping",
                    "@DeleteMapping", "@RequestMapping",
                )):
                    return "api"
                if "@Scheduled" in text:
                    return "scheduler"
        return "function"

    def _extract_java_calls(self, node, code: str, caller_id: str,
                            module_name: str) -> None:
        """提取 Java 方法内的调用关系"""
        if node.type == "method_invocation":
            name_node = node.child_by_field_name("name")
            if name_node:
                callee_name = code[name_node.start_byte:name_node.end_byte]
                callee_candidates = [
                    nid for nid in self._nodes if nid.endswith(f".{callee_name}")
                ]
                for target_id in callee_candidates:
                    if target_id != caller_id:
                        self._add_edge(caller_id, target_id, "call")
        for child in node.children:
            self._extract_java_calls(child, code, caller_id, module_name)

    # ── TypeScript (tree-sitter) ──────────────────────────

    def _build_typescript(self, root: Path, skip_tests: bool) -> None:
        """使用 tree-sitter 解析 TypeScript 文件"""
        try:
            from services.tree_sitter_service import TreeSitterService
        except ImportError:
            logger.warning("TreeSitterService not available, skipping TS analysis")
            return

        ts = TreeSitterService()
        if not ts.is_available():
            logger.warning("tree-sitter not available, skipping TS analysis")
            return

        for path in self._iter_files(root, {".ts", ".tsx"}, skip_tests):
            try:
                code = path.read_text(encoding="utf-8", errors="replace")
                lang = "tsx" if path.suffix == ".tsx" else "typescript"
                module_name = self._module_name_from_path(path, root)
                ast_root = ts.parse(code, lang)
                self._extract_ts_nodes(ast_root, code, str(path), module_name)
            except Exception as e:
                logger.warning("Failed to parse TS file %s: %s", path, e)

    def _extract_ts_nodes(self, ast_root, code: str, file_path: str,
                          module_name: str) -> None:
        """从 TypeScript AST 提取节点和调用关系"""
        self._walk_ts(ast_root, code, file_path, module_name, parent_class=None)

    def _walk_ts(self, node, code: str, file_path: str, module_name: str,
                 parent_class: Optional[str]) -> None:
        """递归遍历 TypeScript AST"""
        if node.type == "class_declaration":
            name_node = node.child_by_field_name("name")
            if name_node:
                class_name = code[name_node.start_byte:name_node.end_byte]
                node_id = f"{module_name}.{class_name}"
                self._add_node(CallGraphNode(
                    id=node_id, name=class_name, type="class",
                    file_path=file_path,
                    line_range=(node.start_point[0] + 1, node.end_point[0] + 1),
                    language="typescript", confidence="low",
                ))
                for child in node.children:
                    self._walk_ts(child, code, file_path, module_name,
                                  parent_class=class_name)
                return

        if node.type in ("function_declaration", "method_definition", "arrow_function"):
            name_node = node.child_by_field_name("name")
            if name_node:
                func_name = code[name_node.start_byte:name_node.end_byte]
                qualified = f"{parent_class}.{func_name}" if parent_class else func_name
                node_id = f"{module_name}.{qualified}"
                self._add_node(CallGraphNode(
                    id=node_id, name=func_name, type="function",
                    file_path=file_path,
                    line_range=(node.start_point[0] + 1, node.end_point[0] + 1),
                    language="typescript", confidence="low",
                ))
                self._extract_ts_calls(node, code, node_id, module_name)
                return

        # 处理 export const name = arrow_function 模式
        if node.type == "lexical_declaration":
            for child in node.children:
                if child.type == "variable_declarator":
                    name_n = child.child_by_field_name("name")
                    value_n = child.child_by_field_name("value")
                    if name_n and value_n and value_n.type == "arrow_function":
                        func_name = code[name_n.start_byte:name_n.end_byte]
                        node_id = f"{module_name}.{func_name}"
                        self._add_node(CallGraphNode(
                            id=node_id, name=func_name, type="function",
                            file_path=file_path,
                            line_range=(node.start_point[0] + 1, node.end_point[0] + 1),
                            language="typescript", confidence="low",
                        ))
                        self._extract_ts_calls(value_n, code, node_id, module_name)

        for child in node.children:
            self._walk_ts(child, code, file_path, module_name, parent_class)

    def _extract_ts_calls(self, node, code: str, caller_id: str,
                          module_name: str) -> None:
        """提取 TypeScript 调用关系"""
        if node.type == "call_expression":
            func_node = node.child_by_field_name("function")
            if func_node:
                callee_name = code[func_node.start_byte:func_node.end_byte]
                # 取最后一个标识符 (e.g. "obj.method" → "method")
                simple_name = callee_name.split(".")[-1]
                callee_candidates = [
                    nid for nid in self._nodes if nid.endswith(f".{simple_name}")
                ]
                for target_id in callee_candidates:
                    if target_id != caller_id:
                        self._add_edge(caller_id, target_id, "call")
        for child in node.children:
            self._extract_ts_calls(child, code, caller_id, module_name)


# ═══════════════════════════════════════════════════════════════
# LibCST Python 访问者
# ═══════════════════════════════════════════════════════════════

# 延迟导入 libcst 基类，不可用时回退到 object
try:
    import libcst as _cst
    _CSTVisitorBase = _cst.CSTVisitor
except ImportError:
    _CSTVisitorBase = object  # type: ignore[misc,assignment]


class _PythonCallGraphVisitor(_CSTVisitorBase):  # type: ignore[misc]
    """LibCST 访问者 — 提取 Python 调用图信息

    需要通过 MetadataWrapper.visit() 调用以启用 PositionProvider。
    继承自 cst.CSTVisitor 以支持 tree.visit(visitor)。
    """

    # 声明元数据依赖（兼容性保留）
    METADATA_DEPENDENCIES: tuple = ()

    def __init__(self, module_name: str, file_path: str):
        if _CSTVisitorBase is not object:
            super().__init__()
        self.module_name = module_name
        self.file_path = file_path
        self.imports: List[Tuple[str, str]] = []    # (from_module, imported_name)
        self.calls: List[Tuple[str, str]] = []      # (caller_qualified, callee_name)
        self.classes: List[dict] = []                # {name, line_start, line_end}
        self.functions: List[dict] = []              # {name, qualified_name, line_start, line_end, node_type}
        self._current_class: Optional[str] = None
        self._current_function: Optional[str] = None
        self._positions: Optional[dict] = None       # PositionProvider resolve 结果

    def _init_visitor(self):
        """延迟导入 libcst"""
        import libcst as cst
        return cst

    def visit_ImportFrom(self, node) -> None:
        """提取 from X import Y"""
        import libcst as cst

        if isinstance(node.module, (cst.Attribute, cst.Name)):
            module_str = self._node_to_str(node.module)
        else:
            module_str = ""

        if isinstance(node.names, cst.ImportStar):
            self.imports.append((module_str, "*"))
        elif isinstance(node.names, (list, tuple)):
            for alias in node.names:
                if isinstance(alias, cst.ImportAlias):
                    imported = self._node_to_str(alias.name)
                    self.imports.append((module_str, imported))

    def visit_ClassDef(self, node) -> None:
        """提取类定义"""
        import libcst as cst

        class_name = node.name.value if isinstance(node.name, cst.Name) else str(node.name)
        pos = self._get_position_from_metadata(node)
        self.classes.append({
            "name": class_name,
            "line_start": pos[0],
            "line_end": pos[1],
        })
        self._current_class = class_name

    def leave_ClassDef(self, node) -> None:
        self._current_class = None

    def visit_FunctionDef(self, node) -> None:
        """提取函数/方法定义"""
        import libcst as cst

        func_name = node.name.value if isinstance(node.name, cst.Name) else str(node.name)
        pos = self._get_position_from_metadata(node)
        qualified = f"{self._current_class}.{func_name}" if self._current_class else func_name

        # 检测 FastAPI 路由装饰器
        node_type = "function"
        for deco in node.decorators:
            deco_str = self._node_to_str(deco.decorator)
            if any(p in deco_str for p in ("router.get", "router.post", "router.put",
                                           "router.delete", "router.patch", "app.get",
                                           "app.post", "app.put", "app.delete")):
                node_type = "api"
                break

        self.functions.append({
            "name": func_name,
            "qualified_name": qualified,
            "line_start": pos[0],
            "line_end": pos[1],
            "node_type": node_type,
        })
        prev = self._current_function
        self._current_function = qualified
        # 注意：不 return 因为 libcst 自动遍历子树

    def leave_FunctionDef(self, node) -> None:
        # 简化：恢复到 None（嵌套函数场景忽略）
        self._current_function = None

    def visit_Call(self, node) -> None:
        """提取函数/方法调用"""
        callee_name = self._node_to_str(node.func)
        if callee_name:
            # 取最简名 e.g. "self.service.do_thing" → "do_thing"
            simple = callee_name.split(".")[-1]
            caller = self._current_function or "<module>"
            self.calls.append((caller, simple))

    @staticmethod
    def _node_to_str(node) -> str:
        """将 CST 节点转为字符串表示"""
        import libcst as cst

        if isinstance(node, cst.Name):
            return node.value
        if isinstance(node, cst.Attribute):
            parts = []
            current = node
            while isinstance(current, cst.Attribute):
                parts.append(current.attr.value if isinstance(current.attr, cst.Name) else str(current.attr))
                current = current.value
            if isinstance(current, cst.Name):
                parts.append(current.value)
            return ".".join(reversed(parts))
        if isinstance(node, cst.Call):
            return _PythonCallGraphVisitor._node_to_str(node.func)
        return ""

    def _get_position_from_metadata(self, node) -> Tuple[int, int]:
        """通过 PositionProvider 解析结果获取节点行范围"""
        try:
            if self._positions is not None and node in self._positions:
                pos = self._positions[node]
                return (pos.start.line, pos.end.line)
        except Exception:
            pass
        return (0, 0)
