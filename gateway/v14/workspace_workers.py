"""Role-scoped worker steps sharing the parent's lease, budget and selected route."""
import copy
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextvars import copy_context

from v14.engineering_workspace import act, load_file, view
from v14.execution_binding import fingerprint
from v14.workspace_process import identifier
from v14.workspace_checkpoint import save as save_checkpoint

READS = {'paths', 'search', 'read', 'repo_search', 'glob', 'symbols', 'relationships', 'explore', 'instructions'}
ROLES = {
    'investigator': READS,
    'retrieval': READS,
    'planner': READS | {'requirements'},
    'reviewer': READS | {'candidate_files', 'compare_candidates', 'check_output'},
    'test worker': READS | {'discover_tests', 'checks', 'run_test', 'inspect_failure', 'check_output'},
    'implementation worker': READS | {'checks', 'check', 'candidate_files', 'check_output'},
    'editor': READS | {'checks', 'check', 'candidate_files', 'check_output'}
}
READ_ONLY_ROLES = {'investigator', 'retrieval', 'planner', 'reviewer'}
EDIT_ROLES = {'editor', 'implementation worker'}


def binding(job):
    data, request = job['data'], job.get('request', {})
    return fingerprint({'head_sha': data['head_sha'], 'principal': job.get('principal'),
                        'model': request.get('model'), 'binding': job.get('execution_binding'),
                        'execution_binding_id': request.get('execution_binding_id'),
                        'actor': data.get('actor_id'), 'permissions': data.get('permission_revision'),
                        'policy': data.get('admission', {}).get('policy_sha256')})


def admit(job, action):
    """Validate every fixed field before a new worker can consume a model call."""
    parent = job['data']
    name = identifier(action.get('worker_id'))
    workers = parent.setdefault('workspace_workers', {})
    saved = workers.get(name)
    request = job.get('request', {})
    if ('model' in action and action['model'] != request.get('model') or
            'execution_binding_id' in action and action['execution_binding_id'] != request.get('execution_binding_id')):
        raise ValueError('Per-role route changes require separately admitted jobs; this worker uses the selected parent route')
    if saved is None:
        role, objective, budget = action.get('role'), action.get('objective'), action.get('budget', 6)
        paths, dependencies = action.get('paths', []), action.get('depends_on', [])
        if (role not in ROLES or not isinstance(objective, str) or not 1 <= len(objective.strip()) <= 2000 or
                type(budget) is not int or not 2 <= budget <= 12 or len(workers) >= 8):
            raise ValueError('Start up to eight named workers with a role, objective and a 2–12 call budget')
        if (not isinstance(paths, list) or len(paths) > 60 or
                any(not isinstance(path, str) or path not in parent['tree'] for path in paths) or len(set(paths)) != len(paths)):
            raise ValueError('Worker paths must be distinct observed paths in the pinned tree')
        if (not isinstance(dependencies, list) or len(dependencies) > 7 or
                any(not isinstance(key, str) or key not in workers or key == name for key in dependencies) or len(set(dependencies)) != len(dependencies)):
            raise ValueError('Worker dependencies must name previously admitted workers')
        saved = workers[name] = dict(role=role, objective=objective, budget=budget, calls=0,
                                    head_sha=parent['head_sha'], state='ACTIVE', paths=paths,
                                    depends_on=dependencies, binding=binding(job), route_policy='PARENT_SELECTED_ONLY')
    if saved['head_sha'] != parent['head_sha'] or saved.get('binding', binding(job)) != binding(job):
        raise ValueError('Start a new worker after the source, owner, policy or selected route changes')
    if any(key in action and saved.get(key, []) != action[key] for key in ('role', 'objective', 'budget', 'paths', 'depends_on')):
        raise ValueError('Worker objective, role, scope, dependencies and budget are fixed at admission')
    return name, saved


def _check_scope(backend, local, saved, tool):
    paths = saved.get('paths', [])
    if not paths:
        return
    allowed = set(local['data']['tree'])
    requested = [tool['path']] if 'path' in tool else tool.get('paths', [])
    if not isinstance(requested, list) or any(path not in allowed for path in requested):
        raise ValueError('The requested source is outside this worker scope')
    proposal = tool.get('proposal')
    if proposal and any(change.get('path') not in paths for change in proposal.get('changes', [])):
        raise ValueError('An implementation worker can propose changes only to its admitted paths')
    candidates = [tool[key] for key in ('candidate_check_id', 'left_check_id', 'right_check_id', 'check_id') if key in tool]
    if candidates:
        from v14.engineering_workspace_checks import retained_candidate
        if any(change['path'] not in paths for key in candidates for change in retained_candidate(backend, local, key)):
            raise ValueError('This candidate includes files outside the worker scope')


