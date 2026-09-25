"""Bounded A/B proof: cancelled disconnect watcher can swallow cancel in real Request.
Run: python probe_watcher_cancel.py source|parent
The browser is replaced with an immediate async driver. A full-capacity request
fails before any driver await, deterministically exposing watcher cancel timing.
Normal successful requests are controls; they DO NOT hang in this probe.
The actual FastAPI/Starlette Request, route and production middleware are unchanged.
No network or browser process is opened. A second watcher cancellation drains probes.
"""
import asyncio,json,sys,time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock,Mock
from httpx import ASGITransport,AsyncClient
revision=sys.argv[1] if len(sys.argv)>1 else 'source'
sys.path.insert(0,str(Path(__file__).parent/revision/'python-service/src'))
from fastapi import FastAPI
from routers import browser,journey
from services.browser_service import BrowserService
import main

async def run(production, full_capacity):
    service=BrowserService()
    page=Mock(url='http://local/',screenshot=AsyncMock(return_value=b'jpeg'),add_init_script=AsyncMock())
    context=Mock(new_page=AsyncMock(return_value=page),add_init_script=AsyncMock(),close=AsyncMock())
    service._browser=SimpleNamespace(new_context=AsyncMock(return_value=context),close=AsyncMock())
    service._playwright=SimpleNamespace(stop=AsyncMock())
    browser.browser_service=service
    if full_capacity:
        service.max_sessions=1
        await service.get_or_create_session('existing')
    journey.JOURNEY_EXECUTION_TIMEOUT_SECONDS=.05
    app=main.app if production else FastAPI()
    app.include_router(journey.router,prefix='/api/browser')
    async with AsyncClient(transport=ASGITransport(app=app,raise_app_exceptions=False),base_url='http://test') as client:
        begin=time.perf_counter()
        request=asyncio.create_task(client.post('/api/browser/journey/run',json={'session_id':'normal','base_url':'http://local/','steps':[{'action':'screenshot'}]}))
        done,_=await asyncio.wait({request},timeout=.15)
        watchers=[task for task in asyncio.all_tasks() if task.get_coro().__qualname__=='_wait_for_disconnect']
        record={'revision':revision,'variant':'production_main' if production else 'bare_fastapi','completed_in_150ms':bool(done),'configured_work_budget_ms':50,'full_capacity':full_capacity,'page_screenshots':page.screenshot.await_count,'registered_sessions':list(service._sessions),'journey_lease_released':bool(service._sessions) and getattr(service._sessions.get('normal'),'owner_task',None) is None,'watchers':[{'done':t.done(),'cancelling':t.cancelling(),'line':t.get_stack()[-1].f_lineno if t.get_stack() else None} for t in watchers]}
        # is_disconnected's one external cancellation was swallowed. At 150ms
        # the poll is in its 250ms asyncio sleep, making a second cancel effective.
        for task in watchers:
            task.cancel()
        drained,_=await asyncio.wait({request},timeout=.3)
        record['drained_after_second_watcher_cancel']=bool(drained)
        if drained:
            result=request.result()
            record['http_status']=result.status_code
            record['body']=result.text
        else:
            request.cancel()
            for task in watchers:
                task.cancel()
        record['elapsed_seconds']=round(time.perf_counter()-begin,3)
        print(json.dumps(record,ensure_ascii=False),flush=True)
        assert drained,'probe failed to drain safely'
    await service.shutdown()

async def run_all():
    await run(False,False)
    await run(False,True)
    await run(True,False)
    await run(True,True)
asyncio.run(run_all())
