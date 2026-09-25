from fastapi import FastAPI, Request
from typing import Optional
for optional in [False, True]:
    app = FastAPI()
    try:
        if optional:
            @app.get('/')
            async def endpoint(http_request: Optional[Request] = None): return {'ok': True}
        else:
            @app.get('/')
            async def endpoint(http_request: Request = None): return {'ok': True}
        print(f'Optional={optional}: route registration OK')
    except Exception as error:
        print(f'Optional={optional}: {type(error).__name__}: {error}')
