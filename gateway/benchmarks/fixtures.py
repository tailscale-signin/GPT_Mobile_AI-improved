"""Original executable repository tasks. Expected values stay in the host grader.

The pack contains twelve different contracts, eight independently seeded data
variants per contract and four composed, multi-module capstones. It measures this
fixture pack, not general intelligence or an external benchmark leaderboard.
"""
from copy import deepcopy
import builtins
import json
import random
from types import ModuleType

from benchmarks.contracts import PRESETS, digest
from v14.engineering_backend import blob_sha

VERSION = 'gateway-coding-fixtures-v1'
WRAPPER = '''from core import solve

def handle(request):
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    return {'result': solve(request['items'], request.get('config', {}))}
'''
BROKEN_WRAPPER = WRAPPER.replace("solve(request['items'],", "solve(request['items'][:1],")

# Each trusted reference is original harness code; candidate source is NEVER
# imported into this process. Only the isolated container executes candidates.
SPECS = (
    ('pagination', 'Merge paginated records',
     'Flatten pages in order. For each id keep the greatest integer revision (later wins ties). Return records sorted by id, preserving payload fields. Ignore malformed records.',
     '''def solve(items, config):
    latest = {}
    for page in items:
        for row in page if isinstance(page, list) else []:
            if isinstance(row, dict) and isinstance(row.get('id'), str) and type(row.get('revision')) is int:
                old = latest.get(row['id'])
                if old is None or row['revision'] >= old['revision']:
                    latest[row['id']] = dict(row)
    return [latest[key] for key in sorted(latest)]
''', "row['revision'] >= old['revision']", "row['revision'] < old['revision']"),
    ('idempotency', 'Reconcile uncertain operations',
     'Process events keyed by id. submit creates pending once; dispatch changes pending to uncertain; applied changes pending/uncertain to applied; cancel changes pending to cancelled. Terminal applied/cancelled never change. reconcile with found=true changes uncertain to applied; false leaves uncertain. Return sorted id/state records.',
     '''def solve(items, config):
    states = {}
    for event in items:
        if not isinstance(event, dict) or not isinstance(event.get('id'), str):
            continue
        key, action = event['id'], event.get('action')
        state = states.get(key)
        if action == 'submit' and state is None:
            states[key] = 'pending'
        elif action == 'dispatch' and state == 'pending':
            states[key] = 'uncertain'
        elif action == 'applied' and state in ('pending', 'uncertain'):
            states[key] = 'applied'
        elif action == 'cancel' and state == 'pending':
            states[key] = 'cancelled'
        elif action == 'reconcile' and state == 'uncertain' and event.get('found') is True:
            states[key] = 'applied'
    return [{'id': key, 'state': states[key]} for key in sorted(states)]
''', "action == 'submit' and state is None", "action == 'submit'"),
    ('dependencies', 'Schedule dependency waves',
     'Tasks have unique string ids, a depends list, and kind read/write. At each wave sort ready ids. Emit up to config.width reads, but a write is alone and only chosen when it sorts first. Reject duplicate ids, missing dependencies and cycles with {error:invalid_graph}. width is an integer 1..4 (default 2), booleans invalid.',
     '''def solve(items, config):
    width = config.get('width', 2)
    if type(width) is not int or not 1 <= width <= 4:
        return {'error': 'invalid_graph'}
    tasks = {}
    for row in items:
        if not isinstance(row, dict) or not isinstance(row.get('id'), str) or row['id'] in tasks or not isinstance(row.get('depends', []), list):
            return {'error': 'invalid_graph'}
        tasks[row['id']] = row
    if any(not isinstance(dep, str) or dep not in tasks for row in tasks.values() for dep in row.get('depends', [])):
        return {'error': 'invalid_graph'}
    done, waves = set(), []
    while len(done) < len(tasks):
        ready = sorted(key for key, row in tasks.items() if key not in done and set(row.get('depends', [])) <= done)
        if not ready:
            return {'error': 'invalid_graph'}
        selected = [ready[0]] if tasks[ready[0]].get('kind') == 'write' else [key for key in ready if tasks[key].get('kind') != 'write'][:width]
        waves.append(selected)
        done.update(selected)
    return waves
''', "set(row.get('depends', [])) <= done", "True"),
    ('budget', 'Reserve required schemas',
     'config.capacity is a nonnegative integer, not boolean. Each item has unique id, nonnegative integer cost and boolean required. Admit all required items first or return {error:required_overflow}. Then admit optional items in input order if each fits remaining capacity. Return selected ids in original order and used total; invalid input returns {error:invalid_budget}.',
     '''def solve(items, config):
    capacity = config.get('capacity')
    if type(capacity) is not int or capacity < 0:
        return {'error': 'invalid_budget'}
    seen = set()
    for row in items:
        if not isinstance(row, dict) or not isinstance(row.get('id'), str) or row['id'] in seen or type(row.get('cost')) is not int or row['cost'] < 0 or type(row.get('required')) is not bool:
            return {'error': 'invalid_budget'}
        seen.add(row['id'])
    chosen = {row['id'] for row in items if row['required']}
    used = sum(row['cost'] for row in items if row['required'])
    if used > capacity:
        return {'error': 'required_overflow'}
    for row in items:
        if not row['required'] and used + row['cost'] <= capacity:
            chosen.add(row['id'])
            used += row['cost']
    return {'selected': [row['id'] for row in items if row['id'] in chosen], 'used': used}
''', "if row['required']}", "if not row['required']}"),
    ('streaming', 'Assemble split protocol frames',
     'Concatenate string chunks, parse newline-delimited JSON object frames. content must be string; append in order. A single final {done:true} is mandatory, may have trailing whitespace, and no later frames. Reject malformed, unfinished or duplicate terminal data with {error:invalid_stream}.',
     '''import json
def solve(items, config):
    if any(not isinstance(item, str) for item in items):
        return {'error': 'invalid_stream'}
    text, done = '', False
    try:
        for line in ''.join(items).splitlines():
            if not line.strip():
                continue
            frame = json.loads(line)
            if done or not isinstance(frame, dict):
                return {'error': 'invalid_stream'}
            if frame == {'done': True}:
                done = True
            elif set(frame) == {'content'} and isinstance(frame['content'], str):
                text += frame['content']
            else:
                return {'error': 'invalid_stream'}
    except (ValueError, TypeError):
        return {'error': 'invalid_stream'}
    return {'text': text} if done else {'error': 'invalid_stream'}
''', "''.join(items).splitlines()", "items"),
    ('migration', 'Migrate saved messages',
     'Records have id and body strings, integer updated timestamp. Keep highest timestamp per id; last wins tie. Preserve id/body/updated, add starred only when input is exactly true, and schema=2. Ignore invalid records. Return updated descending, id ascending for ties. Never modify input.',
     '''def solve(items, config):
    records = {}
    for row in items:
        if not isinstance(row, dict) or not isinstance(row.get('id'), str) or not isinstance(row.get('body'), str) or type(row.get('updated')) is not int:
            continue
        old = records.get(row['id'])
        if old is None or row['updated'] >= old['updated']:
            records[row['id']] = {'id': row['id'], 'body': row['body'], 'updated': row['updated'], 'starred': row.get('starred') is True, 'schema': 2}
    return sorted(records.values(), key=lambda row: (-row['updated'], row['id']))
''', "row.get('starred') is True", "bool(row.get('starred'))"),
    ('patches', 'Apply atomic anchored edits',
     'config.files maps safe relative paths to source strings. Each edit has path, old, new. Paths cannot start /, contain backslash, or empty/dot/dotdot/.git components. Every old anchor must be nonempty and match once. Multiple edits apply in order; any failure returns {error:patch_conflict} and no partial output. Return final file map, preserving untouched files.',
     '''def solve(items, config):
    files = config.get('files')
    if not isinstance(files, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in files.items()):
        return {'error': 'patch_conflict'}
    result = dict(files)
    for row in items:
        if not isinstance(row, dict):
            return {'error': 'patch_conflict'}
        path, old, new = row.get('path'), row.get('old'), row.get('new')
        if not isinstance(path, str) or path.startswith('/') or chr(92) in path or any(part in ('', '.', '..', '.git') for part in path.split('/')) or path not in result or not isinstance(old, str) or not old or not isinstance(new, str) or result[path].count(old) != 1:
            return {'error': 'patch_conflict'}
        result[path] = result[path].replace(old, new, 1)
    return result
''', "result[path].count(old) != 1", "result[path].count(old) < 1"),
    ('ci', 'Verify exact-revision checks',
     'config.sha is the target revision, config.required is a list of distinct required check names. For that SHA choose greatest integer attempt per name (later wins tie). All required success => PASSED. Missing check or pending result => PENDING. Any required latest failure/cancelled/skipped => FAILED. Ignore other checks/revisions. Empty required list => BLOCKED.',
     '''def solve(items, config):
    required = config.get('required', [])
    if not isinstance(required, list) or not required or any(not isinstance(x, str) for x in required) or len(set(required)) != len(required):
        return 'BLOCKED'
    latest = {}
    for row in items:
        if not isinstance(row, dict) or row.get('sha') != config.get('sha') or row.get('name') not in required or type(row.get('attempt')) is not int:
            continue
        old = latest.get(row['name'])
        if old is None or row['attempt'] >= old['attempt']:
            latest[row['name']] = row
    if any(row.get('status') in ('failure', 'cancelled', 'skipped') for row in latest.values()):
        return 'FAILED'
    if any(name not in latest or latest[name].get('status') != 'success' for name in required):
        return 'PENDING'
    return 'PASSED'
''', "row['attempt'] >= old['attempt']", "row['attempt'] < old['attempt']"),
    ('retries', 'Plan bounded retry deadlines',
     'Process outcomes in order starting at config.now. success ends COMPLETED. Permanent status (anything except 429/503) ends FAILED. 429/503 wait max(2**attempt, nonnegative retry_after) seconds. If next attempt time >= config.deadline return DEADLINE without another dispatch. At most config.max_attempts calls. Return state, dispatches, waits. Missing max_attempts defaults 3.',
     '''def solve(items, config):
    now, deadline = config.get('now', 0), config.get('deadline', 100)
    maximum, waits, calls = config.get('max_attempts', 3), [], 0
    for row in items:
        if calls >= maximum or now >= deadline:
            return {'state': 'DEADLINE', 'dispatches': calls, 'waits': waits}
        calls += 1
        status = row.get('status')
        if status == 200:
            return {'state': 'COMPLETED', 'dispatches': calls, 'waits': waits}
        if status not in (429, 503):
            return {'state': 'FAILED', 'dispatches': calls, 'waits': waits}
        delay = max(2 ** (calls - 1), max(0, row.get('retry_after', 0)))
        if calls >= maximum or now + delay >= deadline:
            return {'state': 'DEADLINE', 'dispatches': calls, 'waits': waits}
        waits.append(delay)
        now += delay
    return {'state': 'INCOMPLETE', 'dispatches': calls, 'waits': waits}
''', "status not in (429, 503)", "status == 0"),
    ('permissions', 'Resolve repository authority',
     'Items are grants with principal, repository, scopes (list), revoked boolean. Merge only non-revoked exact-principal/exact-repository grants. config.operation read requires read or write; write requires write. Unknown operations deny. A grant with revoked=true contributes nothing; case is significant. Return boolean.',
     '''def solve(items, config):
    scopes = set()
    for row in items:
        if isinstance(row, dict) and row.get('principal') == config.get('principal') and row.get('repository') == config.get('repository') and row.get('revoked') is not True and isinstance(row.get('scopes'), list):
            scopes.update(scope for scope in row['scopes'] if isinstance(scope, str))
    operation = config.get('operation')
    return bool(scopes & {'read', 'write'}) if operation == 'read' else 'write' in scopes if operation == 'write' else False
''', "row.get('revoked') is not True", "True"),
    ('evidence', 'Score incomplete evidence honestly',
     'Each criterion has unique id, positive integer weight and status pass/fail/unknown. Return earned, possible, unknown and score (100*earned/possible only with all graded, otherwise null). Invalid criteria return {error:invalid_evidence}; empty criteria have score null. Boolean weights invalid.',
     '''def solve(items, config):
    seen, earned, possible, unknown = set(), 0, 0, 0
    for row in items:
        if not isinstance(row, dict) or not isinstance(row.get('id'), str) or row['id'] in seen or type(row.get('weight')) is not int or row['weight'] <= 0 or row.get('status') not in ('pass', 'fail', 'unknown'):
            return {'error': 'invalid_evidence'}
        seen.add(row['id'])
        possible += row['weight']
        earned += row['weight'] if row['status'] == 'pass' else 0
        unknown += row['weight'] if row['status'] == 'unknown' else 0
    return {'earned': earned, 'possible': possible, 'unknown': unknown, 'score': 100 * earned / possible if possible and not unknown else None}
''', "if possible and not unknown", "if possible"),
    ('graph', 'Find a constrained dependency route',
     'Directed edges have from/to strings and nonnegative integer cost (booleans invalid). Return minimum cost from config.start to config.end, or null if unreachable. Duplicate edges allowed; start=end costs 0. Any invalid edge returns {error:invalid_graph}. Zero-cost cycles must terminate.',
     '''import heapq
def solve(items, config):
    edges = {}
    for row in items:
        if not isinstance(row, dict) or not isinstance(row.get('from'), str) or not isinstance(row.get('to'), str) or type(row.get('cost')) is not int or row['cost'] < 0:
            return {'error': 'invalid_graph'}
        edges.setdefault(row['from'], []).append((row['to'], row['cost']))
    start, end = config.get('start'), config.get('end')
    distance, queue = {start: 0}, [(0, start)]
    while queue:
        cost, node = heapq.heappop(queue)
        if cost != distance[node]:
            continue
        if node == end:
            return cost
        for target, weight in edges.get(node, []):
            candidate = cost + weight
            if candidate < distance.get(target, float('inf')):
                distance[target] = candidate
                heapq.heappush(queue, (candidate, target))
    return None
''', "candidate = cost + weight", "candidate = weight"),
)


