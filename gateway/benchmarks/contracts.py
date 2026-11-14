"""Pure benchmark identities, coverage and comparisons. Unknown is never zero."""
import hashlib
import json
import math
from statistics import median

SCHEMA = 'gateway.benchmark.v1'
TERMINAL = {'COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED', 'INTERRUPTED'}
PRESETS = {'quick': 1, 'standard': 3, 'deep': 8}
SUITES = {'quick_check', 'batch_comparison', 'coding'}
CODING_WEIGHTS = {
    'functional': 300, 'regression': 150, 'tests': 100,
    'diagnosis': 100, 'design': 100, 'workflow': 75,
    'recovery': 75, 'safety': 50, 'evidence': 50,
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=True, allow_nan=False).encode()).hexdigest()


def validate_request(value):
    if not isinstance(value, dict) or set(value) - {'request_id', 'model', 'suite', 'preset', 'seed', 'concurrency'}:
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
        if not all(row.get('graded') is True and row.get('integrity') == 'VERIFIED'
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
    first_resolved_known = complete and all(type(row.get('firstSubmissionResolved')) is bool for row in observed.values())
    return {'version': 'coding-evidence-v1', 'maximum': 1000, 'score': round(score, 2) if score is not None else None,
            'qualified': complete, 'dimensions': dimensions, 'coverage': coverage(cases, planned),
            'resolved': sum(c.get('resolved') is True for c in cases), 'planned': planned,
            'passAtOne': sum(c['firstSubmissionResolved'] for c in observed.values()) / planned if first_resolved_known else None,
            'firstSubmissionScore': round(first_score, 2) if first_score is not None else None,
            'finalResolvedRate': sum(c.get('resolved') is True for c in cases) / planned if complete and planned else None,
            'scope': 'isolated_repository_fixtures', 'liveGitHub': 'NOT_EXECUTED',
            'label': 'Complete coding evidence' if complete else 'Partial coding evidence; overall score unavailable',
            'earned': sum(d['earned'] for d in dimensions.values()), 'possible': 1000,
            'unknownWeight': sum(d['unknownWeight'] for d in dimensions.values()),
            'preset': preset, 'aggregation': 'equal_family_mean; deep_core_80_capstones_20; no_speed_points'}


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
