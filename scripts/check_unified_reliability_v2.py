"""Validate v2 scope and evidence references; do not infer device qualification.

This checker intentionally does not run Android or native acceptance tests.
Only recorded, verifiable evidence can satisfy the release gate.
"""

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ROOT / 'docs/implementation/unified-reliability-v2'
_COMMIT_SHA = re.compile(r'^[0-9a-fA-F]{40}$')
_REPOSITORY_PREFIXES = {'app', 'docs', 'scripts', '.github', 'gateway'}


def _require(condition, message):
    # Explicit checks still run with PYTHONOPTIMIZE=1 or `python -O`.
    if not condition:
        raise ValueError(message)


def _evidence_path(reference):
    _require(isinstance(reference, str) and bool(reference.strip()), 'Evidence reference is blank')
    relative = Path(reference)
    _require(not relative.is_absolute() and '..' not in relative.parts,
             f'Unsafe evidence reference: {reference}')
    base = ROOT if relative.parts[0] in _REPOSITORY_PREFIXES else SCOPE
    path = (base / relative).resolve()
    _require(path.is_relative_to(ROOT.resolve()), f'Evidence escapes repository: {reference}')
    _require(path.is_file(), f'Missing evidence file: {reference}')
    return path


def _check_acceptance_receipt(case, path):
    # Our committed machine-generated fixture receipts have a known schema.
    # A file path alone is not proof that this specific case/commit passed.
    if path.parent != (SCOPE / 'evidence').resolve() or path.suffix != '.json':
        return
    receipt = json.loads(path.read_text(encoding='utf-8'))
    _require(receipt.get('commit') == case['commit'],
             f"{case['id']}: fixture receipt commit does not match declared commit")
    fixtures = receipt.get('acceptance_fixtures', {}).get(case['id'], [])
    _require(bool(fixtures) and all(row.get('outcome') == 'PASS' for row in fixtures),
             f"{case['id']}: fixture receipt has no passing case evidence")


def validate(require_done=False):
    tasks = json.loads((SCOPE / 'tasks.json').read_text(encoding='utf-8'))
    cases = json.loads((SCOPE / 'acceptance-tests.json').read_text(encoding='utf-8'))
    expected_tasks = {f'V{i:02}' for i in range(1, 49)}
    expected_cases = {f'{prefix}{i:02}' for prefix, count in
                      [('LA-QA', 36), ('LC-T', 18), ('CM-T', 26), ('IX', 30)]
                      for i in range(1, count + 1)}
    _require(isinstance(tasks, list) and len(tasks) == 48 and
             {t['id'] for t in tasks} == expected_tasks, 'Task scope was changed')
    _require(isinstance(cases, list) and len(cases) == 110 and
             {c['id'] for c in cases} == expected_cases, 'Acceptance scope was changed')
    task_by_id = {task['id']: task for task in tasks}
    case_by_id = {case['id']: case for case in cases}

    for task in tasks:
        _require(set(task['dependencies']) <= expected_tasks, f"{task['id']}: unknown dependency")
        _require(set(task['acceptance_ids']) <= expected_cases, f"{task['id']}: unknown acceptance")
        _require(task['status'] in {'NOT_STARTED', 'IN_PROGRESS', 'IMPLEMENTED',
                                    'VERIFIED', 'DONE', 'BLOCKED'},
                 f"{task['id']}: invalid task status")
        if task['status'] in {'VERIFIED', 'DONE'}:
            commits = task.get('implementation_commits', [])
            evidence = task.get('evidence', [])
            _require(bool(commits) and all(isinstance(sha, str) and
                                          _COMMIT_SHA.fullmatch(sha) for sha in commits),
                     f"{task['id']}: verified task needs real commit hashes")
            _require(bool(evidence), f"{task['id']}: verified task needs evidence")
            for reference in evidence:
                _evidence_path(reference)
        if task['status'] == 'DONE':
            _require(not task.get('remaining_work'), f"{task['id']}: unfinished work remains")
            _require(all(task_by_id[dep]['status'] == 'DONE' for dep in task['dependencies']),
                     f"{task['id']}: unfinished prerequisite task")
            _require(all(case_by_id[case_id]['outcome'] == 'PASS'
                         for case_id in task['acceptance_ids']),
                     f"{task['id']}: required acceptance case not passed")

    for case in cases:
        _require(case['outcome'] in {'NOT_RUN', 'PASS', 'FAIL', 'BLOCKED'},
                 f"{case['id']}: invalid acceptance result")
        if case['outcome'] == 'PASS':
            _require(isinstance(case.get('commit'), str) and
                     _COMMIT_SHA.fullmatch(case['commit']),
                     f"{case['id']}: passing case needs a valid commit hash")
            references = case.get('evidence', [])
            _require(bool(references), f"{case['id']}: passing case needs evidence")
            for reference in references:
                _check_acceptance_receipt(case, _evidence_path(reference))

    if require_done:
        incomplete = [t['id'] for t in tasks if t['status'] != 'DONE']
        unpassed = [c['id'] for c in cases if c['outcome'] != 'PASS']
        _require(not incomplete and not unpassed,
                 f'Cannot claim full v2 completion: tasks={incomplete}; cases={unpassed}')
    return len(tasks), len(cases)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--require-done', action='store_true')
    args = parser.parse_args()
    print('Scope inventory:', validate(args.require_done), '(not proof of device qualification)')
