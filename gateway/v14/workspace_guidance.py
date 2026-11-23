"""Revision-bound conventions and on-demand procedures for the engineering workspace."""
import json
import re

from v14.engineering_backend import safe_path


def instruction_paths(tree, paths):
    result = set()
    for path in paths:
        parts = safe_path(path).split('/')[:-1]
        for depth in range(len(parts) + 1):
            for filename in ('CLAUDE.md', 'AGENTS.md'):
                candidate = '/'.join(parts[:depth] + [filename])
                if tree.get(candidate, {}).get('mode') in {'100644', '100755'}:
                    result.add(candidate)
    # Rules nearest the file win. AGENTS.md wins within the same directory.
    return sorted(result, key=lambda p: (p.count('/'), p.rpartition('/')[0], p.endswith('AGENTS.md')))


def activate(backend, job, paths):
    from v14.engineering_workspace import load_file
    data = job['data']
    names = instruction_paths(data['tree'], paths)
    for path in names:
        load_file(backend, job, path)
    data['active_instructions'] = names
    return names


def contents(data):
    rules = []
    for path in data.get('active_instructions', []):
        source = data.get('files', {}).get(path)
        if not source or source['sha'] != data['tree'][path]['sha']:
            raise ValueError('Refresh the pinned repository instructions before continuing')
        rules.append({'path': path, 'blob_sha': source['sha'], 'text': source['content']})
    return rules


# Each action is a procedure within the existing task scope. Procedures never
# change host policy, select a different model or acquire publishing authority.
PROCEDURES = {
    'diagnose-android-crash': ('Read the stack and affected code', 'Reproduce the crash', 'Test the smallest repair'),
    'repair-ci': ('Identify the failed check and failing revision', 'Reproduce the failure', 'Verify the candidate against the same check'),
    'gateway-readiness': ('Read the service identity and readiness evidence', 'Isolate the failed prerequisite', 'Verify restored readiness'),
    'implement-issue': ('Group reports by failure evidence', 'Check applicability at the selected head', 'Reproduce the failure', 'Test the change', 'Inspect the integrated revision and CI'),
    'review-changes': ('Inspect the complete diff and applicable instructions', 'Trace affected callers', 'Compare available test evidence with acceptance criteria'),
    'prepare-release': ('Verify source revision and required checks', 'Inspect release scope and version', 'Verify published assets against the approved revision')
}


# These are dependencies of a procedure, never permission grants.
PROCEDURE_TOOLS = {
    'diagnose-android-crash': ('read', 'check'),
    'repair-ci': ('read', 'checks', 'check'),
    'gateway-readiness': ('read', 'check'),
    'implement-issue': ('read', 'search', 'check'),
    'review-changes': ('read', 'search'),
    'prepare-release': ('read', 'checks'),
}
REQUESTS = {
    'diagnose-android-crash': r'(?:diagnose|investigate|fix) (?:this |the |an? )?android crash',
    'repair-ci': r'(?:fix|repair|investigate) (?:this |the |a )?(?:failing ci|ci (?:failure|check))',
    'gateway-readiness': r'(?:investigate|diagnose|check) (?:the )?gateway readiness',
    'implement-issue': r'(?:implement|fix) (?:issue #\d+|these issues|this issue)',
    'review-changes': r'review (?:the |these )?changed files',
    'prepare-release': r'prepare (?:a |the |this )?release',
}


def requested_procedure(objective):
    """Only a leading slash command or a direct request starts a procedure."""
    objective = objective.strip()
    command = objective.split(maxsplit=1)
    if command and command[0].startswith('/') and command[0][1:] in PROCEDURES:
        return command[0][1:], command[1] if len(command) == 2 else ''
    name = next((key for key, pattern in REQUESTS.items() if re.match(pattern + r'\b', objective, re.I)), None)
    return name, objective


def start_procedure(data, name):
    saved = data.get('workflow') or {}
    if saved.get('name') != name or saved.get('head_sha') != data['head_sha']:
        data['workflow'] = {'name': name, 'head_sha': data['head_sha'], 'steps': list(PROCEDURES[name]), 'completed': []}
    return data['workflow']


