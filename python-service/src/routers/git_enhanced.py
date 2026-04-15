"""
Git Enhanced Router — Git 增强能力域路由
"""
import logging
from typing import Optional
from fastapi import APIRouter
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Git Enhanced"])


# ── Pydantic 请求/响应模型 ──

class DiffRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    ref1: str = Field(default="HEAD~1", description="起始引用")
    ref2: str = Field(default="HEAD", description="结束引用")


class DiffResponse(BaseModel):
    summary: str = Field(description="变更统计摘要")
    detailed: str = Field(description="详细 diff 内容")
    files_changed: int = Field(description="变更文件数")


class LogRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    max_count: int = Field(default=20, ge=1, le=100, description="最大条目数")
    branch: Optional[str] = Field(default=None, description="分支名")


class LogEntry(BaseModel):
    sha: str
    message: str
    author: str
    date: str
    files: list[str]


class LogResponse(BaseModel):
    commits: list[LogEntry]
    total: int


class BlameRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    file_path: str = Field(..., description="文件相对路径")
    ref: str = Field(default="HEAD", description="引用")


class BlameLine(BaseModel):
    line_no: int
    sha: str
    author: str
    date: str
    content: str


class BlameResponse(BaseModel):
    file_path: str
    lines: list[BlameLine]
    total_lines: int


class GitResponse(BaseModel):
    """统一响应包装 — Java 端据此区分'能力缺失'和'业务错误'"""
    success: bool = True
    data: dict | None = None
    error_code: str | None = None
    error_message: str | None = None


# ── Service 延迟初始化 ──

_service = None


def _get_service():
    global _service
    if _service is None:
        from services.git_enhanced_service import GitEnhancedService
        _service = GitEnhancedService()
    return _service


# ── 路由端点 ──

@router.post("/diff", response_model=GitResponse)
async def git_diff(request: DiffRequest):
    try:
        svc = _get_service()
        result = svc.semantic_diff(request.repo_path, request.ref1, request.ref2)
        return GitResponse(success=True, data=result)
    except ValueError as e:
        return GitResponse(success=False, error_code="INVALID_INPUT", error_message=str(e))
    except Exception as e:
        logger.error(f"Git diff failed: {e}")
        return GitResponse(success=False, error_code="INTERNAL_ERROR", error_message=str(e))


@router.post("/log", response_model=GitResponse)
async def git_log(request: LogRequest):
    try:
        svc = _get_service()
        result = svc.enhanced_log(request.repo_path, request.max_count, request.branch)
        return GitResponse(success=True, data=result)
    except ValueError as e:
        return GitResponse(success=False, error_code="INVALID_INPUT", error_message=str(e))
    except Exception as e:
        logger.error(f"Git log failed: {e}")
        return GitResponse(success=False, error_code="INTERNAL_ERROR", error_message=str(e))


@router.post("/blame", response_model=GitResponse)
async def git_blame(request: BlameRequest):
    try:
        svc = _get_service()
        result = svc.file_blame(request.repo_path, request.file_path, request.ref)
        return GitResponse(success=True, data=result)
    except ValueError as e:
        return GitResponse(success=False, error_code="INVALID_INPUT", error_message=str(e))
    except Exception as e:
        logger.error(f"Git blame failed: {e}")
        return GitResponse(success=False, error_code="INTERNAL_ERROR", error_message=str(e))
