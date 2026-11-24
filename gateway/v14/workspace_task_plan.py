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
    active_patch = None
    if data.get('changes'):
        from v14.engineering_validation import patch_identity
        active_patch = patch_identity(data['head_sha'], data['changes'])
    rows, observed = [], set()
    for item in plan['items']:
        state = item['state'] if current else 'STALE'
        dependencies_observed = observed.issuperset(item['depends_on'])
        if state == 'EVIDENCE_OBSERVED' and (not dependencies_observed or
                active_patch is not None and item.get('observed_patch_identity') != active_patch):
            state = 'PENDING'
        if state == 'EVIDENCE_OBSERVED':
            observed.add(item['id'])
        rows.append(dict(item, state=state, ready=state == 'PENDING' and dependencies_observed))
    return {'state': 'CURRENT' if current else 'STALE', 'head_sha': plan['head_sha'],
            'items': rows, 'completed': sum(row['state'] == 'EVIDENCE_OBSERVED' for row in rows),
            'total': len(rows), 'completion_authority': False,
            'coverage': 'Observed mapped behavior cases; final exact-patch and publication gates remain required'}


def observed_check(data, receipt):
    if not receipt or receipt.get('head_sha') != data['head_sha']:
        return None
    check = data.get('workspace_checks', {}).get(receipt.get('check_id'))
    policy = data.get('admission', {}).get('policy_sha256')
    if (not check or not policy or check.get('source_sha') != data['head_sha'] or check.get('status') != 'PASSED'
            or check.get('policy_sha256') != policy or not check.get('patch_identity')
            or check['patch_identity'] != receipt.get('patch_identity')):
        return None
    cases = {case['name'] for case in check.get('case_results', [])
             if case.get('status') == 'PASSED' and isinstance(case.get('report_sha256'), str)
             and re.fullmatch('[a-f0-9]{64}', case['report_sha256'])}
    return check['patch_identity'], cases


def required_cases(accepted, row):
    expected = set()
    for key in row['requirement_ids']:
        specs = accepted[key].get('evidence', [])
        if not specs or any(spec.get('kind') != 'behavior_test' for spec in specs):
            return set()
        expected.update(spec['name'] for spec in specs)
    return expected


def next_work(job):
    """Choose a ready package from the accepted plan without giving the model additional operations."""
    plan = status(job)
    ready = [item for item in plan['items'] if item['ready']]
    if not ready:
        return None
    accepted = {row['id']: row for row in job['request']['acceptance']}
    data = job['data']
    observations = {}
    for receipt in data.get('workspace_receipts', []):
        observation = observed_check(data, receipt)
        reference = receipt.get('receipt_sha256')
        if observation and reference:
            patch, cases = observation
            observations.setdefault(patch, []).append((reference, cases))
    if data.get('changes'):
        from v14.engineering_validation import patch_identity
        patch = patch_identity(data['head_sha'], data['changes'])
        observations = {patch: observations.get(patch, [])}
    children = {item['id']: 0 for item in ready}
    for item in plan['items']:
        for dependency in item['depends_on']:
            if dependency in children:
                children[dependency] += 1
    decisions = []
    for item in ready:
        expected = required_cases(accepted, item)
        shortest = None
        for evidence in observations.values():
            remaining, references = expected.copy(), []
            for reference, cases in reversed(evidence):
                if remaining.intersection(cases):
                    remaining.difference_update(cases)
                    references.append(reference)
            if expected and not remaining and len(references) <= 16 and (shortest is None or len(references) < len(shortest)):
                shortest = references
        decision = {'id': item['id'], 'requirement_ids': item['requirement_ids'],
                    'head_sha': data['head_sha'], 'completion_authority': False}
        if shortest is not None:
            decision.update(reason='RETAINED_BEHAVIOR_EVIDENCE', action='task_plan_complete', receipt_sha256=shortest)
        else:
            decision.update(reason='READY_DEPENDENCIES', role=item['role'], paths=item['paths'][:4],
                            evidence_cases=sorted(expected)[:8])
        priority = (shortest is None, -children[item['id']], -len(item['requirement_ids']), len(item['paths']), item['id'])
        decisions.append((priority, decision))
    return min(decisions, key=lambda candidate: candidate[0])[1]


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
        effective = next(item for item in status(job)['items'] if item['id'] == row['id'])
        if effective['state'] == 'EVIDENCE_OBSERVED':
            if references != row['receipt_ids']:
                raise ValueError('This task already has different observed evidence')
            return status(job)
        if not effective['ready']:
            raise ValueError('Observe the dependency evidence before completing this work package')
        receipts = {item.get('receipt_sha256'): item for item in data.get('workspace_receipts', [])}
        named_cases, patches = set(), set()
        for reference in references:
            receipt = receipts.get(reference)
            observation = observed_check(data, receipt)
            if observation is None:
                raise ValueError('Completion requires an actual passing check on the exact source, patch and policy')
            patch, cases = observation
            patches.add(patch)
            named_cases.update(cases)
        if len(patches) != 1:
            raise ValueError('All task evidence must describe one exact candidate patch')
        if data.get('changes'):
            from v14.engineering_validation import patch_identity
            if patch_identity(data['head_sha'], data['changes']) not in patches:
                raise ValueError('Task evidence belongs to an earlier candidate patch')
        expected = required_cases(accepted, row)
        if not expected:
            raise ValueError('This requirement has no supported executable behavior evidence mapping')
        if not expected <= named_cases:
            raise ValueError('The retained check receipts do not cover every mapped behavior case')
        row.update(state='EVIDENCE_OBSERVED', receipt_ids=copy.deepcopy(references), observed_cases=sorted(expected), observed_patch_identity=next(iter(patches)))
    elif action['action'] != 'task_plan_status':
        raise ValueError('Unknown task plan action')
    return status(job)
