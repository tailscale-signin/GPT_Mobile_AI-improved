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
    async def ready(capabilities: str = ""):
        response = {"service": "gpt-mobile-issue-reporter", "version": 2, "reportContextVersions": [1, 2, 3, 4, 5, 6, 7],
                "reportDiagnosticFeatures": ["GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1", "INCIDENT_FAILURE_DETAILS_V1", "DISPATCH_POLICY_DIAGNOSTICS_V1", "CHANGED_POLICY_FIELDS_V1", "EXECUTION_OUTCOME_DIAGNOSTICS_V1", "RUN_FAILURE_EVIDENCE_V1", "RECOVERY_OBSERVATIONS_V1", "ISSUE_RECOVERY_DETAILS_V1", "ADMISSION_DIAGNOSTICS_V1", "SESSION_RECOVERY_DIAGNOSTICS_V1", "VOICE_PLAYBACK_PHASE_V1", "CAPACITY_ADMISSION_EVIDENCE_V1", "CONVERSATION_CONFLICT_EVIDENCE_V1", "ACCEPTANCE_SCOPE_EVIDENCE_V1", "VOICE_INSTALL_EVIDENCE_V1", "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1", "VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1", "COMPLETION_EVIDENCE_V1", "COMPOSER_IME_EVIDENCE_V1", "GITHUB_PREREQUISITE_EVIDENCE_V1", "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1", "ISSUE_RECOVERY_CODES_V1", "CAPTURE_BOUNDARY_EVIDENCE_V1", "HANDOFF_RECONCILIATION_EVIDENCE_V1", "CHAT_DELIVERY_EVIDENCE_V1", "CLAIM_FRESHNESS_EVIDENCE_V1", "PROFILE_SELECTION_EVIDENCE_V1", "GH_COOLDOWN_V1"]}

        # Existing clients bound the plain readiness response to 1 KiB. Only
        # clients negotiating the bounded extended view receive newer features.
        if capabilities == "extended":
            response["reportDiagnosticFeatures"].extend([
                "CLAIM_EXTRACTION_SCHEMA_HASH_V1",
                "ACCEPTANCE_PUBLICATION_EVIDENCE_V1", "TOOL_REPEAT_ADMISSION_EVIDENCE_V1",
                "TOOL_EVENT_PERSISTENCE_EVIDENCE_V1", "ACCEPTANCE_OBSERVATION_CAUSES_V1",
                "SQLITE_CONSTRAINT_EVIDENCE_V1", "URL_READER_AVAILABILITY_V1", "GITHUB_POLICY_RESPONSE_EVIDENCE_V1",
                "TOOL_REPEAT_HISTORY_EVIDENCE_V1",
                "GITHUB_CI_SEMANTIC_EVIDENCE_V1",
                "TOOL_INVENTORY_PAGINATION_EVIDENCE_V1",
                "GITHUB_SEARCH_VALIDATION_EVIDENCE_V1",
                "REPORT_TEXT_ATTACHMENT_V1",
                "GITHUB_PR_CONTEXT_RACE_EVIDENCE_V1",
                "TOOL_PROTOCOL_FRAME_DIAGNOSTICS_V1",
                "TERMINAL_DELIVERY_CODES_V1", "PROVIDER_TERMINAL_CAUSES_V1",
                "PAGINATION_AND_PROVIDER_LIMIT_EVIDENCE_V1",
                "PRIVACY_ELIGIBILITY_EVIDENCE_V1",
                "GATEWAY_CAPABILITY_EVIDENCE_V1", "STATUS_COVERAGE_EVIDENCE_V1", "AUDIO_FOCUS_MODE_V1",
            ])
        return response

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
