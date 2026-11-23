"""Durable two-model comparisons owned by the desktop, executed by Gateway.

An admitted run survives a closed window. Unsubmitted episodes resume when the
comparison is reopened. Unknown admission is reconciled, never silently resent.
"""
from collections import defaultdict
import json
import hashlib
import math
from pathlib import Path
import re
import time
import uuid

from benchmarks.contracts import TERMINAL, digest, validate_request
from benchmarks.settings import load_settings, verify_submission
from desktop import benchmark
from desktop.configuration import atomic_json, load
from v14.state_lock import StateDirectoryLock

SCHEMA = 'gateway.desktop.comparison.v1'
QUALIFICATION_POLICY = 'all-attempt-identity-v2'
ORDERINGS = {'SELECTED_ORDER', 'COUNTERBALANCED'}
MAX_BYTES = 32 * 1024 * 1024


def _directory(root):
    return Path(root) / 'data' / 'benchmark-comparisons'


def _path(root, ident):
    if not isinstance(ident, str) or not re.fullmatch(r'compare_[a-f0-9]{32}', ident):
        raise ValueError('Invalid comparison identity.')
    return _directory(root) / (ident + '.json')


def _lock(root):
    return StateDirectoryLock(_directory(root) / 'owner')


def _configuration(root):
    # The digest contains no endpoint or credential in exported evidence.
    return digest({'gateway': load(root), 'benchmark': load_settings(root)})


def _save(root, session):
    session['updatedAt'] = time.time()
    # Each Gateway response already has a bounded case list. Keep snapshots
    # separately so repeated Deep runs cannot make their session unreadable.
    saved = dict(session, queue=[])
    for row in session['queue']:
        copy = dict(row)
        run = copy.pop('run', None)
        if run is not None:
            ordinal = row['ordinal']
            if type(ordinal) is not int or not 0 <= ordinal < 20:
                raise ValueError('Invalid paired episode ordinal.')
            receipt = _directory(root) / session['id'] / f'{ordinal:02}.json'
            raw = json.dumps(run, ensure_ascii=True, allow_nan=False, sort_keys=True).encode()
            if len(raw) > 16 * 1024 * 1024:
                raise ValueError('A Gateway episode exceeds the retained evidence bound.')
            run_digest = hashlib.sha256(raw).hexdigest()
            if row.get('runDigest') != run_digest or not receipt.exists():
                atomic_json(receipt, run)
            row.update(runFile=f'{ordinal:02}.json', runDigest=run_digest)
            copy.update(runFile=row['runFile'], runDigest=run_digest)
        saved['queue'].append(copy)
    atomic_json(_path(root, session['id']), saved)
    return session


def read(root, ident, *, include_evidence=True):
    path = _path(root, ident)
    if path.stat().st_size > MAX_BYTES:
        raise ValueError('Comparison evidence exceeds its read limit.')
    session = json.loads(path.read_bytes())
    if not isinstance(session, dict) or session.get('schema') != SCHEMA or session.get('id') != ident:
        raise ValueError('Saved comparison is invalid.')
    if digest(session.get('manifest')) != session.get('manifestDigest'):
        raise ValueError('Comparison manifest changed; retained results cannot be compared.')
    if include_evidence:
        for row in session['queue']:
            if row.get('runFile'):
                ordinal = row.get('ordinal')
                if type(ordinal) is not int or not 0 <= ordinal < 20 or row['runFile'] != f'{ordinal:02}.json':
                    raise ValueError('Invalid paired evidence path.')
                receipt = _directory(root) / ident / row['runFile']
                if receipt.stat().st_size > 16 * 1024 * 1024:
                    raise ValueError('A retained episode exceeds its evidence bound.')
                run = json.loads(receipt.read_bytes())
                fingerprint = hashlib.sha256(json.dumps(run, ensure_ascii=True, allow_nan=False, sort_keys=True).encode()).hexdigest()
                if fingerprint != row.get('runDigest'):
                    raise ValueError('Retained paired evidence changed.')
                row['run'] = run
        # Saved summaries are a cache. Opening/exporting a completed session must apply
        # the current evidence checks, just as observing an active session does.
        session['summary'] = summarize(session)
    elif isinstance(session.get('summary'), dict) and session['summary'].get('qualificationPolicy') != QUALIFICATION_POLICY:
        session['summary'] = dict(session['summary'], status='INCONCLUSIVE', winner=None, confidenceInterval95=None,
                                  reasons=['Open this saved comparison to revalidate its retained evidence.'])
    return session


