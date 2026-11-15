"""Candidate patch grading using independent, executable fixture evidence."""
import ast
from copy import deepcopy
import json

from benchmarks import fixtures, sandbox
from benchmarks.contracts import CODING_WEIGHTS, digest
from v14.engineering_backend import blob_sha, prepare_changes


def same(left, right):
    # JSON equality is typed: true must not accidentally equal integer 1.
    return json.dumps(left, sort_keys=True, ensure_ascii=True, allow_nan=False) == json.dumps(right, sort_keys=True, ensure_ascii=True, allow_nan=False)


def proposal(text, task):
    if len(text.encode()) > 65536:
        raise ValueError('PATCH_OUTPUT_TOO_LARGE')
    value = json.loads(text)
    if not isinstance(value, dict) or set(value) - {'changes', 'diagnosis', 'tests', 'evidence'}:
        raise ValueError('PATCH_CONTRACT_INVALID')
    sources = task['sources']
    changes = value.get('changes')
    if not isinstance(changes, list) or not changes or any(not isinstance(row, dict) or row.get('path') not in sources or row.get('delete') for row in changes):
        raise ValueError('PATCH_SCOPE_VIOLATION')
    files = {path: {'content': source, 'sha': blob_sha(source), 'visible_characters': len(source)} for path, source in sources.items()}
    tree = {path: {'sha': blob_sha(source), 'mode': '100644'} for path, source in sources.items()}
    applied = prepare_changes(value, files, tree)
    if not applied:
        raise ValueError('PATCH_UNCHANGED')
    candidate = dict(sources)
    for change in applied:
        candidate[change['path']] = change['content']
    tests = value.get('tests', [])
    if not isinstance(tests, list) or not 0 <= len(tests) <= 6 or len(json.dumps(tests)) > 16384:
        raise ValueError('REGRESSION_VECTOR_LIMIT')
    for test in tests:
        if not isinstance(test, dict) or set(test) != {'input', 'expected'} or not isinstance(test['input'], dict) or len(json.dumps(test)) > 4096:
            raise ValueError('REGRESSION_VECTOR_INVALID')
        items = test['input'].get('items')
        if isinstance(items, list) and len(items) > 64:
            raise ValueError('REGRESSION_VECTOR_LIMIT')
    return value, candidate, applied


def baseline(task, request):
    """Evaluate only our own shipped defective reference, never candidate source."""
    return fixtures.trusted_fixture(task, request, broken=True)


def source_design(candidate):
    """Check executable interface constraints, not stylistic preferences."""
    checks = []
    for path, source in candidate.items():
        try:
            parsed = ast.parse(source)
            name = 'handle' if path == 'service.py' else 'solve'
            function = next((x for x in parsed.body if isinstance(x, ast.FunctionDef) and x.name == name), None)
            checks.append(function is not None and len(function.args.args) == (1 if path == 'service.py' else 2))
        except (SyntaxError, ValueError, RecursionError):
            checks.append(False)
    return sum(checks) / len(checks)


