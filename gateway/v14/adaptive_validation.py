"""Static test discovery and semantic selection of immutable approved commands."""
import hashlib
import json

from desktop.acceptance_discovery import parse_tests
from v14.engineering_workspace import load_file


def capabilities(command, cases):
    words = ' '.join(command).lower()
    result = ['check_patch']
    if cases or any(word in words for word in ('pytest', 'unittest', 'testdebug', 'testrelease', ' test', 'check_gateway')):
        result.append('run_test')
    if any(word in words for word in ('assemble', 'compile', 'kotlinc', 'javac', 'py_compile')):
        result.append('compile_module')
    if any(word in words for word in ('lint', 'ruff', 'checkstyle', 'flake8')):
        result.append('lint_changed_files')
    return result


def discover(backend, job, action, checks):
    query, offset = action.get('query', ''), action.get('offset', 0)
    if not isinstance(query, str) or len(query) > 200 or type(offset) is not int or offset < 0:
        raise ValueError('Invalid static test discovery page')
    paths = sorted(path for path, entry in job['data']['tree'].items()
                   if entry['mode'] in {'100644', '100755'} and 'test' in path.casefold()
                   and query.casefold() in path.casefold() and path.endswith(('.py', '.kt', '.java')))
    binding = hashlib.sha256(json.dumps([job['data']['head_sha'], query, paths, checks], sort_keys=True).encode()).hexdigest()
    cursor = action.get('cursor')
    if cursor is None:
        cursor = {'binding': binding, 'file_offset': offset, 'case_offset': 0}
    if (not isinstance(cursor, dict) or set(cursor) != {'binding', 'file_offset', 'case_offset'}
            or cursor['binding'] != binding
            or any(type(cursor[key]) is not int or cursor[key] < 0 for key in ('file_offset', 'case_offset'))
            or cursor['file_offset'] > len(paths)
            or cursor['file_offset'] == len(paths) and cursor['case_offset'] != 0):
        raise ValueError('Test discovery cursor belongs to a different query, source or approved check catalog')
    offset, case_offset = cursor['file_offset'], cursor['case_offset']
    rows, skipped = [], []
    end = min(offset + 10, len(paths))
    next_cursor = None
    for index in range(offset, end):
        path = paths[index]
        if backend.cancellation(job).is_set():
            raise InterruptedError('Test discovery cancelled')
        original = load_file(backend, job, path)
        if len(original['content'].encode()) > 256 * 1024:
            if case_offset:
                raise ValueError('Test discovery cursor exceeds the retained case window')
            skipped.append({'path': path, 'reason': 'DISCOVERY_SIZE_LIMIT'})
            continue
        candidates = parse_tests(path, original['content'])
        if case_offset > len(candidates):
            raise ValueError('Test discovery cursor exceeds the retained case window')
        selected = candidates[case_offset:case_offset + 256 - len(rows)]
        for candidate in selected:
            approved = [check['index'] for check in checks if candidate['case_id'] in check['cases']]
            rows.append(dict(candidate, blob_sha=original['sha'], approved_check_indices=approved,
                             policy_state='APPROVED' if approved else 'HOST_POLICY_REQUIRED'))
        consumed = case_offset + len(selected)
        if consumed < len(candidates):
            next_cursor = dict(cursor, file_offset=index, case_offset=consumed)
            break
        case_offset = 0
        if len(rows) == 256:
            end = index + 1
            break
    if next_cursor is None and end < len(paths):
        next_cursor = dict(cursor, file_offset=end, case_offset=0)
    return {'tests': rows, 'truncated': next_cursor is not None, 'skipped': skipped, 'executed': False,
            'next_cursor': next_cursor,
            'next_offset': next_cursor['file_offset'] if next_cursor is not None and next_cursor['case_offset'] == 0 else None,
            'matching_files': len(paths), 'scope': 'discovery'}
