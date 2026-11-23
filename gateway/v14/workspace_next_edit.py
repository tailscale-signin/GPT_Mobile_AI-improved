"""Experimental local edit suggestions; normal candidate validation owns adoption."""
import copy
import difflib
from urllib.parse import urlsplit
import ipaddress

from v14.engineering_workspace import act as workspace_act, load_file, view
from v14.engineering_backend import prepare_changes
from v14.engineering_scope import accepted_scope
from v14.engineering_validation import patch_identity
from v14.execution_binding import fingerprint
from v14.workspace_checkpoint import save


def readiness(backend, job):
    selected = job.get('execution_binding') or {}
    host = urlsplit(str(getattr(getattr(backend, 'runtime', None), 'LLAMA_BASE', ''))).hostname
    try:
        local = host == 'localhost' or ipaddress.ip_address(host or '').is_loopback
    except ValueError:
        local = False
    ready = (selected.get('provider_kind') in {'LLAMA', 'OLLAMA'} and local and
             'LOCAL_ONLY' in selected.get('required_capability_policy', []) and accepted_scope(job.get('request', {})) == 'implement')
    return {'state': 'AVAILABLE' if ready else 'LOCAL_BINDING_REQUIRED', 'local_only': True,
            'model': selected.get('requested_model_id'), 'model_replaced': False, 'applies_automatically': False,
            'qualification': 'Experimental single edit; selected model must support the bounded JSON edit contract'}


def act(backend, job, action):
    if backend.private_artifacts is None:
        raise ValueError('Local next-edit suggestions require private durable artifacts')
    data = job['data']
    saved = data.setdefault('workspace_next_edits', {})
    if action['action'] == 'next_edit_validate':
        item = saved.get(action.get('next_edit_id'))
        if not item or item.get('state') != 'PROPOSED' or item['source_sha'] != data['head_sha']:
            raise ValueError('Choose a retained next edit for the exact current source')
        retained = backend.private_artifacts.json_value(job, item['proposal_ref'])
        if retained['binding'] != fingerprint(job.get('execution_binding')):
            raise ValueError('The selected local execution binding changed')
        local = copy.deepcopy(job)
        path = item['path']
        original = load_file(backend, local, path)
        if original['sha'] != retained['blob_sha']:
            raise ValueError('The next-edit source blob changed')
        original['source_exposure'] = retained['source_exposure']
        receipt = workspace_act(backend, local, {'action': 'check', 'head_sha': data['head_sha'],
                       'check_index': action.get('check_index'), 'proposal': retained['proposal']})
        data['workspace_checks'] = local['data']['workspace_checks']
        data['workspace_check_executions'] = local['data']['workspace_check_executions']
        item['candidate_check_id'] = receipt['check_id']
        item['test_status'] = receipt['status']
        save(backend, job, 'workspace_next_edits', item, member=item['next_edit_id'])
        return dict(receipt, next_edit_id=item['next_edit_id'], applies_automatically=False)
    if readiness(backend, job)['state'] != 'AVAILABLE':
        raise PermissionError('Next edit requires the selected, explicitly local-only execution binding')
    path, instruction = action.get('path'), action.get('instruction')
    if not isinstance(instruction, str) or not 1 <= len(instruction.strip()) <= 2000 or path not in data['tree']:
        raise ValueError('Choose an observed source path and a bounded edit instruction')
    selection = {key: action[key] for key in ('path', 'instruction', 'start_line', 'line_count') if key in action}
    key = 'edit_' + fingerprint([data['head_sha'], job.get('execution_binding'), selection])[:32]
    if key in saved:
        return dict(saved[key], applies_automatically=False)
    if len(saved) >= 24:
        raise ValueError('The job has reached its retained next-edit suggestion allowance')
    local = copy.deepcopy(job)
    data_local = local['data']
    data_local['files'] = {}
    observation = workspace_act(backend, local, {'action': 'read', 'head_sha': data['head_sha'], 'path': path,
                                                'start_line': action.get('start_line', 1), 'line_count': action.get('line_count', 80)})
    data_local['workspace_receipts'] = [observation]
    data_local['plan'] = {'paths': [path], 'plan': instruction}
    row = dict(next_edit_id=key, source_sha=data['head_sha'], path=path, state='CALL_PENDING',
               route_policy='SELECTED_LOCAL_ONLY', applies_automatically=False)
    saved[key] = row
    save(backend, job, 'workspace_next_edits', row, member=key)
    try:
        evidence = view(data_local)
        evidence['next_edit'] = {'path': path, 'instruction': instruction,
                                 'contract': 'Propose only the smallest anchored edit to this file; never apply or publish it'}
        result = backend.model(local, 'coding agent', evidence)
        if 'workspace_action' in result:
            row.update(state='MORE_CONTEXT_REQUIRED', next_action=result['workspace_action'].get('action'))
            return dict(row)
        proposal = {'changes': result.get('changes')}
        if not isinstance(proposal['changes'], list) or len(proposal['changes']) != 1 or proposal['changes'][0].get('path') != path:
            raise ValueError('A local next edit must propose exactly one change to its selected file')
        changes = prepare_changes(proposal, data_local['files'], data_local['tree'], head_sha=data['head_sha'])
        if not changes:
            row['state'] = 'NO_CHANGE'
            return dict(row)
        source = data_local['files'][path]
        retained = {'proposal': proposal, 'source_exposure': source.get('source_exposure'),
                    'blob_sha': source['sha'], 'binding': fingerprint(job.get('execution_binding'))}
        reference = backend.private_artifacts.put_json(job, retained, 'next-edit-proposal')
        diff = ''.join(difflib.unified_diff(source['content'].splitlines(True), (changes[0]['content'] or '').splitlines(True),
                                         fromfile=path, tofile=path))
        diff_ref = backend.private_artifacts.put_json(job, {'diff': diff, 'source_sha': data['head_sha']}, 'next-edit-diff')
        row.update(state='PROPOSED', proposal_ref=reference, diff_ref=diff_ref, diff=diff[:8000],
                   diff_complete=len(diff) <= 8000, patch_identity=patch_identity(data['head_sha'], changes), test_status='NOT_RUN')
        return dict(row)
    except BaseException:
        row['state'] = 'RECONCILIATION_REQUIRED' if row['state'] == 'CALL_PENDING' else 'FAILED'
        raise
    finally:
        save(backend, job, 'workspace_next_edits', row, member=key)
