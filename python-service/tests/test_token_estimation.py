"""
TC-PY-001: Token 估算 API 验证
验证 token_estimator 路由的 2 个端点：estimate（批量）和 estimate-single（单条）
"""
import time
import pytest


@pytest.mark.asyncio
async def test_estimate_batch(client):
    """批量 Token 估算端点：POST /api/v1/tokens/estimate"""
    start = time.monotonic()
    resp = await client.post("/api/v1/tokens/estimate", json={
        "texts": ["hello world", "你好世界"],
        "model": "cl100k_base"
    })
    elapsed_ms = (time.monotonic() - start) * 1000

    assert resp.status_code == 200
    data = resp.json()
    # 必需字段
    assert "counts" in data
    assert "total" in data
    assert "method" in data
    # counts 长度 = 输入文本数
    assert len(data["counts"]) == 2
    # 每个 count > 0
    assert all(c > 0 for c in data["counts"])
    # total = sum(counts)
    assert data["total"] == sum(data["counts"])
    # method 为 tiktoken 或 heuristic
    assert data["method"] in ("tiktoken", "heuristic")
    # 响应时间 < 100ms
    assert elapsed_ms < 500, f"响应耗时 {elapsed_ms:.1f}ms 超过 500ms"


@pytest.mark.asyncio
async def test_estimate_single(client):
    """单条 Token 估算端点：POST /api/v1/tokens/estimate-single"""
    start = time.monotonic()
    resp = await client.post("/api/v1/tokens/estimate-single", json={
        "text": "测试文本 Hello World",
        "model": "cl100k_base"
    })
    elapsed_ms = (time.monotonic() - start) * 1000

    assert resp.status_code == 200
    data = resp.json()
    assert "count" in data
    assert "method" in data
    assert data["count"] > 0
    assert data["method"] in ("tiktoken", "heuristic")
    assert elapsed_ms < 500, f"响应耗时 {elapsed_ms:.1f}ms 超过 500ms"


@pytest.mark.asyncio
async def test_estimate_chinese_token_count_reasonable(client):
    """中文 token 计数合理性：中文每字约 1-3 token"""
    resp = await client.post("/api/v1/tokens/estimate-single", json={
        "text": "你好世界",
        "model": "cl100k_base"
    })
    data = resp.json()
    # 4 个汉字，token 数应在 1-12 范围内
    assert 1 <= data["count"] <= 12, f"中文 token 计数 {data['count']} 不合理"


@pytest.mark.asyncio
async def test_estimate_empty_texts(client):
    """空文本列表应返回空结果"""
    resp = await client.post("/api/v1/tokens/estimate", json={
        "texts": [],
        "model": "cl100k_base"
    })
    assert resp.status_code == 200
    data = resp.json()
    assert data["counts"] == []
    assert data["total"] == 0