def reference(family, request):
    spec = next(x for x in SPECS if x[0] == family)
    namespace = {}
    exec(compile(spec[3], '<trusted-benchmark-reference>', 'exec'), namespace)
    if not isinstance(request, dict) or not isinstance(request.get('items'), list):
        return {'error': 'invalid_request'}
    return {'result': namespace['solve'](deepcopy(request['items']), deepcopy(request.get('config', {})))}


def examples(family, seed):
    """Diverse hidden values, including invalid and regression boundaries."""
    rng = random.Random(seed)
    a, b = rng.randint(3, 20), rng.randint(21, 50)
    key = 'item-' + str(seed % 997)
    cases = []
    def add(items, **config):
        cases.append({'items': items, 'config': config})
    if family == 'pagination':
        add([[{'id': key, 'revision': a, 'body': 'old'}, {'id': 'b', 'revision': b}], [{'id': key, 'revision': b, 'body': 'new'}]])
        add([[], [{'id': key, 'revision': True}, {'id': key, 'revision': a}], [{'id': key, 'revision': a, 'body': 'tie'}]])
        add([None, [{'id': 'z', 'revision': -2}, {'id': 'a', 'revision': 0}]])
    elif family == 'idempotency':
        add([{'id': key, 'action': action} for action in ('submit', 'dispatch', 'applied', 'submit', 'cancel')])
        add([{'id': key, 'action': 'submit'}, {'id': key, 'action': 'dispatch'}, {'id': key, 'action': 'reconcile', 'found': False}, {'id': key, 'action': 'reconcile', 'found': True}])
        add([{'id': 'b', 'action': 'submit'}, {'id': 'b', 'action': 'cancel'}, {'id': 'b', 'action': 'dispatch'}, {'id': 'a', 'action': 'submit'}])
    elif family == 'dependencies':
        add([{'id': 'c', 'depends': ['a', 'b']}, {'id': 'b', 'depends': []}, {'id': 'a', 'depends': []}], width=2)
        add([{'id': 'a', 'kind': 'write'}, {'id': 'b'}, {'id': 'c', 'depends': ['a']}], width=3)
        add([{'id': 'a', 'depends': ['b']}, {'id': 'b', 'depends': ['a']}], width=2)
        add([{'id': 'a', 'depends': ['missing']}], width=1)
        add([{'id': 'a'}], width=True)
    elif family == 'budget':
        add([{'id': 'optional', 'cost': b, 'required': False}, {'id': 'needed', 'cost': a, 'required': True}, {'id': 'small', 'cost': 1, 'required': False}], capacity=b)
        add([{'id': 'a', 'cost': a, 'required': True}], capacity=a - 1)
        add([{'id': 'a', 'cost': True, 'required': True}], capacity=10)
        add([{'id': 'a', 'cost': 0, 'required': True}, {'id': 'b', 'cost': 0, 'required': False}], capacity=0)
    elif family == 'streaming':
        stream = json.dumps({'content': key}) + '\n' + json.dumps({'content': ' snow 雪'}) + '\n{"done":true}\n'
        split = rng.randint(1, len(stream) - 2)
        add([stream[:split], stream[split:]])
        add(['{"content":"x"}\n'])
        add(['{"done":true}\n', '{"done":true}\n'])
        add(['[1,2]\n', '{"done":true}\n'])
    elif family == 'migration':
        add([{'id': key, 'body': 'old', 'updated': a}, {'id': key, 'body': 'new', 'updated': b, 'starred': 'yes'}, {'id': 'a', 'body': 'other', 'updated': b, 'starred': True}])
        add([{'id': 'bad', 'body': 'bad', 'updated': True}, {'id': 'ok', 'body': '', 'updated': 0, 'starred': 1}])
        add([{'id': key, 'body': 'old', 'updated': a}, {'id': key, 'body': 'tie', 'updated': a}])
    elif family == 'patches':
        add([{'path': 'a.py', 'old': 'old', 'new': key}, {'path': 'b.py', 'old': 'x', 'new': 'y'}], files={'a.py': 'old end', 'b.py': 'x', 'keep.txt': 'keep'})
        add([{'path': 'a.py', 'old': 'x', 'new': 'y'}], files={'a.py': 'x x'})
        add([{'path': '../a.py', 'old': 'x', 'new': 'y'}], files={'../a.py': 'x'})
        add([{'path': 'a.py', 'old': 'x', 'new': 'y'}, {'path': 'a.py', 'old': 'missing', 'new': 'z'}], files={'a.py': 'x'})
    elif family == 'ci':
        add([{'name': 'unit', 'sha': key, 'attempt': 1, 'status': 'failure'}, {'name': 'unit', 'sha': key, 'attempt': 2, 'status': 'success'}, {'name': 'lint', 'sha': key, 'attempt': 1, 'status': 'success'}], sha=key, required=['unit', 'lint'])
        add([{'name': 'unit', 'sha': 'other', 'attempt': 9, 'status': 'success'}], sha=key, required=['unit'])
        add([{'name': 'unit', 'sha': key, 'attempt': 1, 'status': 'success'}, {'name': 'unit', 'sha': key, 'attempt': 2, 'status': 'skipped'}], sha=key, required=['unit'])
        add([], sha=key, required=[])
    elif family == 'retries':
        add([{'status': 429, 'retry_after': a}, {'status': 503}, {'status': 200}], now=0, deadline=b + a, max_attempts=3)
        add([{'status': 422}, {'status': 200}], deadline=100)
        add([{'status': 429, 'retry_after': b}, {'status': 200}], deadline=b)
        add([{'status': 503}, {'status': 200}], deadline=100, max_attempts=1)
    elif family == 'permissions':
        add([{'principal': key, 'repository': 'a/b', 'scopes': ['write'], 'revoked': True}, {'principal': key, 'repository': 'a/b', 'scopes': ['read']}], principal=key, repository='a/b', operation='write')
        add([{'principal': key, 'repository': 'a/b', 'scopes': ['read']}, {'principal': key, 'repository': 'a/b', 'scopes': ['write']}], principal=key, repository='a/b', operation='write')
        add([{'principal': key, 'repository': 'a/B', 'scopes': ['write']}], principal=key, repository='a/b', operation='read')
    elif family == 'evidence':
        add([{'id': 'a', 'weight': a, 'status': 'pass'}, {'id': 'b', 'weight': b, 'status': 'unknown'}])
        add([{'id': 'a', 'weight': a, 'status': 'pass'}, {'id': 'b', 'weight': b, 'status': 'fail'}])
        add([{'id': 'a', 'weight': True, 'status': 'pass'}])
        add([{'id': 'a', 'weight': 1, 'status': 'pass'}, {'id': 'a', 'weight': 1, 'status': 'pass'}])
    else:
        add([{'from': 's', 'to': 'a', 'cost': a}, {'from': 'a', 'to': 't', 'cost': b}, {'from': 's', 'to': 't', 'cost': a + b + 1}], start='s', end='t')
        add([{'from': 's', 'to': 'a', 'cost': 0}, {'from': 'a', 'to': 's', 'cost': 0}], start='s', end='t')
        add([{'from': 's', 'to': 't', 'cost': True}], start='s', end='t')
        add([], start='s', end='s')
    # Shared wrapper and unchanged-input boundaries are independent of the
    # family-specific core defect; normal multi-record fixtures force both files.
    cases.append({'items': [], 'config': cases[0].get('config', {})})
    cases.append({'items': 'invalid'})
    return cases


