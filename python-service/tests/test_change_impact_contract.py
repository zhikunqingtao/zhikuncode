import os
import sys

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from routers import analysis as analysis_module  # noqa: E402


app = FastAPI()
app.include_router(analysis_module.router, prefix="/api/analysis")


@pytest.mark.asyncio
async def test_change_impact_validation_uses_structured_non_retryable_envelope(tmp_path):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/api/analysis/change-impact", json={
            "file_path": str(tmp_path.parent / "outside.py"),
            "changed_lines": [1],
            "project_root": str(tmp_path),
            "depth": 3,
        })

    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "FILE_OUTSIDE_PROJECT"
    assert body["error"]["retryable"] is False
    assert isinstance(body["elapsed_ms"], float)


def test_change_impact_openapi_has_typed_response():
    schema = app.openapi()["paths"]["/api/analysis/change-impact"]["post"]
    success_schema = schema["responses"]["200"]["content"]["application/json"]["schema"]
    assert success_schema["$ref"].endswith("/ChangeImpactResponse")
