"""Edit only MCP enablement while preserving commands, credentials and other settings."""
import hashlib
import json
from pathlib import Path
from desktop.configuration import atomic_json


def load_addons(path):
    path = Path(path)
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Add-on configuration exceeds 2 MiB')
    raw = path.read_bytes()
    config = json.loads(raw)
    servers = config.get('mcpServers') if isinstance(config, dict) else None
    if not isinstance(servers, dict) or len(servers) > 128 or any(not isinstance(v, dict) for v in servers.values()):
        raise ValueError('Choose a JSON configuration containing an mcpServers object')
    return config, hashlib.sha256(raw).hexdigest()


def save_addons(path, expected, enabled):
    config, current = load_addons(path)
    if current != expected:
        raise ValueError('The add-on file changed. Reload it before saving your changes.')
    if set(enabled) - config['mcpServers'].keys() or any(type(value) is not bool for value in enabled.values()):
        raise ValueError('Invalid add-on selection')
    for name, value in enabled.items():
        config['mcpServers'][name]['disabled'] = not value
    atomic_json(Path(path), config)
    return load_addons(path)[1]
