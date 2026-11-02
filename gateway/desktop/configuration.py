"""Validated persistent settings; network access is an explicit choice; localhost remains the default."""
import ipaddress
import json
import os
import re
import socket
import uuid
from pathlib import Path
from urllib.parse import urlsplit

DEFAULTS = dict(schema=1, port=8090, backend='http://127.0.0.1:8080', workers=2,
                active_chats=8, shutdown_seconds=30, update_mode='notify',
                update_interval_hours=24, start_at_login=True, access='loopback',
                tailnet_host='', tailnet_user='', engineering=False, mcp_config='',
                bind_host='127.0.0.1', network_hostnames='')


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
    if cfg['update_mode'] not in {'off', 'notify', 'auto'} or cfg['access'] not in {'loopback', 'network', 'tailscale'}:
        raise ValueError('Unsupported access or update mode')
    # Old settings retain their backend, limits and legacy identity fields for
    # recovery, but never turn a header-trusting proxy into an open listener.
    if cfg['access'] == 'tailscale':
        cfg.update(access='loopback', bind_host='127.0.0.1', network_hostnames='')
    if not isinstance(cfg['bind_host'], str):
        raise ValueError('Listen address must be an IP string')
    try:
        address = ipaddress.ip_address(cfg['bind_host'])
    except (ValueError, TypeError):
        raise ValueError('Listen address must be a literal IP: 127.0.0.1, a PC interface IP, or 0.0.0.0') from None
    if address.is_multicast or '%' in str(address) or str(address) == '255.255.255.255':
        raise ValueError('Choose a unicast interface IP or 0.0.0.0 for all IPv4 interfaces')
    cfg['bind_host'] = str(address)
    cfg['access'] = 'loopback' if address.is_loopback else 'network'
    if not isinstance(cfg['network_hostnames'], str) or len(cfg['network_hostnames']) > 2048:
        raise ValueError('Additional hostnames must be a comma-separated list')
    names = [name.strip().lower() for name in cfg['network_hostnames'].split(',') if name.strip()]
    if any(len(name) > 253 or not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', name) or '..' in name for name in names):
        raise ValueError('Enter hostnames without scheme, port, path or wildcard')
    cfg['network_hostnames'] = ', '.join(dict.fromkeys(names))
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


def control_host(cfg):
    host = cfg['bind_host']
    return {'0.0.0.0': '127.0.0.1', '::': '::1'}.get(host, host)


def authority(host, port):
    return f'[{host}]:{port}' if ':' in host else f'{host}:{port}'


def environment(root, cfg, management_token=''):
    cfg = validate(cfg)
    root = Path(root).resolve()
    data = root / 'data'
    data.mkdir(parents=True, exist_ok=True)
    mcp = data / 'mcp.json'
    if not mcp.exists():
        atomic_json(mcp, {'mcpServers': {}})
    names = ['127.0.0.1', 'localhost', '::1', control_host(cfg)]
    names += [socket.gethostname().lower(), socket.getfqdn().lower()]
    names += [name.strip() for name in cfg['network_hostnames'].split(',') if name.strip()]
    hosts = list(dict.fromkeys(authority(h, cfg['port']) for h in names))
    # Environment variables from a previous manual install must not override managed identity/state.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GATEWAY_')}
    env.update(GATEWAY_HOST=cfg['bind_host'], GATEWAY_PORT=str(cfg['port']), LLAMA_BASE=cfg['backend'],
               GATEWAY_AUTH_MODE='open_network' if cfg['access'] == 'network' else 'loopback',
               GATEWAY_ALLOWED_HOSTS=json.dumps(hosts),
               GATEWAY_ALLOWED_ORIGINS=json.dumps([] if cfg['access'] == 'network' else ['http://' + h for h in hosts]),
               GATEWAY_REMOTE_ENGINEERING=str(cfg['engineering']).lower(), GATEWAY_MANAGEMENT_TOKEN=management_token,
               GATEWAY_JOB_DB=str(data / 'jobs.sqlite3'), GATEWAY_AUTH_DB=str(data / 'auth.sqlite3'),
               GATEWAY_MAX_WORKERS=str(cfg['workers']), GATEWAY_MAX_ACTIVE_CHATS=str(cfg['active_chats']),
               GATEWAY_SHUTDOWN_GRACE=str(cfg['shutdown_seconds']),
               MCP_CONFIG_PATH=cfg['mcp_config'] or str(mcp), GATEWAY_DESKTOP_MANAGED='1')
    return env
