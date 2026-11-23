"""Authenticated workspace projections; observations never grant publication authority."""
from copy import deepcopy

from v14.engineering_scope import accepted_scope

ACTIVE = {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW'}
INTELLIGENCE_ACTIONS = {
    'worker', 'worker_wave', 'worker_status', 'task_plan', 'task_plan_status', 'task_plan_complete',
    'context_pack', 'context_summary', 'task_context_bundle', 'tool_profile', 'integration_status', 'integration_call', 'next_edit', 'next_edit_validate',
    'environment_qualify', 'candidate_rank', 'terminal_open', 'terminal_fork', 'terminal_switch',
    'terminal_close', 'terminal_start', 'terminal_poll', 'terminal_cancel', 'code_intelligence'
}
AGENT_ACTIONS = {'agent_qualify', 'agent_start', 'agent_status', 'agent_events', 'agent_steer', 'agent_cancel',
                 'agent_import', 'agent_artifacts', 'agent_approve_plan', 'agent_compare'}
MAINTENANCE_ACTIONS = {'environment_qualify', 'integration_status', 'agent_qualify', 'agent_status',
                       'agent_events', 'agent_artifacts', 'agent_cancel'}
PRODUCT_REFERENCES = (
    ('perch', 'Perch', 'Pinned source, candidate diffs, tests and private review drafts share one workspace.'),
    ('devswarm', 'DevSwarm', 'Role-scoped workers and separate candidate checkouts retain source and conflict evidence.'),
    ('windsurf', 'Windsurf', 'Task-focused tools, progressive instructions and local edit suggestions are built into this workspace.'),
    ('amazon_q', 'Amazon Q Developer', 'Use an explicitly configured supported runtime; this release does not run an Amazon Q integration.'),
    ('roo', 'Roo Code', 'Custom roles inform the worker workflow; Roo is not a runnable adapter in this release.'),
    ('continue', 'Continue', 'Extensible context and task-tool profiles are available; Continue is not a runnable adapter in this release.'),
    ('sweep', 'Sweep issue bot', 'Dependency-ready accepted tasks and optional local next-edit suggestions are available; no Sweep bot is started.'),
    ('type', 'Type.com connector', 'Exact host-configured MCP tools and permissions are reused; no Type account connection is implied.'),
    ('github_mcp', 'Official GitHub MCP', 'Host-configured complementary security-alert reads coexist with the native GitHub workflow.'),
)


def available_actions(job):
    import re
    if not re.fullmatch('[a-f0-9]{40}', str(job['data'].get('head_sha', ''))):
        return []
    actions = {'review_draft'}
    if job['state'] in ACTIVE and accepted_scope(job['request']) == 'implement':
        actions |= INTELLIGENCE_ACTIONS | AGENT_ACTIONS
    elif job['state'] in {'NEEDS_INPUT', 'MERGE_READY'} and accepted_scope(job['request']) == 'implement':
        actions |= MAINTENANCE_ACTIONS
    return sorted(actions)


def _environment(job):
    from v14.engineering_validation import load_policy
    from v14.workspace_environment import read
    admitted = job['data'].get('admission') or {}
    receipt = job['data'].get('workspace_environment_qualification')
    result = {'status': 'NOT_QUALIFIED', 'source_sha': job['data'].get('head_sha'),
              'admission': {key: deepcopy(admitted[key]) for key in
                            ('ready', 'checks', 'policy_sha256', 'development_environment') if key in admitted},
              'qualification': deepcopy(receipt), 'model_executed': False}
    try:
        policy, _, image, commands, _ = load_policy(job['request']['repository'])
        import hashlib
        import json
        digest = hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        _, _, limits = read(policy)
        current = digest == admitted.get('policy_sha256')
        from v14.workspace_lsp import SERVERS
        result.update(policy_current=current, policy_sha256=digest, image=image, limits=limits,
                      command_count=len(commands), terminal_enabled=policy.get('workspace_terminal') is True,
                      language_servers=[{'language': name, 'command': argv, 'state': 'NOT_PROBED'} for name, argv in sorted(SERVERS.items())])
        from v14.engineering_workspace_checks import catalog
        if current:
            _, approved = catalog(job)
            result['checks'] = [dict(row, name=' '.join(row['command'])) for row in approved]
        result['status'] = ('READY' if receipt and receipt.get('ready') is True and
                            receipt.get('source_sha') == result['source_sha'] and
                            receipt.get('policy_sha256') == digest and current else
                            'NOT_QUALIFIED' if current else 'POLICY_CHANGED')
    except (ValueError, OSError, KeyError, TypeError):
        result.update(status='SETUP_REQUIRED', policy_current=False)
    return result


def snapshot(service, principal, job_id):
    job = service.evidence_access(principal, job_id)
    service.artifacts.restore_stage(job)
    data = job['data']
    head = data.get('head_sha')
    checks = data.get('workspace_checks') or {}
    workers = []
    for name, value in list((data.get('workspace_workers') or {}).items())[:8]:
        row = {key: deepcopy(value[key]) for key in ('role', 'objective', 'state', 'head_sha', 'calls', 'budget',
               'candidate_check_id', 'test_status', 'last_action', 'last_receipt', 'findings', 'depends_on') if key in value}
        row.update(worker_id=name, id=name, source_current=value.get('head_sha') == head)
        from v14.workspace_workers import ROLES
        row['permitted_tools'] = sorted(ROLES.get(value.get('role'), ()))
        workers.append(row)
    terminals = dict(data.get('terminal_branches') or {})
    active = data.get('terminal_session') or {}
    if active.get('session_id'):
        terminals[active['session_id']] = active
    terminal_rows = [{key: row.get(key) for key in ('session_id', 'head_sha', 'candidate_check_id',
                     'forked_from', 'patch_identity')} for row in list(terminals.values())[:3]]
    for row in terminal_rows:
        row.update(active=row['session_id'] == active.get('session_id'), source_current=row['head_sha'] == head,
                   expires_at=job.get('deadline'))
    verdicts = {row['id']: row for row in (data.get('completion_verification') or {}).get('requirements', [])
                if isinstance(row, dict) and isinstance(row.get('id'), str)}
    requirements = [dict(deepcopy(row), verdict=deepcopy(verdicts.get(row.get('id'))),
                         evidence_receipt=deepcopy((data.get('criterion_receipts') or {}).get(row.get('id'))))
                    for row in job['request'].get('acceptance', [])[:256]]
    result = {'schema_version': 1, 'job_id': job_id, 'principal_id': principal,
              'gateway_id': service.store.conversations.gateway_id(), 'repository': job['request']['repository'],
              'branch': job['request']['branch'], 'head_sha': head, 'revision': job['revision'], 'state': job['state'],
              'objective': job['request']['objective'],
              'workers': workers, 'terminals': terminal_rows, 'requirements': requirements,
              'requirement_count': len(job['request'].get('acceptance', [])),
              'environment': _environment(job), 'agents': [], 'agent_runs': [],
              'product_references': [{'id': key, 'name': name, 'state': 'REFERENCE_ONLY',
                                      'feature': feature, 'executable': False} for key, name, feature in PRODUCT_REFERENCES],
              'instructions': list(data.get('active_instructions') or [])[:64],
              'tool_profile': deepcopy(data.get('workspace_tool_profile') or {}),
              'summaries': deepcopy(data.get('workspace_summaries') or [])[-16:],
              'task_plan': deepcopy(data.get('workspace_task_plan') or {}),
              'candidate_comparison': deepcopy(data.get('workspace_candidate_comparison') or {}),
              'candidates': [{key: value.get(key) for key in ('check_id', 'source_sha', 'patch_identity',
                              'check_index', 'status', 'exit_code', 'attempt_id', 'scope')}
                             for value in list(checks.values())[-24:]],
              'available_actions': available_actions(job), 'execution_owner': 'GATEWAY',
              'publication_requires_current_validation': True}
    agent_runs = getattr(service, 'agent_runs', None)
    if agent_runs is not None:
        capabilities = agent_runs.capabilities(job)
        result['agents'] = capabilities.get('agents', []) if isinstance(capabilities, dict) else capabilities
        result['agent_runs'] = agent_runs.list(job)
    from v14.workspace_intelligence import snapshot as intelligence
    details = intelligence(service.backend, job)
    details.pop('available_actions', None)
    result.update(details)
    result['tool_profile'] = result.get('tool_profile') or {}
    commands = getattr(service, 'workspace_commands', None)
    if commands is not None:
        result['commands'] = commands.list(job)
        result['review_drafts'] = commands.drafts(job)
    # Revocation, deletion or a concurrent source move invalidates this snapshot.
    current = service.evidence_access(principal, job_id)
    if current['revision'] != job['revision'] or current['data'].get('head_sha') != head:
        raise ValueError('Workspace changed while it was read; refresh the current revision')
    return result


def qualify_environment(backend, job, action):
    from v14.engineering_preflight import preflight
    request = dict(job['request'], source_sha=job['data']['head_sha'])
    result = preflight(request)
    result.update(source_sha=job['data']['head_sha'], qualification_scope='HOST_PREREQUISITES', model_executed=False)
    admitted = job['data'].get('admission') or {}
    if result.get('policy_sha256') != admitted.get('policy_sha256'):
        result.update(ready=False, reason='POLICY_CHANGED')
    job['data']['workspace_environment_qualification'] = result
    return result


def rank_candidates(backend, job, action):
    """Compare only receipts with the same source, policy and executed check."""
    from v14.engineering_workspace_checks import catalog, retained_candidate
    policy, approved = catalog(job)
    requested = action.get('check_ids')
    checks = job['data'].get('workspace_checks') or {}
    if (not isinstance(requested, list) or not 2 <= len(requested) <= 8 or
            len(set(requested)) != len(requested) or any(key not in checks for key in requested)):
        raise ValueError('Select two to eight distinct retained candidate checks')
    rows, groups, paths = [], set(), {}
    observed_indices = {item['index'] for item in approved}
    for key in requested:
        saved = checks[key]
        changes = retained_candidate(backend, job, key)
        group = (saved['source_sha'], saved['policy_sha256'], saved['check_index'])
        groups.add(group)
        rows.append({'check_id': key, 'patch_identity': saved['patch_identity'], 'source_sha': saved['source_sha'],
                     'policy_sha256': policy, 'check_index': saved['check_index'], 'status': saved['status'],
                     'exit_code': saved.get('exit_code'), 'changed_files': len(changes),
                     'complete_validation': False, 'scope': 'EXPLORATORY_CHECK',
                     'executed': type(saved['check_index']) is int and saved['check_index'] in observed_indices and
                                 saved['status'] in {'PASSED', 'FAILED'} and type(saved.get('exit_code')) is int,
                     'model_quality_score': None})
        paths[key] = {row['path'] for row in changes}
    executed = all(row['executed'] for row in rows)
    comparable = executed and len(groups) == 1
    if comparable:
        rows.sort(key=lambda row: (row['status'] != 'PASSED' or row['exit_code'] != 0, row['check_id']))
    overlaps = [{'left_check_id': left, 'right_check_id': right, 'paths': sorted(paths[left] & paths[right])}
                for index, left in enumerate(requested) for right in requested[index + 1:] if paths[left] & paths[right]]
    result = {'head_sha': job['data']['head_sha'], 'comparable': comparable,
              'reason': 'NOT_EXECUTED' if not executed else 'SAME_EXECUTED_CHECK' if comparable else 'DIFFERENT_CHECK_CONTRACTS',
              'candidates': rows, 'overlaps': overlaps, 'selected_candidate': None,
              'automatic_merge': False, 'scope': 'CANDIDATE_CHECK_COMPARISON'}
    job['data']['workspace_candidate_comparison'] = result
    return result


def source_page(service, principal, job_id, head_sha, path, start_line=1, line_count=80):
    from v14.engineering_workspace import load_file
    if type(start_line) is not int or start_line < 1 or type(line_count) is not int or not 1 <= line_count <= 200:
        raise ValueError('Read 1–200 lines at a positive source line')
    job = service.evidence_access(principal, job_id)
    if job['data'].get('head_sha') != head_sha:
        raise ValueError('The source revision changed; refresh the workspace')
    service.artifacts.restore_stage(job)
    original = load_file(service.backend, job, path)
    lines = original['content'].splitlines(True)
    if start_line > len(lines) + 1:
        raise ValueError('Source line exceeds the retained file')
    selected, size = [], 0
    for line in lines[start_line - 1:start_line - 1 + line_count]:
        encoded = len(line.encode())
        if size + encoded > 16000:
            break
        selected.append(line)
        size += encoded
    if not selected and start_line <= len(lines):
        raise ValueError('This source line exceeds the bounded source viewer')
    current = service.evidence_access(principal, job_id)
    if current['data'].get('head_sha') != head_sha:
        raise ValueError('The source revision changed during the read')
    next_line = start_line + len(selected)
    return {'schema_version': 1, 'job_id': job_id, 'head_sha': head_sha, 'path': path,
            'blob_sha': original['sha'], 'content': ''.join(selected), 'start_line': start_line,
            'end_line': next_line - 1, 'total_lines': len(lines),
            'next_line': next_line if next_line <= len(lines) else None}


def tree_page(service, principal, job_id, head_sha, query='', offset=0):
    if not isinstance(query, str) or len(query) > 200 or type(offset) is not int or offset < 0:
        raise ValueError('Use a bounded path query and nonnegative offset')
    job = service.evidence_access(principal, job_id)
    if job['data'].get('head_sha') != head_sha:
        raise ValueError('The source revision changed; refresh the workspace')
    service.artifacts.restore_stage(job)
    rows = sorted((path, entry['sha']) for path, entry in (job['data'].get('tree') or {}).items()
                  if entry.get('mode') in {'100644', '100755'} and query.casefold() in path.casefold())
    if offset > len(rows):
        raise ValueError('The source path cursor is outside this revision')
    current = service.evidence_access(principal, job_id)
    if current['data'].get('head_sha') != head_sha:
        raise ValueError('The source revision changed during the read')
    return {'schema_version': 1, 'job_id': job_id, 'head_sha': head_sha,
            'paths': [{'path': path, 'blob_sha': sha} for path, sha in rows[offset:offset + 80]],
            'total': len(rows), 'offset': offset, 'next_offset': offset + 80 if offset + 80 < len(rows) else None}
