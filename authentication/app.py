"""ASGI adapter. Run behind the project's HTTPS reverse proxy; never on public HTTP."""

import hashlib
import hmac
import json
import os
import threading
import time
from pathlib import Path

from .github_broker import AuthorizationError, Broker, Config, github_exchange, https_origin


def create_app(config: Config, exchange=github_exchange):
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
    from starlette.concurrency import run_in_threadpool

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    broker = Broker(config, exchange)
    origin = https_origin(config.public_url)
    callback_origin = https_origin(config.callback_url)
    limit_lock = threading.Lock()
    attempts = {}

    @app.middleware("http")
    async def admission(request: Request, call_next):
        headers = {"Cache-Control": "no-store", "Pragma": "no-cache", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff"}
        if str(request.base_url).rstrip("/") != origin or request.headers.get("origin") not in (None, origin, callback_origin):
            return JSONResponse({"error": "HTTPS_ORIGIN_REQUIRED"}, status_code=403, headers=headers)
        # Never trust arbitrary X-Forwarded-For: configure the ASGI server to trust
        # only the local reverse proxy. Raw addresses are not retained or exported.
        address = request.client.host if request.client else "unknown"
        key = hmac.new(config.client_secret.encode(), address.encode(), hashlib.sha256).hexdigest()
        now = time.monotonic()
        with limit_lock:
            for expired in [k for k, v in attempts.items() if v[0] + 600 <= now]:
                del attempts[expired]
            since, count = attempts.get(key, (now, 0))
            limited = count >= 60 or key not in attempts and len(attempts) >= 10000
            if not limited:
                attempts[key] = (since, count + 1)
        if limited:
            return JSONResponse({"error": "RATE_LIMITED"}, status_code=429, headers={**headers, "Retry-After": "600"})
        response = await call_next(request)
        response.headers.update(headers)
        return response

    async def arguments(request):
        if request.headers.get("content-type", "").split(";")[0] != "application/json":
            raise AuthorizationError("Use a JSON request body")
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 8192:
                raise AuthorizationError("Authorization request exceeds its size limit")
        try:
            result = json.loads(body)
            if not isinstance(result, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in result.items()):
                raise ValueError()
            return result
        except (ValueError, UnicodeError):
            raise AuthorizationError("Invalid authorization request") from None
        finally:
            body[:] = b"\0" * len(body)

    @app.exception_handler(AuthorizationError)
    async def denied(request, error):
        return JSONResponse({"error": "AUTHORIZATION_FAILED", "message": str(error)}, status_code=400)

    @app.get("/health/ready")
    async def ready():
        return {"service": "github-public-auth", "version": 1, "clientId": config.client_id, "pkce": "S256", "gatewayRequired": False}

    @app.post("/v1/github/oauth/start")
    async def start(request: Request):
        values = await arguments(request)
        if set(values) != {"codeChallenge"}:
            raise AuthorizationError("Only a codeChallenge is accepted")
        return await run_in_threadpool(broker.begin, values["codeChallenge"])

    @app.post("/v1/github/oauth/exchange")
    async def complete(request: Request):
        values = await arguments(request)
        if set(values) != {"state", "codeVerifier", "code"}:
            raise AuthorizationError("State, codeVerifier and code are required")
        return await run_in_threadpool(broker.complete, values["state"], values["codeVerifier"], values["code"])

    @app.post("/v1/github/oauth/refresh")
    async def refresh(request: Request):
        values = await arguments(request)
        if set(values) != {"operationId", "refreshToken"}:
            raise AuthorizationError("A refreshToken and unique operationId are required")
        return await run_in_threadpool(broker.refresh, values["operationId"], values["refreshToken"])

    return app


def from_environment():
    return create_app(Config(os.environ["GITHUB_APP_CLIENT_ID"], os.environ["GITHUB_APP_CLIENT_SECRET"],
                             os.environ["GITHUB_APP_CALLBACK_URL"], os.environ["GITHUB_AUTH_PUBLIC_URL"],
                             Path(os.environ["GITHUB_AUTH_STATE_DB"])))
