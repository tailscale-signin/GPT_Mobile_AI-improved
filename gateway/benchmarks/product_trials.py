"""Compare independently observed native-product trials on a preregistered held-out suite."""
from collections import defaultdict
import json
import math
import re
from statistics import median

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from benchmarks.contracts import content_digest, digest

PRODUCTS = ('GPT Mobile AI', 'Codex', 'Claude Code')
PLAN = 'gateway.native-product-plan.v1'
OBSERVATION = 'gateway.native-product-observation.v1'
GRADE = 'gateway.native-product-grade.v1'


def signed_bytes(payload):
    return json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode()


def verified(envelope, key, schema):
    try:
        payload = envelope['payload']
        key.verify(bytes.fromhex(envelope['signature']), signed_bytes(payload))
    except (InvalidSignature, ValueError, KeyError, TypeError) as error:
        raise ValueError('Invalid native trial signature') from error
    if not isinstance(payload, dict) or payload.get('schema') != schema:
        raise ValueError('Unexpected signed trial schema')
    return payload


def sha(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def finite(value, least, most):
    return type(value) in (float, int) and math.isfinite(value) and least <= value <= most


def validate_plan(plan):
    cases = plan.get('cases')
    versions = plan.get('versions')
    if (not isinstance(versions, dict) or set(versions) != set(PRODUCTS)
            or not all(isinstance(s, str) and s.strip() for s in versions.values())
            or not sha(plan.get('orderSeed')) or plan.get('cache') not in {'cold', 'warm'} or not sha(plan.get('controlsDigest'))
            or not finite(plan.get('marginPoints'), 0, 99) or type(plan.get('repeats')) is not int
            or not 1 <= plan['repeats'] <= 20 or not isinstance(cases, list) or not 1 <= len(cases) <= 500
            or not finite(plan.get('maxSeconds'), 1, 86400) or not finite(plan.get('maxCostUsd'), 0, 10000)):
        raise ValueError('Incomplete frozen controls, product versions, budgets or case set')
    seen = set()
    for case in cases:
        if (not isinstance(case, dict) or not sha(case.get('taskDigest')) or case['taskDigest'] in seen
                or not isinstance(case.get('family'), str) or not case['family'].strip()
                or not sha(case.get('environmentDigest')) or not sha(case.get('graderDigest'))):
            raise ValueError('Invalid or duplicate frozen task')
        seen.add(case['taskDigest'])


def schedule(plan):
    """Randomized within each case/repeat block, reproducible from the frozen seed."""
    validate_plan(plan)
    blocks = [(c['taskDigest'], i) for c in plan['cases'] for i in range(plan['repeats'])]
    blocks.sort(key=lambda block: digest([plan['orderSeed'], *block]))
    result = []
    for task, repeat in blocks:
        for product in sorted(PRODUCTS, key=lambda name: digest([plan['orderSeed'], task, repeat, name])):
            result.append({'taskDigest': task, 'repeat': repeat, 'product': product})
    return result


def compare_signed(plan_envelope, observations, grades, observer_public, grader_public):
    """Only the separate proctor and blind grader can attest their own observations."""
    if observer_public == grader_public:
        raise ValueError('Independent observer and grader keys are required')
    observer = Ed25519PublicKey.from_public_bytes(observer_public)
    grader = Ed25519PublicKey.from_public_bytes(grader_public)
    plan = verified(plan_envelope, observer, PLAN)
    validate_plan(plan)
    plan_id = digest(plan)
    tasks = {c['taskDigest']: c for c in plan['cases']}
    if len(observations) > len(tasks) * plan['repeats'] * len(PRODUCTS) or len(grades) > len(observations):
        raise ValueError('Trial inventory exceeds the frozen plan')
    judged, grader_runs = {}, set()
    for envelope in grades:
        row = verified(envelope, grader, GRADE)
        run = row.get('runId')
        if (not sha(run) or run in judged or row.get('planDigest') != plan_id or row.get('taskDigest') not in tasks
                or row.get('graderDigest') != tasks[row['taskDigest']]['graderDigest']
                or row.get('environmentDigest') != tasks[row['taskDigest']]['environmentDigest'] or not sha(row.get('artifactDigest'))
                or not isinstance(row.get('graderRunId'), str) or not row['graderRunId'] or row['graderRunId'] in grader_runs
                or row.get('result') not in {'PASS', 'FAIL', 'GRADER_UNAVAILABLE'}):
            raise ValueError('Invalid independent grading receipt')
        judged[run] = row
        grader_runs.add(row['graderRunId'])

    positions = {(r['product'], r['taskDigest'], r['repeat']): i for i, r in enumerate(schedule(plan))}
    slots, seen = {}, set()
    timings = defaultdict(list)
    costs = defaultdict(list)
    for envelope in observations:
        row = verified(envelope, observer, OBSERVATION)
        product, task, repeat, run = (row.get(k) for k in ('product', 'taskDigest', 'repeat', 'runId'))
        slot = (product, task, repeat)
        cost = row.get('costUsd')
        state = row.get('state')
        if (row.get('planDigest') != plan_id or row.get('controlsDigest') != plan['controlsDigest']
                or product not in PRODUCTS or row.get('version') != plan['versions'][product]
                or task not in tasks or type(repeat) is not int or not 0 <= repeat < plan['repeats']
                or type(row.get('scheduleIndex')) is not int or row['scheduleIndex'] != positions.get(slot)
                or not sha(run) or run in seen or slot in slots or row.get('cache') != plan['cache']
                or not finite(row.get('seconds'), 0, 864000) or cost is not None and not finite(cost, 0, 1000000)
                or type(row.get('interventions')) is not int or row['interventions'] < 0
                or state not in {'DELIVERED', 'PRODUCT_FAILURE', 'HARNESS_FAILURE', 'UNSUPPORTED'}):
            raise ValueError('Unmatched controls, unknown product version or duplicate native trial')
        seen.add(run)
        timings[product].append(row['seconds'])
        if cost is not None:
            costs[product].append(cost)
        result = None
        reason = state
        grade = judged.pop(run, None)
        if state == 'DELIVERED':
            if not sha(row.get('artifactDigest')):
                raise ValueError('A delivered candidate needs an immutable artifact digest')
            if grade is not None and (grade['artifactDigest'] != row['artifactDigest'] or grade['taskDigest'] != task):
                raise ValueError('Independent grade belongs to another artifact or task')
            reason = grade['result'] if grade else 'MISSING_GRADE'
            if grade and grade['result'] in {'PASS', 'FAIL'}:
                result = int(grade['result'] == 'PASS')
        elif grade or row.get('artifactDigest') is not None:
            raise ValueError('A trial without a delivered artifact cannot carry an artifact grade')
        if state == 'PRODUCT_FAILURE':
            result = 0
        if state == 'DELIVERED':
            if row['interventions'] or row['seconds'] > plan['maxSeconds'] or cost is not None and cost > plan['maxCostUsd']:
                result, reason = 0, 'INTERVENTION_OR_BUDGET_EXCEEDED'
            elif cost is None and result == 1:
                result, reason = None, 'COST_UNOBSERVED'
        slots[slot] = (result, reason)
    if judged:
        raise ValueError('Grades without matching product observations')
    pairs = {}
    coverage, performance = {}, {}
    for product in PRODUCTS:
        counts = defaultdict(int)
        for task in tasks:
            for repeat in range(plan['repeats']):
                _, reason = slots.get((product, task, repeat), (None, 'MISSING_OBSERVATION'))
                counts[reason] += 1
        coverage[product] = dict(counts)
        durations = sorted(timings[product])
        measured_costs = costs[product]
        planned = len(tasks) * plan['repeats']
        outcomes = [result for (name, _, _), (result, _) in slots.items() if name == product and result is not None]
        wins = sum(outcomes)
        budget_known = len(measured_costs) == planned and len(outcomes) == planned
        performance[product] = {'planned': planned, 'observed': len(durations), 'verifiedWithinBudget': wins,
                                'verifiedRate': wins / planned if len(outcomes) == planned else None,
                                'elapsedSecondsP50': median(durations) if durations else None,
                                'elapsedSecondsP95': durations[math.ceil(.95 * len(durations)) - 1] if durations else None,
                                'costObserved': len(measured_costs), 'observedCostUsd': sum(measured_costs) if measured_costs else None,
                                'costPerVerifiedCompletionUsd': sum(measured_costs) / wins if budget_known and wins else None}
    for other in PRODUCTS[1:]:
        families = defaultdict(list)
        missing = 0
        for task, case in tasks.items():
            for repeat in range(plan['repeats']):
                a, b = (slots.get((p, task, repeat), (None,))[0] for p in (PRODUCTS[0], other))
                if a is None or b is None:
                    missing += 1
                else:
                    families[case['family']].append(a - b)
        means = [sum(values) / len(values) for values in families.values()]
        # Two simultaneous one-sided lower bounds. Independent units are task
        # families; related variants and repeats cannot increase effective n.
        delta = sum(means) / len(means) if means and not missing else None
        lower = max(-1.0, delta - math.sqrt(2 * math.log(2 / .05) / len(means))) if delta is not None else None
        pairs[other] = {'missingPairs': missing, 'independentFamiliesObserved': len(means),
                        'deltaPoints': None if delta is None else 100 * delta,
                        'lowerSimultaneous95Points': None if lower is None else 100 * lower,
                        'clearsMargin': lower is not None and 100 * lower > plan['marginPoints']}
    return {'schema': 'gateway.native-product-comparison.v1', 'planDigest': plan_id, 'cache': plan['cache'],
            'coverage': coverage, 'performance': performance, 'comparisonsAgainstMobile': pairs, 'marginPoints': plan['marginPoints'],
            'verdict': 'INCOMPLETE' if any(pair['missingPairs'] for pair in pairs.values()) else
            'SUPERIOR_ON_REGISTERED_SUITE' if all(pair['clearsMargin'] for pair in pairs.values()) else 'INCONCLUSIVE'}


def native_answer(product, text):
    """Extract the delivered answer from an isolated single-task native capture."""
    if product == 'Codex':
        events = [json.loads(line) for line in text.splitlines() if line.strip()]
        if (not events or not all(isinstance(e, dict) for e in events) or events[-1].get('type') != 'turn.completed'
                or sum(e.get('type') == 'turn.started' for e in events) != 1
                or sum(e.get('type') == 'turn.completed' for e in events) != 1
                or any(e.get('type') in {'turn.failed', 'error'} for e in events)):
            raise ValueError('Expected one completed Codex exec --json turn')
        threads = [e.get('thread_id') for e in events if e.get('type') == 'thread.started']
        replies = [item.get('text') for event in events if event.get('type') == 'item.completed'
                   for item in [event.get('item') or {}] if item.get('type') == 'agent_message'
                   and item.get('phase') in (None, 'final_answer')]
        ident, answer = (threads[0] if len(threads) == 1 else None), (replies[-1] if replies else None)
    elif product == 'Claude Code':
        value = json.loads(text)
        if not isinstance(value, dict) or value.get('type') != 'result' or value.get('subtype') != 'success' or value.get('is_error') is not False:
            raise ValueError('Expected a successful Claude Code print-mode JSON result')
        ident, answer = value.get('session_id'), value.get('result')
    elif product == 'GPT Mobile AI':
        value = json.loads(text)
        chat = value['conversation']
        messages = value['messages']
        if (chat.get('history_complete') is not True or chat.get('job_state') != 'COMPLETED'
                or len(messages) != 2 or [m.get('role') for m in messages] != ['user', 'assistant']
                or messages[1].get('status') != 'completed' or not messages[0].get('command_id')
                or messages[0]['command_id'] != messages[1].get('command_id')):
            raise ValueError('Expected one completed Gateway conversation with full history')
        ident, answer = chat.get('conversation_id'), messages[-1].get('content')
    else:
        raise ValueError('Unknown native product')
    if not isinstance(ident, str) or not ident.strip() or not isinstance(answer, str) or len(answer.encode()) > 65536:
        raise ValueError('Missing native session identity or invalid final answer')
    return {'runId': digest([product, ident]), 'artifactDigest': content_digest(answer), 'answer': answer}