def dependencies(data, tools, capabilities):
    admission = data.get('admission') or {}
    available = {'read', 'search', 'paths', 'glob', 'symbols', 'relationships', 'repo_search', 'explore', 'instructions',
                 'progress', 'skills', 'skill', 'requirements', 'workflow_step', 'workflow_batch', 'workflow_batch_status', 'workflow_batch_step',
                 'worker', 'worker_wave', 'worker_status', 'task_plan', 'task_plan_status', 'context_pack', 'context_summary', 'task_context_bundle', 'tool_profile', 'integration_status'}
    supported = {'repository'}
    if admission.get('policy_sha256'):
        available.update(('checks', 'check', 'check_output', 'discover_tests', 'inspect_failure', 'candidates', 'compare_candidates', 'candidate_files', 'run_test', 'compile_module', 'lint_changed_files', 'check_patch', 'task_plan_complete'))
        supported.add('validation')
    if admission.get('workspace_terminal_enabled'):
        available.update(('terminal_open', 'terminal_start', 'terminal_poll', 'terminal_cancel'))
        supported.add('terminal')
        profile = (admission.get('development_environment') or {}).get('profile')
        language = {'Android / Kotlin': 'kotlin', 'Python': 'python', 'Node / TypeScript': 'typescript'}.get(profile)
        if language:
            available.add('code_intelligence')
            supported.add('language-server-' + language)
    return {'tools': list(tools), 'executor_capabilities': list(capabilities),
            'missing_tools': sorted(set(tools) - available), 'missing_capabilities': sorted(set(capabilities) - supported)}


