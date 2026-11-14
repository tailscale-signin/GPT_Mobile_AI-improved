"""Conservative per-GitHub-credential API pacing shared by Gateway engineering jobs.

Serializes HTTP requests across concurrent read bundles and retains rate-limit
cooldowns without holding a worker. This is not a GitHub credential manager.
"""
import hashlib
import math
import threading
import time
from contextlib import contextmanager
from email.utils import parsedate_to_datetime
from types import SimpleNamespace

import requests


class GitHubApiRateWait(requests.HTTPError):
    def __init__(self, seconds, *, reason='RATE_LIMIT_COOLDOWN'):
        super().__init__('GitHub API capacity is temporarily unavailable')
        self.dispatched = False
        self.reason = reason
        self.response = SimpleNamespace(status_code=429, headers={
            'Retry-After': str(max(1, math.ceil(seconds)))})


class GitHubApiGovernor:
    def __init__(self, clock=time.monotonic, wall=time.time):
        self.clock, self.wall = clock, wall
        self.condition = threading.Condition(threading.RLock())
        self.accounts = {}

    @staticmethod
    def _identity(token):
        if not isinstance(token, str) or not token:
            raise ValueError('GitHub account credential is unavailable')
        return hashlib.sha256(token.encode('utf-8')).hexdigest()

    def _record(self, token):
        key = self._identity(token)
        return self.accounts.setdefault(key, {'active': False, 'cooldown_until': 0.0,
                                              'next_mutation': 0.0})

    @contextmanager
    def lease(self, token, *, mutating=False, cancelled=lambda: False, resource='core', reserve=0):
        key = self._identity(token)
        with self.condition:
            while True:
                if cancelled():
                    raise InterruptedError('GitHub request stopped before dispatch')
                record = self.accounts.setdefault(key, {'active': False, 'cooldown_until': 0.0, 'next_mutation': 0.0})
                now = self.clock()
                if record['cooldown_until'] > now:
                    # Hand the authoritative wait back to the durable job journal.
                    raise GitHubApiRateWait(record['cooldown_until'] - now)
                # Source indexing shares the credential with foreground work.
                # A reserve is local policy, never an assumed GitHub quota. Only
                # an observed core remaining/reset pair can defer a source read.
                if resource == 'core' and record.get('core_reset_at', 0) > now:
                    remaining = record.get('core_remaining')
                    if remaining is not None and remaining <= reserve:
                        reason = 'FOREGROUND_RESERVE' if reserve and remaining > 0 else 'OBSERVED_QUOTA_EXHAUSTED'
                        raise GitHubApiRateWait(record['core_reset_at'] - now, reason=reason)
                if not record['active'] and (not mutating or now >= record['next_mutation']):
                    record['active'] = True
                    if resource == 'core' and record.get('core_reset_at', 0) > now and record.get('core_remaining') is not None:
                        # Spend the admission atomically even if the next response
                        # lacks quota headers or its connection is interrupted.
                        record['core_remaining'] = max(0, record['core_remaining'] - 1)
                    break
                self.condition.wait(timeout=.1)
        try:
            yield
        finally:
            with self.condition:
                record = self.accounts[key]
                record['active'] = False
                if mutating:
                    record['next_mutation'] = max(record['next_mutation'], self.clock() + 1.0)
                self.condition.notify_all()

    @staticmethod
    def _retry_after(headers, wall_now):
        raw = headers.get('retry-after')
        if raw is not None:
            try:
                value = float(raw)
            except (TypeError, ValueError, OverflowError):
                try:
                    value = parsedate_to_datetime(str(raw)).timestamp() - wall_now
                except (TypeError, ValueError, OverflowError):
                    value = None
            if value is not None and math.isfinite(value):
                return max(0.0, value)
        return None

    @staticmethod
    def _headers(headers):
        # Response test doubles and adapter-specific responses may not expose a
        # mapping. Treat absent metadata as an ordinary response instead of
        # breaking the request path after a successful HTTP exchange.
        items = headers.items() if callable(getattr(headers, 'items', None)) else ()
        try:
            return {str(key).lower(): value for key, value in items}
        except TypeError:
            return {}

    @staticmethod
    def _reset(headers):
        try:
            reset = float(headers.get('x-ratelimit-reset'))
            if reset >= 1_000_000_000_000:
                reset /= 1000
            return reset if 1_000_000_000 <= reset < 100_000_000_000 else None
        except (TypeError, ValueError, OverflowError):
            return None

    @classmethod
    def retry_delay(cls, status, headers, wall_now):
        """Bounded metadata-only classification; a bare forbidden response is not throttling."""
        headers = cls._headers(headers)
        limited = status == 429 or status == 403 and (
            'retry-after' in headers or str(headers.get('x-ratelimit-remaining')) == '0')
        if not limited:
            return None
        cooldown = cls._retry_after(headers, wall_now)
        if cooldown is None:
            cooldown = 60.0
        reset = cls._reset(headers)
        if reset is not None and str(headers.get('x-ratelimit-remaining')) == '0':
            cooldown = max(cooldown, reset - wall_now)
        return max(1.0, cooldown)

    def observe(self, token, status, headers, *, mutating=False):
        headers = self._headers(headers)
        wall_now = self.wall()
        cooldown = self.retry_delay(status, headers, wall_now)
        with self.condition:
            record = self._record(token)
            now = self.clock()
            reset = self._reset(headers)
            remaining = str(headers.get('x-ratelimit-remaining', ''))
            if headers.get('x-ratelimit-resource') == 'core' and 1 <= len(remaining) <= 12 and remaining.isascii() and remaining.isdecimal() and reset is not None and reset > wall_now:
                record.update(core_remaining=int(remaining), core_reset_at=now + reset - wall_now)
            if cooldown is not None:
                record['cooldown_until'] = max(record['cooldown_until'], now + cooldown)
            self.condition.notify_all()


GLOBAL_GITHUB_GOVERNOR = GitHubApiGovernor()
