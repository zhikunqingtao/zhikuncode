"""
tree-sitter 语法解析引擎 — §4.14.1

50+ 语言的统一 AST 接口，用于符号提取、代码导航和 LLM 上下文构建。
与 LSP 互补: tree-sitter 直接在进程内解析（轻量级），适合快速符号提取。
"""

import logging
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

try:
    import tree_sitter_languages
    from tree_sitter import Parser, Node

    _TREE_SITTER_AVAILABLE = True
except ImportError:
    _TREE_SITTER_AVAILABLE = False
    Parser = None  # type: ignore
    Node = None  # type: ignore


@dataclass
class SymbolInfo:
    name: str
    kind: str          # "function" | "class" | "method" | "variable" | "import"
    start_line: int
    end_line: int
    docstring: Optional[str] = None
    parent: Optional[str] = None  # 所属类名 (方法时)


class TreeSitterService:
    """tree-sitter 语法解析引擎 — 50+ 语言的统一 AST 接口"""

    # 已加载的 parser 缓存 (语言名 -> Parser 实例)
    _parsers: dict = {}

    # 文件扩展名 -> tree-sitter 语言名
    LANG_MAP = {
        ".py": "python", ".js": "javascript", ".ts": "typescript",
        ".tsx": "tsx", ".jsx": "javascript", ".java": "java",
        ".go": "go", ".rs": "rust", ".rb": "ruby", ".cpp": "cpp",
        ".c": "c", ".cs": "c_sharp", ".kt": "kotlin", ".swift": "swift",
        ".php": "php", ".scala": "scala", ".lua": "lua", ".r": "r",
    }

    # 各语言的函数/类定义 node type
    FUNCTION_TYPES = {
        "function_definition", "function_declaration", "method_definition",
        "method_declaration", "arrow_function",
    }
    CLASS_TYPES = {"class_definition", "class_declaration", "class_specifier"}
    IMPORT_TYPES = {
        "import_statement", "import_from_statement", "import_declaration",
    }

    def __init__(self) -> None:
        self._parsers: dict[str, "Parser"] = {}

    @staticmethod
    def is_available() -> bool:
        return _TREE_SITTER_AVAILABLE

    def get_parser(self, language: str) -> "Parser":
        """获取或创建语言 parser (延迟加载)"""
        if not _TREE_SITTER_AVAILABLE:
            raise RuntimeError("tree-sitter not installed")
        if language not in self._parsers:
            parser = Parser()
            lang = tree_sitter_languages.get_language(language)
            parser.set_language(lang)
            self._parsers[language] = parser
        return self._parsers[language]

    def language_for_extension(self, ext: str) -> Optional[str]:
        """根据文件扩展名返回 tree-sitter 语言名"""
        return self.LANG_MAP.get(ext)

    def parse(self, code: str, language: str):
        """解析代码，返回 AST 根节点"""
        parser = self.get_parser(language)
        tree = parser.parse(bytes(code, "utf-8"))
        return tree.root_node

    def extract_symbols(self, code: str, language: str) -> list[SymbolInfo]:
        """从 AST 提取所有符号 — 用于 LLM 上下文的代码地图"""
        root = self.parse(code, language)
        symbols: list[SymbolInfo] = []
        self._walk_symbols(root, code, symbols, parent=None)
        return symbols

    def extract_imports(self, code: str, language: str) -> list[str]:
        """提取 import 语句 — 用于依赖关系分析"""
        root = self.parse(code, language)
        imports: list[str] = []
        self._walk_imports(root, code, imports)
        return imports

    def build_code_map(self, code: str, language: str) -> str:
        """生成代码地图摘要 — 紧凑的符号概览，节省 LLM token"""
        symbols = self.extract_symbols(code, language)
        lines: list[str] = []
        for s in symbols:
            indent = "  " if s.parent else ""
            doc = f" — {s.docstring[:50]}..." if s.docstring else ""
            lines.append(f"{indent}{s.kind} {s.name} (L{s.start_line}-{s.end_line}){doc}")
        return "\n".join(lines)

    def _walk_symbols(self, node, code: str, symbols: list[SymbolInfo],
                      parent: Optional[str]) -> None:
        """递归遍历 AST 提取符号"""
        node_type = node.type

        if node_type in self.FUNCTION_TYPES:
            name_node = node.child_by_field_name("name")
            if name_node:
                name = code[name_node.start_byte:name_node.end_byte]
                kind = "method" if parent else "function"
                symbols.append(SymbolInfo(
                    name=name, kind=kind,
                    start_line=node.start_point[0] + 1,
                    end_line=node.end_point[0] + 1,
                    parent=parent,
                ))

        elif node_type in self.CLASS_TYPES:
            name_node = node.child_by_field_name("name")
            if name_node:
                class_name = code[name_node.start_byte:name_node.end_byte]
                symbols.append(SymbolInfo(
                    name=class_name, kind="class",
                    start_line=node.start_point[0] + 1,
                    end_line=node.end_point[0] + 1,
                ))
                # 递归查找类内方法
                for child in node.children:
                    self._walk_symbols(child, code, symbols, parent=class_name)
                return  # 已处理子节点

        for child in node.children:
            self._walk_symbols(child, code, symbols, parent=parent)

    def _walk_imports(self, node, code: str, imports: list[str]) -> None:
        """递归遍历 AST 提取 import 语句"""
        if node.type in self.IMPORT_TYPES:
            text = code[node.start_byte:node.end_byte].strip()
            imports.append(text)
            return
        for child in node.children:
            self._walk_imports(child, code, imports)
