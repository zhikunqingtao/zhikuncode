"""Real uvicorn loopback HTTP, mocked browser only. No application lifespan/browser start."""
import asyncio,json,socket,sys,time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock,Mock
import httpx,uvicorn
revision=sys.argv[1] if len(sys.argv)>1 else 'source'
sys.path.insert(0,str(Path(__file__).parent/revision/'python-service/src'))
from routers import browser,journey
from services.browser_service import BrowserService
from main import app
app.include_router(journey.router,prefix='/api/browser')

async def run():
    service=BrowserService()
    page=Mock(url='http://local/',screenshot=AsyncMock(return_value=b'jpeg'),add_init_script=AsyncMock())
    context=Mock(new_page=AsyncMock(return_value=page),add_init_script=AsyncMock(),close=AsyncMock())
    service._browser=SimpleNamespace(new_context=AsyncMock(return_value=context),close=AsyncMock())
    service._playwright=SimpleNamespace(stop=AsyncMock())
    service.max_sessions=1
    browser.browser_service=service
    await service.get_or_create_session('occupied')
    journey.JOURNEY_EXECUTION_TIMEOUT_SECONDS=.05
    sock=socket.socket()
    sock.bind(('127.0.0.1',0))
    sock.listen(10)
    server=uvicorn.Server(uvicorn.Config(app,lifespan='off',log_level='critical',access_log=False))
    serving=asyncio.create_task(server.serve(sockets=[sock]))
    while not server.started:
        await asyncio.sleep(.001)
    record={'revision':revision,'transport':'real_loopback_uvicorn','work_budget_ms':50}
    try:
        async with httpx.AsyncClient(timeout=1) as client:
            request=asyncio.create_task(client.post(f'http://127.0.0.1:{sock.getsockname()[1]}/api/browser/journey/run',json={'session_id':'new','base_url':'http://local/','steps':[{'action':'screenshot'}]}))
            done,_=await asyncio.wait({request},timeout=.15)
            record['completed_in_150ms']=bool(done)
            watchers=[task for task in asyncio.all_tasks() if task.get_coro().__qualname__=='_wait_for_disconnect']
            record['watchers']=[{'cancelling':t.cancelling(),'line':t.get_stack()[-1].f_lineno} for t in watchers]
            for t in watchers:
                t.cancel()
            done,_=await asyncio.wait({request},timeout=.3)
            record['drained']=bool(done)
            if done:
                response=request.result()
                record['status']=response.status_code
                record['body']=response.text
            assert done
        print(json.dumps(record,ensure_ascii=False),flush=True)
    finally:
        server.should_exit=True
        await serving
        await service.shutdown()
        sock.close()
asyncio.run(run())
