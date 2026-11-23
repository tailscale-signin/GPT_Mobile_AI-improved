"""Convert provider output into exact-source, unvalidated workspace candidates.

This module has no GitHub mutation surface. Imported changes retain the same
file/blob/policy guards as other workspace candidates and never become a merge
or test receipt merely because an external agent said it was finished.
"""
import base64
import re
from urllib.parse import quote

from v14.agent_config import AgentError
from v14.engineering_backend import blob_sha, safe_path


def verify_changes(job, changes):
    tree = job['data']['tree']
    if not isinstance(changes, list) or not 1 <= len(changes) <= 20:
        raise AgentError('AGENT_CANDIDATE_INVALID', 'Import one to 20 coordinated text changes.')
    result, paths, size = [], set(), 0
    for item in changes:
        if not isinstance(item, dict) or set(item) != {'path', 'expected_blob_sha', 'content', 'mode'}:
            raise AgentError('AGENT_CANDIDATE_INVALID', 'The candidate does not use the exact source-change contract.')
        try:
            path = safe_path(item['path'])
        except ValueError:
            raise AgentError('AGENT_CANDIDATE_PATH_INVALID', 'The candidate contains an unsafe source path.') from None
        entry = tree.get(path, {})
        if (path in paths or entry.get('mode', '100644') not in {'100644', '100755'}
                or item['expected_blob_sha'] != entry.get('sha') or item['mode'] != entry.get('mode', '100644')):
            raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The candidate does not match the pinned source tree and regular-file modes.')
        # A new path cannot traverse a tracked link/file or replace a tracked
        # directory. This also rejects ambiguous case-only aliases below.
        parts = path.split('/')
        if any('/'.join(parts[:n]) in tree for n in range(1, len(parts))) or any(p.startswith(path + '/') for p in tree):
            raise AgentError('AGENT_CANDIDATE_PATH_INVALID', 'The candidate crosses an existing source path.')
        if any(p.casefold() == path.casefold() and p != path for p in tree.keys() | paths):
            raise AgentError('AGENT_CANDIDATE_PATH_INVALID', 'The candidate contains a case-ambiguous source path.')
        content = item['content']
        if content is None:
            if not entry:
                raise AgentError('AGENT_CANDIDATE_INVALID', 'A deleted path must exist in the pinned source.')
        elif not isinstance(content, str) or '\0' in content:
            raise AgentError('AGENT_CANDIDATE_INVALID', 'Only UTF-8 text changes can be imported.')
        else:
            size += len(content.encode('utf-8'))
            if size > 512 * 1024 or re.search(r'^(?:<<<<<<<|=======|>>>>>>>)', content, re.M):
                raise AgentError('AGENT_CANDIDATE_INVALID', 'Resolve conflicts and keep the candidate within its coordinated change allowance.')
            if blob_sha(content) == entry.get('sha'):
                continue
        paths.add(path)
        result.append(dict(item))
    if not result:
        raise AgentError('AGENT_CANDIDATE_EMPTY', 'The agent has no changed text candidate to import.')
    return result


