"""Interacting repository contracts, composed from the twelve calibrated repairs.

These are disposable integration fixtures. They do not claim to execute GitHub,
CI services, credential revocation, or a production repository migration.
"""
from copy import deepcopy
import json

from benchmarks.contracts import digest


PUBLICATION = '''from core_idempotency import solve as operations
from core_ci import solve as checks
from core_evidence import solve as evidence

def handle(request):
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    rows, config = request['items'], request.get('config', {})
    ledger = operations([row['value'] for row in rows if row.get('kind') == 'operation'], {})
    ci = checks([row['value'] for row in rows if row.get('kind') == 'check'], config)
    report = evidence([row['value'] for row in rows if row.get('kind') == 'criterion'], {})
    reconciled = bool(ledger) and all(row['state'] in ('applied', 'cancelled') for row in ledger)
    proved = report.get('possible', 0) > 0 and report.get('unknown') == 0 and report.get('earned') == report.get('possible')
    ready = reconciled and ci == 'PASSED' and proved
    return {'state': 'READY' if ready else 'BLOCKED', 'operations': ledger, 'checks': ci,
            'evidence': report, 'may_publish': ready,
            'may_dispatch': not any(row['state'] == 'uncertain' for row in ledger)}
'''

PLANNING = '''from core_pagination import solve as pages
from core_budget import solve as reserve
from core_dependencies import solve as schedule

def handle(request):
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    config = request.get('config', {})
    rows = pages(request['items'], {})
    tasks = {row['id']: dict(row) for row in rows}
    needed = {key for key, row in tasks.items() if row.get('required') is True}
    pending = list(needed)
    while pending:
        key = pending.pop()
        dependencies = tasks[key].get('depends', [])
        if not isinstance(dependencies, list):
            return {'result': {'error': 'invalid_graph'}}
        for dependency in dependencies:
            if dependency not in tasks:
                return {'result': {'error': 'invalid_graph'}}
            if dependency not in needed:
                needed.add(dependency)
                pending.append(dependency)
    for key in needed:
        tasks[key]['required'] = True
    allocation = reserve(list(tasks.values()), config)
    if 'error' in allocation:
        return {'result': allocation}
    chosen = [tasks[key] for key in allocation['selected']]
    waves = schedule(chosen, config)
    if isinstance(waves, dict):
        return {'result': waves}
    return {'result': {'selected': allocation['selected'], 'used': allocation['used'], 'waves': waves}}
'''

TRANSACTION = '''from core_permissions import solve as authority
from core_ci import solve as checks
from core_patches import solve as patch

def handle(request):
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    config = request.get('config', {})
    allowed = authority(config.get('grants', []), dict(config, operation='write'))
    ci = checks(config.get('checks', []), config)
    if not allowed or ci != 'PASSED':
        return {'state': 'BLOCKED', 'reason': 'authority' if not allowed else 'checks', 'files': dict(config.get('files', {}))}
    proposed = patch(request['items'], config)
    if proposed.get('error') == 'patch_conflict':
        return {'state': 'CONFLICT', 'files': dict(config.get('files', {}))}
    return {'state': 'COMMITTED', 'files': proposed}
'''

RECOVERY = '''import json
from core_migration import solve as migrate
from core_retries import solve as retry
from core_streaming import solve as stream

def handle(request):
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    attempts, config = request['items'], request.get('config', {})
    saved = migrate(config.get('saved', []), {})
    outcome = retry(attempts, config)
    result = {'state': outcome['state'], 'dispatches': outcome['dispatches'], 'waits': outcome['waits'], 'records': saved}
    if outcome['state'] != 'COMPLETED':
        return result
    admitted = attempts[outcome['dispatches'] - 1]
    decoded = stream(admitted.get('chunks', []), {})
    try:
        records = json.loads(decoded['text'])
        if not isinstance(records, list):
            raise ValueError('records')
    except (ValueError, KeyError, TypeError):
        return dict(result, state='INVALID_STREAM')
    return dict(result, records=migrate(saved + records, {}))
'''