def history(root):
    rows = []
    for path in _directory(root).glob('compare_*.json'):
        try:
            session = read(root, path.stem, include_evidence=False)
            rows.append({key: session.get(key) for key in ('id', 'createdAt', 'state', 'manifest', 'summary')})
        except (OSError, ValueError, TypeError):
            continue
    return sorted(rows, key=lambda row: row['createdAt'], reverse=True)


def create(root, models, *, preset='quick', repeats=3, ordering='SELECTED_ORDER', seed=17, suite='coding', practical_margin=.05, task_pack='python'):
    if not isinstance(models, list) or len(models) != 2 or len(set(models)) != 2:
        raise ValueError('Choose two different exact model IDs.')
    if type(repeats) is not int or not 1 <= repeats <= 10 or ordering not in ORDERINGS:
        raise ValueError('Choose 1–10 fresh episodes and a supported ordering.')
    if suite not in {'coding', 'quick_check'}:
        raise ValueError('Compare coding or quick-check runs; HTTP scheduling is a separate experiment.')
    if task_pack not in {'python', 'repository'} or suite != 'coding' and task_pack != 'python':
        raise ValueError('Choose a supported coding task pack.')
    if type(practical_margin) not in (int, float) or not math.isfinite(practical_margin) or not 0 <= practical_margin <= 1:
        raise ValueError('The practical improvement margin must be between zero and one.')
    for model in models:
        validate_request(dict(request_id='comparison-preflight', model=model, suite=suite, preset=preset, seed=seed))
    if seed + repeats > 2**31 - 1:
        raise ValueError('The fixture seed exceeds the allowed range.')
    verify_submission(root, suite, task_pack=task_pack)
    binding = benchmark.execution_binding(root)
    advertised = benchmark.discover(root)
    if any(model not in advertised for model in models):
        raise ValueError('Both selected models must be advertised by this Gateway.')
    ident = 'compare_' + uuid.uuid4().hex
    sizes = {'quick': 4, 'standard': 8, 'deep': 12} if task_pack == 'repository' else {'quick': 12, 'standard': 36, 'deep': 100}
    manifest = dict(models=models, suite=suite, preset=preset, repeats=repeats, ordering=ordering, seed=seed, taskPack=task_pack,
                    taskCount=sizes[preset] if suite == 'coding' else 5,
                    configurationDigest=_configuration(root), practicalMargin=practical_margin,
                    evidenceLane='EXECUTABLE_CODING' if suite == 'coding' else 'CONTRACT',
                    statisticsPolicy='bounded-family-hoeffding-95-with-exact-sign-gate-v2', minimumFreshEpisodes=3,
                    runtimeScope='GATEWAY_SELECTED_MODELS', profileScope='Gateway frozen settings; model ID is not a saved Android profile',
                    queueLifecycle='Admitted runs continue on Gateway; reopen to resume remaining episodes.')
    queue = []
    for repeat in range(repeats):
        order = (1, 0) if ordering == 'COUNTERBALANCED' and repeat % 2 else (0, 1)
        for arm in order:
            queue.append(dict(ordinal=len(queue), repeat=repeat, arm=arm, model=models[arm], seed=seed + repeat,
                              requestId=f'{ident}-{repeat}-{arm}', state='PENDING'))
    session = dict(schema=SCHEMA, id=ident, createdAt=time.time(), state='READY', manifest=manifest,
                   manifestDigest=digest(manifest), binding=binding, queue=queue, summary=None)
    lock = _lock(root)
    lock.acquire()
    try:
        active = [row for row in history(root) if row['state'] not in {'COMPLETED', 'CANCELLED'}]
        if active:
            raise ValueError('Resume or stop the saved comparison before starting another.')
        return _save(root, session)
    finally:
        lock.release()


def _run_value(value):
    return value.get('run', value) if isinstance(value, dict) else {}