def unified_changes(job, patch, original):
    """Apply a bounded textual Git patch, with exact hunk context and no fuzz.

    Binary patches, rename/copy headers, links, mode changes and quoted paths
    are intentionally refused. Callers can obtain a regular-file candidate
    through the provider's Git branch path for those unsupported diff forms.
    """
    if not isinstance(patch, str) or not patch.startswith('diff --git ') or len(patch.encode()) > 1024 * 1024:
        raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The provider did not supply a bounded textual Git patch.')
    lines, position, changes = patch.splitlines(True), 0, []
    while position < len(lines):
        header = re.fullmatch(r'diff --git a/(.+) b/(.+)\n?', lines[position])
        if not header or header[1] != header[2] or header[1].startswith('"'):
            raise AgentError('AGENT_PATCH_UNSUPPORTED', 'Use a regular-file patch with unchanged path names.')
        try:
            path = safe_path(header[1])
        except ValueError:
            raise AgentError('AGENT_CANDIDATE_PATH_INVALID', 'The provider patch contains an unsafe path.') from None
        position += 1
        before = after = None
        new_mode = None
        while position < len(lines) and not lines[position].startswith('@@'):
            value = lines[position].rstrip('\n')
            if value.startswith('--- '):
                before = value[4:]
            elif value.startswith('+++ '):
                after = value[4:]
            elif value.startswith('new file mode '):
                new_mode = value.removeprefix('new file mode ')
                if new_mode != '100644':
                    raise AgentError('AGENT_PATCH_UNSUPPORTED', 'New files must use the coordinated regular-file mode.')
            elif not re.fullmatch(r'(?:index [a-f0-9]+\.\.[a-f0-9]+(?: 100(?:644|755))?|(?:new|deleted) file mode 100(?:644|755))', value):
                raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The patch contains an unsupported header or file mode.')
            position += 1
            if position < len(lines) and lines[position].startswith('diff --git '):
                break
        entry = job['data']['tree'].get(path)
        if before not in {'a/' + path, '/dev/null'} or after not in {'b/' + path, '/dev/null'} or (before == '/dev/null') != (entry is None):
            raise AgentError('AGENT_PATCH_SOURCE_CHANGED', 'The patch paths do not describe the pinned source.')
        source = original(path) if entry else ''
        if entry and blob_sha(source) != entry['sha']:
            raise AgentError('AGENT_PATCH_SOURCE_CHANGED', 'The patch base blob failed its source identity check.')
        source_lines, output, consumed, hunks = source.splitlines(True), [], 0, 0
        while position < len(lines) and lines[position].startswith('@@'):
            hunk = re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n?', lines[position])
            if not hunk or hunks >= 100:
                raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The patch contains an invalid or oversized hunk list.')
            old_start, old_count, new_start, new_count = int(hunk[1]), int(hunk[2] or 1), int(hunk[3]), int(hunk[4] or 1)
            start = old_start if old_count == 0 else old_start - 1
            if start < consumed or start > len(source_lines):
                raise AgentError('AGENT_PATCH_SOURCE_CHANGED', 'The patch hunk position does not match its source.')
            output.extend(source_lines[consumed:start])
            if (new_start if new_count == 0 else new_start - 1) != len(output):
                raise AgentError('AGENT_PATCH_SOURCE_CHANGED', 'The patch target hunk position is inconsistent.')
            position += 1
            old, new, last = [], [], None
            while position < len(lines) and not lines[position].startswith(('@@', 'diff --git ')):
                line = lines[position]
                if line.startswith('\\ No newline at end of file'):
                    if last is None:
                        raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The patch newline marker has no preceding line.')
                    for collection in last:
                        collection[-1] = collection[-1].removesuffix('\n')
                    last = None
                elif line[:1] in {' ', '+', '-'}:
                    last = []
                    if line[0] in ' -':
                        old.append(line[1:]); last.append(old)
                    if line[0] in ' +':
                        new.append(line[1:]); last.append(new)
                else:
                    raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The patch contains a non-text hunk line.')
                position += 1
            if len(old) != old_count or len(new) != new_count or source_lines[start:start + old_count] != old:
                raise AgentError('AGENT_PATCH_SOURCE_CHANGED', 'The patch context differs from the pinned source; no fuzzy application was attempted.')
            output.extend(new)
            consumed, hunks = start + old_count, hunks + 1
        output.extend(source_lines[consumed:])
        content = ''.join(output)
        if not hunks or after == '/dev/null' and content:
            raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The patch does not completely describe its textual change.')
        changes.append({'path': path, 'expected_blob_sha': entry['sha'] if entry else None,
                        'content': None if after == '/dev/null' else content, 'mode': entry['mode'] if entry else '100644'})
        if len(changes) > 20:
            raise AgentError('AGENT_CANDIDATE_INVALID', 'The patch exceeds the coordinated file allowance.')
    return verify_changes(job, changes)


