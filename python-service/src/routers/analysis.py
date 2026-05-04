"""Analysis routes (F25: API Contract, F33: Change Impact, F35: Diagram Generation, F40: Code Path Tracing)."""

import asyncio
import logging
import os
import time
from pathlib import Path
from typing import Any, List, Literal, Optional

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


# ── Helper functions (shared) ──

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


# ═══════════════════════════════════════════════════════════════
# F35: Diagram Generation (时序图 / 流程图)
# ═══════════════════════════════════════════════════════════════


class DiagramOptions(BaseModel):
    depth: int = Field(default=3, ge=1, le=5, description="追踪深度")
    include_tests: bool = Field(default=False, description="是否包含测试文件")
    format: str = Field(default="mermaid", description="输出格式")


class DiagramRequest(BaseModel):
    diagram_type: Literal["sequence", "flowchart"]
    target: str  # API路径或方法签名
    project_root: str  # 项目根目录绝对路径
    options: DiagramOptions = DiagramOptions()


class DiagramMetadataResponse(BaseModel):
    nodes_count: int
    edges_count: int
    languages_analyzed: List[str]
    analysis_time_ms: float


class DiagramGenerationResult(BaseModel):
    diagram_type: str
    mermaid_syntax: str
    confidence_score: float  # 0-1
    metadata: DiagramMetadataResponse
    warnings: List[str] = []


_DIAGRAM_TIMEOUT_SECONDS = 30


@router.post("/generate-diagram", response_model=DiagramGenerationResult)
async def generate_diagram(request: DiagramRequest):
    """生成代码图表（时序图/流程图）— F35"""
    # 1. 参数校验：project_root 必须是存在的目录
    if not _validate_path_safe(request.project_root):
        raise HTTPException(status_code=400, detail="Invalid project_root: path traversal detected")

    if not os.path.isdir(request.project_root):
        raise HTTPException(status_code=400, detail=f"project_root does not exist: {request.project_root}")

    if not request.target.strip():
        raise HTTPException(status_code=400, detail="target must not be empty")

    # 2. 按 diagram_type 分发到对应生成器
    try:
        result = await asyncio.wait_for(
            _run_diagram_generation(request),
            timeout=_DIAGRAM_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=408,
            detail=f"Diagram generation timed out after {_DIAGRAM_TIMEOUT_SECONDS}s",
        )
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error("Diagram generation failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Diagram generation error: {str(e)}")

    return result


async def _run_diagram_generation(request: DiagramRequest) -> DiagramGenerationResult:
    """在线程池中运行图表生成（CPU 密集型操作）"""
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, _generate_diagram_sync, request)
    return result


def _generate_diagram_sync(request: DiagramRequest) -> DiagramGenerationResult:
    """同步执行图表生成逻辑"""
    if request.diagram_type == "sequence":
        from analyzers.sequence_diagram_generator import SequenceDiagramGenerator
        generator = SequenceDiagramGenerator(project_root=request.project_root)
        diagram_result = generator.generate(
            target=request.target,
            depth=request.options.depth,
            include_tests=request.options.include_tests,
        )
    else:
        from analyzers.flow_chart_generator import FlowChartGenerator
        generator = FlowChartGenerator(project_root=request.project_root)
        diagram_result = generator.generate(
            target=request.target,
            depth=request.options.depth,
        )

    # 如果 confidence_score 为 0 且有 "未找到" 警告，视为 target 未找到
    if diagram_result.confidence_score == 0.0 and any(
        "未找到" in w for w in diagram_result.warnings
    ):
        raise FileNotFoundError(f"Target '{request.target}' not found in project")

    return DiagramGenerationResult(
        diagram_type=diagram_result.diagram_type,
        mermaid_syntax=diagram_result.mermaid_syntax,
        confidence_score=diagram_result.confidence_score,
        metadata=DiagramMetadataResponse(
            nodes_count=diagram_result.metadata.nodes_count,
            edges_count=diagram_result.metadata.edges_count,
            languages_analyzed=diagram_result.metadata.languages_analyzed,
            analysis_time_ms=diagram_result.metadata.analysis_time_ms,
        ),
        warnings=diagram_result.warnings,
    )


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


# ═══════════════════════════════════════════════════════════════
# F40: Code Path Tracing (代码路径追踪)
# ═══════════════════════════════════════════════════════════════


class APIEndpointRequest(BaseModel):
    project_root: str = Field(..., description="项目根目录路径")
    languages: Optional[List[str]] = Field(None, description="指定语言过滤")


class CodePathRequest(BaseModel):
    project_root: str = Field(..., description="项目根目录路径")
    entry_file: str = Field(..., description="入口方法所在文件路径")
    entry_function: str = Field(..., description="入口方法名")
    max_depth: int = Field(10, ge=1, le=20, description="最大追踪深度")


def _scan_endpoints_sync(request: APIEndpointRequest) -> dict:
    """同步执行 API 端点扫描"""
    from analyzers.code_path_tracer import CodePathTracer
    tracer = CodePathTracer(project_root=request.project_root)
    endpoints = tracer.scan_api_endpoints(languages=request.languages)
    return {
        "success": True,
        "endpoints": [ep.model_dump() for ep in endpoints],
        "total": len(endpoints),
    }


def _trace_code_path_sync(request: CodePathRequest) -> dict:
    """同步执行代码路径追踪"""
    from analyzers.code_path_tracer import CodePathTracer
    tracer = CodePathTracer(project_root=request.project_root)
    result = tracer.trace_code_path(
        entry_file=request.entry_file,
        entry_function=request.entry_function,
        max_depth=request.max_depth,
    )
    return {
        "success": True,
        "data": result.model_dump(),
    }


@router.post("/api-endpoints")
async def scan_api_endpoints(request: APIEndpointRequest):
    """扫描项目所有 API 端点 (F40)"""
    if not _validate_path_safe(request.project_root):
        raise HTTPException(status_code=400, detail="Invalid project_root: path traversal detected")

    if not os.path.isdir(request.project_root):
        raise HTTPException(status_code=400, detail=f"project_root does not exist: {request.project_root}")

    try:
        result = await asyncio.wait_for(
            asyncio.get_event_loop().run_in_executor(
                None, _scan_endpoints_sync, request
            ),
            timeout=30.0,
        )
        return result
    except asyncio.TimeoutError:
        raise HTTPException(status_code=408, detail="API endpoint scan timed out after 30s")
    except Exception as e:
        logger.error("API endpoint scan failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Scan error: {str(e)}")


@router.post("/code-path")
async def trace_code_path(request: CodePathRequest):
    """追踪指定 API 的完整代码路径 (F40)"""
    if not _validate_path_safe(request.project_root):
        raise HTTPException(status_code=400, detail="Invalid project_root: path traversal detected")

    if not os.path.isdir(request.project_root):
        raise HTTPException(status_code=400, detail=f"project_root does not exist: {request.project_root}")

    if not _validate_path_safe(request.entry_file):
        raise HTTPException(status_code=400, detail="Invalid entry_file: path traversal detected")

    try:
        result = await asyncio.wait_for(
            asyncio.get_event_loop().run_in_executor(
                None, _trace_code_path_sync, request
            ),
            timeout=30.0,
        )
        return result
    except asyncio.TimeoutError:
        raise HTTPException(status_code=408, detail="Code path tracing timed out after 30s")
    except Exception as e:
        logger.error("Code path tracing failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Trace error: {str(e)}")
