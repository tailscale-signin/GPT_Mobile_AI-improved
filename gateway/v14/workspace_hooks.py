"""Deterministic lifecycle hooks from the immutable host validation contract."""
import fnmatch
import hashlib
import json

from v14.engineering_validation import load_policy, patch_identity


def settings(policy, commands):
    hooks = policy.get('workspace_hooks', {})
    if not isinstance(hooks, dict) or set(hooks) - {'format_paths', 'before_publish'}:
        raise ValueError('Choose format_paths and before_publish lifecycle hooks')
    patterns, indices = hooks.get('format_paths', []), hooks.get('before_publish', [])
    if (not isinstance(patterns, list) or len(patterns) > 16 or
            any(not isinstance(p, str) or not 1 <= len(p) <= 200 for p in patterns) or
            not isinstance(indices, list) or len(indices) > len(commands) or
            any(type(i) is not int or not 0 <= i < len(commands) for i in indices) or len(set(indices)) != len(indices)):
        raise ValueError('Use bounded path patterns and distinct approved command indexes for hooks')
    return patterns, indices


def admitted(job):
    expected = job['data'].get('admission', {}).get('policy_sha256')
    if not job['data'].get('admission', {}).get('workspace_hooks_enabled'):
        return [], []
    if not expected:
        raise ValueError('Lifecycle hooks require the admitted validation contract')
    policy, _, _, commands, _ = load_policy(job['request']['repository'])
    if hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest() != expected:
        raise ValueError('Hook configuration changed after admission')
    return settings(policy, commands)


def after_edit(job, changes):
    patterns, _ = admitted(job)
    result, formatted = [], []
    for change in changes:
        item = dict(change)
        if item['content'] and any(fnmatch.fnmatchcase(item['path'], glob) for glob in patterns):
            text = ''.join(line.rstrip('\r\n').rstrip(' \t') + line[len(line.rstrip('\r\n')):] for line in item['content'].splitlines(True))
            if not text.endswith(('\n', '\r')):
                text += '\r\n' if '\r\n' in text else '\n'
            if text != item['content']:
                formatted.append(item['path'])
            item['content'] = text
        result.append(item)
    return result, {'event': 'after_edit', 'formatted': formatted, 'patch_identity': patch_identity(job['data']['head_sha'], result)}


def before_publish(job, validation):
    _, indices = admitted(job)
    observed = {r['command_index']: r for r in validation.get('commands', []) if 'command_index' in r}
    bound = validation.get('patch_identity') == patch_identity(job['data']['head_sha'], job['data']['changes'])
    checks = [{'command_index': i, 'exit_code': observed.get(i, {}).get('exit_code'),
               'output_sha256': observed.get(i, {}).get('output_sha256')} for i in indices]
    return {'event': 'before_publish', 'checks': checks, 'patch_identity': validation.get('patch_identity'),
            'passed': not checks or bound and all(c['exit_code'] == 0 and c['output_sha256'] for c in checks)}