def build(seed):
    from benchmarks.fixtures import SPECS, VERSION
    key, sha = 'operation-' + str(seed), 'revision-' + str(seed)
    definitions = [
        ('publication', 'Reconciled publication gate', ('idempotency', 'ci', 'evidence'), PUBLICATION,
         "ready = reconciled and ci == 'PASSED' and proved", "ready = reconciled or ci == 'PASSED' or proved",
         'items contain kind operation/check/criterion and a value object. Apply the operation ledger, exact-SHA latest-attempt required checks, and unknown-aware evidence contracts. Publish only when at least one operation exists, every operation is applied or cancelled, all required checks pass, and nonempty evidence has full earned weight and no unknown. An uncertain operation forbids dispatch. Return state, operations, checks, evidence, may_publish and may_dispatch exactly as shown.'),
        ('planning', 'Paginated budget and dependency plan', ('pagination', 'budget', 'dependencies'), PLANNING,
         "tasks[key]['required'] = True", "tasks[key]['required'] = False",
         'items are pages of revisioned task records. Merge latest records first. Transitively mark every dependency of a required task as required before reserving capacity. Missing required dependencies are invalid_graph. Reserve all required costs before optional tasks. Schedule only the selected tasks into read/write dependency waves; any invalid selected graph rejects the plan. Return result with selected ids in sorted merged order, used cost and waves, or the first stage error.'),
        ('transaction', 'Authority and CI gated patch transaction', ('permissions', 'ci', 'patches'), TRANSACTION,
         "if not allowed or ci != 'PASSED':", "if not allowed and ci != 'PASSED':",
         'items are ordered anchored edits. config contains grants, principal, repository, checks, sha, required, files. Require current non-revoked exact-repository write authority AND passing latest required checks on the exact SHA before applying any edit. Deny with BLOCKED and authority reason first, otherwise checks. Any patch conflict returns CONFLICT and the entire original file map. Only a complete valid patch returns COMMITTED. Never mutate the input files.'),
        ('recovery', 'Retry, stream and message migration recovery', ('migration', 'retries', 'streaming'), RECOVERY,
         "admitted = attempts[outcome['dispatches'] - 1]", "admitted = attempts[-1]",
         'items are ordered HTTP attempt outcomes, with status/retry_after and chunks. Apply bounded retry rules; stop at the first success or permanent failure. Only the admitted successful attempt may supply newline-delimited content frames ending in done. Concatenated content is a JSON list of saved-message records. A malformed/unfinished stream returns INVALID_STREAM without changing migrated saved records. On success merge config.saved plus received records using schema-2 latest-update and exact-boolean-starred rules. Later attempts must not affect the result. Return state, dispatches, waits, records.'),
    ]
    result = []
    for index, (name, title, families, wrapper, anchor, defect, contract) in enumerate(definitions):
        sources, references, specs = {}, {}, []
        for family in families:
            spec = next(row for row in SPECS if row[0] == family)
            path = 'core_' + family + '.py'
            references[path] = spec[3]
            sources[path] = spec[3].replace(spec[4], spec[5], 1)
            specs.append(family + ': ' + spec[2])
        sources['service.py'], references['service.py'] = wrapper.replace(anchor, defect, 1), wrapper
        vectors = examples(name, key, sha)
        requirements = contract + '\n' + '\n'.join(specs)
        result.append({'id': 'capstone-' + name, 'family': 'capstone', 'title': title,
                       'problemId': 'capstone-' + name, 'seed': seed, 'capstone': True,
                       'sources': sources, 'referenceSources': references, 'vectors': vectors,
                       'requirements': requirements,
                       'manifestDigest': digest([VERSION, name, seed, sources, requirements, vectors])})
    return result


