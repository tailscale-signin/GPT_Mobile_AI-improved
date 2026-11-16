"""Model-selectable checks use only the immutable host-approved command catalog.

Exploratory receipts never replace REVIEW's complete, exact-patch validation.
"""
import hashlib
import json
import re

from v14.engineering_artifacts import CHUNK
from v14.adaptive_validation import capabilities, discover

from v14.engineering_validation import load_policy, patch_identity, validate


def catalog(job):
    policy, _, _, commands, coverage = load_policy(job['request']['repository'])
    policy_sha = hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    admitted = job['data'].get('admission', {}).get('policy_sha256')
    if not admitted or admitted != policy_sha:
        raise ValueError('Workspace checks require the unchanged admitted validation policy')
    return policy_sha, [{'index': index, 'command': command,
                         'cases': [item['case_id'] for item in coverage if item['command_index'] == index],
                         'capabilities': capabilities(command, [item for item in coverage if item['command_index'] == index])}
                        for index, command in enumerate(commands)]


def retained_candidate(backend, job, check_id):
    data = job['data']
    saved = data.get('workspace_checks', {}).get(check_id) if isinstance(check_id, str) else None
    artifacts = backend.private_artifacts
    policy_sha, _ = catalog(job)
    if (not saved or saved['source_sha'] != data['head_sha'] or saved['policy_sha256'] != policy_sha
            or not saved.get('candidate_ref') or artifacts is None):
        raise ValueError('Select an exact retained candidate on the current source and validation policy')
    changes = artifacts.json_value(job, saved['candidate_ref'])
    if patch_identity(data['head_sha'], changes) != saved['patch_identity']:
        raise ValueError('The retained candidate does not match the tested patch')
    return changes


