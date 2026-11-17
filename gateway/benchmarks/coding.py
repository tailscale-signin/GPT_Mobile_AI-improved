"""Candidate patch grading using independent, executable fixture evidence."""
import ast
from copy import deepcopy
import json
import math

from benchmarks import fixtures, sandbox
from benchmarks.contracts import CODING_WEIGHTS, digest
from v14.engineering_backend import blob_sha, prepare_changes


def same(left, right):
    # JSON equality is typed: true must not accidentally equal integer 1.
    return json.dumps(left, sort_keys=True, ensure_ascii=True, allow_nan=False) == json.dumps(right, sort_keys=True, ensure_ascii=True, allow_nan=False)



# Closed, source-owned diagnostic categories. Never export raw model patches
# or exception messages: both may contain private source and filenames.
PATCH_REPAIR_HINTS = {
    'PATCH_JSON_INVALID': 'Return one complete JSON object without Markdown or trailing commentary.',
    'PATCH_OUTPUT_TRUNCATED': 'Use smaller anchored edits and shorter regression vectors so the JSON can finish.',
    'PATCH_GENERATION_INCOMPLETE': 'Return one complete proposal with a verified terminal response.',
    'PATCH_BLOB_MISMATCH': 'Copy the exact displayed BLOB SHA for each changed file.',
    'PATCH_ANCHOR_CONFLICT': 'Choose old text that occurs exactly once in the displayed file.',
    'PATCH_ANCHOR_NOT_EXPOSED': 'Use only exact anchors from the displayed source.',
    'PATCH_ANCHOR_OVERLAP': 'Use disjoint edits or combine them into one replacement.',
    'PATCH_REPRESENTATION_INVALID': 'Select exactly one of edits, hunks, content, or delete per change.',
    'PATCH_PATH_INVALID': 'Use only the displayed safe relative paths.',
    'PATCH_CONTRACT_INVALID': 'Follow the declared changes, diagnosis, tests and evidence JSON schema.',
    'PATCH_SCOPE_VIOLATION': 'Edit only allowed displayed files; do not request deletion.',
    'PATCH_UNCHANGED': 'Provide an actual correction, not the original source.',
    'REGRESSION_VECTOR_LIMIT': 'Provide no more than six bounded regression vectors.',
    'REGRESSION_VECTOR_INVALID': 'Each vector needs an input object and expected value.',
    'PATCH_OUTPUT_TOO_LARGE': 'Reduce the output to a bounded patch.',
    'PATCH_PARSE_OR_ANCHOR_INVALID': 'Verify JSON structure, exact source anchors and blob hashes.',
}


def classify_patch_rejection(error):
    """Map validator messages to privacy-safe codes, never raw exception text."""
    if isinstance(error, json.JSONDecodeError):
        return 'PATCH_JSON_INVALID'
    message = str(error)
    if message in PATCH_REPAIR_HINTS:
        return message
    matchers = (
        ('ANCHOR_CONFLICT', 'PATCH_ANCHOR_CONFLICT'),
        ('Read the exact existing blob', 'PATCH_BLOB_MISMATCH'),
        ('New files must have null expected_blob_sha', 'PATCH_BLOB_MISMATCH'),
        ('Read the exact patch anchor range', 'PATCH_ANCHOR_NOT_EXPOSED'),
        ('Patch anchors must not overlap', 'PATCH_ANCHOR_OVERLAP'),
        ('Select exactly one patch representation', 'PATCH_REPRESENTATION_INVALID'),
        ('Invalid anchored edits', 'PATCH_ANCHOR_CONFLICT'),
        ('Invalid repository file path', 'PATCH_PATH_INVALID'),
        ('Unsafe repository file path', 'PATCH_PATH_INVALID'),
        ('Invalid change contract', 'PATCH_CONTRACT_INVALID'),
        ('Duplicate file change', 'PATCH_CONTRACT_INVALID'),
        ('Return 1–20 coordinated file changes', 'PATCH_CONTRACT_INVALID'),
    )
    for fragment, code in matchers:
        if fragment in message:
            return code
    return 'PATCH_PARSE_OR_ANCHOR_INVALID'


def rejected_submission(task, code):
    """Grade a received invalid submission without claiming sandbox execution."""
    safe = code if code in PATCH_REPAIR_HINTS else 'PATCH_PARSE_OR_ANCHOR_INVALID'
    return {
        'id': task['id'], 'family': task['family'], 'title': task['title'],
        'capstone': task['capstone'], 'fixtureDigest': task['manifestDigest'],
        'status': 'FAILED', 'executed': False, 'graded': True, 'resolved': False,
        'qualified': False,
        'integrity': 'VIOLATION' if safe in {'PATCH_SCOPE_VIOLATION', 'PATCH_PATH_INVALID'} else 'VERIFIED',
        'error': safe,
        'failureStage': 'MODEL_OUTPUT' if safe in {'PATCH_OUTPUT_TRUNCATED', 'PATCH_GENERATION_INCOMPLETE'} else 'PATCH_VALIDATION',
        'repairHint': PATCH_REPAIR_HINTS[safe], 'score': 0,
        'dimensions': {key: None if key == 'safety' else 0.0 for key in CODING_WEIGHTS},
    }


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('PATCH_CONTRACT_INVALID')
        result[key] = value
    return result


def reject_json_constant(_value):
    raise ValueError('PATCH_CONTRACT_INVALID')


