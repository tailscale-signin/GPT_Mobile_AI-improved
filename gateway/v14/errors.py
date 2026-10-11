"""Stable, sanitized failure envelopes for the v14 HTTP contract."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class GatewayFailure(Exception):
    code: str
    message: str
    status_code: int = 400
    retryable: bool = False
    details: dict | None = None

    def __post_init__(self):
        Exception.__init__(self, self.message)
        if self.code not in FAILURE_CODES:
            raise ValueError("Unknown gateway failure code")
        if not 400 <= self.status_code <= 599:
            raise ValueError("Gateway failure must use an error HTTP status")

    def payload(self, trace_id: str | None = None) -> dict:
        error = {"code": self.code, "message": self.message, "retryable": self.retryable}
        if self.details:
            # Details are deliberately a caller-sanitized allowlisted object.
            error["details"] = dict(self.details)
        result = {"error": error}
        if trace_id:
            result["traceId"] = trace_id
        return result


FAILURE_CODES = frozenset({
    "invalid_request", "contract_unsupported", "permission_denied", "quota_exceeded",
    "context_limit_exceeded", "completion_limit_exceeded", "gateway_busy",
    "upstream_unavailable", "protocol_error", "persistence_failed",
    "external_outcome_unknown", "deadline_exceeded", "cancellation_requested",
})


def invalid_request(message: str) -> GatewayFailure:
    # Keep messages concise; callers should never pass raw provider or tool output.
    return GatewayFailure("invalid_request", message, 400)
