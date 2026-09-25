"""Focused independent Browser/Journey contract probes; no real browser/network."""
import asyncio
from datetime import datetime,timedelta
import json
from pathlib import Path
import sys
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock,Mock,patch
from httpx import ASGITransport,AsyncClient

root = Path(__file__).parent
revision = sys.argv[1] if len(sys.argv)>1 else 'source'
sys.path.insert(0,str(root/revision/'python-service/src'))
from services.browser_service import BrowserService
from services.journey_models import JourneyRunRequest
from routers import browser,journey
from main import app
app.include_router(browser.router,prefix='/api/browser')
app.include_router(journey.router,prefix='/api/browser')

def build_service():
    service=BrowserService()
    service.cleanup_timeout=.02
    contexts=[]
    async def allocate(**kwargs):
        page=Mock()
        page.url='http://local/'
        page.add_init_script=AsyncMock()
        page.screenshot=AsyncMock(return_value=b'jpeg')
        context=Mock(new_page=AsyncMock(return_value=page),add_init_script=AsyncMock(),close=AsyncMock())
        contexts.append(context)
        return context
    service._browser=SimpleNamespace(new_context=AsyncMock(side_effect=allocate),close=AsyncMock())
    service._playwright=SimpleNamespace(stop=AsyncMock())
    return service,contexts

def emit(name,**details):
    print(json.dumps({'revision':revision,'probe':name,**details},ensure_ascii=False),flush=True)

def payload(sid):
    return {'session_id':sid,'base_url':'http://local/','steps':[{'action':'screenshot'}]}

async def capacity():
    service,contexts=build_service()
    service.max_sessions=1
    browser.browser_service=service
    async with AsyncClient(transport=ASGITransport(app=app,raise_app_exceptions=False),base_url='http://test') as client:
        first=await client.post('/api/browser/journey/run',json=payload('first'))
        second=await client.post('/api/browser/journey/run',json=payload('second'))
        emit('journey_capacity_http',first_status=first.status_code,second_status=second.status_code,second_body=second.text,allocations=len(contexts),registered=list(service._sessions))
    await service.shutdown()
    ordinary,contexts=build_service()
    ordinary.max_sessions=1
    first=await ordinary.get_or_create_session('ordinary-first')
    try:
        await ordinary.get_or_create_session('ordinary-second')
        outcome='created'
    except Exception as e:
        outcome=type(e).__name__+': '+str(e)
    emit('ordinary_capacity',outcome=outcome,allocations=len(contexts),first_close_calls=first.context.close.await_count,registered=list(ordinary._sessions))
    await ordinary.shutdown()

async def close_failure_recovery():
    service,contexts=build_service()
    service.max_sessions=1
    failed=await service.get_or_create_session('failed')
    failed.context.close.side_effect=RuntimeError('synthetic close failure')
    close_results=[]
    for i in range(2):
        try:
            close_results.append(await service.close_session('failed'))
        except Exception as e:
            close_results.append(type(e).__name__+': '+str(e))
    failed.last_activity=datetime.now()-timedelta(days=1)
    if hasattr(service,'_cleanup_expired_sessions'):
        await service._cleanup_expired_sessions()
    before={'close_results':close_results,'close_calls_after_ttl':failed.context.close.await_count,'registered':list(service._sessions),'cached_tasks':len(getattr(service,'_resource_close_tasks',{}))}
    old_browser,old_driver=service._browser,service._playwright
    await service.shutdown()
    after={'registered':list(service._sessions),'browser_is_none':service._browser is None,'driver_is_none':service._playwright is None,'cached_tasks':len(getattr(service,'_resource_close_tasks',{}))}
    replacement,_=build_service()
    fresh_driver=SimpleNamespace(stop=AsyncMock(),chromium=SimpleNamespace(launch=AsyncMock(return_value=replacement._browser)))
    with patch('services.browser_service.async_playwright',return_value=SimpleNamespace(start=AsyncMock(return_value=fresh_driver),__aexit__=AsyncMock())):
        await service.startup()
        new=await service.get_or_create_session('failed')
        recovered=new is not failed
        await service.shutdown()
    emit('failed_close_ttl_recovery',before=before,after=after,same_id_recovered_after_service_shutdown_startup=recovered,old_browser_close_calls=old_browser.close.await_count,old_driver_stop_calls=old_driver.stop.await_count)

async def external_close_during_create():
    service,contexts=build_service()
    browser.browser_service=service
    entered=asyncio.Event()
    original=service._browser.new_context.side_effect
    async def allocate(**kwargs):
        context=await original(**kwargs)
        async def stalled():
            entered.set()
            await asyncio.Event().wait()
        context.new_page.side_effect=stalled
        return context
    service._browser.new_context.side_effect=allocate
    async with AsyncClient(transport=ASGITransport(app=app,raise_app_exceptions=False),base_url='http://test') as client:
        request=asyncio.create_task(client.post('/api/browser/journey/run',json=payload('cancelled-create')))
        await asyncio.wait_for(entered.wait(),1)
        close=await client.post('/api/browser/close_session',json={'session_id':'cancelled-create'})
        try:
            result=await asyncio.wait_for(request,.5)
            request_result={'status':result.status_code,'body':result.text}
        except BaseException as e:
            request_result={'exception':type(e).__name__}
        emit('external_close_during_create',close_status=close.status_code,close_body=close.text,journey=request_result,registered=list(service._sessions),creating=list(getattr(service,'_creating',{})),context_close_calls=contexts[0].close.await_count)
    await service.shutdown()

async def watcher_exception():
    service,contexts=build_service()
    browser.browser_service=service
    req=JourneyRunRequest(**payload('watcher-exception'))
    watcher=SimpleNamespace(is_disconnected=AsyncMock(side_effect=RuntimeError('receive pipeline failed')))
    try:
        await journey.journey_run(req,watcher)
    except BaseException as e:
        emit('watcher_exception',exception=type(e).__name__,status=getattr(e,'status_code',None),detail=getattr(e,'detail',str(e)),registered=list(service._sessions),creating=list(getattr(service,'_creating',{})))
    await service.shutdown()

async def passive_disconnect(request):
    await asyncio.Event().wait()

async def run():
    if len(sys.argv)>2 and sys.argv[2]=='external_actual':
        await external_close_during_create()
        return
    # Isolate error mapping from the independently proved watcher-cancellation
    # hang. All actual routes/main middleware and BrowserService stay unchanged.
    original_watcher=getattr(journey,"_wait_for_disconnect",None)
    journey._wait_for_disconnect=passive_disconnect
    await capacity()
    await close_failure_recovery()
    await external_close_during_create()
    if revision=='source':
        journey._wait_for_disconnect=original_watcher
        await watcher_exception()

asyncio.run(run())
