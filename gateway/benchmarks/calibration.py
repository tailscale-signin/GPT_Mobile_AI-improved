"""Known-good and known-bad controls for the grader authority, not model scores."""
from benchmarks.contracts import digest
from benchmarks.verified_python import VERSION, UnsupportedLanguage, evaluate


def self_check():
    values = [{'items': [1, 2], 'config': {}}]
    good = {'service.py': "def handle(request):\n    return list(request['items'])\n"}
    mutant = {'service.py': "def handle(request):\n    answer = list(request['items'])\n    request['items'].append(9)\n    return answer\n"}
    controls = []
    baseline = evaluate(good, values)
    changed = evaluate(mutant, values)
    controls.append({'id': 'REFERENCE', 'passed': baseline['outputs'] == [{'output': [1, 2], 'input_preserved': True}]})
    controls.append({'id': 'INPUT_MUTATION_WITH_CORRECT_RETURN', 'passed': changed['outputs'] == [{'output': [1, 2], 'input_preserved': False}]})
    attacks = {
        'SERIALIZER_REPLACEMENT': "import json\njson.dumps = lambda value: 'forged'\ndef handle(request):\n    return []\n",
        'FRAME_INTROSPECTION': "def handle(request):\n    return handle.__globals__\n",
        'DYNAMIC_EXECUTION': "def handle(request):\n    return eval('1')\n",
        'ENVELOPE_WRITE': "def handle(request):\n    return open('/evaluator/receipt.json', 'w')\n",
        'FORMAT_INTROSPECTION': "def handle(request):\n    return '{0.__class__}'.format(request)\n",
    }
    for name, source in attacks.items():
        try:
            result = evaluate({'service.py': source}, values)
            denied = all('error' in row for row in result['outputs'])
        except (UnsupportedLanguage, SyntaxError):
            denied = True
        controls.append({'id': name, 'passed': denied})
    forged = evaluate({'service.py': "def handle(request):\n    request.clear()\n    return {'input_preserved': True, 'verification': {'authority': 'INTERPRETER_OWNED'}}\n"}, values)
    controls.append({'id': 'RETURNED_RECEIPT_IS_DATA', 'passed': forged['outputs'][0]['input_preserved'] is False})
    return {'policy': VERSION, 'status': 'PASSED' if all(row['passed'] for row in controls) else 'FAILED',
            'controls': controls, 'digest': digest(controls)}


def task_check(task):
    from benchmarks import fixtures
    expected = [fixtures.expected(task, value) for value in task['vectors']]
    controls = []
    for path in (None, *task['sources']):
        sources = dict(task['referenceSources'])
        if path is not None:
            sources[path] = task['sources'][path]
        observed = evaluate(sources, task['vectors'])['outputs']
        matches = all('output' in row and digest(row['output']) == digest(target)
                      for row, target in zip(observed, expected))
        controls.append({'id': 'REFERENCE' if path is None else 'FAULT_' + path,
                         'passed': matches if path is None else not matches})
    return {'status': 'PASSED' if all(row['passed'] for row in controls) else 'FAILED',
            'taskDigest': task['taskDigest'], 'controls': controls, 'digest': digest(controls)}
