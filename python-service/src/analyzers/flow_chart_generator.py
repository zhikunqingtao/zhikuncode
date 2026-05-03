"""
流程图生成器 — 从方法体生成 Mermaid 流程图

通过 tree-sitter 解析方法体的 AST，提取控制流结构（if/else、循环、
try/catch、switch/case），生成 Mermaid flowchart TD 语法。

- 支持 Java 和 Python 两种语言
- 嵌套深度限制（默认 3 层）
- 超过嵌套限制的分支折叠为单节点
"""

import logging
import re
import time
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from .call_graph_builder import SKIP_DIRS
from .diagram_models import DiagramMetadata, DiagramResult

logger = logging.getLogger(__name__)


def _escape_mermaid_flow(text: str) -> str:
    """转义 Mermaid flowchart 节点文本中的特殊字符"""
    text = text.replace('"', "'")
    text = text.replace("(", "#40;").replace(")", "#41;")
    text = text.replace("{", "#123;").replace("}", "#125;")
    text = text.replace("[", "#91;").replace("]", "#93;")
    text = text.replace("<", "#60;").replace(">", "#62;")
    return text


class _NodeIdGen:
    """流程图节点 ID 生成器"""

    def __init__(self):
        self._counter = 0

    def next(self) -> str:
        self._counter += 1
        return f"N{self._counter}"

    @property
    def count(self) -> int:
        return self._counter


