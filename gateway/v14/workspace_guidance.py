"""Revision-bound conventions and on-demand procedures for the engineering workspace."""
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
        return {'procedures': list(PROCEDURES), 'repository_skills': names[offset:offset + 32],
                'next_offset': offset + 32 if offset + 32 < len(names) else None}
    if kind == 'skill':
        name = action.get('name')
        if name in PROCEDURES:
            steps = list(PROCEDURES[name])
            saved = data.get('workflow') or {}
            if saved.get('name') != name or saved.get('head_sha') != data['head_sha']:
                data['workflow'] = {'name': name, 'head_sha': data['head_sha'], 'steps': steps, 'completed': []}
            return {'workflow': data['workflow']}
        if name not in skill_paths(data['tree']):
            raise ValueError('Select a procedure or pinned repository SKILL.md from the catalog')
        source = load_file(backend, job, name)
        if len(source['content']) > 16000:
            return {'path': name, 'blob_sha': source['sha'], 'next_action': 'Use read with start_line to page the skill'}
        return {'path': name, 'blob_sha': source['sha'], 'instructions': source['content']}
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
