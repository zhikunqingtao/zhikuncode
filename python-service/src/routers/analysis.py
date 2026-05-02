"""Analysis routes (F25: API Contract, F33: Change Impact)."""

import logging
import os
import time
from pathlib import Path
from typing import Any, List, Optional

import httpx
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Analysis"])

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://localhost:8080")
JAVA_OPENAPI_PATH = "/v3/api-docs"
_CACHE_TTL_SECONDS = 300  # 5 minutes

# ── Simple TTL cache ──
_merged_cache: dict[str, Any] = {"data": None, "timestamp": 0.0}


# ── Pydantic response models ──

class MergedOpenAPIResponse(BaseModel):
    openapi: str = "3.0.3"
    info: dict[str, str] = Field(default_factory=dict)
    paths: dict[str, Any] = Field(default_factory=dict)
    components: dict[str, Any] = Field(default_factory=dict)
    tags: list[dict[str, Any]] = Field(default_factory=list)
    warnings: Optional[list[str]] = None


class JavaProxyErrorResponse(BaseModel):
    error: str
    detail: str


# ── Helper functions ──

def merge_openapi_specs(python_spec: dict, java_spec: dict) -> dict:
    """Merge Python and Java OpenAPI specs into a single unified spec."""
    merged = {
        "openapi": "3.0.3",
        "info": {
            "title": "ZhikunCode API (Merged)",
            "version": "1.0.0",
            "description": "Combined API specification from Java Backend and Python Service",
        },
        "paths": {**java_spec.get("paths", {}), **python_spec.get("paths", {})},
        "components": {
            "schemas": {
                **java_spec.get("components", {}).get("schemas", {}),
                **python_spec.get("components", {}).get("schemas", {}),
            }
        },
        "tags": java_spec.get("tags", []) + python_spec.get("tags", []),
    }
    return merged


async def _fetch_java_openapi() -> Optional[dict]:
    """Fetch OpenAPI spec from Java backend, returns None on failure."""
    url = f"{JAVA_BACKEND_URL}{JAVA_OPENAPI_PATH}"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()
    except Exception as e:
        logger.warning(f"Failed to fetch Java OpenAPI spec from {url}: {e}")
        return None


# ── Route endpoints ──

@router.get("/health")
async def health():
    """Health check for analysis service."""
    return {"status": "ok", "service": "analysis"}


@router.get("/openapi/merged")
async def get_merged_openapi(request: Request, refresh: bool = False):
    """Merge Java backend and Python service OpenAPI specs.

    Uses a 5-minute TTL cache. Pass ?refresh=true to force refresh.
    """
    now = time.time()

    # Check cache
    if (
        not refresh
        and _merged_cache["data"] is not None
        and (now - _merged_cache["timestamp"]) < _CACHE_TTL_SECONDS
    ):
        logger.debug("Returning cached merged OpenAPI spec")
        return _merged_cache["data"]

    # Get Python spec from the running app
    python_spec = request.app.openapi()

    # Fetch Java spec with graceful degradation
    warnings: list[str] = []
    java_spec = await _fetch_java_openapi()

    if java_spec is None:
        warnings.append(
            f"Java backend unreachable at {JAVA_BACKEND_URL}{JAVA_OPENAPI_PATH}; "
            "returning Python-only spec"
        )
        merged = merge_openapi_specs(python_spec, {})
    else:
        merged = merge_openapi_specs(python_spec, java_spec)

    if warnings:
        merged["warnings"] = warnings

    # Update cache
    _merged_cache["data"] = merged
    _merged_cache["timestamp"] = now
    logger.info("Merged OpenAPI spec generated (warnings=%d)", len(warnings))

    return merged


@router.get("/openapi/java")
async def get_java_openapi():
    """Proxy to Java backend OpenAPI spec."""
    url = f"{JAVA_BACKEND_URL}{JAVA_OPENAPI_PATH}"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()
    except Exception as e:
        logger.error(f"Java backend unreachable: {e}")
        raise HTTPException(
            status_code=502,
            detail={"error": "Java backend unreachable", "detail": str(e)},
        )


@router.get("/openapi/python")
async def get_python_openapi(request: Request):
    """Return Python service's own OpenAPI spec."""
    return request.app.openapi()


# ═══════════════════════════════════════════════════════════════
# F33: Change Impact Analysis
# ═══════════════════════════════════════════════════════════════

class ChangeImpactRequest(BaseModel):
    file_path: str = Field(..., description="被修改的文件路径")
    changed_lines: List[int] = Field(..., description="修改的行号列表")
    project_root: str = Field(..., description="项目根目录")
    depth: int = Field(3, ge=1, le=5, description="BFS 最大深度 (1|3|5)")


_change_impact_analyzer = None


def _get_change_impact_analyzer():
    global _change_impact_analyzer
    if _change_impact_analyzer is None:
        from analyzers.change_impact_analyzer import ChangeImpactAnalyzer
        _change_impact_analyzer = ChangeImpactAnalyzer()
    return _change_impact_analyzer


def _validate_path_safe(path: str) -> bool:
    """验证路径安全：禁止 .. 穿越"""
    normalized = os.path.normpath(path)
    return ".." not in normalized.split(os.sep)


def _validate_paths_contained(file_path: str, project_root: str) -> bool:
    """Ensure file_path is within project_root."""
    try:
        Path(file_path).resolve().relative_to(Path(project_root).resolve())
        return True
    except ValueError:
        return False


@router.post("/change-impact")
async def analyze_change_impact(request: ChangeImpactRequest):
    """分析代码变更的影响链路 (F33)"""
    start_ms = time.time() * 1000

    # 路径安全验证
    if not _validate_path_safe(request.file_path):
        raise HTTPException(status_code=400, detail="Invalid file_path: path traversal detected")
    if not _validate_path_safe(request.project_root):
        raise HTTPException(status_code=400, detail="Invalid project_root: path traversal detected")

    # 验证 file_path 位于 project_root 内
    if not _validate_paths_contained(request.file_path, request.project_root):
        raise HTTPException(status_code=400, detail="Invalid file_path: must be within project_root")

    if not os.path.isdir(request.project_root):
        raise HTTPException(status_code=400, detail=f"project_root does not exist: {request.project_root}")

    try:
        analyzer = _get_change_impact_analyzer()
        result = await analyzer.analyze(
            file_path=request.file_path,
            changed_lines=request.changed_lines,
            project_root=request.project_root,
            depth=request.depth,
        )
        elapsed_ms = round(time.time() * 1000 - start_ms, 1)
        return {
            "success": True,
            "data": result.model_dump(),
            "elapsed_ms": elapsed_ms,
        }
    except Exception as e:
        logger.error("Change impact analysis failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Analysis error: {str(e)}")