def task(family, seed, variant=0):
    spec = next(x for x in SPECS if x[0] == family)
    broken = spec[3].replace(spec[4], spec[5], 1)
    sources = {'core.py': broken, 'service.py': BROKEN_WRAPPER}
    return {'id': family + '-' + str(variant + 1), 'problemId': family, 'family': family, 'title': spec[1],
            'seed': seed, 'capstone': False, 'requirements': spec[2], 'sources': sources,
            'referenceSources': {'core.py': spec[3], 'service.py': WRAPPER},
            'vectors': examples(family, seed), 'manifestDigest': digest([VERSION, family, seed, sources, spec[2]])}


def suite(preset='quick', seed=17):
    if preset not in PRESETS:
        raise ValueError('Unknown coding preset')
    result = [task(spec[0], seed + family_index * 1009 + variant * 7919, variant)
              for family_index, spec in enumerate(SPECS) for variant in range(PRESETS[preset])]
    if preset == 'deep':
        from benchmarks.capstones import build
        result.extend(build(seed + 100000))
    return result


def trusted_fixture(task_value, vector, *, broken=False):
    """Only shipped reference/baseline sources enter this host interpreter.

    Never accept candidate source here. grading sends candidates to sandbox.execute.
    Local modules are bound per invocation so repeated trials cannot share state.
    """
    sources = task_value['sources'] if broken else task_value['referenceSources']
    modules = {}
    def local_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name + '.py' not in sources:
            return builtins.__import__(name, globals, locals, fromlist, level)
        if name not in modules:
            module = ModuleType(name)
            module.__dict__['__builtins__'] = dict(vars(builtins), __import__=local_import)
            modules[name] = module
            exec(compile(sources[name + '.py'], '<trusted-benchmark-' + name + '>', 'exec'), module.__dict__)
        return modules[name]
    try:
        return local_import('service').handle(deepcopy(vector))
    except Exception as error:
        if broken:
            return {'error': 'BASELINE_' + type(error).__name__}
        raise


def expected(task_value, vector):
    return trusted_fixture(task_value, vector) if task_value['capstone'] else reference(task_value['family'], vector)


def prompt(task_value):
    files = '\n\n'.join('FILE ' + path + '\nBLOB ' + blob_sha(source) + '\n' + source
                          for path, source in task_value['sources'].items())
    return ('Repair this isolated repository task. Fix the contract in every relevant file.\n'
            + task_value['requirements'] + '\nThe service.handle(request) wrapper must pass all items, preserve caller input, and return error invalid_request for non-list items.\n'
            + files + '\nReturn one JSON object, no Markdown. Format: '
            '{"changes":[{"path":"core.py","expected_blob_sha":"exact displayed BLOB","edits":[{"old":"unique source anchor","new":"replacement"}]}],'
            '"diagnosis":{"path.py":"exact buggy source line"},'
            '"tests":[{"input":{"items":[],"config":{}},"expected":{"result":null}}],'
            '"evidence":{"tests_executed":false,"changed_files":["core.py"]}}. '
            'Only edit displayed files. Supply three to six substantive regression input/expected vectors. '
            'For capstones use the integrated service contract. You have not executed tests; do not claim execution. '
            'The evaluator is separate and expected hidden answers are not available. Preserve function interfaces.')
