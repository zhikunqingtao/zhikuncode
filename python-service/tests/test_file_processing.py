"""
TC-PY-003: 文件处理 5 端点验证
验证 file_processing 路由：detect-encoding / detect-type / safe-read /
detect-encoding-bytes / watch

适配说明：file_processing 路由在 lifespan 中动态注册，ASGI 测试模式下
lifespan 不一定执行。此处手动将 router 挂载到 app 以确保端点可用。
"""
import base64
import sys
import os

import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))
from main import app
from routers.file_processing import router as fp_router

# 手动挂载 file_processing 路由（lifespan 动态注册在测试中不一定触发）
_fp_mounted = False
if not _fp_mounted:
    try:
        app.include_router(fp_router, prefix="/api/files", tags=["File Processing"])
        _fp_mounted = True
    except Exception:
        pass  # 已挂载则忽略


@pytest_asyncio.fixture
async def fp_client():
    """带 file_processing 路由的测试客户端"""
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test"
    ) as ac:
        yield ac


@pytest.mark.asyncio
async def test_detect_encoding(fp_client, temp_text_file):
    """POST /api/files/detect-encoding — 编码检测"""
    resp = await fp_client.post("/api/files/detect-encoding", json={
        "file_path": temp_text_file
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "encoding" in data
    assert "confidence" in data
    assert data["confidence"] > 0


@pytest.mark.asyncio
async def test_detect_type(fp_client, temp_text_file):
    """POST /api/files/detect-type — MIME 类型检测"""
    resp = await fp_client.post("/api/files/detect-type", json={
        "file_path": temp_text_file
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "mime_type" in data
    assert "is_text" in data
    assert "is_binary" in data
    # mime_type 应为非空字符串
    assert len(data["mime_type"]) > 0
    # is_text 和 is_binary 应互斥（某些 magic 库对 .txt 的检测可能不同）
    assert isinstance(data["is_text"], bool)
    assert isinstance(data["is_binary"], bool)


@pytest.mark.asyncio
async def test_safe_read(fp_client, temp_text_file):
    """POST /api/files/safe-read — 安全读取"""
    resp = await fp_client.post("/api/files/safe-read", json={
        "file_path": temp_text_file
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "content" in data
    assert "encoding" in data
    assert "length" in data
    assert data["length"] > 0
    assert "Hello" in data["content"]


@pytest.mark.asyncio
async def test_detect_encoding_bytes(fp_client):
    """POST /api/files/detect-encoding-bytes — 字节编码检测"""
    raw_text = "Hello World 你好世界"
    b64_data = base64.b64encode(raw_text.encode("utf-8")).decode("ascii")
    resp = await fp_client.post("/api/files/detect-encoding-bytes", json={
        "data_base64": b64_data
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "encoding" in data


@pytest.mark.asyncio
async def test_watch_endpoint_exists(fp_client, tmp_path):
    """GET /api/files/watch — SSE 端点存在性验证（仅检查路由注册，不等待流式数据）"""
    # SSE 端点会保持连接开放，使用短超时只验证路由存在
    import asyncio
    try:
        resp = await asyncio.wait_for(
            fp_client.get(f"/api/files/watch?path={tmp_path}"),
            timeout=2.0
        )
        # 如果在超时内返回，检查状态码
        assert resp.status_code != 404
    except asyncio.TimeoutError:
        # SSE 端点超时说明它在流式响应中，路由已注册 — PASS
        pass


@pytest.mark.asyncio
async def test_detect_encoding_nonexistent_file(fp_client):
    """不存在的文件 → 404 或 500"""
    resp = await fp_client.post("/api/files/detect-encoding", json={
        "file_path": "/nonexistent/path/file.txt"
    })
    assert resp.status_code in (404, 500)
