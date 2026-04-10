"""
Token Estimator Router — 使用 tiktoken 进行 Token 计数估算。

提供 REST API 供 Java 后端调用，用于精确估算 prompt/completion 的 Token 数量。
tiktoken 对 OpenAI 模型精确，对 Claude 约 5-10% 误差，仅用于预估。
"""

import logging
from fastapi import APIRouter

logger = logging.getLogger(__name__)

router = APIRouter()

# 延迟加载 tiktoken（首次调用时初始化）
_encoder = None


def _get_encoder():
    """获取 tiktoken 编码器（cl100k_base，兼容 GPT-4/Claude）"""
    global _encoder
    if _encoder is None:
        try:
            import tiktoken
            _encoder = tiktoken.get_encoding("cl100k_base")
            logger.info("tiktoken cl100k_base 编码器已初始化")
        except ImportError:
            logger.warning("tiktoken 未安装，将使用字符估算回退")
            _encoder = "fallback"
    return _encoder


def _count_tokens(text: str) -> int:
    """使用 tiktoken 计数，失败时回退到字符估算"""
    enc = _get_encoder()
    if enc == "fallback" or enc is None:
        # 回退: 英文约4字符/token，中文约2字符/token，取平均3.5
        return max(1, len(text) // 3)
    try:
        return len(enc.encode(text))
    except Exception:
        return max(1, len(text) // 3)


@router.post("/estimate")
async def estimate_tokens(body: dict):
    """
    估算文本的 Token 数量。

    请求体:
    {
        "texts": ["text1", "text2", ...],  // 要估算的文本列表
        "model": "cl100k_base"             // 编码器名称（可选，默认 cl100k_base）
    }

    响应:
    {
        "counts": [123, 456, ...],  // 各文本的 token 数
        "total": 579,               // 总 token 数
        "method": "tiktoken"        // 使用的计数方法
    }
    """
    texts = body.get("texts", [])
    if not texts:
        return {"counts": [], "total": 0, "method": "none"}

    enc = _get_encoder()
    method = "tiktoken" if enc != "fallback" and enc is not None else "heuristic"

    counts = [_count_tokens(t) for t in texts]

    return {
        "counts": counts,
        "total": sum(counts),
        "method": method,
    }


@router.post("/estimate-single")
async def estimate_single(body: dict):
    """
    估算单条文本的 Token 数量。

    请求体: { "text": "...", "model": "cl100k_base" }
    响应: { "count": 123, "method": "tiktoken" }
    """
    text = body.get("text", "")
    if not text:
        return {"count": 0, "method": "none"}

    enc = _get_encoder()
    method = "tiktoken" if enc != "fallback" and enc is not None else "heuristic"

    return {
        "count": _count_tokens(text),
        "method": method,
    }
