"""Bounded selected-model investigators. Suggestions carry no mutation authority."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from copy import deepcopy
import hashlib
import json

from v14.engineering_workspace import act, view
from v14.execution_binding import ExecutionBindingError


ROLES = {'source explorer', 'test investigator', 'CI debugger', 'security reviewer', 'regression reviewer', 'patch reviewer'}


def investigate(backend, job, tasks):
    """Full Batch shares run budgets/lease, but never mutable source contexts."""
    if job['request'].get('execution_mode') != 'full':
        return []
    configured = job['request'].get('specialist_roles')
    if configured is not None:
        tasks = [task for task in tasks if task['role'] in configured]
        if not tasks:
            return []
    if not callable(backend.save_specialists):
        raise ExecutionBindingError('SPECIALIST_CHECKPOINT_REQUIRED', 'Durable investigator retention is unavailable.')
    if not 1 <= len(tasks) <= 6 or len({task['role'] for task in tasks}) != len(tasks):
        raise ValueError('Use one to six distinct bounded specialist roles')
    binding = hashlib.sha256(json.dumps([job['data']['head_sha'], tasks], sort_keys=True).encode()).hexdigest()
    saved = job['data'].get('specialists', {})
    saved = deepcopy(saved) if saved.get('binding') == binding else {'binding': binding, 'results': {}}

    def worker(task):
        role = task['role']
        if role not in {'source explorer', 'test investigator', 'failure investigator', 'CI debugger', 'security reviewer', 'regression reviewer', 'patch reviewer'}:
            raise ValueError('Unsupported read-only specialist role')
        local = deepcopy(job)
        local['data'].setdefault('files', {})
        local['data'].setdefault('tree', {})
        local['data'].setdefault('plan', {})
        observations, paths = [], {}
        status = 'LIMIT_REACHED'
        format_error = False
        for attempt in range(3):
            if backend.cancellation(local).is_set():
                raise InterruptedError('Specialist cancelled')
            evidence = {'head_sha': local['data']['head_sha'], 'task': task['evidence'],
                        'workspace': view(local['data']), 'observations': observations[-2:],
                        'synthesis_requested': attempt == 2,
                        'format_repair': format_error}
            try:
                result = backend.model(local, role, evidence)
                if isinstance(result, dict) and 'workspace_action' in result:
                    action = result['workspace_action']
                    if not isinstance(action, dict) or not isinstance(action.get('action'), str):
                        raise ValueError('Invalid specialist workspace action')
                    if action['action'] not in {'paths', 'search', 'read', 'repo_search', 'glob', 'symbols', 'relationships', 'explore'}:
                        status = 'ACTION_REJECTED'
                        break
                    observation = act(backend, local, action)
                    observations.append(observation)
                    observed_paths = (observation.get('paths', []) + [observation.get('path')]
                                      + [match.get('path') for match in observation.get('matches', [])])
                    for path in observed_paths:
                        if path in local['data']['tree'] and len(paths) < 20:
                            paths.setdefault(path, None)
                    continue
                if (not isinstance(result, dict) or set(result) != {'findings', 'paths'} or not isinstance(result['findings'], list)
                        or len(result['findings']) > 12 or any(not isinstance(item, str) or len(item) > 1200 for item in result['findings'])
                        or not isinstance(result['paths'], list) or len(result['paths']) > 20
                        or any(not isinstance(path, str) or path not in local['data']['tree'] for path in result['paths'])):
                    raise ValueError('Specialist findings must be bounded and cite existing source paths')
                status = 'COMPLETED'
                break
            except ExecutionBindingError:
                # Admission, ownership and capacity failures are typed stops,
                # not malformed model output that permits a format-repair call.
                raise
            except ValueError:
                if format_error or attempt == 2:
                    status = 'FORMAT_REJECTED'
                    break
                format_error = True
        from v14.inference_budget import compact_evidence
        retained, _ = compact_evidence(observations, 768, nodes=96)
        findings = result['findings'] if status == 'COMPLETED' else []
        cited = result['paths'] if status == 'COMPLETED' else []
        references = [{'path': path, 'blob_sha': local['data']['tree'][path].get('sha'), 'head_sha': local['data']['head_sha']}
                      for path in list(dict.fromkeys(cited + list(paths)))[:20]]
        return {'references': references, 'role': role, 'head_sha': local['data']['head_sha'], 'findings': findings,
                'paths': list(dict.fromkeys(cited + list(paths)))[:20], 'observations': retained,
                'status': status, 'calls': attempt + 1,
                'execution_receipt': local['data'].get('latest_execution_receipt'), 'authority': 'SUGGESTIONS_ONLY'}

    # Local bindings share the GPU; serialize unless the scheduler attests capacity.
    binding = job.get('execution_binding') or {}
    provider = binding.get('provider_kind') or job['data'].get('latest_execution_receipt', {}).get('provider_kind')
    capacity = 1 if provider in {'LLAMA', 'OLLAMA'} or job['request'].get('execution_binding_id') else 2
    with ThreadPoolExecutor(max_workers=min(capacity, len(tasks)), thread_name_prefix='engineering-specialist') as pool:
        pending = {pool.submit(worker, task): task['role'] for task in tasks if task['role'] not in saved['results']}
        for future in as_completed(pending):
            result = future.result()
            saved['results'][pending[future]] = result
            backend.save_specialists(job, saved)
    return [saved['results'][task['role']] for task in tasks]
