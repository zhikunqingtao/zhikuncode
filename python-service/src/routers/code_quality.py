"""Code quality analysis routes (F3: Complexity Treemap)."""

import logging
import os
import time
from pathlib import Path
from typing import Optional, List

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

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

    # 路径安全校验
    project_root = os.path.abspath(request.project_root)
    if ".." in request.project_root:
        raise HTTPException(status_code=400, detail="Path traversal not allowed")
    if not os.path.isabs(request.project_root):
        raise HTTPException(status_code=400, detail="project_root must be an absolute path")
    if not os.path.isdir(project_root):
        raise HTTPException(status_code=400, detail=f"Directory not found: {project_root}")

    target_path = None
    if request.target_path:
        target_path = os.path.abspath(request.target_path)
        if ".." in request.target_path:
            raise HTTPException(status_code=400, detail="Path traversal not allowed in target_path")
        # 验证 target_path 位于 project_root 内
        try:
            Path(target_path).resolve().relative_to(Path(project_root).resolve())
        except ValueError:
            raise HTTPException(status_code=400, detail="target_path must be within project_root")
        if not os.path.exists(target_path):
            raise HTTPException(status_code=400, detail=f"Target path not found: {target_path}")

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
