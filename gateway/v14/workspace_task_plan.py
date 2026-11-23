"""Dependency-ready work packages bound to accepted criteria and actual evidence.

This plan is an execution aid. It never replaces the final completion/merge gate.
"""
import copy
import re

from v14.execution_binding import fingerprint
from v14.workspace_process import identifier
from v14.workspace_workers import ROLES


def identity(job):
    return fingerprint([job.get('principal'), job['data']['head_sha'], job['request'].get('acceptance', []),
                        job['data'].get('admission', {}).get('policy_sha256')])


def status(job):
    data = job['data']
    plan = data.get('workspace_task_plan')
    if not plan:
        return {'state': 'NOT_PLANNED', 'items': [], 'completed': 0, 'total': 0, 'completion_authority': False}
    current = plan['binding'] == identity(job)
    rows = []
    for item in plan['items']:
        dependencies = [row for row in plan['items'] if row['id'] in item['depends_on']]
        state = 'STALE' if not current else item['state']
        rows.append(dict(item, state=state, ready=current and state == 'PENDING' and
                         all(row['state'] == 'EVIDENCE_OBSERVED' for row in dependencies)))
    return {'state': 'CURRENT' if current else 'STALE', 'head_sha': plan['head_sha'],
            'items': rows, 'completed': sum(row['state'] == 'EVIDENCE_OBSERVED' for row in rows),
            'total': len(rows), 'completion_authority': False,
            'coverage': 'Observed mapped behavior cases; final exact-patch and publication gates remain required'}


def act(job, action):
    data = job['data']
    accepted = {row['id']: row for row in job.get('request', {}).get('acceptance', [])}
    if action['action'] == 'task_plan':
        values = action.get('items')
        if not accepted or not isinstance(values, list) or not 1 <= len(values) <= 80:
            raise ValueError('Plan one to eighty work packages from existing accepted requirement IDs')
        rows, seen = [], set()
        for value in values:
            if not isinstance(value, dict) or set(value) - {'id', 'requirement_ids', 'depends_on', 'paths', 'role'}:
                raise ValueError('A task needs an ID, accepted requirement IDs, dependencies, role and observed paths')
            name = identifier(value.get('id'))
            requirements, dependencies, paths = value.get('requirement_ids'), value.get('depends_on', []), value.get('paths', [])
            role = value.get('role', 'editor')
            if name in seen or role not in ROLES:
                raise ValueError('Choose unique task IDs and a supported worker role')
            for selected, allowed, maximum in ((requirements, accepted, 32), (dependencies, seen, 16), (paths, data['tree'], 60)):
                if (not isinstance(selected, list) or len(selected) > maximum or
                        any(not isinstance(item, str) or item not in allowed for item in selected) or
                        len(selected) != len(set(selected))):
                    raise ValueError('Task references must be distinct, observed and within the accepted scope')
            if not requirements:
                raise ValueError('Every work package must map to at least one accepted requirement')
            rows.append(dict(id=name, requirement_ids=requirements, depends_on=dependencies, paths=paths,
                             role=role, state='PENDING', receipt_ids=[]))
            seen.add(name)
        if set(accepted) - {key for row in rows for key in row['requirement_ids']}:
            raise ValueError('Every accepted requirement must remain covered by the task plan')
        plan = dict(head_sha=data['head_sha'], binding=identity(job), items=rows)
        previous = data.get('workspace_task_plan')
        if previous:
            shape = lambda item: {key: item[key] for key in ('id', 'requirement_ids', 'depends_on', 'paths', 'role')}
            if previous['binding'] != plan['binding'] or list(map(shape, previous['items'])) != list(map(shape, rows)):
                raise ValueError('The existing task plan has different scope; start a new revision-bound job')
        else:
            data['workspace_task_plan'] = plan
    elif action['action'] == 'task_plan_complete':
        plan = data.get('workspace_task_plan')
        if not plan or plan['binding'] != identity(job):
            raise ValueError('A current accepted task plan is required')
        row = next((item for item in plan['items'] if item['id'] == action.get('id')), None)
        references = action.get('receipt_sha256')
        if not row or not isinstance(references, list) or not 1 <= len(references) <= 16 or any(not isinstance(ref, str) for ref in references):
            raise ValueError('Name a task and one to sixteen exact current workspace check receipts')
        if row['state'] == 'EVIDENCE_OBSERVED':
            if references != row['receipt_ids']:
                raise ValueError('This task already has different observed evidence')
            return status(job)
        if any(item['state'] != 'EVIDENCE_OBSERVED' for item in plan['items'] if item['id'] in row['depends_on']):
            raise ValueError('Observe the dependency evidence before completing this work package')
        receipts = {item.get('receipt_sha256'): item for item in data.get('workspace_receipts', [])}
        named_cases, patches = set(), set()
        for reference in references:
            receipt = receipts.get(reference)
            check = data.get('workspace_checks', {}).get(receipt.get('check_id')) if receipt else None
            if (not receipt or receipt.get('head_sha') != data['head_sha'] or not check or
                    check.get('source_sha') != data['head_sha'] or check.get('status') != 'PASSED' or
                    check.get('policy_sha256') != data.get('admission', {}).get('policy_sha256') or
                    check.get('patch_identity') != receipt.get('patch_identity')):
                raise ValueError('Completion requires an actual passing check on the exact source, patch and policy')
            patches.add(check['patch_identity'])
            named_cases.update(case['name'] for case in check.get('case_results', [])
                               if case.get('status') == 'PASSED' and isinstance(case.get('report_sha256'), str)
                               and re.fullmatch('[a-f0-9]{64}', case['report_sha256']))
        if len(patches) != 1:
            raise ValueError('All task evidence must describe one exact candidate patch')
        expected = set()
        for key in row['requirement_ids']:
            specs = accepted[key].get('evidence', [])
            if not specs or any(spec.get('kind') != 'behavior_test' for spec in specs):
                raise ValueError('This requirement has no supported executable behavior evidence mapping')
            expected.update(spec['name'] for spec in specs)
        if not expected <= named_cases:
            raise ValueError('The retained check receipts do not cover every mapped behavior case')
        row.update(state='EVIDENCE_OBSERVED', receipt_ids=copy.deepcopy(references), observed_cases=sorted(expected), observed_patch_identity=next(iter(patches)))
    elif action['action'] != 'task_plan_status':
        raise ValueError('Unknown task plan action')
    return status(job)
