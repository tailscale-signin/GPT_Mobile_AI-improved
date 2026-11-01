"""Validated persistent settings; no shell, credentials, or executable configuration."""
import ipaddress
import json
import os
import uuid
from pathlib import Path
from urllib.parse import urlsplit

DEFAULTS = dict(schema=1, port=8090, backend='http://127.0.0.1:8080', workers=2,
                active_chats=8, shutdown_seconds=30, update_mode='notify',
                update_interval_hours=24, start_at_login=True, access='loopback',
                tailnet_host='', tailnet_user='', engineering=False, mcp_config='')


def validate(value):
    if not isinstance(value, dict) or set(value) - DEFAULTS.keys():
        raise ValueError('Unknown settings fields')
    cfg = dict(DEFAULTS, **value)
    if type(cfg['schema']) is not int or cfg['schema'] != 1:
        raise ValueError('Unsupported settings schema')
    for key, low, high in [('port', 1024, 65535), ('workers', 1, 16), ('active_chats', 1, 64),
                           ('shutdown_seconds', 10, 120), ('update_interval_hours', 1, 168)]:
        if type(cfg[key]) is not int or not low <= cfg[key] <= high:
            raise ValueError(f'{key} must be between {low} and {high}')
    if cfg['workers'] > cfg['active_chats']:
        raise ValueError('Workers cannot exceed active chat limit')
    for key in ('start_at_login', 'engineering'):
        if type(cfg[key]) is not bool:
            raise ValueError(key + ' must be boolean')
    if cfg['update_mode'] not in {'off', 'notify', 'auto'} or cfg['access'] not in {'loopback', 'tailscale'}:
        raise ValueError('Unsupported access or update mode')
    if not isinstance(cfg['backend'], str):
        raise ValueError('Backend must be a URL string')
    backend = urlsplit(cfg['backend'])
    if backend.scheme not in {'http', 'https'} or not backend.hostname or backend.username or backend.password or backend.query or backend.fragment or backend.path not in {'', '/'}:
        raise ValueError('Backend must be an HTTP(S) origin without credentials or a /v1 suffix')
    try:
        loopback = ipaddress.ip_address(backend.hostname).is_loopback
    except ValueError:
        loopback = backend.hostname == 'localhost'
    if backend.scheme == 'http' and not loopback:
        raise ValueError('Use HTTPS for a non-loopback model backend')
    if backend.port == cfg['port'] and loopback:
        raise ValueError('Gateway and model backend must use different ports')
    if cfg['access'] == 'tailscale':
        host, user = cfg['tailnet_host'], cfg['tailnet_user']
        if not isinstance(host, str) or not host.endswith('.ts.net') or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-.' for c in host) or '..' in host:
            raise ValueError('Enter the exact lowercase Tailscale HTTPS hostname ending .ts.net')
        if not isinstance(user, str) or not user or len(user) > 128 or any(c.isspace() for c in user):
            raise ValueError('Enter the exact authorized Tailscale user login')
    path = cfg['mcp_config']
    if not isinstance(path, str) or path and (not Path(path).is_absolute() or not Path(path).is_file()):
        raise ValueError('MCP configuration must be an existing absolute file path')
    cfg['backend'] = cfg['backend'].rstrip('/')
    return cfg


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp-' + uuid.uuid4().hex)
    try:
        with temporary.open('w', encoding='utf-8', newline='\n') as stream:
            json.dump(value, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load(root):
    return validate(json.loads((Path(root) / 'settings.json').read_text(encoding='utf-8')))


def environment(root, cfg, management_token=''):
    cfg = validate(cfg)
    root = Path(root).resolve()
    data = root / 'data'
    data.mkdir(parents=True, exist_ok=True)
    mcp = data / 'mcp.json'
    if not mcp.exists():
        atomic_json(mcp, {'mcpServers': {}})
    hosts = [f'127.0.0.1:{cfg["port"]}', f'localhost:{cfg["port"]}']
    users = {}
    if cfg['access'] == 'tailscale':
        hosts.append(cfg['tailnet_host'])
        users[cfg['tailnet_user']] = {'principalId': cfg['tailnet_user'],
            'scopes': ['chat', 'jobs', 'tools'] + (['engineering'] if cfg['engineering'] else [])}
    # Environment variables from a previous manual install must not override managed identity/state.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GATEWAY_')}
    env.update(GATEWAY_HOST='127.0.0.1', GATEWAY_PORT=str(cfg['port']), LLAMA_BASE=cfg['backend'],
               GATEWAY_AUTH_MODE='trusted_proxy' if cfg['access'] == 'tailscale' else 'loopback',
               GATEWAY_ALLOWED_HOSTS=json.dumps(hosts),
               GATEWAY_ALLOWED_ORIGINS=json.dumps(['https://' + h if h.endswith('.ts.net') else 'http://' + h for h in hosts]),
               GATEWAY_TRUSTED_USERS=json.dumps(users), GATEWAY_MANAGEMENT_TOKEN=management_token,
               GATEWAY_JOB_DB=str(data / 'jobs.sqlite3'), GATEWAY_AUTH_DB=str(data / 'auth.sqlite3'),
               GATEWAY_MAX_WORKERS=str(cfg['workers']), GATEWAY_MAX_ACTIVE_CHATS=str(cfg['active_chats']),
               GATEWAY_SHUTDOWN_GRACE=str(cfg['shutdown_seconds']),
               MCP_CONFIG_PATH=cfg['mcp_config'] or str(mcp), GATEWAY_DESKTOP_MANAGED='1')
    return env