def advance(root, ident):
    """One bounded observation/admission; suitable for either UI's async poll."""
    lock = _lock(root)
    lock.acquire()
    try:
        session = read(root, ident)
        if session['state'] in {'COMPLETED', 'CANCELLED'}:
            return session
        manifest = session['manifest']
        if digest(manifest) != session['manifestDigest']:
            raise ValueError('Comparison manifest changed; retained results cannot be compared.')
        entry = next((row for row in session['queue'] if row['state'] not in TERMINAL), None)
        if entry is None:
            session.update(state='CANCELLED' if session.get('stopRequested') else 'COMPLETED', summary=summarize(session))
            return _save(root, session)
        if entry.get('runId'):
            # Binding is checked for reads as well as new admissions.
            run = _run_value(benchmark.service_request(root, '/' + entry['runId'], binding=session['binding']))
            entry.update(run=run, state=run.get('state', 'RUNNING'))
            if session.get('stopRequested') and entry['state'] not in TERMINAL:
                run = _run_value(benchmark.service_request(root, '/' + entry['runId'] + '/cancel', method='POST', payload={}, binding=session['binding']))
                entry.update(run=run, state=run.get('state', 'RUNNING'))
        elif entry['state'] in {'DISPATCHING', 'UNCERTAIN'}:
            observation = benchmark.reconcile_run(root, entry['requestId'], session['binding'])
            run = observation.get('run')
            if isinstance(run, dict) and run.get('id'):
                entry.update(runId=run['id'], run=run, state=run.get('state', 'RUNNING'))
            elif observation.get('submissionState') == 'REMOVED':
                entry.update(state='FAILED', reason='The original submitted run was removed; it was not resubmitted.')
            else:
                entry.update(state='UNCERTAIN', reason='Original submission not observed. Reconnect to the original Gateway and check again.')
                session['state'] = 'WAITING_FOR_RECONCILIATION'
                return _save(root, session)
        else:
            if _configuration(root) != manifest['configurationDigest']:
                session.update(state='SETTINGS_CHANGED', recovery='Restore the frozen Gateway and benchmark settings before resuming. Start a new comparison for changed conditions.')
                return _save(root, session)
            verify_submission(root, manifest['suite'], task_pack=manifest.get('taskPack', 'python'))
            entry['state'] = 'DISPATCHING'
            session['state'] = 'RUNNING'
            _save(root, session)
            try:
                run = _run_value(benchmark.start_run(root, entry['model'], manifest['suite'], manifest['preset'],
                    request_id=entry['requestId'], seed=entry['seed'], binding=session['binding'],
                    task_pack=manifest.get('taskPack', 'python'),
                    comparison_context={'session_id': session['id'], 'pair_index': entry['repeat'], 'repeat_index': entry['repeat'],
                                        'settings_digest': manifest['configurationDigest'], 'route_identity': digest(session['binding'])}))
                if not isinstance(run.get('id'), str):
                    raise ValueError('Gateway returned no benchmark identity; check the original submission.')
                entry.update(runId=run['id'], run=run, state=run.get('state', 'RUNNING'))
            except benchmark.BenchmarkRejected as error:
                entry.update(state='BLOCKED', reason=str(error)[:300])
            except Exception:
                entry['state'] = 'UNCERTAIN'
                session['state'] = 'WAITING_FOR_RECONCILIATION'
                _save(root, session)
                raise
        ended = all(row['state'] in TERMINAL for row in session['queue'])
        session['state'] = ('CANCELLED' if ended else 'STOPPING') if session.get('stopRequested') else ('COMPLETED' if ended else 'RUNNING')
        session['summary'] = summarize(session)
        return _save(root, session)
    finally:
        lock.release()


def stop(root, ident):
    lock = _lock(root)
    lock.acquire()
    try:
        session = read(root, ident)
        session['stopRequested'] = True
        _save(root, session)
        for row in session['queue']:
            if row.get('runId') and row['state'] not in TERMINAL:
                run = _run_value(benchmark.service_request(root, '/' + row['runId'] + '/cancel', method='POST', payload={}, binding=session['binding']))
                row.update(run=run, state=run.get('state', 'RUNNING'))
            elif row['state'] == 'PENDING':
                row.update(state='CANCELLED', reason='Stopped before dispatch.')
        # An uncertain submission must still be reconciled; cancellation is not proof it never ran.
        session['state'] = 'CANCELLED' if all(row['state'] in TERMINAL for row in session['queue']) else 'STOPPING'
        session['summary'] = summarize(session)
        return _save(root, session)
    finally:
        lock.release()


