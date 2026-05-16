"""
Tokenizer Router — 精确 Token 计数服务。

供 Java 端 TokenizerService 调用，使用 tiktoken cl100k_base 编码器。
与 token_estimator.py 的区别：此路由面向 Java 端单文本精确计数场景，
返回结构化响应（含 elapsed_ms）便于性能监控。
"""

import logging
import time

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

logger = logging.getLogger(__name__)

router = APIRouter()

# 延迟加载 tiktoken 编码器
_encoder = None


def _get_encoder():
    """获取 tiktoken 编码器（cl100k_base，兼容 GPT-4/Claude）"""
    global _encoder
    if _encoder is None:
        try:
            import tiktoken
            _encoder = tiktoken.get_encoding("cl100k_base")
            logger.info("tiktoken cl100k_base 编码器已初始化 (tokenizer router)")
        except ImportError:
            logger.warning("tiktoken 未安装，将使用字符估算回退")
            _encoder = "fallback"
    return _encoder


class TokenCountRequest(BaseModel):
    text: str
    model: str = "default"


class TokenCountResponse(BaseModel):
    token_count: int
    model: str
    elapsed_ms: float


@router.post("/count", response_model=TokenCountResponse)
async def count_tokens(request: TokenCountRequest):
    """
    精确计算文本的 Token 数量。

    使用 tiktoken cl100k_base 编码器，兼容 GPT-4/Claude 系列模型。
    tiktoken 未安装时回退到字符估算（len // 4）。
    """
    start = time.time()

    try:
        enc = _get_encoder()
        if enc == "fallback" or enc is None:
            # tiktoken 未安装时的简单估算
            token_count = max(1, len(request.text) // 4)
        else:
            token_count = len(enc.encode(request.text))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

    elapsed = (time.time() - start) * 1000
    return TokenCountResponse(
        token_count=token_count,
        model=request.model,
        elapsed_ms=round(elapsed, 2),
    )