class FlowChartGenerator:
    """从方法体生成 Mermaid 流程图"""

    def __init__(self, project_root: str):
        """
        初始化流程图生成器。

        Args:
            project_root: 项目根目录路径
        """
        self._project_root = project_root

    def generate(
        self,
        target: str,
        depth: int = 3,
    ) -> DiagramResult:
        """
        生成指定方法的流程图。

        Args:
            target: 方法签名（如 SessionService.createSession）
            depth: 嵌套深度限制 (1-5)，默认 3

        Returns:
            DiagramResult 包含 mermaid_syntax, confidence_score, metadata, warnings
        """
        start_time = time.monotonic()
        warnings: List[str] = []
        depth = max(1, min(5, depth))

        try:
            method_info = self._find_method(target)
            if method_info is None:
                elapsed = (time.monotonic() - start_time) * 1000
                warnings.append(f"未找到匹配 '{target}' 的方法定义")
                return DiagramResult(
                    diagram_type="flowchart",
                    mermaid_syntax=(
                        f"flowchart TD\n"
                        f"    A[\"未找到方法: {_escape_mermaid_flow(target)}\"]"
                    ),
                    confidence_score=0.0,
                    metadata=DiagramMetadata(
                        nodes_count=0, edges_count=0,
                        languages_analyzed=[],
                        analysis_time_ms=round(elapsed, 1),
                    ),
                    warnings=warnings,
                )

            file_path, language, method_code, method_name = method_info

            id_gen = _NodeIdGen()
            lines: List[str] = ["flowchart TD"]

            if language == "python":
                flow_nodes, flow_edges = self._extract_python_flow(
                    method_code, method_name, id_gen, depth, warnings
                )
            else:
                flow_nodes, flow_edges = self._extract_java_flow(
                    method_code, method_name, language, id_gen, depth, warnings
                )

            lines.extend(flow_nodes)
            lines.extend(flow_edges)
            edges_count = len(flow_edges)

            confidence = self._compute_confidence(
                id_gen.count, edges_count, warnings, language
            )

            elapsed = (time.monotonic() - start_time) * 1000
            return DiagramResult(
                diagram_type="flowchart",
                mermaid_syntax="\n".join(lines),
                confidence_score=round(confidence, 2),
                metadata=DiagramMetadata(
                    nodes_count=id_gen.count,
                    edges_count=edges_count,
                    languages_analyzed=[language],
                    analysis_time_ms=round(elapsed, 1),
                ),
                warnings=warnings,
            )

        except Exception as e:
            elapsed = (time.monotonic() - start_time) * 1000
            logger.error("流程图生成失败: %s", e, exc_info=True)
            warnings.append(f"生成过程出错: {e}")
            return DiagramResult(
                diagram_type="flowchart",
                mermaid_syntax="flowchart TD\n    A[\"生成失败\"]",
                confidence_score=0.0,
                metadata=DiagramMetadata(
                    nodes_count=0, edges_count=0,
                    languages_analyzed=[],
                    analysis_time_ms=round(elapsed, 1),
                ),
                warnings=warnings,
            )

    # ── 方法定位 ──────────────────────────────────────────

    def _find_method(
        self, target: str
    ) -> Optional[Tuple[str, str, str, str]]:
        """
        在项目中搜索匹配 target 的方法定义。

        Returns:
            (file_path, language, method_source_code, method_name) 或 None
        """
        if "." in target:
            parts = target.rsplit(".", 1)
            class_name = parts[0].split(".")[-1]
            method_name = parts[1]
        else:
            class_name = None
            method_name = target

        root = Path(self._project_root)

        result = self._search_java_method(root, class_name, method_name)
        if result:
            return result

        result = self._search_python_method(root, class_name, method_name)
        if result:
            return result

        return None

    def _search_java_method(
        self, root: Path, class_name: Optional[str], method_name: str,
    ) -> Optional[Tuple[str, str, str, str]]:
        """在 Java 文件中搜索方法"""
        try:
            from services.tree_sitter_service import TreeSitterService
        except ImportError:
            return None

        ts = TreeSitterService()
        if not ts.is_available():
            return None

        for path in root.rglob("*.java"):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            if class_name and class_name not in path.stem:
                continue
            try:
                code = path.read_text(encoding="utf-8", errors="replace")
                ast_root = ts.parse(code, "java")
                result = self._find_java_method_node(
                    ast_root, code, class_name, method_name
                )
                if result:
                    method_code, name = result
                    return (str(path), "java", method_code, name)
            except Exception as e:
                logger.debug("解析 Java 文件失败 %s: %s", path, e)
        return None

    def _find_java_method_node(
        self, node, code: str, class_name: Optional[str], method_name: str,
    ) -> Optional[Tuple[str, str]]:
        """递归查找 Java 方法节点"""
        if node.type == "method_declaration":
            name_node = node.child_by_field_name("name")
            if name_node:
                name = code[name_node.start_byte:name_node.end_byte]
                if name == method_name:
                    return (code[node.start_byte:node.end_byte], name)
        for child in node.children:
            result = self._find_java_method_node(child, code, class_name, method_name)
            if result:
                return result
        return None

    def _search_python_method(
        self, root: Path, class_name: Optional[str], method_name: str,
    ) -> Optional[Tuple[str, str, str, str]]:
        """在 Python 文件中搜索方法"""
        for path in root.rglob("*.py"):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            try:
                code = path.read_text(encoding="utf-8", errors="replace")
                if class_name:
                    pattern = re.compile(
                        rf"class\s+{re.escape(class_name)}\b.*?"
                        rf"def\s+{re.escape(method_name)}\s*\(",
                        re.DOTALL,
                    )
                else:
                    pattern = re.compile(rf"def\s+{re.escape(method_name)}\s*\(")
                match = pattern.search(code)
                if match:
                    method_code = self._extract_python_method_body(
                        code, match.start(), method_name
                    )
                    if method_code:
                        return (str(path), "python", method_code, method_name)
            except Exception as e:
                logger.debug("解析 Python 文件失败 %s: %s", path, e)
        return None

    @staticmethod
    def _extract_python_method_body(
        code: str, start_pos: int, method_name: str,
    ) -> Optional[str]:
        """从代码中提取 Python 方法体"""
        lines = code[start_pos:].split("\n")
        def_line_idx = -1
        for i, line in enumerate(lines):
            if f"def {method_name}" in line:
                def_line_idx = i
                break
        if def_line_idx < 0:
            return None

        def_line = lines[def_line_idx]
        indent = len(def_line) - len(def_line.lstrip())

        body_lines = [lines[def_line_idx]]
        for line in lines[def_line_idx + 1:]:
            stripped = line.strip()
            if not stripped:
                body_lines.append(line)
                continue
            current_indent = len(line) - len(line.lstrip())
            if current_indent <= indent and stripped:
                break
            body_lines.append(line)
        return "\n".join(body_lines)

    # ── Python 控制流提取 ────────────────────────────────

    def _extract_python_flow(
        self, method_code: str, method_name: str,
        id_gen: _NodeIdGen, max_depth: int, warnings: List[str],
    ) -> Tuple[List[str], List[str]]:
        """从 Python 方法源码提取控制流"""
        nodes: List[str] = []
        edges: List[str] = []

        start_id = id_gen.next()
        nodes.append(f'    {start_id}["Start: {_escape_mermaid_flow(method_name)}"]')

        prev_id = start_id
        code_lines = method_code.split("\n")
        body_start = 1
        for i, line in enumerate(code_lines):
            if "def " in line:
                body_start = i + 1
                break

        prev_id = self._parse_python_block(
            code_lines[body_start:], id_gen, nodes, edges, prev_id,
            current_depth=0, max_depth=max_depth, warnings=warnings,
        )

        end_id = id_gen.next()
        nodes.append(f'    {end_id}(["End"])')
        if prev_id:
            edges.append(f"    {prev_id} --> {end_id}")

        return nodes, edges

    def _parse_python_block(
        self, lines: List[str], id_gen: _NodeIdGen,
        nodes: List[str], edges: List[str], prev_id: str,
        current_depth: int, max_depth: int, warnings: List[str],
    ) -> str:
        """递归解析 Python 代码块"""
        if not lines:
            return prev_id

        if current_depth >= max_depth:
            fold_id = id_gen.next()
            nodes.append(f'    {fold_id}["... 嵌套已折叠 ..."]')
            edges.append(f"    {prev_id} --> {fold_id}")
            warnings.append(f"嵌套深度超过 {max_depth} 层，部分逻辑已折叠")
            return fold_id

        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if not line or line.startswith("#"):
                i += 1
                continue

            if line.startswith("if ") or line.startswith("if("):
                condition = line.rstrip(":").replace("if ", "", 1).strip()
                cond_id = id_gen.next()
                esc = _escape_mermaid_flow(condition[:40])
                nodes.append(f'    {cond_id}{{{{"{esc}?"}}}}')
                edges.append(f"    {prev_id} --> {cond_id}")
                yes_id = id_gen.next()
                nodes.append(f'    {yes_id}["执行 if 分支"]')
                edges.append(f'    {cond_id} -->|"Yes"| {yes_id}')
                no_id = id_gen.next()
                nodes.append(f'    {no_id}["跳过/else"]')
                edges.append(f'    {cond_id} -->|"No"| {no_id}')
                merge_id = id_gen.next()
                nodes.append(f'    {merge_id}(["合并"])')
                edges.append(f"    {yes_id} --> {merge_id}")
                edges.append(f"    {no_id} --> {merge_id}")
                prev_id = merge_id
                i = self._skip_python_block(lines, i)
                continue

            if line.startswith(("for ", "while ")):
                loop_desc = line.rstrip(":").strip()[:40]
                loop_id = id_gen.next()
                esc = _escape_mermaid_flow(loop_desc)
                nodes.append(f'    {loop_id}{{{{"{esc}"}}}}')
                edges.append(f"    {prev_id} --> {loop_id}")
                body_id = id_gen.next()
                nodes.append(f'    {body_id}["循环体"]')
                edges.append(f'    {loop_id} -->|"继续"| {body_id}')
                edges.append(f"    {body_id} --> {loop_id}")
                exit_id = id_gen.next()
                nodes.append(f'    {exit_id}(["循环结束"])')
                edges.append(f'    {loop_id} -->|"结束"| {exit_id}')
                prev_id = exit_id
                i = self._skip_python_block(lines, i)
                continue

            if line.startswith("try:") or line.startswith("try "):
                try_id = id_gen.next()
                nodes.append(f'    {try_id}["try 块"]')
                edges.append(f"    {prev_id} --> {try_id}")
                success_id = id_gen.next()
                nodes.append(f'    {success_id}["正常执行"]')
                edges.append(f'    {try_id} -->|"成功"| {success_id}')
                except_id = id_gen.next()
                nodes.append(f'    {except_id}["异常处理"]')
                edges.append(f'    {try_id} -->|"异常"| {except_id}')
                merge_id = id_gen.next()
                nodes.append(f'    {merge_id}(["合并"])')
                edges.append(f"    {success_id} --> {merge_id}")
                edges.append(f"    {except_id} --> {merge_id}")
                prev_id = merge_id
                i = self._skip_python_block(lines, i)
                continue

            if line.startswith("return"):
                ret_val = line.replace("return", "").strip()[:30]
                ret_label = f"Return {ret_val}" if ret_val else "Return"
                ret_id = id_gen.next()
                nodes.append(f'    {ret_id}(["{_escape_mermaid_flow(ret_label)}"])')
                edges.append(f"    {prev_id} --> {ret_id}")
                prev_id = ret_id
                i += 1
                continue

            stmt_desc = line[:40] if len(line) > 40 else line
            stmt_id = id_gen.next()
            nodes.append(f'    {stmt_id}["{_escape_mermaid_flow(stmt_desc)}"]')
            edges.append(f"    {prev_id} --> {stmt_id}")
            prev_id = stmt_id
            i += 1

        return prev_id

    @staticmethod
    def _skip_python_block(lines: List[str], start_idx: int) -> int:
        """跳过 Python 代码块（基于缩进）"""
        if start_idx >= len(lines):
            return start_idx
        first_line = lines[start_idx]
        base_indent = len(first_line) - len(first_line.lstrip())
        i = start_idx + 1
        while i < len(lines):
            line = lines[i]
            stripped = line.strip()
            if not stripped:
                i += 1
                continue
            current_indent = len(line) - len(line.lstrip())
            if current_indent <= base_indent:
                if stripped.startswith(("elif ", "else:", "else ",
                                       "except:", "except ", "finally:")):
                    i += 1
                    continue
                break
            i += 1
        return i

    # ── Java 控制流提取 ──────────────────────────────────

    def _extract_java_flow(
        self, method_code: str, method_name: str, language: str,
        id_gen: _NodeIdGen, max_depth: int, warnings: List[str],
    ) -> Tuple[List[str], List[str]]:
        """从 Java 方法源码提取控制流"""
        try:
            from services.tree_sitter_service import TreeSitterService
        except ImportError:
            warnings.append("tree-sitter 不可用，回退到正则解析")
            return self._extract_java_flow_regex(
                method_code, method_name, id_gen, max_depth, warnings
            )

        ts = TreeSitterService()
        if not ts.is_available():
            warnings.append("tree-sitter 不可用，回退到正则解析")
            return self._extract_java_flow_regex(
                method_code, method_name, id_gen, max_depth, warnings
            )

        nodes: List[str] = []
        edges_list: List[str] = []

        start_id = id_gen.next()
        nodes.append(f'    {start_id}["Start: {_escape_mermaid_flow(method_name)}"]')

        ast_root = ts.parse(method_code, language)
        prev_id = self._walk_java_flow(
            ast_root, method_code, id_gen, nodes, edges_list,
            start_id, 0, max_depth, warnings,
        )

        end_id = id_gen.next()
        nodes.append(f'    {end_id}(["End"])')
        if prev_id:
            edges_list.append(f"    {prev_id} --> {end_id}")

        return nodes, edges_list

    def _walk_java_flow(
        self, node, code: str, id_gen: _NodeIdGen,
        nodes: List[str], edges: List[str], prev_id: str,
        current_depth: int, max_depth: int, warnings: List[str],
    ) -> str:
        """递归遍历 Java AST 提取控制流"""
        if current_depth >= max_depth:
            fold_id = id_gen.next()
            nodes.append(f'    {fold_id}["... 嵌套已折叠 ..."]')
            edges.append(f"    {prev_id} --> {fold_id}")
            return fold_id

        for child in node.children:
            if child.type == "if_statement":
                cond_node = child.child_by_field_name("condition")
                cond_text = ""
                if cond_node:
                    cond_text = code[cond_node.start_byte:cond_node.end_byte][:40]
                cond_id = id_gen.next()
                esc = _escape_mermaid_flow(cond_text)
                nodes.append(f'    {cond_id}{{{{"{esc}?"}}}}')
                edges.append(f"    {prev_id} --> {cond_id}")
                yes_id = id_gen.next()
                nodes.append(f'    {yes_id}["if 分支"]')
                edges.append(f'    {cond_id} -->|"Yes"| {yes_id}')
                no_id = id_gen.next()
                nodes.append(f'    {no_id}["else"]')
                edges.append(f'    {cond_id} -->|"No"| {no_id}')
                merge_id = id_gen.next()
                nodes.append(f'    {merge_id}(["合并"])')
                edges.append(f"    {yes_id} --> {merge_id}")
                edges.append(f"    {no_id} --> {merge_id}")
                prev_id = merge_id

            elif child.type in ("for_statement", "while_statement",
                                "enhanced_for_statement"):
                raw = code[child.start_byte:child.end_byte]
                loop_text = raw.split("{")[0].strip()[:40]
                loop_id = id_gen.next()
                esc = _escape_mermaid_flow(loop_text)
                nodes.append(f'    {loop_id}{{{{"{esc}"}}}}')
                edges.append(f"    {prev_id} --> {loop_id}")
                body_id = id_gen.next()
                nodes.append(f'    {body_id}["循环体"]')
                edges.append(f'    {loop_id} -->|"继续"| {body_id}')
                edges.append(f"    {body_id} --> {loop_id}")
                exit_id = id_gen.next()
                nodes.append(f'    {exit_id}(["循环结束"])')
                edges.append(f'    {loop_id} -->|"结束"| {exit_id}')
                prev_id = exit_id

            elif child.type == "try_statement":
                try_id = id_gen.next()
                nodes.append(f'    {try_id}["try 块"]')
                edges.append(f"    {prev_id} --> {try_id}")
                success_id = id_gen.next()
                nodes.append(f'    {success_id}["正常执行"]')
                edges.append(f'    {try_id} -->|"成功"| {success_id}')
                except_id = id_gen.next()
                nodes.append(f'    {except_id}["catch 处理"]')
                edges.append(f'    {try_id} -->|"异常"| {except_id}')
                merge_id = id_gen.next()
                nodes.append(f'    {merge_id}(["合并"])')
                edges.append(f"    {success_id} --> {merge_id}")
                edges.append(f"    {except_id} --> {merge_id}")
                prev_id = merge_id

            elif child.type in ("switch_expression", "switch_statement"):
                switch_id = id_gen.next()
                nodes.append(f'    {switch_id}{{{{"switch"}}}}')
                edges.append(f"    {prev_id} --> {switch_id}")
                merge_id = id_gen.next()
                nodes.append(f'    {merge_id}(["合并"])')
                case_count = 0
                for sc in child.children:
                    if sc.type in ("switch_block_statement_group", "switch_block"):
                        case_count += 1
                        case_id = id_gen.next()
                        nodes.append(f'    {case_id}["case {case_count}"]')
                        edges.append(f"    {switch_id} --> {case_id}")
                        edges.append(f"    {case_id} --> {merge_id}")
                if case_count == 0:
                    edges.append(f"    {switch_id} --> {merge_id}")
                prev_id = merge_id

            elif child.type == "return_statement":
                raw = code[child.start_byte:child.end_byte].strip()[:30]
                ret_id = id_gen.next()
                nodes.append(f'    {ret_id}(["{_escape_mermaid_flow(raw)}"])')
                edges.append(f"    {prev_id} --> {ret_id}")
                prev_id = ret_id

            elif child.type == "block":
                prev_id = self._walk_java_flow(
                    child, code, id_gen, nodes, edges, prev_id,
                    current_depth + 1, max_depth, warnings,
                )

            elif child.type == "expression_statement":
                raw = code[child.start_byte:child.end_byte].strip()[:40]
                if raw:
                    stmt_id = id_gen.next()
                    nodes.append(f'    {stmt_id}["{_escape_mermaid_flow(raw)}"]')
                    edges.append(f"    {prev_id} --> {stmt_id}")
                    prev_id = stmt_id

        return prev_id

    def _extract_java_flow_regex(
        self, method_code: str, method_name: str,
        id_gen: _NodeIdGen, max_depth: int, warnings: List[str],
    ) -> Tuple[List[str], List[str]]:
        """tree-sitter 不可用时的正则回退方案"""
        nodes: List[str] = []
        edges: List[str] = []

        start_id = id_gen.next()
        nodes.append(f'    {start_id}["Start: {_escape_mermaid_flow(method_name)}"]')
        body_id = id_gen.next()
        nodes.append(f'    {body_id}["方法体 #40;正则解析#41;"]')
        edges.append(f"    {start_id} --> {body_id}")

        if_count = len(re.findall(r"\bif\s*\(", method_code))
        loop_count = len(re.findall(r"\b(for|while)\s*\(", method_code))
        try_count = len(re.findall(r"\btry\s*\{", method_code))

        prev_id = body_id
        if if_count > 0:
            nid = id_gen.next()
            nodes.append(f'    {nid}{{{{"含 {if_count} 个 if 分支"}}}}')
            edges.append(f"    {prev_id} --> {nid}")
            prev_id = nid
        if loop_count > 0:
            nid = id_gen.next()
            nodes.append(f'    {nid}{{{{"含 {loop_count} 个循环"}}}}')
            edges.append(f"    {prev_id} --> {nid}")
            prev_id = nid
        if try_count > 0:
            nid = id_gen.next()
            nodes.append(f'    {nid}["含 {try_count} 个 try-catch"]')
            edges.append(f"    {prev_id} --> {nid}")
            prev_id = nid

        end_id = id_gen.next()
        nodes.append(f'    {end_id}(["End"])')
        edges.append(f"    {prev_id} --> {end_id}")
        warnings.append("使用正则回退方案，精度有限")
        return nodes, edges

    # ── 置信度计算 ────────────────────────────────────────

    @staticmethod
    def _compute_confidence(
        nodes_count: int, edges_count: int,
        warnings: List[str], language: str,
    ) -> float:
        """
        计算流程图的置信度评分 (0-1)。

        评分规则:
          - 基线: 0.5
          - 有节点和边: +0.2
          - 无折叠警告: +0.1
          - 使用 tree-sitter 解析: +0.1
          - 无错误警告: +0.1
        """
        score = 0.5
        if nodes_count > 0 and edges_count > 0:
            score += 0.2
        if not any("折叠" in w for w in warnings):
            score += 0.1
        if not any("正则回退" in w for w in warnings):
            score += 0.1
        if not any("出错" in w for w in warnings):
            score += 0.1
        return min(1.0, score)
