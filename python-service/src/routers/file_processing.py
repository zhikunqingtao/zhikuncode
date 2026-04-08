"""
File Processing Router — §4.14.7

文件编码检测 (chardet)、MIME 类型检测 (python-magic)、
安全读取 (自动编码)、文件变更监听 (watchfiles SSE)。
"""

import json
import logging
from datetime import datetime
from typing import Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["File Processing"])


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


# ── Service 延迟初始化 ──

_detector = None


def _get_detector():
    global _detector
    if _detector is None:
        from services.file_detector import FileDetector
        _detector = FileDetector()
    return _detector


# ── 路由端点 ──

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
