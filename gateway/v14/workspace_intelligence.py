"""Intelligence operations within one existing revision-bound engineering job."""
import copy

from v14.execution_binding import canonical, fingerprint
from v14.workspace_checkpoint import save
from v14.workspace_workers import ROLES, projection, wave

ACTIONS = frozenset({'worker_wave', 'worker_status', 'task_plan', 'task_plan_status', 'task_plan_complete',
                     'context_pack', 'context_summary', 'tool_profile', 'integration_status', 'integration_call',
                     'next_edit', 'next_edit_validate'})
PROFILES = {'plan': 'planner', 'edit': 'editor', 'review': 'reviewer', 'test': 'test worker', 'retrieve': 'retrieval'}


def tools(job, profile):
    from v14.workspace_guidance import dependencies
    if profile not in PROFILES:
        raise ValueError('Choose plan, edit, review, test or retrieve')
    desired = sorted(ROLES[PROFILES[profile]])
    missing = dependencies(job['data'], desired, [])['missing_tools']
    return [tool for tool in desired if tool not in missing], missing


def summary(backend, job, maximum=4096):
    from v14.inference_budget import compact_evidence
    if type(maximum) is not int or not 1024 <= maximum <= 16384:
        raise ValueError('Choose a 1–16 KiB summary allowance')
    artifacts = backend.private_artifacts
    if artifacts is None:
        raise ValueError('Retaining full evidence requires private artifact storage')
    data = job['data']
    retained = {
        'source_sha': data['head_sha'], 'plan': data.get('plan'),
        'working_notes': data.get('progress_notes'),
        'receipts': data.get('workspace_receipts', []),
        'workers': data.get('workspace_workers', {}),
        'checks': data.get('workspace_checks', {}),
        'requirements': job.get('request', {}).get('acceptance', []),
        'requirement_evidence_refs': data.get('requirement_evidence_refs', []),
        'criterion_receipts': data.get('criterion_receipts', {}),
        'task_plan': data.get('workspace_task_plan')}
    reference = artifacts.put_json(job, retained, 'workspace-summary-evidence')
    checks = [{'check_id': key, 'source_sha': check.get('source_sha'), 'status': check.get('status'),
               'patch_identity': check.get('patch_identity'), 'complete_validation': check.get('complete_validation') is True}
              for key, check in data.get('workspace_checks', {}).items()]
    compact = {'source_sha': data['head_sha'], 'next_action': (data.get('progress_notes') or {}).get('next_action'),
               'check_observations': checks, 'workers': [
                   {'worker_id': key, 'state': row['state'], 'calls': row['calls'], 'budget': row['budget'],
                    'last_receipt': row.get('last_receipt'), 'findings': row.get('findings', '')[:400]}
                   for key, row in data.get('workspace_workers', {}).items()],
               'receipt_ids': [row.get('receipt_sha256') for row in data.get('workspace_receipts', [])
                               if row.get('head_sha') == data['head_sha']],
               'requirement_ids': [row.get('id') for row in retained['requirements']]}
    # Space for immutable references and framing is outside the text slice.
    view, omissions = compact_evidence(compact, max(128, maximum // 3), nodes=max(24, maximum // 40))
    result = {'source_sha': data['head_sha'], 'summary': view, 'evidence_ref': reference,
              'summary_complete': not omissions, 'omissions': omissions[:8],
              'authority': 'EXTRACTIVE_WORKING_CONTEXT', 'completion_authority': False}
    while len(canonical(result).encode()) > maximum and result['omissions']:
        result['omissions'].pop()
        result['summary_complete'] = False
    if len(canonical(result).encode()) > maximum:
        result['summary'] = {'source_sha': data['head_sha'], 'state': 'PAGE_RETAINED_EVIDENCE'}
        result['summary_complete'] = False
    data['workspace_summaries'] = (data.get('workspace_summaries', []) + [result])[-8:]
    save(backend, job, 'workspace_summaries', data['workspace_summaries'])
    return result


def snapshot(backend, job):
    from v14.workspace_guidance import manifest
    from v14.workspace_task_plan import status as plan_status
    from v14.workspace_tool_integrations import status as integration_status
    from v14.workspace_next_edit import readiness
    data, omissions = job['data'], {}
    def page(name, rows, maximum):
        selected = []
        for row in rows:
            if len(canonical(selected + [row]).encode()) > maximum:
                break
            selected.append(row)
        if len(selected) != len(rows):
            omissions[name] = len(rows) - len(selected)
        return selected
    def preview(text, maximum):
        return text.encode()[:maximum].decode('utf-8', 'ignore') if isinstance(text, str) else text
    worker_rows = []
    for key, value in data.get('workspace_workers', {}).items():
        row = projection(key, value)
        for field in ('findings', 'objective'):
            if field in row:
                row[field] = preview(row[field], 1000)
                if row[field] != value[field]:
                    omissions['workers.' + field] = omissions.get('workers.' + field, 0) + 1
        row['paths'] = value.get('paths', [])[:8]
        row['omitted_paths'] = max(0, len(value.get('paths', [])) - 8)
        if row['omitted_paths']:
            omissions['workers.paths'] = omissions.get('workers.paths', 0) + row['omitted_paths']
        worker_rows.append(row)
    plan = plan_status(job)
    for item in plan['items']:
        item['omitted_paths'] = max(0, len(item.get('paths', [])) - 4)
        item['paths'] = item.get('paths', [])[:4]
        if item['omitted_paths']:
            omissions['task_plan.paths'] = omissions.get('task_plan.paths', 0) + item['omitted_paths']
    plan['items'] = page('task_plan.items', plan['items'], 49152)
    plan['omitted_items'] = plan['total'] - len(plan['items'])
    guidance = manifest(job)
    guidance['active_instructions'] = page('guidance.active_instructions', guidance['active_instructions'], 8192)
    guidance['omitted_instruction_count'] = guidance['active_instruction_count'] - len(guidance['active_instructions'])
    if guidance['omitted_instruction_count']:
        omissions['guidance.active_instructions'] = guidance['omitted_instruction_count']
    summaries = copy.deepcopy(data.get('workspace_summaries', [])[-2:])
    for row in summaries:
        if len(canonical(row).encode()) > 8192:
            row.update(summary={'state': 'PAGE_RETAINED_EVIDENCE'}, summary_complete=False, omissions=[])
    context_pack = copy.deepcopy(data.get('workspace_context_pack'))
    if context_pack:
        for field in ('files', 'omitted'):
            context_pack[field] = page('context_pack.' + field, context_pack.get(field, []), 8192)
    integrations = integration_status(backend, job)
    for entry in integrations['integrations']:
        tool_rows = []
        for original in entry.get('tools', []):
            row = dict(original)
            schema = row.get('input_schema')
            if schema is not None and len(canonical(schema).encode()) > 8192:
                row.update(input_schema=None, schema_sha256=fingerprint(schema), schema_omitted=True)
                omissions['integrations.schemas'] = omissions.get('integrations.schemas', 0) + 1
            tool_rows.append(row)
        entry['tools'] = page('integrations.' + entry['id'] + '.tools', tool_rows, 16384)
        entry['omitted_tools'] = len(tool_rows) - len(entry['tools'])
        if len(canonical(entry.get('readiness', {})).encode()) > 4096:
            entry['readiness'] = {'detail_omitted': True}
    edits = []
    for value in list(data.get('workspace_next_edits', {}).values())[-24:]:
        row = {key: value[key] for key in ('next_edit_id', 'source_sha', 'path', 'state', 'route_policy',
            'applies_automatically', 'proposal_ref', 'diff_ref', 'patch_identity', 'test_status', 'candidate_check_id',
            'diff_complete', 'next_action') if key in value}
        if 'diff' in value:
            row['diff'] = preview(value['diff'], 2000)
            row['diff_complete'] = value.get('diff_complete') is True and row['diff'] == value['diff']
            if row['diff'] != value['diff']:
                omissions['next_edits.diff'] = omissions.get('next_edits.diff', 0) + 1
        edits.append(row)
    result = {'available_actions': sorted(ACTIONS),
            'workers': page('workers', worker_rows, 24576),
            'worker_roles': sorted(ROLES), 'worker_route_policy': 'PARENT_SELECTED_ONLY',
            'per_role_route_override': 'REQUIRES_SEPARATELY_ADMITTED_JOB',
            'task_plan': plan, 'tool_profile': data.get('workspace_tool_profile'),
            'tool_profiles': [{'id': name, 'tools': tools(job, name)[0], 'missing_tools': tools(job, name)[1]} for name in PROFILES],
            'guidance': guidance, 'summaries': summaries,
            'context_pack': context_pack, 'integrations': integrations,
            'next_edit_readiness': readiness(backend, job),
            'next_edits': page('next_edits', edits, 32768)}
    profile = result.get('tool_profile')
    if profile:
        result['tool_profile'] = {key: profile[key] for key in ('profile', 'head_sha', 'tools', 'missing_tools', 'authority', 'binding') if key in profile}
    # A final byte gate protects the Android board even if a future field grows.
    # Detail operations and immutable evidence references remain separately usable.
    if len(canonical(result).encode()) > 261120:
        for field in ('context_pack', 'summaries', 'workers', 'next_edits'):
            omissions[field] = 'BYTE_BUDGET'
            result[field] = None if field == 'context_pack' else []
        result['task_plan']['items'] = []
        result['task_plan']['omitted_items'] = result['task_plan']['total']
        omissions['task_plan.items'] = result['task_plan']['total']
    result['projection'] = {'max_bytes': 262144, 'complete': not omissions, 'omissions': omissions}
    return result


def act(backend, job, action):
    kind = action.get('action')
    if kind not in ACTIONS or action.get('head_sha') != job['data']['head_sha']:
        raise ValueError('Select a workspace intelligence action at the exact current source revision')
    if kind == 'worker_wave':
        return wave(backend, job, action)
    if kind == 'worker_status':
        return {'workers': [projection(key, value) for key, value in job['data'].get('workspace_workers', {}).items()],
                'route_policy': 'PARENT_SELECTED_ONLY'}
    if kind.startswith('task_plan'):
        from v14.workspace_task_plan import act as task_action
        result = task_action(job, action)
        if kind != 'task_plan_status':
            save(backend, job, 'workspace_task_plan', job['data']['workspace_task_plan'])
        return result
    if kind == 'context_pack':
        from v14.workspace_context_pack import pack
        result = pack(backend, job, action)
        if job['data'].get('workspace_context_pack'):
            save(backend, job, 'workspace_context_pack', job['data']['workspace_context_pack'])
        return result
    if kind == 'context_summary':
        return summary(backend, job, action.get('max_bytes', 4096))
    if kind == 'tool_profile':
        from v14.workspace_guidance import manifest
        profile = action.get('profile')
        allowed, missing = tools(job, profile)
        result = {'profile': profile, 'head_sha': job['data']['head_sha'], 'tools': allowed,
                  'missing_tools': missing, 'authority': 'DISCOVERY_FILTER_ONLY',
                  'guidance': manifest(job), 'binding': fingerprint([job.get('principal'), job['data']['head_sha'], profile])}
        job['data']['workspace_tool_profile'] = result
        save(backend, job, 'workspace_tool_profile', result)
        return result
    if kind == 'integration_status':
        from v14.workspace_tool_integrations import status
        return status(backend, job, discover=True)
    if kind == 'integration_call':
        from v14.workspace_tool_integrations import call
        result = call(backend, job, action)
        save(backend, job, 'workspace_integrations', result, member=result['invocation_id'])
        return result
    if kind in {'next_edit', 'next_edit_validate'}:
        from v14.workspace_next_edit import act as next_edit
        return next_edit(backend, job, action)
    raise ValueError('Unknown workspace intelligence action')
