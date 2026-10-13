"""Validate scope identity and declared evidence; this does not execute Android tests."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ROOT / 'docs/implementation/unified-reliability-v2'


def validate(require_done=False):
    tasks = json.loads((SCOPE / 'tasks.json').read_text())
    cases = json.loads((SCOPE / 'acceptance-tests.json').read_text())
    expected_tasks = {f'V{i:02}' for i in range(1, 49)}
    expected_cases = {f'{prefix}{i:02}' for prefix, count in [('LA-QA', 36), ('LC-T', 18), ('CM-T', 26), ('IX', 30)] for i in range(1, count + 1)}
    assert len(tasks) == 48 and {t['id'] for t in tasks} == expected_tasks, 'Task scope was changed'
    assert len(cases) == 110 and {t['id'] for t in cases} == expected_cases, 'Acceptance scope was changed'
    for task in tasks:
        assert set(task['dependencies']) <= expected_tasks
        assert set(task['acceptance_ids']) <= expected_cases
        assert task['status'] in {'NOT_STARTED', 'IN_PROGRESS', 'IMPLEMENTED', 'VERIFIED', 'DONE', 'BLOCKED'}
        if task['status'] in {'VERIFIED', 'DONE'}:
            assert task['implementation_commits'] and task['evidence'], task['id']
    for case in cases:
        assert case['outcome'] in {'NOT_RUN', 'PASS', 'FAIL', 'BLOCKED'}
        if case['outcome'] == 'PASS':
            assert case['commit'] and case['evidence'], case['id']
    if require_done:
        incomplete = [t['id'] for t in tasks if t['status'] != 'DONE']
        unpassed = [t['id'] for t in cases if t['outcome'] != 'PASS']
        assert not incomplete and not unpassed, f'Cannot claim full v2 completion: tasks={incomplete}; cases={unpassed}'
    return len(tasks), len(cases)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--require-done', action='store_true')
    args = parser.parse_args()
    print('Scope inventory:', validate(args.require_done), '(not proof of implementation or device qualification)')
