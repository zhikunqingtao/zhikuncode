"""Deterministic ASGI disconnect experiment: no network, browser, or repository writes."""
import asyncio
import json
import sys
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

SRC = Path(__file__).parent / 'source/python-service/src'
sys.path.insert(0, str(SRC))
from fastapi import FastAPI
from routers import browser, journey
import main

async def run(with_real_middleware):
    started = asyncio.Event()
    close_times = []
    calls = []
    async def screenshot(**kwargs):
        started.set()
        await asyncio.Event().wait()
    async def close(*args, **kwargs):
        close_times.append(time.perf_counter())
        return True
    session = SimpleNamespace(page=SimpleNamespace(url='http://local/', screenshot=screenshot), _js_errors=[], _record_opts={})
    service = SimpleNamespace(default_timeout=30000, _create_context_for_journey=AsyncMock(return_value=session), close_session=AsyncMock(side_effect=close), release_session=AsyncMock())
    browser.browser_service = service
    journey.JOURNEY_EXECUTION_TIMEOUT_SECONDS = 0.4
    journey.DISCONNECT_POLL_SECONDS = 0.01
    app = main.app if with_real_middleware else FastAPI()
    app.include_router(journey.router, prefix='/api/browser')
    payload = json.dumps({'session_id':'rv-disconnect', 'base_url':'http://local/', 'steps':[{'action':'screenshot'}]}).encode()
    first = True
    async def receive():
        nonlocal first
        if first:
            first = False
            calls.append('request')
            return {'type':'http.request','body':payload,'more_body':False}
        if not started.is_set():
            await started.wait()
        calls.append('disconnect')
        return {'type':'http.disconnect'}
    messages = []
    async def send(message):
        messages.append(message)
    scope = {'type':'http', 'asgi':{'version':'3.0','spec_version':'2.4'}, 'http_version':'1.1', 'scheme':'http', 'method':'POST', 'path':'/api/browser/journey/run', 'raw_path':b'/api/browser/journey/run', 'query_string':b'', 'root_path':'', 'headers':[(b'content-type',b'application/json')], 'server':('localhost',80), 'client':('client',1234)}
    began = time.perf_counter()
    await asyncio.wait_for(app(scope,receive,send),2)
    elapsed = time.perf_counter()-began
    result = {'variant':'production_main_middleware' if with_real_middleware else 'bare_fastapi', 'elapsed_seconds':round(elapsed,3), 'closed_after_seconds':round(close_times[0]-began,3) if close_times else None, 'status':[m.get('status') for m in messages if m['type']=='http.response.start'], 'body':b''.join(m.get('body',b'') for m in messages).decode(), 'receive_disconnect_calls':calls.count('disconnect'), 'close_calls':service.close_session.await_count}
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result

async def main_probe():
    bare = await run(False)
    wrapped = await run(True)
    assert bare['status'] == [499] and bare['elapsed_seconds'] < 0.2
    assert wrapped['status'] == [504] and wrapped['elapsed_seconds'] >= 0.35
    print('REPRODUCED: production middleware loses http.disconnect observation until deadline.',flush=True)

asyncio.run(main_probe())
