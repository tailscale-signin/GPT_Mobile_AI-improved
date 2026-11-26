"""Changed-file coverage for a specific PR base/head, including GitHub's 3,000-file REST ceiling."""
import io
import re

from desktop.repository_validation import git_prefix
from v14.engineering_source import prepare_source
from v14.engineering_validation import cancellation_scope, load_policy, run_bounded
from v14.validation_host import executable


REST_FILE_LIMIT = 3000


def mirror_inventory(backend, job, base, head):
    """Diff the exact Git objects in this repository's host-approved mirror, including renames."""
    if not all(re.fullmatch(r'[0-9a-f]{40}', sha) for sha in (base, head)):
        raise ValueError('Cannot compare unpinned revisions')
    _, mirror, _, _, _ = load_policy(job['request']['repository'])
    cancel = backend.cancellation(job)
    for sha in (base, head):
        commit = backend.api(backend.root(job) + '/git/commits/' + sha, job=job)
        if commit.get('sha') != sha:
            raise ValueError('Commit does not match the pinned revision')
        prepare_source(dict(job, data=dict(job['data'], head_sha=sha)), backend.token(), commit['tree']['sha'], cancel)
    output = io.BytesIO()
    # Three-dot uses the PR merge base. No worktree, path interpolation, or external diff drivers.
    command = git_prefix(executable('git')) + ['--no-replace-objects', '--git-dir=' + str(mirror),
        'diff', '--no-ext-diff', '--no-textconv', '--find-renames', '--name-status', '-z', base + '...' + head, '--']
    with cancellation_scope(cancel, job['deadline']):
        status = run_bounded(command, mirror.parent, 60, maximum=16 * 1024 * 1024, destination=output)
    if status['exit_code'] != 0:
        raise ValueError('The approved mirror could not compare these commits')
    raw = output.getvalue()
    if raw and not raw.endswith(b'\0'):
        raise ValueError('Incomplete local diff')
    fields = iter(raw[:-1].split(b'\0') if raw else [])
    paths = {}
    for code in fields:
        if not re.fullmatch(rb'[ADMT]|R[0-9]+', code):
            raise ValueError('Unsupported local diff record')
        first = next(fields, b'').decode('utf-8')
        path = next(fields, b'').decode('utf-8') if code.startswith(b'R') else first
        if not first or not path or path in paths:
            raise ValueError('Incomplete or duplicate local filename')
        paths[path] = first if code.startswith(b'R') else None
    return paths


def collect(backend, job, pull, base, head):
    endpoint = backend.root(job) + '/pulls/' + str(pull)

    def revision():
        pr = backend.api(endpoint, job=job)
        if (pr['head']['sha'] != head or pr['base']['sha'] != base or
                pr['base']['ref'] != job['request']['base'] or pr['state'] != 'open'):
            raise ValueError('PR changed while collecting its changed files')
        count = pr.get('changed_files')
        if type(count) is not int or count < 0:
            raise ValueError('GitHub did not report a valid PR changed-file count')
        return count

    expected = revision()
    limit = min(expected, REST_FILE_LIMIT)
    paths = {}
    reason = None
    for start in range(0, max(1, limit), 100):
        rows = backend.api(endpoint + '/files?per_page=100&page=' + str(1 + start // 100), job=job)
        if not isinstance(rows, list):
            raise ValueError('Invalid PR file collection')
        for row in rows:
            path = row.get('filename') if isinstance(row, dict) else None
            previous = row.get('previous_filename') if path and row.get('status') == 'renamed' else None
            if (not isinstance(path, str) or not path or path in paths or
                    row.get('status') == 'renamed' and (not isinstance(previous, str) or not previous)):
                reason = 'DUPLICATE_OR_INVALID_FILENAME'
                continue
            paths[path] = previous
        if len(rows) != min(100, limit - start):
            reason = 'INCOMPLETE_REMOTE_PAGE'
            break

    source = 'GITHUB_PR_FILES'
    if reason is None and expected > REST_FILE_LIMIT:
        reason = 'GITHUB_PR_FILE_LIMIT'
    if reason:
        try:
            exact = mirror_inventory(backend, job, base, head)
        except (InterruptedError, PermissionError):
            raise
        except (ValueError, FileNotFoundError, TimeoutError):
            exact = None
        if exact is not None:
            if len(exact) == expected and all(path in exact and exact[path] == old for path, old in paths.items()):
                paths, source, reason = exact, 'APPROVED_GIT_MIRROR', None
            else:
                reason = 'LOCAL_DIFF_DISAGREES_WITH_GITHUB'
    if revision() != expected:
        raise ValueError('PR file count changed while collecting its changed files')
    coverage = {'source': source, 'base_sha': base, 'head_sha': head, 'expected': expected,
                'observed_unique': len(paths), 'missing': max(0, expected - len(paths)), 'complete': reason is None,
                'renamed': {path: old for path, old in paths.items() if old is not None}}
    if reason:
        coverage['reason'] = reason
    return list(paths), coverage