def _number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _measurements(rows):
    attempts = []
    for row in rows:
        for case in row.get('run', {}).get('cases', []):
            attempts.extend(case.get('attempts') or [case])
    totals = {}
    for field in ('inputTokens', 'outputTokens', 'totalMs'):
        values = [(attempt.get('timing') or attempt).get(field) for attempt in attempts]
        totals[field] = sum(values) if values and all(_number(value) for value in values) else None
        totals[field + 'Measured'] = sum(_number(value) for value in values)
    spans = []
    for row in rows:
        run = row.get('run')
        if not run:
            continue
        start, end = run.get('createdAt'), run.get('endedAt')
        spans.append((end - start) * 1000 if _number(start) and _number(end) and end >= start else None)
    totals['endToEndMs'] = sum(spans) if spans and all(_number(value) for value in spans) else None
    totals['timingScope'] = 'endToEndMs sums admitted run wall time including queue/setup/validation; totalMs sums all model-request attempts'
    totals.update(attempts=len(attempts), cost=None, costReason='No complete frozen pricing and usage receipt in these Gateway runs.')
    return totals


def summarize(session):
    """No unqualified score or fastest-incorrect run can become a winner."""
    manifest, queue = session['manifest'], session['queue']
    reasons, differences, paired_cases = set(), defaultdict(list), 0
    per_arm = []
    for arm, model in enumerate(manifest['models']):
        rows = [row for row in queue if row['arm'] == arm]
        def resolutions(field):
            measured = [(row['run'].get('summary') or {}).get(field) for row in rows if row.get('run')]
            return sum(measured) if measured and all(type(value) is int and value >= 0 for value in measured) else None
        per_arm.append(dict(model=model, completedEpisodes=sum(row['state'] == 'COMPLETED' for row in rows),
                            plannedEpisodes=manifest['repeats'], measurements=_measurements(rows),
                            resolved=resolutions('finalResolved'), initialResolved=resolutions('initialResolved'),
                            bestResolved=resolutions('bestResolved'),
                            planned=manifest['taskCount'] * manifest['repeats'],
                            observedPlanned=sum(row.get('run', {}).get('planned', 0) for row in rows)))
    for repeat in range(manifest['repeats']):
        pair = {row['arm']: row for row in queue if row['repeat'] == repeat}
        if set(pair) != {0, 1} or any(row['state'] != 'COMPLETED' for row in pair.values()):
            reasons.add('Every planned model episode must finish before comparison qualification.')
            continue
        a, b = (pair[arm].get('run', {}) for arm in (0, 1))
        sa, sb = a.get('summary') or {}, b.get('summary') or {}
        if manifest['suite'] != 'coding':
            reasons.add('Contract results do not establish coding superiority.')
            continue
        if any(summary.get('qualified') is not True or summary.get('calibration', {}).get('status') != 'PASSED' for summary in (sa, sb)):
            reasons.add('Independent grading and calibration are incomplete for one or both models.')
            continue
        identity_matches = True
        for key in ('fixtureManifest', 'graderDigest', 'repairBudget', 'executionScope'):
            if sa.get(key) is None or sa.get(key) != sb.get(key):
                reasons.add('Task, grader, route or repair policy is missing or mismatched.')
                identity_matches = False
        if not identity_matches:
            continue
        cases = [run.get('cases', []) for run in (a, b)]
        if any(not isinstance(rows, list) or any(not isinstance(case, dict) or not isinstance(case.get('id'), str) or not case['id'] for case in rows)
               or len(rows) != len({case['id'] for case in rows}) for rows in cases):
            reasons.add('Task evidence contains missing or duplicate task identities.')
            continue
        ac, bc = ({case['id']: case for case in rows} for rows in cases)
        if set(ac) != set(bc) or len(ac) != a.get('planned') or len(bc) != b.get('planned') or a.get('planned') != manifest['taskCount'] or b.get('planned') != manifest['taskCount']:
            reasons.add('Planned task coverage differs; unsupported model tasks cannot disappear.')
            continue
        for key in ac:
            x, y = ac[key], bc[key]
            if not x.get('taskDigest') or x.get('taskDigest') != y.get('taskDigest'):
                reasons.add('Exact task identity is not verified.')
                continue
            if any(case.get('observedModel') != manifest['models'][arm] for arm, case in enumerate((x, y))):
                reasons.add('The delivered model identity is not confirmed.')
                continue
            family = x.get('family')
            if not family or family != y.get('family'):
                reasons.add('Task-family identity is not verified.')
                continue
            # Use the final submitted attempt, never a best-attempt projection.
            attempts = [case.get('attempts') or [case] for case in (x, y)]
            if any(not isinstance(rows, list) or any(not isinstance(attempt, dict) or attempt.get('observedModel') != manifest['models'][arm]
                                                   or attempt.get('taskDigest') != ac[key]['taskDigest']
                                                   or attempt.get('graderDigest') != sa['graderDigest'] for attempt in rows)
                   for arm, rows in enumerate(attempts)):
                reasons.add('Every generation attempt must retain its selected model, task and grader identity.')
                continue
            final = [rows[-1] for rows in attempts]
            if any(type(case.get('resolved')) is not bool for case in final):
                reasons.add('Final submitted outcome is unknown.')
                continue
            differences[family].append(int(final[0]['resolved']) - int(final[1]['resolved']))
            paired_cases += 1
    groups = [sum(values) / len(values) for values in differences.values()]
    delta = sum(groups) / len(groups) if groups else None
    interval, sign_probability = None, None
    if manifest['statisticsPolicy'] != 'bounded-family-hoeffding-95-with-exact-sign-gate-v2':
        reasons.add('Historical statistical policy; start a new frozen comparison for current qualification.')
    if groups and len(groups) >= 12 and manifest['repeats'] >= manifest['minimumFreshEpisodes'] and not reasons:
        radius = math.sqrt(2 * math.log(2 / .05) / len(groups))
        interval = [max(-1, delta - radius), min(1, delta + radius)]
        positive, negative = sum(value > 0 for value in groups), sum(value < 0 for value in groups)
        n = positive + negative
        sign_probability = min(1, 2 * sum(math.comb(n, i) for i in range(min(positive, negative) + 1)) / 2**n) if n else 1
    else:
        reasons.add('At least three fresh episodes and twelve matched task families are required; variants are grouped by family.')
    winner = None
    if interval and sign_probability <= .05:
        if interval[0] > manifest['practicalMargin']:
            winner = manifest['models'][0]
        elif interval[1] < -manifest['practicalMargin']:
            winner = manifest['models'][1]
    return dict(status='SUPERIOR_ON_MATCHED_SUITE' if winner else 'INCONCLUSIVE', winner=winner, qualificationPolicy=QUALIFICATION_POLICY,
                evidenceLane=manifest['evidenceLane'], models=per_arm, pairedTasks=paired_cases,
                independentTaskGroups=len(groups), difference=delta, confidenceInterval95=interval,
                practicalMargin=manifest['practicalMargin'], reasons=sorted(reasons),
                signProbability=sign_probability, aggregation='Equal family weight; variants and repeats averaged within each family.',
                uncertaintyAssumption='Independent bounded family means; repeats do not increase the independent family count. Conservative finite-sample interval.',
                interpretation='Family-grouped paired difference in final verified completion; positive favors the first model. No general model ranking is implied.',
                firstFinalBest='First submission, final submission and best candidate remain distinct in each run evidence.',
                policy=manifest['statisticsPolicy'])


def public_snapshot(session, *, complete=False):
    """Keep repeated UI snapshots bounded; explicit exports retain all cases."""
    result = {key: value for key, value in session.items() if key != 'binding'}
    if complete:
        result['detailScope'] = 'COMPLETE_RETAINED_COMPARISON_EVIDENCE'
        return result
    result['queue'] = [{key: value for key, value in entry.items() if key != 'run'} | {
        'run': {key: value for key, value in entry.get('run', {}).items()
                if key in {'id', 'state', 'request', 'planned', 'completed', 'createdAt', 'startedAt', 'endedAt', 'summary', 'failure'}}
    } for entry in session['queue']]
    result['detailScope'] = 'DISPLAY_SUMMARY; full case evidence is retained for explicit export and individual run inspection'
    return result


def export(root, ident, path):
    """Write large evidence locally instead of transporting it through a UI frame."""
    snapshot = public_snapshot(read(root, ident), complete=True)
    atomic_json(Path(path), snapshot)
    return {'exported': True, 'comparisonId': ident, 'detailScope': snapshot['detailScope']}
