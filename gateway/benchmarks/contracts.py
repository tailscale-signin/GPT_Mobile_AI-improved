"""Pure benchmark identities, coverage and comparisons. Unknown is never zero."""
import hashlib
import json
import math
import re
from statistics import median

SCHEMA = 'gateway.benchmark.v1'
TERMINAL = {'COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED', 'INTERRUPTED'}
PRESETS = {'quick': 1, 'standard': 3, 'deep': 8}
SUITES = {'quick_check', 'batch_comparison', 'coding', 'coding_submission'}
CODING_WEIGHTS = {
    'functional': 300, 'regression': 150, 'tests': 100,
    'diagnosis': 100, 'design': 100, 'workflow': 75,
    'recovery': 75, 'safety': 50, 'evidence': 50,
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=True, allow_nan=False).encode()).hexdigest()


def content_digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def validate_request(value):
    extra = {'comparison_context', 'execution_binding_id', 'task_id', 'fixture_digest', 'proposal_digest', 'grader_digest', 'environment_digest',
             'generation_receipt', 'evidence_lane', 'repository', 'base_sha', 'cohort', 'task_pack'}
    if not isinstance(value, dict) or set(value) - {'request_id', 'model', 'suite', 'preset', 'seed', 'concurrency'} - extra:
        raise ValueError('Unsupported benchmark request')
    request = dict(value)
    for key, maximum in (('request_id', 128), ('model', 200)):
        text = request.get(key)
        if not isinstance(text, str) or not 1 <= len(text) <= maximum or text != text.strip() or any(ord(c) < 32 for c in text):
            raise ValueError('A bounded ' + key + ' is required')
    if request.get('suite') not in SUITES or request.get('preset', 'quick') not in PRESETS:
        raise ValueError('Select a supported suite and preset')
    request.setdefault('preset', 'quick')
    request.setdefault('seed', 17)
    request.setdefault('concurrency', 2)
    if type(request['seed']) is not int or not 0 <= request['seed'] <= 2**31 - 1:
        raise ValueError('Invalid fixture seed')
    if type(request['concurrency']) is not int or request['concurrency'] not in (2, 4):
        raise ValueError('Select two or four concurrent requests')
    context = request.get('comparison_context')
    if context is not None:
        required = {'session_id', 'pair_index', 'repeat_index', 'settings_digest', 'route_identity'}
        optional = {'profile_uid', 'profile_revision'}
        if (not isinstance(context, dict) or not required <= set(context) or set(context) - required - optional
                or bool(set(context) & optional) and not optional <= set(context)):
            raise ValueError('Complete frozen comparison context is required')
        for key in set(context) - {'pair_index', 'repeat_index'}:
            item = context[key]
            if not isinstance(item, str) or not 1 <= len(item) <= 512 or any(ord(c) < 32 for c in item):
                raise ValueError('Invalid comparison identity')
        for key in ('pair_index', 'repeat_index'):
            if type(context[key]) is not int or not 0 <= context[key] <= 10000:
                raise ValueError('Invalid comparison trial identity')
    binding = request.get('execution_binding_id')
    if binding is not None and (not isinstance(binding, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', binding)):
        raise ValueError('Invalid selected execution binding')
    if request['suite'] == 'coding_submission':
        from benchmarks.external import generation_receipt
        receipt = generation_receipt(request.get('generation_receipt'))
        if receipt['model'] != request['model']:
            raise ValueError('Selected model identity changed')
        for key in ('fixture_digest', 'proposal_digest', 'grader_digest'):
            if not re.fullmatch(r'[a-f0-9]{64}', str(request.get(key, ''))):
                raise ValueError('Immutable task and proposal identities are required')
        if 'environment_digest' in request and not re.fullmatch(r'[a-f0-9]{64}', str(request['environment_digest'])):
            raise ValueError('Immutable runtime identity is required')
        if not isinstance(request.get('task_id'), str) or not re.fullmatch(r'[a-z0-9-]{1,100}', request['task_id']):
            raise ValueError('Invalid immutable task identity')
        if request.get('evidence_lane') not in {'EXECUTABLE', 'LIVE_GITHUB'}:
            raise ValueError('Invalid evidence lane')
        if request.get('task_pack', 'python') not in {'python', 'repository'}:
            raise ValueError('Invalid task pack')
        if context is not None and any(context[key] != receipt[key] for key in ('profile_uid', 'profile_revision', 'settings_digest', 'route_identity') if key in context):
            raise ValueError('Comparison selected-profile receipt changed')
    elif set(request) & {'task_id', 'fixture_digest', 'proposal_digest', 'grader_digest', 'environment_digest', 'generation_receipt', 'evidence_lane', 'repository', 'base_sha', 'cohort'}:
        raise ValueError('External grading fields require the one-task evaluation endpoint')
    if 'task_pack' in request and (request['suite'] not in {'coding', 'coding_submission'} or request['task_pack'] not in {'python', 'repository'}):
        raise ValueError('A task pack requires a supported coding suite')
    return request


def distribution(values):
    values = sorted(float(x) for x in values if type(x) in (int, float) and math.isfinite(x) and x >= 0)
    if not values:
        return {'count': 0, 'median': None, 'p95': None, 'minimum': None, 'maximum': None}
    return {'count': len(values), 'median': median(values),
            'p95': values[max(0, math.ceil(len(values) * .95) - 1)] if len(values) >= 20 else None,
            'minimum': values[0], 'maximum': values[-1]}


def coverage(cases, planned):
    """These outcome counts partition cases; execution/grade are separate facets."""
    counts = {key: 0 for key in ('PASSED', 'FAILED', 'BLOCKED', 'ERROR', 'CANCELLED', 'INTERRUPTED', 'RUNNING')}
    for case in cases:
        state = case.get('status', 'RUNNING')
        counts[state if state in counts else 'ERROR'] += 1
    counts['PENDING'] = max(0, planned - len(cases))
    return dict(planned=planned, recorded=len(cases), outcomes=counts,
                executed=sum(c.get('executed') is True for c in cases),
                graded=sum(c.get('graded') is True for c in cases))


def coding_score(cases, planned, preset):
    """A public score needs every declared criterion, not a convenient subset."""
    families = ('pagination', 'idempotency', 'dependencies', 'budget', 'streaming', 'migration',
                'patches', 'ci', 'retries', 'permissions', 'evidence', 'graph')
    repeats = PRESETS[preset]
    core_share = .8 if preset == 'deep' else 1.0
    slots = {family + '-' + str(index + 1): core_share / (len(families) * repeats)
             for family in families for index in range(repeats)}
    if preset == 'deep':
        slots.update({'capstone-' + name: .05 for name in ('publication', 'planning', 'transaction', 'recovery')})
    observed = {}
    valid_identity = planned == len(slots)
    for case in cases:
        key = case.get('id')
        if key not in slots or key in observed:
            valid_identity = False
        else:
            observed[key] = case
    def valid(value):
        return type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1
    def points(rows):
        if set(rows) != set(slots):
            return None
        if not all(row.get('graded') is True and row.get('executed') is True and row.get('qualified') is not False
                   and row.get('integrity') == 'VERIFIED'
                   and all(valid(row.get('dimensions', {}).get(name)) for name in CODING_WEIGHTS) for row in rows.values()):
            return None
        return sum(slots[key] * sum(weight * row['dimensions'][name] for name, weight in CODING_WEIGHTS.items())
                   for key, row in rows.items())
    dimensions = {}
    for name, weight in CODING_WEIGHTS.items():
        measured = {key: row['dimensions'][name] for key, row in observed.items()
                    if row.get('graded') is True and row.get('integrity') == 'VERIFIED' and valid(row.get('dimensions', {}).get(name))}
        known = sum(slots[key] * weight for key in measured)
        earned = sum(slots[key] * weight * value for key, value in measured.items())
        dimensions[name] = {'weight': weight, 'measured': len(measured), 'planned': len(slots),
                            'value': earned if valid_identity and len(measured) == len(slots) else None,
                            'earned': earned, 'possible': weight, 'unknownWeight': max(0.0, weight - known)}
    score = points(observed) if valid_identity else None
    complete = score is not None
    first = {key: row['attempts'][0] for key, row in observed.items() if isinstance(row.get('attempts'), list) and row['attempts']}
    first_score = points(first) if valid_identity else None
    earned = sum(d['earned'] for d in dimensions.values())
    initial_resolved = sum(row.get('initialResolved', row.get('firstSubmissionResolved')) is True for row in observed.values())
    final_resolved = sum(row.get('finalResolved', row.get('resolved')) is True for row in observed.values())
    best_resolved = sum(row.get('bestResolved', row.get('resolved')) is True for row in observed.values())
    def known_outcome(row):
        return (row.get('outcomeKnown') is True or row.get('graded') is True and row.get('executed') is True
                and row.get('qualified') is not False and row.get('integrity') == 'VERIFIED'
                and all(valid(row.get('dimensions', {}).get(name)) for name in CODING_WEIGHTS))
    delivered = {key: row['attempts'][-1] if isinstance(row.get('attempts'), list) and row['attempts'] else row
                 for key, row in observed.items()}
    outcomes_complete = valid_identity and set(observed) == set(slots) and all(known_outcome(row) for row in delivered.values())
    initial_complete = (valid_identity and set(first) == set(slots) and all(known_outcome(row) for row in first.values())
                        and all(type(row.get('firstSubmissionResolved')) is bool for row in observed.values()))
    best_complete = outcomes_complete and all(known_outcome(attempt) for row in observed.values() for attempt in row.get('attempts', [row]))
    return {'version': 'coding-evidence-v2', 'contractVersion': 'coding-evidence-v2',
            'maximum': 1000, 'score': round(score, 2) if score is not None else None,
            'qualified': complete, 'dimensions': dimensions, 'coverage': coverage(cases, planned),
            'resolved': sum(c.get('resolved') is True for c in cases), 'planned': planned,
            'passAtOne': initial_resolved / planned if initial_complete else None,
            'firstSubmissionScore': round(first_score, 2) if first_score is not None else None,
            'finalResolvedRate': final_resolved / planned if outcomes_complete else None,
            'scope': 'isolated_repository_fixtures', 'liveGitHub': 'NOT_EXECUTED',
            'initialResolved': initial_resolved, 'finalResolved': final_resolved, 'bestResolved': best_resolved,
            'firstAttemptRate': initial_resolved / planned if initial_complete else None,
            'finalAttemptRate': final_resolved / planned if outcomes_complete else None,
            'bestWithinBudgetRate': best_resolved / planned if best_complete else None,
            'outcomeComplete': outcomes_complete, 'initialOutcomeComplete': initial_complete,
            'bestOutcomeComplete': best_complete, 'knownOutcomes': sum(known_outcome(row) for row in delivered.values()),
            'scoreQualification': 'VERIFIED' if complete else 'INCOMPLETE',
            'label': 'Complete coding evidence' if complete else 'Partial coding evidence; overall score unavailable',
            'earned': earned, 'provisionalEarned': earned if not complete else None, 'possible': 1000,
            'unknownWeight': sum(d['unknownWeight'] for d in dimensions.values()),
            'preset': preset, 'aggregation': 'equal_family_mean; deep_core_80_capstones_20; no_speed_points'}


def repository_score(cases, planned, preset):
    """Native task outcomes, without inventing unmeasured nine-part rubric points."""
    from benchmarks.repository_tasks import TASK_IDS, PRESETS as NATIVE_PRESETS
    wanted = {TASK_IDS[index] for index in NATIVE_PRESETS[preset]}
    rows = {case.get('id'): case for case in cases}
    identity = len(rows) == len(cases) and planned == len(wanted) and set(rows) <= wanted
    def known(case):
        return case.get('outcomeKnown') is True and case.get('graded') is True
    def attempts(case):
        return case.get('attempts') or [case]
    initial = {key: attempts(case)[0] for key, case in rows.items()}
    final = {key: attempts(case)[-1] for key, case in rows.items()}
    complete = identity and set(rows) == wanted
    initial_complete = complete and all(known(case) for case in initial.values())
    final_complete = complete and all(known(case) for case in final.values())
    best_complete = complete and all(known(attempt) for case in rows.values() for attempt in attempts(case))
    initial_resolved = sum(case.get('resolved') is True for case in initial.values())
    final_resolved = sum(case.get('resolved') is True for case in final.values())
    best_resolved = sum(any(attempt.get('resolved') is True for attempt in attempts(case)) for case in rows.values())
    return {'version': 'repository-outcomes-v1', 'contractVersion': 'repository-outcomes-v1',
            'score': None, 'maximum': 1000, 'qualified': final_complete and all(case.get('qualified') is True for case in final.values()),
            'secondaryRubricStatus': 'UNMEASURED_CRITERIA', 'coverage': coverage(cases, planned),
            'planned': planned, 'resolved': final_resolved, 'initialResolved': initial_resolved,
            'finalResolved': final_resolved, 'bestResolved': best_resolved,
            'outcomeComplete': final_complete, 'initialOutcomeComplete': initial_complete, 'bestOutcomeComplete': best_complete,
            'knownOutcomes': sum(known(case) for case in final.values()),
            'passAtOne': initial_resolved / planned if initial_complete else None,
            'firstAttemptRate': initial_resolved / planned if initial_complete else None,
            'finalAttemptRate': final_resolved / planned if final_complete else None,
            'finalResolvedRate': final_resolved / planned if final_complete else None,
            'bestWithinBudgetRate': best_resolved / planned if best_complete else None,
            'scoreQualification': 'OUTCOME_ONLY' if final_complete else 'INCOMPLETE',
            'scope': 'ISOLATED_NATIVE_REPOSITORY_CONTRACT', 'executionScope': 'ISOLATED_NATIVE_REPOSITORY_CONTRACT',
            'liveGitHub': 'NOT_EXECUTED', 'taskPack': 'repository', 'preset': preset,
            'label': 'Verified native task outcomes' if final_complete else 'Incomplete native task evidence',
            'aggregation': 'equal_distinct_task_outcomes; unmeasured_rubric_points_omitted'}


def paired_summary(cases, pairs):
    grouped = {}
    for case in cases:
        grouped.setdefault(case['pair'], {})[case['arm']] = case
    valid = []
    for rows in grouped.values():
        if set(rows) != {'sequential', 'concurrent'}:
            continue
        a, b = rows['sequential'], rows['concurrent']
        if all(x.get('status') == 'PASSED' and x.get('correct') == x.get('planned') and x.get('identityVerified') for x in (a, b)):
            if a['totalMs'] > 0 and b['totalMs'] > 0:
                valid.append((a, b))
    qualified = len(valid) == pairs and pairs >= 10
    ratios = [a['totalMs'] / b['totalMs'] for a, b in valid]
    return {'version': 'paired-http-scheduling-v1', 'scope': 'HTTP_REQUEST_SCHEDULING',
            'nativeInferenceBatching': 'NOT_VERIFIED', 'pairedCount': len(valid), 'plannedPairs': pairs,
            'qualified': qualified, 'observedSpeedup': median(ratios) if ratios else None,
            'speedup': median(ratios) if qualified else None,
            'firstContentMs': {arm: distribution(s['firstMs'] for c in cases if c['arm'] == arm for s in c.get('samples', []) if s.get('status') == 'PASSED')
                               for arm in ('sequential', 'concurrent')},
            'makespanMs': {arm: distribution(c.get('totalMs') for c in cases if c['arm'] == arm) for arm in ('sequential', 'concurrent')},
            'limitations': ['Compares serial versus concurrent HTTP requests, not a native batching flag.',
                            'Backend cache, GPU, queue and native batch execution are not inferred.',
                            'Fewer than ten complete matched pairs remain exploratory.'],
            'coverage': coverage(cases, pairs * 2)}
