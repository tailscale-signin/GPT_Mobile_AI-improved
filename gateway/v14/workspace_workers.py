"""Resume one isolated specialist step under the parent's selected inference route."""
import copy

from v14.engineering_workspace import act, load_file, view
from v14.workspace_process import identifier

READS = {'paths', 'search', 'read', 'repo_search', 'glob', 'symbols', 'relationships', 'explore', 'instructions'}
ROLES = {
    'investigator': READS, 'reviewer': READS | {'candidate_files', 'compare_candidates', 'check_output'},
    'test worker': READS | {'discover_tests', 'checks', 'run_test', 'inspect_failure', 'check_output'},
    'implementation worker': READS | {'checks', 'check', 'candidate_files', 'check_output'}
}


def advance(backend, job, action):
    parent = job['data']
    name = identifier(action.get('worker_id'))
    workers = parent.setdefault('workspace_workers', {})
    saved = workers.get(name)
    head = parent['head_sha']
    if saved is None:
        role, objective, budget = action.get('role'), action.get('objective'), action.get('budget', 6)
        if (role not in ROLES or not isinstance(objective, str) or not 1 <= len(objective.strip()) <= 2000 or
                type(budget) is not int or not 2 <= budget <= 12 or len(workers) >= 8):
            raise ValueError('Start up to eight named workers with a role, objective and a 2–12 call budget')
        saved = workers[name] = dict(role=role, objective=objective, budget=budget, calls=0, head_sha=head, state='ACTIVE')
    if saved['head_sha'] != head:
        raise ValueError('Start a new worker at the changed source revision')
    if any(k in action and saved[k] != action[k] for k in ('role', 'objective', 'budget')):
        raise ValueError('Worker objective, role and budget are fixed at admission')
    if saved['state'] != 'ACTIVE':
        return projection(name, saved)
    artifacts = backend.private_artifacts
    if artifacts is None:
        raise ValueError('Durable worker context requires the private artifact store')
    local = dict(job, data=dict(parent))
    data = local['data']
    # Candidate source/exposure belongs only to the worker that inspected it.
    data.update(files={}, workspace_receipts=[], workspace_workers={}, active_instructions=[],
                workspace_checks=copy.deepcopy(parent.get('workspace_checks', {})))
    if saved.get('context_ref'):
        context = artifacts.json_value(job, saved['context_ref'])
        data['workspace_receipts'] = context['receipts']
        data['active_instructions'] = context['instructions']
        for path, exposure in context['paths'].items():
            target = load_file(backend, local, path)
            if exposure:
                target['source_exposure'] = exposure
    role = saved['role']
    evidence = view(data)
    evidence.update(worker_objective=saved['objective'], permitted_tools=sorted(ROLES[role]),
                    remaining_calls=saved['budget'] - saved['calls'], candidate_check_id=saved.get('candidate_check_id'))
    result = backend.model(local, 'workspace specialist', evidence)
    saved['calls'] += 1
    if isinstance(result.get('workspace_action'), dict):
        tool = result['workspace_action']
        if tool.get('action') not in ROLES[role]:
            raise ValueError('This worker cannot use the requested action')
        if tool.get('proposal') and role != 'implementation worker':
            raise ValueError('Only an implementation worker can prepare an isolated patch')
        if tool.get('proposal'):
            from v14.workspace_guidance import instruction_paths
            paths = [change['path'] for change in tool['proposal']['changes']]
            if not set(instruction_paths(data['tree'], paths)).issubset(data['active_instructions']):
                tool = dict(action='instructions', paths=paths, head_sha=head)
        receipt = act(backend, local, tool)
        data['workspace_receipts'].append(receipt)
        saved['last_receipt'] = receipt['receipt_sha256']
        saved['last_action'] = receipt['action']
        if receipt.get('check_id') in data['workspace_checks']:
            key = receipt['check_id']
            parent.setdefault('workspace_checks', {})[key] = data['workspace_checks'][key]
            parent['workspace_check_executions'] = data.get('workspace_check_executions', 0)
            saved['candidate_check_id'] = key
            saved['test_status'] = receipt.get('status')
        context = {'receipts': data['workspace_receipts'][-3:], 'instructions': data['active_instructions'],
                   'paths': {path: source.get('source_exposure') for path, source in data['files'].items()}}
        saved['context_ref'] = artifacts.put_json(job, context, 'worker-context')
        if saved['calls'] >= saved['budget']:
            saved['state'] = 'BUDGET_REACHED'
    elif isinstance(result.get('findings'), str) and 0 < len(result['findings']) <= 3000:
        saved.update(state='COMPLETED', findings=result['findings'])
    else:
        raise ValueError('Worker must request a permitted workspace action or report bounded findings')
    return projection(name, saved)


def projection(name, saved):
    return dict(worker_id=name, permitted_tools=sorted(ROLES[saved['role']]),
                **{k: v for k, v in saved.items() if k != 'context_ref'})