def _isolate_context(backend, local, saved):
    """Drop ambient parent evidence; workers retrieve their own source windows.

    This marker is transient on the copied job and tells the model transport to
    suppress independent memory, conversation, graph and delegated-agent recall.
    The original acceptance contract and parent candidate state remain intact.
    """
    local['_workspace_scoped_worker'] = True
    local.pop('conversation_context', None)
    data, paths = local['data'], saved.get('paths', [])
    data.update(files={}, workspace_receipts=[], workspace_workers={}, active_instructions=[],
                plan={'paths': list(paths), 'plan': saved['objective']})
    for key in ('changes', 'local_validation', 'repair_feedback', 'progress_notes', 'workflow',
                'workflow_batch', 'ci', 'review', 'workspace_task_plan', 'workspace_summaries',
                'workspace_context_pack', 'workspace_next_edits', 'completion_verification',
                'historical_memory', 'graph_context', 'conversation_context'):
        data.pop(key, None)
    if not paths:
        return
    from v14.workspace_guidance import instruction_paths
    allowed = set(paths) | set(instruction_paths(data['tree'], paths))
    data['tree'] = {path: value for path, value in data['tree'].items() if path in allowed}
    # A partially matching requirement is still a parent responsibility. Do not
    # rewrite it into a smaller acceptance contract for the worker.
    local['request']['acceptance'] = [row for row in local['request'].get('acceptance', [])
        if isinstance(row, dict) and isinstance(row.get('paths'), list) and row['paths']
        and set(row['paths']).issubset(paths)]
    checks = {}
    for key, row in data.get('workspace_checks', {}).items():
        if not row.get('candidate_ref') or row.get('source_sha') != data['head_sha']:
            continue
        try:
            changes = backend.private_artifacts.json_value(local, row['candidate_ref'])
        except (KeyError, ValueError, OSError):
            continue
        if isinstance(changes, list) and all(isinstance(change, dict) and change.get('path') in paths for change in changes):
            checks[key] = row
    data['workspace_checks'] = checks


def advance(backend, job, action):
    parent = job['data']
    name, saved = admit(job, action)
    if saved['state'] != 'ACTIVE':
        return projection(name, saved)
    dependencies = saved.get('depends_on', [])
    if any(parent['workspace_workers'][key]['state'] != 'COMPLETED' for key in dependencies):
        return dict(projection(name, saved), waiting_for=[key for key in dependencies
                    if parent['workspace_workers'][key]['state'] != 'COMPLETED'])
    artifacts = backend.private_artifacts
    if artifacts is None:
        raise ValueError('Durable worker context requires the private artifact store')
    if saved['calls'] >= saved['budget']:
        saved['state'] = 'BUDGET_REACHED'
        return projection(name, saved)
    local = copy.deepcopy(job)
    data = local['data']
    # Never inherit another worker's observed-source edit authority.
    _isolate_context(backend, local, saved)
    role_profiles = {'planner': 'plan', 'retrieval': 'retrieve', 'investigator': 'retrieve',
                     'reviewer': 'review', 'editor': 'edit', 'implementation worker': 'edit', 'test worker': 'test'}
    data['workspace_tool_profile'] = {'profile': role_profiles[saved['role']], 'head_sha': data['head_sha'],
                                      'authority': 'DISCOVERY_FILTER_ONLY'}
    paths = saved.get('paths', [])
    if saved.get('context_ref'):
        context = artifacts.json_value(job, saved['context_ref'])
        if context.get('binding', saved.get('binding')) != saved.get('binding'):
            raise ValueError('Worker context belongs to another admitted scope')
        data['workspace_receipts'] = context['receipts']
        data['active_instructions'] = context['instructions']
        for path, exposure in context['paths'].items():
            target = load_file(backend, local, path)
            if exposure:
                target['source_exposure'] = exposure
    role = saved['role']
    evidence = view(data)
    evidence.update(worker_objective=saved['objective'], worker_role=role, scope_paths=paths,
                    acceptance_scope='WORKER_VIEW_ONLY', parent_completion_authority=False,
                    permitted_tools=sorted(ROLES[role]), route_policy='PARENT_SELECTED_ONLY',
                    remaining_calls=saved['budget'] - saved['calls'], candidate_check_id=saved.get('candidate_check_id'),
                    dependency_summaries=[{'worker_id': key, 'findings': parent['workspace_workers'][key].get('findings'),
                        'receipt_sha256': parent['workspace_workers'][key].get('last_receipt')}
                        for key in dependencies])
    # Logical attempts are bounded here. Physical model dispatches additionally
    # reserve the run-wide budget atomically in record_model_attempt.
    saved['calls'] += 1
    saved['state'] = 'CALL_PENDING'
    save_checkpoint(backend, job, 'workspace_workers', saved, member=name)
    try:
        result = backend.model(local, 'workspace specialist', evidence)
        saved['state'] = 'ACTIVE'
        if isinstance(result.get('workspace_action'), dict):
            tool = result['workspace_action']
            if tool.get('action') not in ROLES[role]:
                raise ValueError('This worker cannot use the requested action')
            if tool.get('proposal') and role not in EDIT_ROLES:
                raise ValueError('Only an implementation worker can prepare an isolated patch')
            _check_scope(backend, local, saved, tool)
            if tool.get('proposal'):
                from v14.workspace_guidance import instruction_paths
                affected = [change['path'] for change in tool['proposal']['changes']]
                if not set(instruction_paths(data['tree'], affected)).issubset(data['active_instructions']):
                    tool = dict(action='instructions', paths=affected, head_sha=parent['head_sha'])
            receipt = act(backend, local, tool)
            data['workspace_receipts'].append(receipt)
            saved['last_receipt'] = receipt['receipt_sha256']
            saved['last_action'] = receipt['action']
            saved['evidence_refs'] = list(dict.fromkeys(saved.get('evidence_refs', []) + [receipt['receipt_sha256']]))[-24:]
            reference = artifacts.put_json(job, receipt, 'worker-observation')
            saved['observation_refs'] = (saved.get('observation_refs', []) + [reference])[-24:]
            if receipt.get('check_id') in data.get('workspace_checks', {}):
                key = receipt['check_id']
                parent.setdefault('workspace_checks', {})[key] = data['workspace_checks'][key]
                parent['workspace_check_executions'] = data.get('workspace_check_executions', 0)
                # Make the exact observation available to the parent's task
                # evidence graph without copying this worker's source exposure.
                known = {item.get('receipt_sha256') for item in parent.get('workspace_receipts', [])}
                if receipt['receipt_sha256'] not in known:
                    parent.setdefault('workspace_receipts', []).append(copy.deepcopy(receipt))
                saved['candidate_check_id'] = key
                saved['test_status'] = receipt.get('status')
            context = {'binding': saved.get('binding'), 'receipts': data['workspace_receipts'][-3:],
                       'instructions': data['active_instructions'], 'paths': {
                           path: source.get('source_exposure') for path, source in data['files'].items()}}
            saved['context_ref'] = artifacts.put_json(job, context, 'worker-context')
        elif isinstance(result.get('findings'), str) and 0 < len(result['findings']) <= 3000:
            saved.update(state='COMPLETED', findings=result['findings'])
        else:
            raise ValueError('Worker must request a permitted workspace action or report bounded findings')
    except BaseException:
        saved['state'] = 'RECONCILIATION_REQUIRED' if saved['state'] == 'CALL_PENDING' else 'ACTION_FAILED'
        raise
    finally:
        if saved['state'] == 'ACTIVE' and saved['calls'] >= saved['budget']:
            saved['state'] = 'BUDGET_REACHED'
        save_checkpoint(backend, job, 'workspace_workers', saved, member=name)
    return projection(name, saved)