def act(backend, job, action):
    from v14.engineering_backend import prepare_changes
    from v14.engineering_source import prepare_source

    data = job['data']
    kind = action['action']
    if kind == 'inspect_failure':
        kind = 'check_output'
    artifacts = backend.private_artifacts
    if kind == 'discover_tests':
        _, checks = catalog(job)
        return discover(backend, job, action, checks)
    if kind == 'compare_candidates':
        import difflib
        left = retained_candidate(backend, job, action.get('left_check_id'))
        right = retained_candidate(backend, job, action.get('right_check_id'))
        before, after = {c['path']: c for c in left}, {c['path']: c for c in right}
        patches = []
        for path in sorted(before.keys() | after.keys()):
            source = data.get('files', {}).get(path, {}).get('content', '')
            a = before[path]['content'] if path in before else source
            b = after[path]['content'] if path in after else source
            patches.append(''.join(difflib.unified_diff((a or '').splitlines(True), (b or '').splitlines(True), fromfile=path, tofile=path)))
        diff = ''.join(patches)
        return {'diff': diff[:12000], 'truncated': len(diff) > 12000, 'scope': 'candidate_comparison',
                'left_patch_identity': patch_identity(data['head_sha'], left), 'right_patch_identity': patch_identity(data['head_sha'], right)}
    if kind == 'checks':
        policy_sha, checks = catalog(job)
        return {'policy_sha256': policy_sha, 'checks': checks, 'scope': 'exploratory'}
    if kind == 'candidates':
        policy_sha, _ = catalog(job)
        return {'candidates': [
            {key: saved[key] for key in ('check_id', 'patch_identity', 'check_index', 'status')}
            for saved in data.get('workspace_checks', {}).values()
            if saved.get('candidate_ref') and saved['source_sha'] == data['head_sha']
            and saved['policy_sha256'] == policy_sha
        ], 'scope': 'exploratory'}
    if kind == 'candidate_files':
        changes = retained_candidate(backend, job, action.get('candidate_check_id'))
        return {'files': [{'path': change['path'], 'deleted': change['content'] is None,
                           'expected_blob_sha': change['expected_blob_sha']} for change in changes]}
    if kind == 'check_output':
        check_id = action.get('check_id')
        saved = data.get('workspace_checks', {}).get(check_id)
        if not saved or saved['source_sha'] != data['head_sha']:
            raise ValueError('Output requires a retained check on the current source revision')
        start, count = action.get('start_line', 1), action.get('line_count', 80)
        if type(start) is not int or start < 1 or type(count) is not int or not 1 <= count <= 200:
            raise ValueError('Read 1–200 retained output lines from a positive start_line')
        reference = saved.get('output_ref')
        full_output = artifacts.read_bytes(job, reference).decode('utf-8', 'replace') if reference else None
        lines = (full_output if full_output is not None else saved['output_tail']).splitlines(True)
        if start > len(lines) + 1:
            raise ValueError('Output starts beyond the retained log window')
        selected, size = [], 0
        for line in lines[start - 1:start - 1 + count]:
            if len(line) > 12000:
                # Logs can contain a single unbounded line. Expose an explicit
                # clipped line rather than making every page inaccessible.
                line = line[:11960] + '\n[output line clipped]\n'
            if size + len(line) > 12000:
                break
            selected.append(line)
            size += len(line)
        end = start + len(selected)
        return {'check_id': check_id, 'patch_identity': saved['patch_identity'],
                'content': ''.join(selected), 'start_line': start, 'end_line': end - 1,
                'next_line': end if end <= len(lines) else None, 'retained_lines': len(lines),
                'output_sha256': saved['output_sha256'], 'output_bytes': saved['output_bytes'],
                'retention': 'FULL_OUTPUT' if reference else 'LAST_16000_BYTES', 'scope': 'exploratory'}
    policy_sha, checks = catalog(job)
    index = action.get('check_index')
    if type(index) is not int or not 0 <= index < len(checks):
        raise ValueError('Select an exact approved check_index from checks')
    if kind in {'run_test', 'compile_module', 'lint_changed_files', 'check_patch'} and kind not in checks[index]['capabilities']:
        raise ValueError('The selected approved command does not provide this capability')
    proposal = action.get('proposal')
    attempt = action.get('attempt_id')
    if attempt is not None and (not isinstance(attempt, str) or not re.fullmatch('[a-zA-Z0-9_-]{1,64}', attempt)):
        raise ValueError('Fresh execution requires an attempt_id of 1–64 letters, digits, underscores or hyphens')
    saved_checks = data.setdefault('workspace_checks', {})
    candidate = action.get('candidate_check_id')
    if candidate is not None:
        if proposal is not None:
            raise ValueError('Supply either a new proposal or a retained candidate')
        changes = retained_candidate(backend, job, candidate)
    elif proposal is None:
        changes = []
    elif isinstance(proposal, dict):
        changes = prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])
    else:
        raise ValueError('An exploratory patch must use the normal inspected-source proposal contract')
    identity = patch_identity(data['head_sha'], changes)
    identity_parts = [data['head_sha'], identity, policy_sha, index]
    if attempt is not None:
        identity_parts.append(attempt)
    check_id = hashlib.sha256(json.dumps(identity_parts).encode()).hexdigest()
    if check_id not in saved_checks:
        executions = data.get('workspace_check_executions', len(saved_checks))
        if executions >= 24:
            raise ValueError('The job reached its exploratory check execution allowance')
        data['workspace_check_executions'] = executions + 1
        cancellation = backend.cancellation(job)
        parent = backend.api(backend.root(job) + '/git/commits/' + data['head_sha'], job=job)
        prepare_source(job, backend.token(), parent['tree']['sha'], cancellation)
        candidate_ref, retain = None, {}
        if artifacts is not None:
            candidate_ref = artifacts.put_json(job, changes, 'workspace-check-candidate')
            retain['retain_output'] = lambda log: artifacts.put(
                job, iter(lambda: log.read(CHUNK), b''), purpose='workspace-check-output')
        result = validate(job, changes, cancel=cancellation, command_index=index, **retain)
        command = next((item for item in result.get('commands', []) if item.get('command_index') == index), {})
        saved_checks[check_id] = {
            'check_id': check_id, 'source_sha': data['head_sha'], 'patch_identity': identity,
            'policy_sha256': policy_sha, 'check_index': index, 'scope': 'exploratory',
            'candidate_ref': candidate_ref, 'output_ref': command.get('output_ref'), 'attempt_id': attempt,
            'status': result['status'], 'reason': result.get('reason'), 'exit_code': command.get('exit_code'),
            'output_tail': command.get('output_tail', ''), 'output_sha256': command.get('output_sha256'),
            'output_bytes': command.get('output_bytes'), 'complete_validation': False}
        # Keep only bounded logs. Receipts for evicted checks remain in evidence;
        # the model must not confuse them with output still available here.
        while len(saved_checks) > 24:
            del saved_checks[next(iter(saved_checks))]
    return {key: value for key, value in saved_checks[check_id].items() if key != 'output_tail'}