def grade(task, text, settings, cancel, *, execute=sandbox.execute, docker=None):
    result = {'id': task['id'], 'family': task['family'], 'title': task['title'],
              'capstone': task['capstone'], 'fixtureDigest': task['manifestDigest'],
              'status': 'FAILED', 'executed': False, 'graded': False, 'resolved': False,
              'integrity': 'VERIFIED', 'dimensions': {key: None for key in CODING_WEIGHTS}}
    try:
        value, candidate, changes = proposal(text, task)
    except (ValueError, KeyError, TypeError, RecursionError, OverflowError) as error:
        code = str(error) if str(error) in {'PATCH_OUTPUT_TOO_LARGE', 'PATCH_CONTRACT_INVALID', 'PATCH_SCOPE_VIOLATION', 'PATCH_UNCHANGED', 'REGRESSION_VECTOR_LIMIT', 'REGRESSION_VECTOR_INVALID'} else 'PATCH_PARSE_OR_ANCHOR_INVALID'
        # A received model answer that cannot form a patch is an observed failed
        # submission. Infrastructure/transport failures remain ungraded instead.
        result.update(error=code, integrity='VIOLATION' if code == 'PATCH_SCOPE_VIOLATION' else 'VERIFIED',
                      graded=True, score=0, dimensions={key: 0.0 for key in CODING_WEIGHTS})
        return result
    # Randomized data was frozen before dispatch. The model does not receive
    # these inputs until its candidate runs; expected values never enter Docker.
    vectors = task['vectors']
    targets = [fixtures.expected(task, vector) for vector in vectors]
    declared = value.get('tests', [])
    trusted_tests = []
    test_ids = set()
    for test in declared:
        try:
            expected = fixtures.expected(task, test['input'])
            identity = digest(test['input'])
            if identity not in test_ids and same(expected, test['expected']):
                test_ids.add(identity)
                killed = set()
                for path in task['sources']:
                    mutant = dict(task, referenceSources=dict(task['referenceSources'], **{path: task['sources'][path]}))
                    try:
                        actual = fixtures.trusted_fixture(mutant, test['input'])
                    except Exception:
                        actual = {'error': 'CALIBRATED_MUTANT_FAILURE'}
                    if not same(actual, expected):
                        killed.add(path)
                trusted_tests.append((test, expected, killed))
        except (ValueError, TypeError, KeyError, RecursionError, OverflowError):
            continue
    inputs = vectors + list(reversed(vectors)) + [test['input'] for test, _, _ in trusted_tests]
    observation = execute(candidate, inputs, settings, cancel, docker=docker)
    if observation.get('executed') is not True or observation.get('status') == 'ERROR':
        result.update(status='ERROR', error=observation.get('error', 'SANDBOX_EXECUTION_NOT_CONFIRMED'),
                      executed=observation.get('executed'), outputDigest=observation.get('outputDigest'))
        return result
    outputs = observation.get('outputs', [])
    received = len(outputs) == len(inputs)
    outcomes = [received and 'output' in outputs[i] and same(outputs[i]['output'], expected)
                for i, expected in enumerate(targets)]
    repeated = [received and 'output' in outputs[len(vectors) + i] and same(outputs[len(vectors) + i]['output'], expected)
                for i, expected in enumerate(reversed(targets))]
    tested, killed_faults = [], set()
    for i, (_, expected, killed) in enumerate(trusted_tests):
        row = outputs[2 * len(vectors) + i] if received else {}
        passed = 'output' in row and same(row['output'], expected)
        tested.append(passed and bool(killed))
        if passed:
            killed_faults.update(killed)
    diagnosis = value.get('diagnosis', {})
    diagnosed = []
    for change in changes:
        path = change['path']
        anchor = diagnosis.get(path) if isinstance(diagnosis, dict) else None
        diagnosed.append(isinstance(anchor, str) and anchor.strip() and anchor in task['sources'][path] and anchor not in candidate[path])
    evidence = value.get('evidence', {})
    truthful = (isinstance(evidence, dict) and evidence.get('tests_executed') is False and
                isinstance(evidence.get('changed_files'), list) and
                all(isinstance(path, str) for path in evidence['changed_files']) and
                sorted(evidence['changed_files']) == sorted(change['path'] for change in changes))
    functional = outcomes[:-2] or outcomes
    regression = outcomes[-2:]
    result.update(executed=True, graded=True,
                  resolved=bool(outcomes) and all(outcomes) and all(repeated),
                  status='PASSED' if outcomes and all(outcomes) and all(repeated) else 'FAILED',
                  dimensions={'functional': sum(functional) / len(functional),
                              'regression': sum(regression) / len(regression),
                              'tests': len(killed_faults) / len(task['sources']),
                              'diagnosis': sum(bool(x) for x in diagnosed) / len(task['sources']),
                              'design': source_design(candidate),
                              'workflow': 1.0, 'recovery': sum(repeated) / len(repeated),
                              'safety': 1.0, 'evidence': float(truthful)},
                  checks={'hiddenPassed': sum(outcomes), 'hiddenTotal': len(outcomes),
                          'replayPassed': sum(repeated), 'replayTotal': len(repeated),
                          'validRegressionVectors': len(trusted_tests), 'bugRevealingRegressionVectors': sum(tested),
                          'independentDefectsCaught': len(killed_faults), 'independentDefectsTotal': len(task['sources']),
                          'unchangedSourceFiles': len(task['sources']) - len(changes)},
                  patchDigest=digest(candidate), outputDigest=observation.get('outputDigest'),
                  validationMs=observation.get('durationMs'), sandboxImageDigest=settings['sandbox_image'].split('@')[-1],
                  executionScope='LOCAL_CONTAINER_NO_NETWORK',
                  inputMutationVerification='NOT_VERIFIED',
                  dimensionDefinitions={
                      'functional': 'Hidden contract vectors', 'regression': 'Empty/invalid request boundaries',
                      'tests': 'Correct model-authored regression vectors that detect each independently calibrated faulty module',
                      'diagnosis': 'Exact changed source anchors identified in each faulty file',
                      'design': 'Required module function interfaces parse and remain intact',
                      'workflow': 'Exact inspected blob identities and anchored patch contract',
                      'recovery': 'Same inputs remain correct after reversed-order repeated calls',
                      'safety': 'Candidate patch remains within the allowed disposable source files',
                      'evidence': 'Candidate accurately lists changed files and does not claim unexecuted tests'})
    if observation.get('error'):
        result['error'] = observation['error']
    result['score'] = sum(CODING_WEIGHTS[key] * score for key, score in result['dimensions'].items())
    return result
