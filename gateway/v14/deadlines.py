"""A single monotonic deadline shared by every stage in one admitted run."""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable


class DeadlineExceeded(TimeoutError):
    pass


@dataclass(frozen=True)
class Deadline:
    expires_at: float
    clock: Callable[[], float] = time.monotonic

    @classmethod
    def after(cls, seconds: float, *, clock: Callable[[], float] = time.monotonic,
              minimum: float = 0.1, maximum: float = 900.0) -> "Deadline":
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
            raise ValueError("Deadline must be a number of seconds")
        if not minimum <= seconds <= maximum:
            raise ValueError("Deadline is outside the server-admitted range")
        return cls(clock() + float(seconds), clock)

    def remaining(self) -> float:
        return max(0.0, self.expires_at - self.clock())

    def expired(self) -> bool:
        return self.remaining() <= 0

    def require_time(self) -> float:
        remaining = self.remaining()
        if remaining <= 0:
            raise DeadlineExceeded("Gateway run deadline expired")
        return remaining


def request_deadline(payload: object, *, default_seconds: float = 900.0,
                     maximum_seconds: float = 900.0) -> Deadline:
    """Read the supported run-control subset; never forward or silently ignore it."""
    gateway = payload.get("gateway") if isinstance(payload, dict) else None
    if gateway is not None and not isinstance(gateway, dict):
        raise ValueError("gateway control must be an object")
    if isinstance(gateway, dict):
        if set(gateway) - {"schema", "budget"}:
            raise ValueError("gateway control contains unsupported fields")
        schema = gateway.get("schema")
        if schema is not None and schema != "gptmobile.gateway-run.v1":
            raise ValueError("gateway run schema is unsupported")
    budget = gateway.get("budget", {}) if isinstance(gateway, dict) else {}
    if not isinstance(budget, dict) or set(budget) - {"totalDeadlineMs"}:
        raise ValueError("gateway budget contains unsupported fields")
    value = budget.get("totalDeadlineMs", int(default_seconds * 1000)) if isinstance(budget, dict) else int(default_seconds * 1000)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError("gateway.budget.totalDeadlineMs must be an integer")
    seconds = value / 1000
    return Deadline.after(seconds, maximum=maximum_seconds)