def wave(backend, job, action):
    """One bounded dependency wave; the normal controller owns future waves.

    Read-only workers share no mutable source dictionaries. Check/edit workers
    execute serially, retaining the parent's single exploratory execution budget.
    The existing inference scheduler decides whether the bound model can overlap.
    """
    rows = action.get('workers')
    if not isinstance(rows, list) or not 1 <= len(rows) <= 8 or any(not isinstance(row, dict) for row in rows):
        raise ValueError('A worker wave requires one to eight worker declarations or IDs')
    if len({row.get('worker_id') for row in rows}) != len(rows):
        raise ValueError('A worker may occur only once in a wave')
    staged = copy.deepcopy(job)
    names = [admit(staged, row)[0] for row in rows]
    job['data']['workspace_workers'] = staged['data']['workspace_workers']
    workers = job['data']['workspace_workers']
    ready = [name for name in names if workers[name]['state'] == 'ACTIVE' and
             all(workers[key]['state'] == 'COMPLETED' for key in workers[name].get('depends_on', []))]
    parallel = [name for name in ready if workers[name]['role'] in READ_ONLY_ROLES]
    failures = []
    def read_worker(name):
        isolated = copy.deepcopy(job)
        try:
            advance(backend, isolated, {'worker_id': name})
            return name, isolated['data']['workspace_workers'][name], None
        except Exception as error:
            return name, isolated['data']['workspace_workers'][name], error
    with ThreadPoolExecutor(max_workers=min(4, max(1, len(parallel))), thread_name_prefix='workspace-wave') as pool:
        futures = [pool.submit(copy_context().run, read_worker, name) for name in parallel]
        for future in as_completed(futures):
            name, result, error = future.result()
            workers[name] = result
            if error is not None:
                failures.append(error)
    # A failed authorization or cancellation stops new side effects. Completed
    # independent observations remain in their own contexts for reconciliation.
    if failures:
        raise failures[0]
    for name in ready:
        if name not in parallel:
            advance(backend, job, {'worker_id': name})
    return dict(workers=[projection(name, workers[name]) for name in names],
                admitted_count=len(names), advanced_count=len(ready),
                parallel_read_workers=len(parallel), publication_authority=False,
                route_policy='PARENT_SELECTED_ONLY', next_wave_required=any(workers[name]['state'] == 'ACTIVE' for name in names))


def projection(name, saved):
    return dict(worker_id=name, permitted_tools=sorted(ROLES[saved['role']]),
                **{key: value for key, value in saved.items() if key not in {'context_ref', 'binding', 'observation_refs'}})