def finite_json_float(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError('PATCH_CONTRACT_INVALID')
    return number


def proposal(text, task):
    if len(text.encode()) > 65536:
        raise ValueError('PATCH_OUTPUT_TOO_LARGE')
    value = json.loads(text, object_pairs_hook=unique_object, parse_constant=reject_json_constant, parse_float=finite_json_float)
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


def grade(task, text, settings, cancel, *, execute=sandbox.execute, docker=None, prior=None):
    result = {'id': task['id'], 'family': task['family'], 'title': task['title'],
              'capstone': task['capstone'], 'fixtureDigest': task['manifestDigest'],
              'status': 'FAILED', 'executed': False, 'graded': False, 'resolved': False,
              'qualified': False,
              'integrity': 'VERIFIED', 'dimensions': {key: None for key in CODING_WEIGHTS}}
    try:
        value, candidate, changes = proposal(text, task)
    except (ValueError, KeyError, TypeError, RecursionError, OverflowError) as error:
        # Keep distinct reasons separate; never retain the raw source exception.
        rejected = rejected_submission(task, classify_patch_rejection(error))
        if isinstance(error, json.JSONDecodeError):
            rejected['parserLocation'] = {'line': error.lineno, 'column': error.colno}
        return rejected
    evaluation_key = digest([candidate, value.get('tests', []), value.get('diagnosis'), value.get('evidence')]) if prior is not None else None
    if prior is not None and evaluation_key in prior:
        return dict(prior[evaluation_key], evaluationReused=True)
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
    # Each call gets an independent fixture value and the host retains the original
    # JSON digest. This verifies the transport boundary, not mutation inside Python.
    inputs = [deepcopy(vector) for vector in (
        *vectors, *reversed(vectors), *(test['input'] for test, _, _ in trusted_tests))]
    input_digest = digest(inputs)
    observation = execute(candidate, inputs, settings, cancel, docker=docker)
    try:
        host_preserved = digest(inputs) == input_digest
    except (ValueError, TypeError, RecursionError):
        host_preserved = False
    if not host_preserved:
        result.update(status='ERROR', integrity='VIOLATION', error='HOST_FIXTURE_INPUT_CHANGED',
                      executed=observation.get('executed'), hostInputBoundary='CHANGED')
        return result
    if observation.get('executed') is not True or observation.get('status') == 'ERROR':
        result.update(status='ERROR', error=observation.get('error', 'SANDBOX_EXECUTION_NOT_CONFIRMED'),
                      executed=observation.get('executed'), outputDigest=observation.get('outputDigest'))
        return result
    outputs = observation.get('outputs', [])
    received = len(outputs) == len(inputs)
    # The driver shares a process with candidate code. Its input_preserved flag
    # is an untrusted observation, not independent input-mutation verification.
    # Retain measured outputs and patch scope, but the required preservation
    # contract stays unknown and cannot qualify a complete task or score.
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
    output_correct = bool(outcomes) and all(outcomes) and all(repeated)
    result.update(executed=True, graded=True,
                  resolved=False, outputCorrect=output_correct,
                  status='BLOCKED' if output_correct else 'FAILED',
                  dimensions={'functional': sum(functional) / len(functional),
                              'regression': sum(regression) / len(regression),
                              'tests': len(killed_faults) / len(task['sources']),
                              'diagnosis': sum(bool(x) for x in diagnosed) / len(task['sources']),
                              'design': source_design(candidate),
                              'workflow': 1.0, 'recovery': sum(repeated) / len(repeated),
                              'safety': None, 'evidence': float(truthful)},
                  checks={'hiddenPassed': sum(outcomes), 'hiddenTotal': len(outcomes),
                          'replayPassed': sum(repeated), 'replayTotal': len(repeated),
                          'validRegressionVectors': len(trusted_tests), 'bugRevealingRegressionVectors': sum(tested),
                          'independentDefectsCaught': len(killed_faults), 'independentDefectsTotal': len(task['sources']),
                          'unchangedSourceFiles': len(task['sources']) - len(changes)},
                  uncoveredDefectModules=sorted(set(task['sources']) - killed_faults),
                  patchDigest=digest(candidate), outputDigest=observation.get('outputDigest'),
                  validationMs=observation.get('durationMs'), sandboxImageDigest=settings['sandbox_image'].split('@')[-1],
                  executionScope='LOCAL_CONTAINER_NO_NETWORK',
                  patchScopeVerification='PASSED',
                  hostInputBoundary='PRESERVED', inputMutationVerification='NOT_VERIFIED',
                  qualificationBlockers=['INPUT_PRESERVATION_NOT_VERIFIED'],
                  dimensionDefinitions={
                      'functional': 'Hidden contract vectors', 'regression': 'Empty/invalid request boundaries',
                      'tests': 'Correct model-authored regression vectors that detect each independently calibrated faulty module',
                      'diagnosis': 'Exact changed source anchors identified in each faulty file',
                      'design': 'Required module function interfaces parse and remain intact',
                      'workflow': 'Exact inspected blob identities and anchored patch contract',
                      'recovery': 'Same inputs remain correct after reversed-order repeated calls',
                      'safety': 'Allowed disposable patch scope and independently verified caller-input preservation',
                      'evidence': 'Candidate accurately lists changed files and does not claim unexecuted tests'})
    if observation.get('error'):
        result['error'] = observation['error']
    result['score'] = None
    result['earned'] = sum(CODING_WEIGHTS[key] * score for key, score in result['dimensions'].items() if score is not None)
    result['possible'] = sum(CODING_WEIGHTS.values())
    result['unknownWeight'] = sum(CODING_WEIGHTS[key] for key, score in result['dimensions'].items() if score is None)
    if prior is not None and received and observation.get('status') == 'COMPLETED':
        prior[evaluation_key] = dict(result)
    return result
