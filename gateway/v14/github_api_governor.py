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
    def __init__(self, seconds):
        super().__init__('GitHub API capacity is temporarily unavailable')
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
    def lease(self, token, *, mutating=False, cancelled=lambda: False):
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
                if not record['active'] and (not mutating or now >= record['next_mutation']):
                    record['active'] = True
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

    def observe(self, token, status, headers, *, mutating=False):
        headers = {str(key).lower(): value for key, value in (headers or {}).items()}
        limited = status == 429 or status == 403 and (
            'retry-after' in headers or headers.get('x-ratelimit-remaining') == '0')
        if not limited:
            return
        cooldown = self._retry_after(headers, self.wall())
        if cooldown is None:
            cooldown = 60.0
        reset = headers.get('x-ratelimit-reset')
        if reset is not None:
            try:
                reset_epoch = float(reset)
                if reset_epoch >= 1_000_000_000_000:
                    reset_epoch /= 1000
                if 1_000_000_000 <= reset_epoch < 100_000_000_000:
                    cooldown = max(cooldown, reset_epoch - self.wall())
            except (TypeError, ValueError, OverflowError):
                pass
        with self.condition:
            record = self._record(token)
            record['cooldown_until'] = max(record['cooldown_until'], self.clock() + max(1.0, cooldown))
            self.condition.notify_all()


GLOBAL_GITHUB_GOVERNOR = GitHubApiGovernor()
