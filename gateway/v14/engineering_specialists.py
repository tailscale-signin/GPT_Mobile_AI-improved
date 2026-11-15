"""Bounded selected-model investigators. Suggestions carry no mutation authority."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from copy import deepcopy
import hashlib
import json
import threading

from v14.engineering_workspace import act, view
from v14.execution_binding import ExecutionBindingError


def investigate(backend, job, tasks):
    """Full Batch shares run budgets/lease, but never mutable source contexts."""
    if job['request'].get('execution_mode') != 'full':
        return []
    if not callable(backend.save_specialists):
        raise ExecutionBindingError('SPECIALIST_CHECKPOINT_REQUIRED', 'Durable investigator retention is unavailable.')
    if not 1 <= len(tasks) <= 3 or len({task['role'] for task in tasks}) != len(tasks):
        raise ValueError('Use one to three distinct bounded specialist roles')
    binding = hashlib.sha256(json.dumps([job['data']['head_sha'], tasks], sort_keys=True).encode()).hexdigest()
    saved = job['data'].get('specialists', {})
    saved = deepcopy(saved) if saved.get('binding') == binding else {'binding': binding, 'results': {}}
    lock = threading.Lock()

    def worker(task):
        role = task['role']
        if role not in {'source explorer', 'test investigator', 'failure investigator'}:
            raise ValueError('Unsupported read-only specialist role')
        local = deepcopy(job)
        local['data'].setdefault('files', {})
        local['data'].setdefault('tree', {})
        local['data'].setdefault('plan', {})
        observations = []
        for attempt in range(3):
            if backend.cancellation(local).is_set():
                raise InterruptedError('Specialist cancelled')
            evidence = {'head_sha': local['data']['head_sha'], 'task': task['evidence'],
                        'workspace': view(local['data']), 'observations': observations[-2:]}
            result = backend.model(local, role, evidence)
            if 'workspace_action' in result:
                action = result['workspace_action']
                if not isinstance(action, dict) or action.get('action') not in {'paths', 'search', 'read'}:
                    raise ValueError('Specialists may only inspect source; they cannot execute commands or edit')
                observations.append(act(backend, local, action))
                continue
            if (set(result) != {'findings', 'paths'} or not isinstance(result['findings'], list)
                    or len(result['findings']) > 12 or any(not isinstance(item, str) or len(item) > 1200 for item in result['findings'])
                    or not isinstance(result['paths'], list) or len(result['paths']) > 20
                    or any(not isinstance(path, str) or path not in local['data']['tree'] for path in result['paths'])):
                raise ValueError('Specialist findings must be bounded and cite existing source paths')
            return {'role': role, 'head_sha': local['data']['head_sha'], 'findings': result['findings'],
                    'paths': result['paths'], 'status': 'COMPLETED', 'calls': attempt + 1,
                    'execution_receipt': local['data'].get('latest_execution_receipt'), 'authority': 'SUGGESTIONS_ONLY'}
        return {'role': role, 'head_sha': local['data']['head_sha'], 'findings': [], 'paths': [],
                'status': 'LIMIT_REACHED', 'calls': 3, 'authority': 'SUGGESTIONS_ONLY'}

    with ThreadPoolExecutor(max_workers=min(2, len(tasks)), thread_name_prefix='engineering-specialist') as pool:
        pending = {pool.submit(worker, task): task['role'] for task in tasks if task['role'] not in saved['results']}
        for future in as_completed(pending):
            result = future.result()
            with lock:
                saved['results'][pending[future]] = result
                backend.save_specialists(job, saved)
    return [saved['results'][task['role']] for task in tasks]
