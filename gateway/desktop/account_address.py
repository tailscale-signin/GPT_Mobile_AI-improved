"""Verify an existing personal GitHub credential on a direct private TLS connection."""
import hashlib
import hmac
import json
import re
import time
from urllib.parse import urlsplit

from desktop.configuration import account_engineering, private_lan_ip
from desktop.control_store import ControlError
from desktop.github_credentials import read_token


def credential_key(root):
    if not account_engineering(root):
        return None
    try:
        token = read_token(root)
    except (OSError, ValueError):
        return None
    if not isinstance(token, str) or not re.fullmatch(r'(github_pat_|ghp_)[A-Za-z0-9_]{30,240}', token):
        return None
    return hmac.digest(token.encode('ascii'), b'GPT Mobile Gateway local owner v1', 'sha256')


def tag(key, *fields):
    return hmac.new(key, '\n'.join(map(str, fields)).encode('utf-8'), hashlib.sha256).hexdigest()


def offer(root, peer, host, port, installation, now=None):
    from desktop.control_server import pairing_link
    if not private_lan_ip(host) or not private_lan_ip(peer):
        return None
    try:
        descriptor = json.loads((root / 'data' / 'account-address-listener.json').read_text(encoding='utf-8'))
        endpoint, fingerprint = descriptor['endpoint'], descriptor['fingerprint']
        if urlsplit(endpoint).hostname != host or not re.fullmatch('[0-9a-f]{64}', fingerprint):
            return None
    except (OSError, ValueError, TypeError, KeyError):
        return None
    key = credential_key(root)
    if key is None:
        return None
    expires = int(time.time() if now is None else now) + 120
    proof = tag(key, 'server', installation, endpoint, fingerprint, port, expires)
    return pairing_link(dict(installation_id=installation, gateway_port=port, expires_at=expires,
                             code=f'host_{expires}_{proof}'), endpoint, fingerprint)


def verify(root, peer, endpoint, fingerprint, port, installation, ticket, public_key):
    token = re.fullmatch(r'device_([0-9]{10})_([a-f0-9]{64})', ticket)
    now = time.time()
    if (not token or not private_lan_ip(peer) or not private_lan_ip(urlsplit(endpoint).hostname)
            or not now < int(token[1]) <= now + 125 or len(public_key) > 1024):
        raise ControlError('account_connection_expired', 401)
    key = credential_key(root)
    if key is None or not hmac.compare_digest(token[2], tag(key, 'device', installation, endpoint, fingerprint,
                                                            port, token[1], public_key)):
        raise ControlError('account_connection_unverified', 403)
    return tag(key, 'credential generation', installation)
