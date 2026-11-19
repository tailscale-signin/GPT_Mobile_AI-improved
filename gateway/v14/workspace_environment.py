"""Host-approved development limits and custom setup. Values are repository policy, never host secrets."""
import hashlib
import json
import re


def read(policy):
    options = policy.get('workspace_environment', {})
    fields = {'setup_commands', 'environment', 'memory_gib', 'cpus', 'checkout_gib'}
    if not isinstance(options, dict) or set(options) - fields:
        raise ValueError('Unknown workspace environment field')
    commands, env = options.get('setup_commands', []), options.get('environment', {})
    if (not isinstance(commands, list) or len(commands) > 12 or any(not isinstance(c, list) or not 1 <= len(c) <= 32
            or any(not isinstance(a, str) or not 1 <= len(a) <= 1024 or any(ch in a for ch in '\x00\r\n') for a in c) for c in commands)):
        raise ValueError('Choose up to twelve custom setup commands as argument arrays')
    if (not isinstance(env, dict) or len(env) > 24 or any(not re.fullmatch('[A-Z_][A-Z0-9_]{0,63}', name)
            or not isinstance(value, str) or len(value) > 1024 or any(ch in value for ch in '\x00\r\n') for name, value in env.items())):
        raise ValueError('Use bounded public environment values for the approved commands')
    limits = {}
    for name, default, ceiling in [('memory_gib', 8 if policy.get('prepared_environment') else 6, 32),
                                   ('cpus', 2, 8), ('checkout_gib', 4, 16)]:
        limit = options.get(name, default)
        if type(limit) is not int or not 1 <= limit <= ceiling:
            raise ValueError('Unsupported workspace resource limit for ' + name)
        limits[name] = limit
    return commands, env, limits


def recipe_id(commands, env):
    return hashlib.sha256(json.dumps([commands, env], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
