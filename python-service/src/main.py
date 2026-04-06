"""
AI Code Assistant — Python Service 入口
FastAPI 应用 + 动态能力域路由注册
"""

import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

logger = logging.getLogger(__name__)

# ───── 生命周期管理 ─────
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期: 启动时探测能力域，关闭时清理资源"""
    logger.info("Python Service 启动中...")
    # TODO: 阶段 2+ 将在此处调用 discover_capabilities() 动态注册路由
    yield
    logger.info("Python Service 关闭")


app = FastAPI(
    title="AI Code Assistant - Python Service",
    version="1.0.0",
    lifespan=lifespan,
)

# ───── CORS ─────
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ───── 核心路由 (始终加载) ─────
@app.get("/api/health", tags=["Health"])
async def health():
    """健康检查端点 — Java 后端启动时轮询此端点"""
    return {
        "status": "ok",
        "service": "ai-code-assistant-python",
        "version": "1.0.0",
    }


@app.get("/api/health/capabilities", tags=["Health"])
async def get_capabilities():
    """返回所有能力域的可用状态"""
    # TODO: 阶段 2+ 接入 capabilities.py 的 discover_capabilities()
    return {
        "CODE_INTEL": {"name": "代码智能", "available": False, "reason": "尚未初始化"},
        "FILE_PROCESSING": {"name": "文件处理", "available": False, "reason": "尚未初始化"},
    }


# ───── 启动入口 ─────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="127.0.0.1",
        port=8000,
        reload=True,
        log_level="info",
    )
