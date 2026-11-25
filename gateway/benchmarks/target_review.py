"""Independent labels for evidence the production intelligence rubric cannot infer."""
from hashlib import sha256

from benchmarks import github_intelligence, github_intelligence_targets, product_trials
from benchmarks.contracts import digest

OBSERVATION = 'gateway.intelligence-target-observation.v1'
GRADE = 'gateway.intelligence-target-grade.v1'
TARGETS = ('valid_task_intent', 'unnecessary_tool_calls', 'false_implementation_claims',
           'unreconciled_duplicate_writes', 'preventable_provider_payload_failures')


def inventory_template(report):
    score = github_intelligence.score(report)
    if not score['taskSetDigest'] or report.get('githubIntelligenceScore', score) != score:
        raise ValueError('Review requires ten complete, distinct jobs from the same verified qualification route')
    return {'schema': OBSERVATION, 'qualificationDigest': digest(report), 'taskSetDigest': score['taskSetDigest'],
            'cases': {row['job_id']: dict.fromkeys(TARGETS) for row in report['cases']}}


def check_slots(value, expected):
    if not isinstance(value, dict) or set(value) != set(expected):
        raise ValueError('Review inventory differs from the frozen case, target or event set')


def check_binding(payload, template):
    check_slots(payload, template)
    if any(payload[k] != value for k, value in template.items() if k != 'cases'):
        raise ValueError('Review evidence belongs to another report or signed observation')
    check_slots(payload['cases'], template['cases'])


def grade_template(report, inventory):
    check_binding(inventory, inventory_template(report))
    grades = {}
    seen = {target: set() for target in TARGETS}
    cases = {row['job_id']: row for row in report['cases']}
    for job, row in inventory['cases'].items():
        check_slots(row, TARGETS)
        grades[job] = {}
        for target, events in row.items():
            if events is None:
                grades[job][target] = None
                continue
            if (not isinstance(events, list) or len(events) > 8192 or not all(product_trials.sha(e) for e in events)
                    or len(set(events)) != len(events) or not seen[target].isdisjoint(events)):
                raise ValueError('Each target needs distinct immutable event digests for its complete observed inventory')
            if target == 'valid_task_intent' and len(events) != 1:
                raise ValueError('An observed job needs exactly one accepted task intent')
            posts = cases[job].get('accepted_provider_posts')
            if target == 'preventable_provider_payload_failures' and type(posts) is int and len(events) != posts:
                raise ValueError('The reviewed dispatch count differs from the verified provider receipts')
            seen[target].update(events)
            grades[job][target] = dict.fromkeys(events)
    return {'schema': GRADE, 'qualificationDigest': inventory['qualificationDigest'],
            'observationDigest': digest(inventory), 'cases': grades}


def reviewed_metrics(report, inventory, grades):
    template = grade_template(report, inventory)
    check_binding(grades, template)
    cases = {row['job_id']: row for row in report['cases']}
    result = github_intelligence_targets.measure(dict(report, githubIntelligenceScore=github_intelligence.score(report)))
    for job, row in grades['cases'].items():
        check_slots(row, TARGETS)
    for target in TARGETS:
        positives = samples = complete = 0
        for job, expected in template['cases'].items():
            labels = grades['cases'][job][target]
            if expected[target] is None:
                if labels is not None:
                    raise ValueError('A grade cannot add unobserved events')
                continue
            check_slots(labels, expected[target])
            for label in labels.values():
                if label is None:
                    continue
                check_slots(label, ('positive', 'evidenceDigest'))
                if type(label['positive']) is not bool or not product_trials.sha(label['evidenceDigest']):
                    raise ValueError('Each classified event needs a boolean judgment and an independent evidence digest')
                if target == 'preventable_provider_payload_failures' and label['positive'] and type(cases[job].get('accepted_provider_posts')) is int:
                    raise ValueError('The failure label contradicts verified acceptance for every provider dispatch')
                positives += int(label['positive'])
                samples += 1
            complete += int(all(label is not None for label in labels.values()))
        metric = result['metrics'][target]
        metric['reviewCoverage'] = dict(completeCases=complete, plannedCases=len(cases), labelledEvents=samples)
        if complete != len(cases) or not samples:
            continue
        count = metric['unit'] == 'count'
        value = positives if count else 100 * positives / samples
        limit = metric['target']
        passed = {'<': value < limit, '>=': value >= limit, '==': value == limit}[metric['comparison']]
        metric.update(value=value, samples=samples, positiveEvents=positives, status='MET' if passed else 'MISSED',
                      evidenceSource='INDEPENDENTLY_LABELLED_EVENTS')
    return result


def score_signed(report, observation, grade, observer_key, grader_key):
    if observer_key.public_bytes_raw() == grader_key.public_bytes_raw():
        raise ValueError('The observer and grader must use distinct trusted keys')
    inventory = product_trials.verified(observation, observer_key, OBSERVATION)
    grades = product_trials.verified(grade, grader_key, GRADE)
    result = reviewed_metrics(report, inventory, grades)
    result['independentReview'] = {'qualificationDigest': digest(report), 'observationDigest': digest(inventory),
                                   'gradeDigest': digest(grades),
                                   'observerKeySha256': sha256(observer_key.public_bytes_raw()).hexdigest(),
                                   'graderKeySha256': sha256(grader_key.public_bytes_raw()).hexdigest()}
    return result