def examples(name, key, sha):
    def wrap(items, **config):
        return {'items': items, 'config': config}
    if name == 'publication':
        rows = [{'kind': 'operation', 'value': {'id': key, 'action': action}} for action in ('submit', 'dispatch', 'applied', 'submit')]
        rows += [{'kind': 'check', 'value': {'name': 'unit', 'sha': sha, 'attempt': attempt, 'status': status}} for attempt, status in ((1, 'failure'), (2, 'success'))]
        rows += [{'kind': 'criterion', 'value': {'id': 'tests', 'weight': 9, 'status': 'pass'}}]
        base = wrap(rows, sha=sha, required=['unit'])
        unknown = deepcopy(base)
        unknown['items'].append({'kind': 'criterion', 'value': {'id': 'policy', 'weight': 3, 'status': 'unknown'}})
        uncertain = deepcopy(base)
        uncertain['items'] = [row for row in uncertain['items'] if row.get('value', {}).get('action') != 'applied']
        uncertain['items'].append({'kind': 'operation', 'value': {'id': key, 'action': 'reconcile', 'found': False}})
        failed = deepcopy(base)
        failed['items'].append({'kind': 'check', 'value': {'name': 'unit', 'sha': sha, 'attempt': 3, 'status': 'skipped'}})
        stale = deepcopy(base)
        stale['config']['sha'] = 'changed-' + sha
        reconciled = deepcopy(uncertain)
        reconciled['items'].append({'kind': 'operation', 'value': {'id': key, 'action': 'reconcile', 'found': True}})
        vectors = [base, unknown, uncertain, failed, stale, reconciled]
    elif name == 'planning':
        a = {'id': 'a-read', 'revision': 1, 'cost': 3, 'required': False, 'depends': [], 'kind': 'read'}
        b = {'id': 'b-write', 'revision': 1, 'cost': 4, 'required': True, 'depends': ['a-read'], 'kind': 'write'}
        c = {'id': 'c-optional', 'revision': 1, 'cost': 5, 'required': False, 'depends': []}
        base = wrap([[a, b, c], [dict(a, revision=2, cost=2)]], capacity=6, width=2)
        overflow = deepcopy(base)
        overflow['config']['capacity'] = 5
        cyclic = deepcopy(base)
        cyclic['items'][1][0]['depends'] = ['b-write']
        missing = deepcopy(base)
        missing['items'][0][1]['depends'] = ['absent']
        parallel = wrap([[dict(a, required=True), dict(b, depends=[], kind='read')]], capacity=10, width=2)
        latest = deepcopy(base)
        latest['items'].append([dict(b, revision=3, cost=1), dict(a, revision=3, cost=1)])
        vectors = [base, overflow, cyclic, missing, parallel, latest]
    elif name == 'transaction':
        edits = [{'path': 'a.py', 'old': 'old', 'new': key}, {'path': 'b.py', 'old': 'x', 'new': 'y'}]
        config = {'principal': key, 'repository': 'owner/repo', 'sha': sha, 'required': ['unit'],
                  'files': {'a.py': 'old', 'b.py': 'x', 'keep': 'untouched'},
                  'grants': [{'principal': key, 'repository': 'owner/repo', 'scopes': ['write']}],
                  'checks': [{'name': 'unit', 'sha': sha, 'attempt': 1, 'status': 'failure'},
                             {'name': 'unit', 'sha': sha, 'attempt': 2, 'status': 'success'}]}
        base = wrap(edits, **config)
        revoked = deepcopy(base)
        revoked['config']['grants'][0]['revoked'] = True
        stale = deepcopy(base)
        stale['config']['sha'] = 'new-' + sha
        conflict = deepcopy(base)
        conflict['items'][1]['old'] = 'missing'
        ambiguous = deepcopy(base)
        ambiguous['config']['files']['a.py'] = 'old old'
        wrong_repository = deepcopy(base)
        wrong_repository['config']['repository'] = 'other/repo'
        vectors = [base, revoked, stale, conflict, ambiguous, wrong_repository]
    else:
        saved = [{'id': key, 'body': 'old', 'updated': 1, 'starred': True}]
        received = [{'id': key, 'body': 'new', 'updated': 2, 'starred': 'yes'}, {'id': 'other', 'body': 'snow 雪', 'updated': 3}]
        content = json.dumps({'content': json.dumps(received)}) + '\n{"done":true}\n'
        attempt = {'status': 200, 'chunks': [content[:7], content[7:31], content[31:]]}
        later = {'status': 200, 'chunks': [json.dumps({'content': '[]'}) + '\n{"done":true}\n']}
        base = wrap([{'status': 429, 'retry_after': 2}, attempt, later], saved=saved, now=0, deadline=20, max_attempts=3)
        permanent = deepcopy(base)
        permanent['items'][0]['status'] = 422
        deadline = deepcopy(base)
        deadline['config']['deadline'] = 2
        incomplete = deepcopy(base)
        incomplete['items'][1]['chunks'] = [json.dumps({'content': json.dumps(received)}) + '\n']
        first = wrap([attempt, later], saved=saved, deadline=20)
        duplicate_terminal = deepcopy(base)
        duplicate_terminal['items'][1]['chunks'].append('{"done":true}\n')
        vectors = [base, permanent, deadline, incomplete, first, duplicate_terminal]
    vectors.extend([{'items': [], 'config': deepcopy(vectors[0]['config'])}, {'items': 'invalid'}])
    return vectors
