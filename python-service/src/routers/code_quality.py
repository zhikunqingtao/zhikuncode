"""Code quality analysis routes (F3: Complexity Treemap)."""

import logging
import time
from typing import Optional, List

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from workspace_paths import WorkspacePathError, resolve_workspace_path

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Code Quality"])


# ── Pydantic 请求模型 ──


class ComplexityRequest(BaseModel):
    project_root: str = Field(..., description="项目根目录绝对路径")
    target_path: Optional[str] = Field(None, description="可选子目录路径")
    languages: Optional[List[str]] = Field(
        None, description="分析语言列表，默认 python/java/typescript/javascript"
    )


# ── Service 延迟初始化 ──

_analyzer = None


def _get_analyzer(languages: Optional[List[str]] = None):
    global _analyzer
    if _analyzer is None or languages is not None:
        from services.complexity_analyzer import ComplexityAnalyzer
        if languages is not None:
            return ComplexityAnalyzer(languages=languages)
        _analyzer = ComplexityAnalyzer()
    return _analyzer


# ── 路由端点 ──


@router.get("/health")
async def health():
    """Health check for code quality service."""
    return {"status": "ok", "service": "code-quality"}


@router.post("/complexity")
async def analyze_complexity(request: ComplexityRequest):
    """F3 代码复杂度分析 — 返回项目级 Treemap 数据"""
    start = time.time()

    try:
        project_root_path = resolve_workspace_path(
            request.project_root, require_directory=True)
        target_path_obj = None
        if request.target_path:
            target_path_obj = resolve_workspace_path(
                request.target_path, base=project_root_path)
    except WorkspacePathError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error

    project_root = str(project_root_path)
    target_path = str(target_path_obj) if target_path_obj is not None else None

    try:
        analyzer = _get_analyzer(request.languages)
        result = await analyzer.analyze(project_root, target_path)
        elapsed_ms = int((time.time() - start) * 1000)

        return {
            "success": True,
            "data": {
                "root": result.root.model_dump(exclude_none=True),
                "stats": result.stats.model_dump(),
            },
            "elapsed_ms": elapsed_ms,
        }
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Complexity analysis failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Analysis error: {str(e)}")
