"""Broker-only callback and durable, bound redemption of opaque Android tickets.

Only short-lived ciphertext is journaled. A claimed GitHub exchange is never
reissued, including after process death; a completed exchange can be replayed to
the same PKCE/session holder without exchanging the authorization code again.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import time
import urllib.parse

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

from .github_broker import AuthorizationError, Broker, Transactions, challenge_for, https_origin


class TicketError(AuthorizationError):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code, self.status = code, status


def opaque(value: str) -> bool:
    return isinstance(value, str) and bool(re.fullmatch(r"[A-Za-z0-9_-]{43}", value))


class TicketBroker(Broker):
    TICKET_TTL = 120

    def __init__(self, config, exchange, clock=time.time):
        super().__init__(config, exchange, clock)
        self.clock = clock
        self.app_link = config.app_link_url or https_origin(config.public_url) + "/oauth/github/complete"
        https_origin(self.app_link)
        if self.app_link == config.callback_url or urllib.parse.urlsplit(self.app_link).path != "/oauth/github/complete":
            raise ValueError("Android return URL must use a separate /oauth/github/complete App Link")
        if urllib.parse.urlsplit(config.callback_url).path != "/oauth/github/callback":
            raise ValueError("GitHub callback must terminate at the broker's /oauth/github/callback")
        key = HKDF(algorithm=hashes.SHA256(), length=32,
                   salt=hashlib.sha256(config.client_id.encode()).digest(),
                   info=b"gpt-mobile/oauth-ticket/v2").derive(config.client_secret.encode())
        self.cipher = AESGCM(key)
        with self.transactions.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS oauth_tickets (
                state_hash TEXT PRIMARY KEY, challenge TEXT NOT NULL,
                session_hash TEXT NOT NULL, account_intent TEXT NOT NULL,
                expires INTEGER NOT NULL, phase TEXT NOT NULL,
                ticket_hash TEXT UNIQUE, ticket_expires INTEGER,
                code BLOB, ticket BLOB, outcome BLOB, completed INTEGER)""")

    def seal(self, value, identity, purpose):
        nonce = secrets.token_bytes(12)
        data = json.dumps(value, separators=(",", ":")).encode()
        return nonce + self.cipher.encrypt(nonce, data, (identity + ":" + purpose).encode())

    def unseal(self, value, identity, purpose):
        try:
            return json.loads(self.cipher.decrypt(value[:12], value[12:], (identity + ":" + purpose).encode()))
        except Exception:
            raise TicketError("RECONCILIATION_REQUIRED", "Authorization storage could not be verified. Reconnect GitHub.", 409) from None

    def begin_ticket(self, challenge, session_id, account_intent):
        if not opaque(challenge) or not opaque(session_id) or not re.fullmatch(r"[a-f0-9]{64}", account_intent):
            raise TicketError("INVALID_BINDING", "S256, a client session and an account binding are required.")
        state = secrets.token_urlsafe(32)
        now = int(self.clock())
        with self.transactions.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM oauth_tickets WHERE expires <= ?", (now,))
            if db.execute("SELECT COUNT(*) FROM oauth_tickets").fetchone()[0] >= Transactions.MAX_PENDING:
                raise TicketError("AUTHORIZATION_CAPACITY", "Authorization capacity reached. Try again later.", 429)
            db.execute("INSERT INTO oauth_tickets (state_hash,challenge,session_hash,account_intent,expires,phase) VALUES (?,?,?,?,?,?)",
                       (Transactions.digest(state), challenge, Transactions.digest(session_id), account_intent,
                        now + Transactions.TTL_SECONDS, "PENDING"))
        query = urllib.parse.urlencode({"client_id": self.config.client_id, "redirect_uri": self.config.callback_url,
                                       "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
                                       "scope": "offline_access"})
        return {"protocolVersion": 2, "state": state, "authorizationUrl": "https://github.com/login/oauth/authorize?" + query,
                "redirectUri": self.config.callback_url, "appLinkUri": self.app_link, "expiresIn": Transactions.TTL_SECONDS}

    def callback(self, state, code=None, error=None):
        if not opaque(state):
            raise TicketError("INVALID_STATE", "Authorization state is invalid or expired.")
        identity, now = Transactions.digest(state), int(self.clock())
        with self.transactions.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT expires,phase,ticket,code,ticket_expires FROM oauth_tickets WHERE state_hash=?", (identity,)).fetchone()
            if row is None or row[0] <= now:
                raise TicketError("EXPIRED_STATE", "Authorization state is invalid or expired.")
            if error is not None:
                if row[1] != "PENDING":
                    raise TicketError("CALLBACK_CONFLICT", "Authorization callback has already been recorded.", 409)
                db.execute("UPDATE oauth_tickets SET phase='DECLINED' WHERE state_hash=?", (identity,))
                return self.app_link + "?" + urllib.parse.urlencode({"state": state, "error": "authorization_declined"})
            if not isinstance(code, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,1024}", code):
                raise TicketError("INVALID_CALLBACK", "GitHub authorization callback is invalid.")
            if row[1] == "CALLBACK_RECEIVED" and row[4] > now:
                if not hmac.compare_digest(self.unseal(row[3], identity, "code"), code):
                    raise TicketError("CALLBACK_CONFLICT", "Authorization callback changed.", 409)
                ticket = self.unseal(row[2], identity, "ticket")
            elif row[1] == "PENDING":
                ticket = secrets.token_urlsafe(32)
                db.execute("""UPDATE oauth_tickets SET phase='CALLBACK_RECEIVED', ticket_hash=?, ticket_expires=?, code=?, ticket=?
                              WHERE state_hash=?""",
                           (Transactions.digest(ticket), min(row[0], now + self.TICKET_TTL),
                            self.seal(code, identity, "code"), self.seal(ticket, identity, "ticket"), identity))
            else:
                raise TicketError("CALLBACK_CONFLICT", "Authorization callback has already been handled. Return to the app.", 409)
        return self.app_link + "?" + urllib.parse.urlencode({"state": state, "ticket": ticket})

    def redeem(self, state, ticket, verifier, session_id, account_intent):
        if not all(opaque(value) for value in (state, ticket, session_id)):
            raise TicketError("INVALID_BINDING", "Invalid authorization ticket binding.")
        challenge = challenge_for(verifier)
        identity, now = Transactions.digest(state), int(self.clock())
        with self.transactions.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT challenge,session_hash,account_intent,expires,phase,ticket_hash,ticket_expires,code,outcome,completed
                                FROM oauth_tickets WHERE state_hash=?""", (identity,)).fetchone()
            if (row is None or row[3] <= now or not hmac.compare_digest(row[0], challenge)
                    or not hmac.compare_digest(row[1], Transactions.digest(session_id))
                    or not isinstance(account_intent, str) or not hmac.compare_digest(row[2], account_intent)
                    or not hmac.compare_digest(row[5] or "", Transactions.digest(ticket))):
                raise TicketError("INVALID_BINDING", "Authorization ticket is invalid, expired or belongs to another session.")
            if row[4] == "COMPLETED":
                return self.replayed(self.unseal(row[8], identity, "outcome"), now - row[9])
            if row[4] != "CALLBACK_RECEIVED":
                raise TicketError("RECONCILIATION_REQUIRED", "Exchange outcome requires reconciliation. Reconnect GitHub.", 409)
            if row[6] <= now:
                raise TicketError("TICKET_EXPIRED", "Authorization ticket expired. Start sign-in again.")
            code = self.unseal(row[7], identity, "code")
            # Commit intent before dispatch. EXCHANGING after a crash must not reissue.
            db.execute("UPDATE oauth_tickets SET phase='EXCHANGING',code=NULL,ticket=NULL WHERE state_hash=?", (identity,))
        try:
            result = self.credential(self.exchange({"client_id": self.config.client_id, "client_secret": self.config.client_secret,
                                                    "code": code, "code_verifier": verifier, "redirect_uri": self.config.callback_url}))
            completed = int(self.clock())
            with self.transactions.connect() as db:
                db.execute("UPDATE oauth_tickets SET phase='COMPLETED',outcome=?,completed=? WHERE state_hash=? AND phase='EXCHANGING'",
                           (self.seal(result, identity, "outcome"), completed, identity))
            return result
        except Exception:
            # Neither a network timeout nor failure to persist the successful result
            # proves the code was unconsumed. The durable claim is authoritative.
            raise TicketError("RECONCILIATION_REQUIRED", "Exchange outcome could not be retained. Reconnect GitHub.", 409) from None

    @staticmethod
    def replayed(credential, elapsed):
        for key in ("expires_in", "refresh_token_expires_in"):
            if key in credential:
                credential[key] -= max(0, elapsed)
                if credential[key] <= 0:
                    raise TicketError("CREDENTIAL_EXPIRED", "The established authorization expired. Reconnect GitHub.")
        return credential