def repository_dependencies(backend, job, path):
    from v14.engineering_workspace import load_file
    descriptor = path.rsplit('/', 1)[0] + '/requirements.json'
    if descriptor not in job['data']['tree']:
        return dependencies(job['data'], [], [])
    text = load_file(backend, job, descriptor)['content']
    if len(text) > 4096:
        raise ValueError('Skill requirements exceed 4096 characters')
    fields = json.loads(text)
    if not isinstance(fields, dict) or set(fields) - {'tools', 'executor_capabilities'}:
        raise ValueError('Choose tools and executor_capabilities for this skill')
    values = [fields.get(key, []) for key in ('tools', 'executor_capabilities')]
    for items in values:
        if not isinstance(items, list) or len(items) > 32 or any(not isinstance(item, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', item) for item in items):
            raise ValueError('Skill requirements must be a bounded list of tool or capability names')
    return dependencies(job['data'], *values)


def skill_paths(tree):
    def is_skill(path):
        parts = path.split('/')
        return (len(parts) == 4 and parts[0] in {'.agents', '.claude'} and parts[1] == 'skills'
                and parts[3] == 'SKILL.md' and tree[path].get('mode') in {'100644', '100755'})
    return sorted(p for p in tree if is_skill(p))


def workflow(backend, job, action):
    from v14.engineering_workspace import load_file
    data = job['data']
    kind = action['action']
    if kind == 'skills':
        names = skill_paths(data['tree'])
        offset = action.get('offset', 0)
        if type(offset) is not int or not 0 <= offset <= len(names):
            raise ValueError('Select a valid skill catalog offset')
        return {'procedures': list(PROCEDURES), 'procedure_requirements': {name: dependencies(data, tools, ['repository']) for name, tools in PROCEDURE_TOOLS.items()},
                'repository_skills': names[offset:offset + 32],
                'repository_skill_manifest': [{'path': path, 'blob_sha': data['tree'][path]['sha'], 'instructions_loaded': path in data.get('files', {})} for path in names[offset:offset + 32]],
                'next_offset': offset + 32 if offset + 32 < len(names) else None}
    if kind == 'skill':
        name = action.get('name')
        if name in PROCEDURES:
            return {'workflow': start_procedure(data, name), 'requirements': dependencies(data, PROCEDURE_TOOLS[name], ['repository'])}
        if name not in skill_paths(data['tree']):
            raise ValueError('Select a procedure or pinned repository SKILL.md from the catalog')
        requirements = repository_dependencies(backend, job, name)
        if requirements['missing_tools'] or requirements['missing_capabilities']:
            return {'path': name, 'status': 'REQUIREMENTS_UNAVAILABLE', 'requirements': requirements}
        source = load_file(backend, job, name)
        if len(source['content']) > 16000:
            return {'path': name, 'blob_sha': source['sha'], 'next_action': 'Use read with start_line to page the skill'}
        return {'path': name, 'blob_sha': source['sha'], 'instructions': source['content'], 'requirements': requirements}
    state = data.get('workflow')
    if not state or state['head_sha'] != data['head_sha']:
        raise ValueError('Select a procedure for the current revision first')
    index = action.get('step')
    if type(index) is not int or index != len(state['completed']) or index >= len(state['steps']):
        raise ValueError('Complete the next pending step in order')
    evidence = action.get('receipt_sha256')
    if not evidence or not any(r.get('receipt_sha256') == evidence and r.get('head_sha') == data['head_sha']
                               and r.get('action') not in {'skill', 'skills', 'workflow_step', 'progress'}
                               for r in data.get('workspace_receipts', [])):
        raise ValueError('Reference a current workspace observation')
    state['completed'].append({'step': index, 'receipt_sha256': evidence})
    return {'workflow': state, 'scope': 'working_notes'}


def batch_workflow(job, action):
    """A revision-bound issue workflow. Only compact evidence hashes enter the saved graph."""
    from v14.workspace_process import identifier
    data = job['data']
    head = data['head_sha']
    state = data.get('workflow_batch')
    if action['action'] == 'workflow_batch':
        requested = action.get('items')
        if not isinstance(requested, list) or not 1 <= len(requested) <= 80:
            raise ValueError('Select 1–80 issue or work item IDs in dependency order')
        items = {}
        for item in requested:
            if not isinstance(item, dict):
                raise ValueError('Each work item needs an ID and optional dependencies')
            key = identifier(item.get('id'))
            deps = item.get('depends_on', [])
            if (key in items or not isinstance(deps, list) or len(deps) > 16 or
                    any(not isinstance(d, str) or d not in items for d in deps) or len(deps) != len(set(deps))):
                raise ValueError('Give each item a unique ID and reference only earlier dependencies')
            items[key] = dict(depends_on=deps, receipts=[])
        if state:
            if state['head_sha'] != head or {k:v['depends_on'] for k,v in state['items'].items()} != {k:v['depends_on'] for k,v in items.items()}:
                raise ValueError('The saved workflow belongs to another item set or revision')
        else:
            state = data['workflow_batch'] = dict(head_sha=head, steps=list(PROCEDURES['implement-issue']), items=items)
    if not state or state['head_sha'] != head:
        raise ValueError('Open the batch workflow at the pinned revision first')
    total = len(state['steps'])
    if action['action'] == 'workflow_batch_step':
        key = identifier(action.get('id'))
        row = state['items'].get(key)
        step, evidence = action.get('step'), action.get('receipt_sha256')
        if not row or type(step) is not int or not 0 <= step < total:
            raise ValueError('Select a workflow item and step')
        if any(len(state['items'][d]['receipts']) != total for d in row['depends_on']):
            raise ValueError('Complete upstream work items first')
        if step < len(row['receipts']):
            if evidence != row['receipts'][step]:
                raise ValueError('The step already has a different evidence receipt')
        elif step != len(row['receipts']) or not evidence or not any(
                r.get('head_sha') == head and r.get('receipt_sha256') == evidence and
                r.get('action') not in {'progress','skills','skill','workflow_step','workflow_batch','workflow_batch_step','workflow_batch_status'}
                for r in data.get('workspace_receipts', [])):
            raise ValueError('Reference an observed workspace receipt for the next pending step')
        else:
            row['receipts'].append(evidence)
    keys = list(state['items'])
    offset = action.get('offset', 0)
    if type(offset) is not int or not 0 <= offset <= len(keys):
        raise ValueError('Select a valid batch offset')
    def info(k):
        r = state['items'][k]
        return dict(id=k, completed=len(r['receipts']), depends_on=r['depends_on'], receipt_ids=r['receipts'],
                    ready=all(len(state['items'][d]['receipts']) == total for d in r['depends_on']))
    return dict(steps=state['steps'], items=[info(k) for k in keys[offset:offset+8]], item_count=len(keys),
                completed_items=sum(len(r['receipts']) == total for r in state['items'].values()),
                next_offset=offset+8 if offset+8 < len(keys) else None, scope='workflow_progress')


def manifest(job):
    """Discover instruction and skill identities without loading their content."""
    data = job['data']
    active = data.get('active_instructions', [])
    return {'head_sha': data['head_sha'], 'active_instructions': [
        {'path': path, 'blob_sha': data['tree'][path]['sha'], 'loaded': path in data.get('files', {})}
        for path in active[:32] if path in data.get('tree', {})],
        'active_instruction_count': len(active), 'omitted_instruction_count': max(0, len(active) - 32),
        'skill_count': len(skill_paths(data.get('tree', {}))),
        'procedures': list(PROCEDURES), 'disclosure': 'CATALOG_THEN_EXACT_SKILL',
        'precedence': 'USER_THEN_HOST_POLICY_THEN_PATH_SPECIFIC_REPOSITORY_GUIDANCE'}
