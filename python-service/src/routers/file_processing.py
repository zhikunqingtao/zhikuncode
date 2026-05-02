"""
File Processing Router — §4.14.7

文件编码检测 (chardet)、MIME 类型检测 (python-magic)、
安全读取 (自动编码)、文件变更监听 (watchfiles SSE)。
"""

import json
import logging
import os
from datetime import datetime
from typing import Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["File Processing"])

BASE_ROOT = os.path.abspath(os.getenv("WORKSPACE_ROOT", os.getcwd()))


# ── Pydantic 模型 ──

class EncodingRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")


class EncodingResponse(BaseModel):
    encoding: str
    confidence: float
    language: str


class MimeRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")


class MimeResponse(BaseModel):
    mime_type: str
    description: str
    is_text: bool
    is_binary: bool


class SafeReadRequest(BaseModel):
    file_path: str = Field(..., description="文件路径")


class SafeReadResponse(BaseModel):
    content: str
    encoding: str
    length: int


class EncodingDetectBytesRequest(BaseModel):
    data_base64: str = Field(..., description="Base64 编码的原始字节")


class FileTreeRequest(BaseModel):
    root_path: str = Field(..., description="项目根目录路径")
    max_depth: Optional[int] = Field(5, description="最大递归深度")
    exclude_patterns: Optional[list[str]] = Field(None, description="额外排除的目录/文件名")


class FileTreeNode(BaseModel):
    name: str
    path: str
    type: str  # 'file' or 'dir'
    children: Optional[list['FileTreeNode']] = None
    size: Optional[int] = None
    extension: Optional[str] = None


FileTreeNode.model_rebuild()


# ── Service 延迟初始化 ──

_detector = None


def _get_detector():
    global _detector
    if _detector is None:
        from services.file_detector import FileDetector
        _detector = FileDetector()
    return _detector


# ── 路由端点 ──


@router.post("/tree")
async def get_file_tree(request: FileTreeRequest):
    """返回项目文件树结构"""
    DEFAULT_EXCLUDES = {
        'node_modules', '.git', '__pycache__', 'target', 'dist',
        '.venv', 'venv', '.idea', '.vscode', '.next', 'build',
        '.qoder', '.claude', '.ai-code-assistant', '.scratchpad',
        '.zhikun', '.zhikun-scratchpad', 'egg-info',
    }

    excludes = set(request.exclude_patterns or []) | DEFAULT_EXCLUDES

    def build_tree(path: str, depth: int) -> FileTreeNode:
        name = os.path.basename(path) or path
        if os.path.isfile(path):
            ext = os.path.splitext(name)[1]
            try:
                size = os.path.getsize(path)
            except OSError:
                size = None
            return FileTreeNode(
                name=name,
                path=os.path.relpath(path, request.root_path),
                type='file',
                size=size,
                extension=ext if ext else None,
            )

        children = []
        if depth < max_depth:
            try:
                entries = sorted(os.listdir(path))
                dirs = []
                files = []
                for entry in entries:
                    if entry in excludes or entry.startswith('.'):
                        continue
                    full_path = os.path.join(path, entry)
                    if os.path.isdir(full_path):
                        dirs.append(full_path)
                    else:
                        files.append(full_path)
                # 目录排前面，文件排后面
                for d in dirs:
                    children.append(build_tree(d, depth + 1))
                for f in files:
                    children.append(build_tree(f, depth + 1))
            except PermissionError:
                pass

        return FileTreeNode(
            name=name,
            path=os.path.relpath(path, request.root_path),
            type='dir',
            children=children,
        )

    # 安全校验：限制在工作空间内
    requested_root = os.path.abspath(os.path.join(BASE_ROOT, request.root_path))
    if not (requested_root == BASE_ROOT or requested_root.startswith(BASE_ROOT + os.sep)):
        raise HTTPException(status_code=400, detail="root_path outside workspace is not allowed")

    root = requested_root
    if not os.path.isdir(root):
        raise HTTPException(status_code=400, detail="Invalid root path")

    max_depth = request.max_depth if request.max_depth is not None else 5

    try:
        tree = build_tree(root, 0)  # noqa: uses max_depth from closure
        return {"success": True, "data": tree}
    except Exception as e:
        logger.error(f"File tree build failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/detect-encoding", response_model=EncodingResponse)
async def detect_encoding(request: EncodingRequest):
    """检测文件编码"""
    try:
        det = _get_detector()
        result = det.detect_encoding(request.file_path)
        return EncodingResponse(
            encoding=result.encoding,
            confidence=result.confidence,
            language=result.language,
        )
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File not found: {request.file_path}")
    except Exception as e:
        logger.error(f"Encoding detection failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/detect-type", response_model=MimeResponse)
async def detect_type(request: MimeRequest):
    """检测文件 MIME 类型"""
    try:
        det = _get_detector()
        result = det.detect_type(request.file_path)
        return MimeResponse(
            mime_type=result.mime_type,
            description=result.description,
            is_text=result.is_text,
            is_binary=result.is_binary,
        )
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File not found: {request.file_path}")
    except Exception as e:
        logger.error(f"Type detection failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/safe-read", response_model=SafeReadResponse)
async def safe_read(request: SafeReadRequest):
    """安全读取文件 — 自动检测编码"""
    try:
        det = _get_detector()
        content, encoding = det.safe_read(request.file_path)
        return SafeReadResponse(
            content=content,
            encoding=encoding,
            length=len(content),
        )
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File not found: {request.file_path}")
    except Exception as e:
        logger.error(f"Safe read failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/detect-encoding-bytes", response_model=EncodingResponse)
async def detect_encoding_bytes(request: EncodingDetectBytesRequest):
    """从 Base64 编码的原始字节检测编码"""
    import base64
    try:
        raw = base64.b64decode(request.data_base64)
        det = _get_detector()
        result = det.detect_encoding_bytes(raw)
        return EncodingResponse(
            encoding=result.encoding,
            confidence=result.confidence,
            language=result.language,
        )
    except Exception as e:
        logger.error(f"Encoding detection from bytes failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/watch")
async def watch_files(
    path: str = Query(..., description="监听目录路径"),
    extensions: str = Query("", description="文件扩展名过滤 (逗号分隔)"),
):
    """文件变更监听 — SSE 流式推送 (watchfiles Rust 内核)"""
    try:
        import watchfiles
        from sse_starlette.sse import EventSourceResponse

        ext_filter = set(extensions.split(",")) if extensions else None

        async def event_generator():
            async for changes in watchfiles.awatch(path):
                for change_type, changed_path in changes:
                    if ext_filter and not any(
                        changed_path.endswith(e) for e in ext_filter
                    ):
                        continue
                    yield {
                        "event": "file_change",
                        "data": json.dumps({
                            "type": change_type.name,
                            "path": str(changed_path),
                            "timestamp": datetime.now().isoformat(),
                        }),
                    }

        return EventSourceResponse(event_generator())
    except ImportError:
        raise HTTPException(
            status_code=501,
            detail="watchfiles not installed — file watching unavailable",
        )
