"""Static test discovery and semantic selection of immutable approved commands."""
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
    rows, skipped = [], []
    for path in paths[offset:offset + 10]:
        if backend.cancellation(job).is_set():
            raise InterruptedError('Test discovery cancelled')
        original = load_file(backend, job, path)
        if len(original['content'].encode()) > 256 * 1024:
            skipped.append({'path': path, 'reason': 'DISCOVERY_SIZE_LIMIT'})
            continue
        for candidate in parse_tests(path, original['content']):
            approved = [check['index'] for check in checks if candidate['case_id'] in check['cases']]
            rows.append(dict(candidate, blob_sha=original['sha'], approved_check_indices=approved,
                             policy_state='APPROVED' if approved else 'HOST_POLICY_REQUIRED'))
    return {'tests': rows[:256], 'truncated': len(rows) > 256, 'skipped': skipped, 'executed': False,
            'next_offset': offset + 10 if offset + 10 < len(paths) else None,
            'matching_files': len(paths), 'scope': 'discovery'}
