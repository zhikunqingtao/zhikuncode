"""
test_main.py — FastAPI 应用核心路由测试
使用 httpx AsyncClient + ASGITransport 测试 /api/health 和 /api/health/capabilities
"""

import sys
import os
import pytest
from httpx import AsyncClient, ASGITransport

# 将 src 目录加入 sys.path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from main import app


@pytest.mark.asyncio
async def test_health_returns_ok():
    """健康检查应返回 200 + status ok"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["service"] == "ai-code-assistant-python"
    assert "version" in data


@pytest.mark.asyncio
async def test_health_version_format():
    """版本号应为 x.y.z 格式"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/health")
    version = resp.json()["version"]
    parts = version.split(".")
    assert len(parts) == 3, f"版本号格式不正确: {version}"


@pytest.mark.asyncio
async def test_capabilities_endpoint():
    """能力查询端点应返回 200 + dict"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/health/capabilities")
    assert resp.status_code == 200
    data = resp.json()
    assert isinstance(data, dict)


@pytest.mark.asyncio
async def test_capabilities_contain_known_domains():
    """能力列表应包含已知能力域"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/health/capabilities")
    data = resp.json()
    # 至少包含 CODE_INTEL 和 FILE_PROCESSING
    assert "CODE_INTEL" in data
    assert "FILE_PROCESSING" in data


@pytest.mark.asyncio
async def test_capabilities_entry_structure():
    """每个能力域条目应包含 name, available, reason 字段"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/health/capabilities")
    data = resp.json()
    for domain_name, entry in data.items():
        assert "name" in entry, f"{domain_name} 缺少 name"
        assert "available" in entry, f"{domain_name} 缺少 available"
        assert "reason" in entry, f"{domain_name} 缺少 reason"
        assert isinstance(entry["available"], bool), f"{domain_name} available 应为 bool"


@pytest.mark.asyncio
async def test_nonexistent_route_returns_404():
    """访问不存在的路由应返回 404"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/nonexistent")
    assert resp.status_code == 404
