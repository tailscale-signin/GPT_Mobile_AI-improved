"""Hosted issue reporting endpoint for GPT Mobile AI."""

import hashlib
import hmac
import json
import os
import sqlite3
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from .github_broker import https_origin
from .issue_reports import GitHubIssuePublisher, IssueReporter, ReportError


MAX_REPORT_BYTES = 8192


def create_app(public_url, reporter, rate_key):
    origin = https_origin(public_url)
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def require_origin(request, call_next):
        headers = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff"}
        if str(request.base_url).rstrip("/") != origin or request.headers.get("origin") not in (None, origin):
            return JSONResponse({"error": "HTTPS_ORIGIN_REQUIRED"}, status_code=403, headers=headers)
        response = await call_next(request)
        response.headers.update(headers)
        return response

    @app.exception_handler(ReportError)
    async def report_error(request, error):
        headers = {"Retry-After": str(error.retry_after)} if error.retry_after else {}
        return JSONResponse({"error": error.code}, status_code=error.status, headers=headers)

    @app.exception_handler(sqlite3.Error)
    async def storage_unavailable(request, error):
        return JSONResponse({"error": "REPORT_STORE_UNAVAILABLE"}, status_code=503, headers={"Retry-After": "60"})

    @app.get("/health/ready")
    async def ready():
        return {"service": "gpt-mobile-issue-reporter", "version": 1, "reportContextVersions": [1, 2, 3, 4, 5, 6],
                "reportDiagnosticFeatures": ["GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1"]}

    @app.post("/v1/issue-reports")
    async def submit(request: Request):
        if request.headers.get("content-type", "").partition(";")[0].strip().lower() != "application/json":
            raise ReportError(415, "JSON_REQUIRED")
        payload = bytearray()
        async for chunk in request.stream():
            if len(payload) + len(chunk) > MAX_REPORT_BYTES:
                raise ReportError(413, "REPORT_TOO_LARGE")
            payload.extend(chunk)
        try:
            values = json.loads(payload)
        except (ValueError, UnicodeError, RecursionError):
            raise ReportError(422, "INVALID_REPORT") from None
        address = request.client.host if request.client else "unknown"
        client = hmac.new(rate_key, address.encode(), hashlib.sha256).hexdigest()
        number = await run_in_threadpool(reporter.submit, values, client)
        return {"reportId": values["reportId"], "number": number}

    return app


def from_environment():
    publisher = GitHubIssuePublisher(
        os.environ["GITHUB_REPORT_APP_ID"],
        os.environ["GITHUB_REPORT_INSTALLATION_ID"],
        os.environ["GITHUB_REPORT_APP_SLUG"],
        Path(os.environ["GITHUB_REPORT_PRIVATE_KEY_FILE"]),
    )
    reporter = IssueReporter(Path(os.environ["GITHUB_REPORT_STATE_DB"]), publisher)
    rate_key = os.environ["GITHUB_REPORT_RATE_KEY"].encode()
    if len(rate_key) < 32:
        raise ValueError("Configure at least 32 bytes of secret rate-limit key material")
    return create_app(os.environ["ISSUE_REPORT_ORIGIN"], reporter, rate_key)
