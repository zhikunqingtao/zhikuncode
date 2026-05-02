"""
AI Code Assistant — Python Service 入口
FastAPI 应用 + 动态能力域路由注册

v1.15.0: 能力域动态探测 + 按需加载路由
"""

import importlib
import logging
import os
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from capabilities import (
    discover_capabilities,
    CapabilityDomain,
    CAPABILITY_REGISTRY,
)

logger = logging.getLogger(__name__)

# 路由模块 → (prefix, tags) 映射
ROUTER_PREFIX_MAP = {
    "routers.code_intel": ("/api/code-intel", ["Code Intelligence"]),
    "routers.file_processing": ("/api/files", ["File Processing"]),
    "routers.git_enhanced": ("/api/git", ["Git Enhanced"]),
    "routers.browser": ("/api/browser", ["Browser Automation"]),
    "routers.code_quality": ("/api/code-quality", ["Code Quality"]),
    "routers.analysis": ("/api/analysis", ["Analysis"]),
}


# ───── 生命周期管理 ─────
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期: 启动时探测能力域并注册路由，关闭时清理资源"""
    logger.info("Python Service 启动中...")
    capabilities = discover_capabilities()

    # 动态注册可用能力域的路由
    browser_lifecycle = None
    for domain, info in capabilities.items():
        if info.is_available and info.router_module in ROUTER_PREFIX_MAP:
            try:
                module = importlib.import_module(info.router_module)
                prefix, tags = ROUTER_PREFIX_MAP[info.router_module]
                app.include_router(module.router, prefix=prefix, tags=tags)
                logger.info(f"路由已注册: {prefix} [{info.name}]")
                # 浏览器自动化能力域需要启动/关闭 Playwright
                if info.router_module == "routers.browser" and hasattr(module, "startup_browser"):
                    await module.startup_browser()
                    browser_lifecycle = module
            except Exception as e:
                logger.error(f"加载路由失败 [{info.name}]: {e}")

    yield

    # 关闭浏览器服务
    if browser_lifecycle and hasattr(browser_lifecycle, "shutdown_browser"):
        try:
            await browser_lifecycle.shutdown_browser()
        except Exception as e:
            logger.error(f"关闭浏览器服务失败: {e}")
    logger.info("Python Service 关闭")


# 始终加载的路由 (不依赖能力域)
from routers.token_estimator import router as token_router

app = FastAPI(
    title="AI Code Assistant - Python Service",
    version="1.15.0",
    lifespan=lifespan,
)

# 注册始终可用的 Token 估算路由
app.include_router(token_router, prefix="/api/v1/tokens", tags=["Token Estimation"])

# ───── CORS ─────
allowed_origins = os.getenv("CORS_ORIGINS", "http://localhost:5173,http://localhost:8080").split(",")
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
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
        "version": "1.15.0",
    }


@app.get("/api/health/capabilities", tags=["Health"])
async def get_capabilities():
    """返回所有能力域的可用状态 — Java 后端据此决定是否调用"""
    return {
        domain.name: {
            "name": info.name,
            "available": info.is_available,
            "reason": info.unavailable_reason if not info.is_available else None,
        }
        for domain, info in CAPABILITY_REGISTRY.items()
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