def cursor_changes(backend, job, transport, saved):
    """Independently verify the current provider branch against GitHub objects."""
    from v14.agent_cloud import identifier
    agent = transport.api('GET', '/v1/agents/' + identifier(saved['session_id']))
    if agent.get('id') != saved['session_id'] or agent.get('latestRunId') != saved['remote_run_id']:
        raise AgentError('AGENT_RUN_CHANGED', 'Cursor has a different latest run; its shared Git snapshot cannot be attributed to this candidate.')
    status = transport.status(saved)
    if status['state'] != 'COMPLETED':
        raise AgentError('AGENT_CANDIDATE_PENDING', 'Wait for the exact provider run to finish before importing it.')
    rows = status['response'].get('git', {}).get('branches', [])
    repository = job['request']['repository']
    candidates = [row for row in rows if isinstance(row, dict) and row.get('repoUrl', '').lower() == 'github.com/' + repository.lower()]
    if len(candidates) != 1 or not isinstance(candidates[0].get('branch'), str):
        raise AgentError('AGENT_CANDIDATE_AMBIGUOUS', 'Import requires exactly one attributable repository branch.')
    branch = candidates[0]['branch']
    if not branch.startswith('cursor/') or len(branch) > 240 or any(ord(c) < 33 for c in branch):
        raise AgentError('AGENT_CANDIDATE_BRANCH_INVALID', 'The provider did not return an isolated Cursor branch.')
    target = backend.ref(job, branch)
    if not isinstance(target, str) or not re.fullmatch('[a-f0-9]{40}', target):
        raise AgentError('AGENT_CANDIDATE_PENDING', 'The agent branch is not available on GitHub.')
    compare = backend.api(backend.root(job) + '/compare/' + saved['source_sha'] + '...' + target, job=job)
    files = compare.get('files', [])
    if (compare.get('merge_base_commit', {}).get('sha') != saved['source_sha'] or compare.get('status') not in {'ahead', 'identical'}
            or not isinstance(files, list) or not 1 <= len(files) <= 20):
        raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The remote candidate is not a bounded descendant of the exact accepted source.')
    commit = backend.api(backend.root(job) + '/git/commits/' + target, job=job)
    if commit.get('sha') != target:
        raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'GitHub returned a different candidate commit.')
    tree = backend.api(backend.root(job) + '/git/trees/' + commit['tree']['sha'] + '?recursive=1', job=job)
    if tree.get('truncated') is not False or tree.get('sha') != commit['tree']['sha']:
        raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The remote candidate tree could not be fully verified.')
    target_entries = {v['path']: v for v in tree.get('tree', [])}
    regular_entries = {path: value for path, value in target_entries.items() if value.get('type') != 'tree'}
    base_tree = job['data']['tree']
    differing = {path for path in base_tree.keys() | regular_entries.keys()
                 if any(base_tree.get(path, {}).get(key) != regular_entries.get(path, {}).get(key) for key in ('sha', 'mode'))}
    if differing != {row.get('filename') for row in files}:
        raise AgentError('AGENT_CANDIDATE_INCOMPLETE', 'The GitHub comparison omitted or added a changed path.')
    changes = []
    for row in files:
        path = safe_path(row.get('filename'))
        if row.get('status') not in {'added', 'modified', 'removed'}:
            raise AgentError('AGENT_PATCH_UNSUPPORTED', 'The remote candidate contains a non-textual or renamed change.')
        base, entry = job['data']['tree'].get(path, {}), target_entries.get(path)
        if row['status'] == 'removed':
            if entry is not None:
                raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The candidate deletion disagrees with its target tree.')
            content = None
        else:
            if not entry or entry.get('type') != 'blob' or entry.get('mode') != base.get('mode', '100644'):
                raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'Only unchanged regular-file modes may be imported.')
            blob = backend.api(backend.root(job) + '/git/blobs/' + quote(entry['sha'], safe=''), job=job)
            if blob.get('encoding') != 'base64' or blob.get('size', 524289) > 512 * 1024:
                raise AgentError('AGENT_CANDIDATE_INVALID', 'The changed blob exceeds the text allowance.')
            try:
                raw = base64.b64decode(blob['content'])
                if len(raw) > 512 * 1024 or blob_sha(raw) != entry['sha'] or b'\0' in raw:
                    raise ValueError()
                content = raw.decode('utf-8')
            except (ValueError, UnicodeError):
                raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The remote candidate blob failed its text and identity checks.') from None
        changes.append({'path': path, 'content': content, 'expected_blob_sha': base.get('sha'), 'mode': base.get('mode', '100644')})
    latest = transport.api('GET', '/v1/agents/' + identifier(saved['session_id']))
    if backend.ref(job, branch) != target or latest.get('latestRunId') != saved['remote_run_id']:
        raise AgentError('AGENT_RUN_CHANGED', 'The provider branch or latest run changed during candidate capture.')
    return verify_changes(job, changes), {'provider_head_sha': target, 'source_sha': saved['source_sha'], 'verified_by': 'GITHUB_GIT_OBJECTS'}
