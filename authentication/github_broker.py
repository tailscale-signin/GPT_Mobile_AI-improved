"""Confidential GitHub code exchange with durable, single-use PKCE transactions.

The phone generates and retains its verifier. This service stores only its challenge
and a hash of state; it never journals authorization codes or GitHub credentials.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


class AuthorizationError(Exception):
    """Safe error: never include upstream bodies, request URLs or credentials."""


def https_origin(value: str) -> str:
    uri = urllib.parse.urlsplit(value)
    if (uri.scheme != "https" or not uri.hostname or uri.username or uri.password
            or uri.port not in (None, 443) or uri.fragment or uri.query):
        raise ValueError("A fixed HTTPS URL without credentials, query or fragment is required")
    return f"https://{uri.hostname}"


@dataclass(frozen=True)
class Config:
    client_id: str
    client_secret: str = field(repr=False)
    callback_url: str
    public_url: str
    database: Path

    def __post_init__(self):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", self.client_id):
            raise ValueError("A registered project GitHub App client ID is required")
        if not self.client_secret or len(self.client_secret) > 4096:
            raise ValueError("The broker's confidential client secret is required")
        https_origin(self.callback_url)
        https_origin(self.public_url)


def challenge_for(verifier: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._~-]{43,128}", verifier):
        raise AuthorizationError("Invalid PKCE verifier")
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).decode().rstrip("=")


class Transactions:
    TTL_SECONDS = 600
    MAX_PENDING = 10_000

    def __init__(self, path: Path, clock: Callable[[], float] = time.time):
        self.path, self.clock = path, clock
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS oauth_transactions (state_hash TEXT PRIMARY KEY, challenge TEXT NOT NULL, expires INTEGER NOT NULL, consumed INTEGER NOT NULL DEFAULT 0)")
            db.execute("CREATE TABLE IF NOT EXISTS refresh_attempts (operation_id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, expires INTEGER NOT NULL)")
        path.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        try:
            db.execute("PRAGMA busy_timeout=5000")
            # sqlite3.Connection.__exit__ commits/rolls back but does not close.
            # Close explicitly even when a transaction or admission check raises.
            with db:
                yield db
        finally:
            db.close()

    def begin(self, challenge: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", challenge):
            raise AuthorizationError("S256 PKCE is required")
        state = secrets.token_urlsafe(32)
        now = int(self.clock())
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM oauth_transactions WHERE expires <= ?", (now,))
            if db.execute("SELECT COUNT(*) FROM oauth_transactions").fetchone()[0] >= self.MAX_PENDING:
                raise AuthorizationError("Authorization capacity reached; try again later")
            db.execute("INSERT INTO oauth_transactions VALUES (?, ?, ?, 0)", (self.digest(state), challenge, now + self.TTL_SECONDS))
        return state

    @staticmethod
    def digest(state: str) -> str:
        return hashlib.sha256(state.encode()).hexdigest()

    def consume(self, state: str, verifier: str):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", state):
            raise AuthorizationError("Authorization state is invalid or expired")
        challenge = challenge_for(verifier)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT challenge, expires, consumed FROM oauth_transactions WHERE state_hash = ?", (self.digest(state),)).fetchone()
            if row is None or row[1] <= self.clock() or row[2] or not hmac.compare_digest(row[0], challenge):
                raise AuthorizationError("Authorization state is invalid, expired, consumed or does not match PKCE")
            db.execute("UPDATE oauth_transactions SET consumed = 1 WHERE state_hash = ?", (self.digest(state),))

    def claim_refresh(self, operation_id: str, refresh_token: str):
        if not re.fullmatch(r"[a-f0-9-]{36}", operation_id):
            raise AuthorizationError("A unique refresh operation identifier is required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM refresh_attempts WHERE expires <= ?", (int(self.clock()),))
            if db.execute("SELECT COUNT(*) FROM refresh_attempts").fetchone()[0] >= self.MAX_PENDING:
                raise AuthorizationError("Authorization capacity reached; try again later")
            try:
                db.execute("INSERT INTO refresh_attempts VALUES (?, ?, ?)", (operation_id, self.digest(refresh_token), int(self.clock()) + self.TTL_SECONDS))
            except sqlite3.IntegrityError:
                raise AuthorizationError("Refresh already attempted; reconnect rather than replay an uncertain rotation") from None


class Broker:
    def __init__(self, config: Config, exchange: Callable[[dict], dict], clock=time.time):
        self.config = config
        self.transactions = Transactions(config.database, clock)
        self.exchange = exchange

    def begin(self, challenge: str) -> dict:
        state = self.transactions.begin(challenge)
        query = urllib.parse.urlencode({
            "client_id": self.config.client_id, "redirect_uri": self.config.callback_url,
            "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
            "scope": "offline_access",
        })
        return {"state": state, "authorizationUrl": "https://github.com/login/oauth/authorize?" + query,
                "redirectUri": self.config.callback_url, "expiresIn": Transactions.TTL_SECONDS}

    def complete(self, state: str, verifier: str, code: str) -> dict:
        if not isinstance(code, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,1024}", code):
            raise AuthorizationError("Invalid authorization code")
        self.transactions.consume(state, verifier)
        # Consumption precedes network I/O. A lost response must start a new sign-in;
        # replaying an uncertain authorization-code exchange is never automatic.
        try:
            result = self.exchange({"client_id": self.config.client_id, "client_secret": self.config.client_secret,
                                    "code": code, "code_verifier": verifier, "redirect_uri": self.config.callback_url})
        except Exception:
            raise AuthorizationError("Code exchange could not be verified; start sign-in again") from None
        return self.credential(result)

    def refresh(self, operation_id: str, refresh_token: str) -> dict:
        if not self.valid_token(refresh_token):
            raise AuthorizationError("Invalid refresh credential")
        self.transactions.claim_refresh(operation_id, refresh_token)
        try:
            result = self.exchange({"client_id": self.config.client_id, "client_secret": self.config.client_secret,
                                    "grant_type": "refresh_token", "refresh_token": refresh_token})
        except Exception:
            raise AuthorizationError("Refresh outcome is uncertain; reconnect GitHub") from None
        credential = self.credential(result)
        if "refresh_token" not in credential:
            raise AuthorizationError("GitHub did not return a rotated refresh credential; reconnect")
        return credential

    @staticmethod
    def credential(result: dict) -> dict:
        if not isinstance(result, dict):
            raise AuthorizationError("GitHub returned invalid authorization data")
        token = result.get("access_token")
        if result.get("error") or result.get("token_type") != "bearer" or not Broker.valid_token(token):
            raise AuthorizationError("GitHub did not authorize this sign-in; start sign-in again")
        clean = {"access_token": token, "token_type": "bearer"}
        for key in ("expires_in", "refresh_token_expires_in"):
            if key in result:
                value = result[key]
                if type(value) is not int or not 0 < value <= 366 * 86400:
                    raise AuthorizationError("GitHub returned invalid credential expiration")
                clean[key] = value
        if "refresh_token" in result:
            if not Broker.valid_token(result["refresh_token"]):
                raise AuthorizationError("GitHub returned an invalid refresh credential")
            clean["refresh_token"] = result["refresh_token"]
        return clean

    @staticmethod
    def valid_token(value):
        return isinstance(value, str) and bool(re.fullmatch(r"[!-~]{1,4096}", value))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def github_exchange(payload: dict) -> dict:
    request = urllib.request.Request("https://github.com/login/oauth/access_token",
                                     data=urllib.parse.urlencode(payload).encode(),
                                     headers={"Accept": "application/json", "User-Agent": "GPT-Mobile-GitHub-Auth/1"})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
            body = response.read(32769)
            if len(body) > 32768:
                raise AuthorizationError("Oversized authorization response")
            result = json.loads(body)
            if not isinstance(result, dict):
                raise AuthorizationError("Invalid authorization response")
            return result
    except Exception:
        raise AuthorizationError("GitHub authorization exchange unavailable") from None
