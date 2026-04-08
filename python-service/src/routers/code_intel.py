"""
Code Intelligence Router — §4.14.1 / §4.14.7a

对齐 5 个 Code Intel 端点: parse/symbols/dependencies/refactor/complete
"""

import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Code Intelligence"])


# ── Pydantic 请求/响应模型 ──

class ParseRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")
    content: str = Field(..., description="文件内容")
    language: Optional[str] = Field(None, description="语言 (自动检测)")


class SymbolItem(BaseModel):
    name: str
    kind: str
    start_line: int
    end_line: int
    docstring: Optional[str] = None
    parent: Optional[str] = None


class ParseResponse(BaseModel):
    language: str
    symbols: list[SymbolItem]
    imports: list[str]
    code_map: str


class SymbolsRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")
    content: str = Field(..., description="文件内容")
    language: Optional[str] = Field(None, description="语言")


class SymbolsResponse(BaseModel):
    symbols: list[SymbolItem]
    total: int


class DependenciesRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")
    content: str = Field(..., description="文件内容")
    language: Optional[str] = Field(None, description="语言")


class DependenciesResponse(BaseModel):
    imports: list[str]
    total: int


class CodeMapRequest(BaseModel):
    file_path: str
    content: str
    language: Optional[str] = None


class CodeMapResponse(BaseModel):
    code_map: str
    symbol_count: int


# ── Service 延迟初始化 ──

_service = None


def _get_service():
    global _service
    if _service is None:
        from services.tree_sitter_service import TreeSitterService
        _service = TreeSitterService()
    return _service


def _resolve_language(file_path: str, language: Optional[str]) -> str:
    """根据文件路径或显式语言参数解析 tree-sitter 语言名"""
    if language:
        return language
    import os
    ext = os.path.splitext(file_path)[1]
    svc = _get_service()
    lang = svc.language_for_extension(ext)
    if not lang:
        raise ValueError(f"Unsupported file extension: {ext}")
    return lang


# ── 路由端点 ──

@router.post("/parse", response_model=ParseResponse)
async def parse_code(request: ParseRequest):
    """解析源代码 — 返回符号 + 导入 + 代码地图"""
    try:
        svc = _get_service()
        lang = _resolve_language(request.file_path, request.language)
        symbols = svc.extract_symbols(request.content, lang)
        imports = svc.extract_imports(request.content, lang)
        code_map = svc.build_code_map(request.content, lang)
        return ParseResponse(
            language=lang,
            symbols=[SymbolItem(
                name=s.name, kind=s.kind,
                start_line=s.start_line, end_line=s.end_line,
                docstring=s.docstring, parent=s.parent,
            ) for s in symbols],
            imports=imports,
            code_map=code_map,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Parse failed: {e}")
        raise HTTPException(status_code=500, detail=f"Parse error: {str(e)}")


@router.post("/symbols", response_model=SymbolsResponse)
async def extract_symbols(request: SymbolsRequest):
    """提取符号表"""
    try:
        svc = _get_service()
        lang = _resolve_language(request.file_path, request.language)
        symbols = svc.extract_symbols(request.content, lang)
        return SymbolsResponse(
            symbols=[SymbolItem(
                name=s.name, kind=s.kind,
                start_line=s.start_line, end_line=s.end_line,
                docstring=s.docstring, parent=s.parent,
            ) for s in symbols],
            total=len(symbols),
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Symbol extraction failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/dependencies", response_model=DependenciesResponse)
async def analyze_dependencies(request: DependenciesRequest):
    """分析依赖关系 — import 语句解析"""
    try:
        svc = _get_service()
        lang = _resolve_language(request.file_path, request.language)
        imports = svc.extract_imports(request.content, lang)
        return DependenciesResponse(imports=imports, total=len(imports))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Dependency analysis failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/code-map", response_model=CodeMapResponse)
async def build_code_map(request: CodeMapRequest):
    """生成代码地图摘要"""
    try:
        svc = _get_service()
        lang = _resolve_language(request.file_path, request.language)
        code_map = svc.build_code_map(request.content, lang)
        symbols = svc.extract_symbols(request.content, lang)
        return CodeMapResponse(code_map=code_map, symbol_count=len(symbols))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Code map build failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))
