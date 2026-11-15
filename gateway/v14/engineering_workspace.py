"""Revision-bound, bounded workspace actions for the durable coding loop."""
import base64
import hashlib
import json

MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_RETAINED_BYTES = 16 * 1024 * 1024


def load_file(backend, job, path):
    from v14.engineering_backend import blob_sha, safe_path
    path = safe_path(path)
    data = job['data']
    entry = data['tree'].get(path)
    if not entry or entry['mode'] not in {'100644', '100755'}:
        raise ValueError('Workspace action requires an observed regular source path')
    files = data.setdefault('files', {})
    if path not in files:
        blob = backend.api(backend.root(job) + '/git/blobs/' + entry['sha'], job=job)
        if blob.get('encoding') != 'base64' or not 0 <= blob.get('size', MAX_FILE_BYTES + 1) <= MAX_FILE_BYTES:
            raise ValueError('Source exceeds the bounded workspace file allowance')
        raw = base64.b64decode(blob['content'])
        if len(raw) > MAX_FILE_BYTES or blob_sha(raw) != entry['sha'] or b'\0' in raw:
            raise ValueError('Workspace blob identity or text encoding is invalid')
        if sum(len(value['content'].encode()) for value in files.values()) + len(raw) > MAX_RETAINED_BYTES:
            raise ValueError('Workspace source retention allowance reached')
        files[path] = {'sha': entry['sha'], 'content': raw.decode('utf-8'), 'visible_characters': 0}
    if files[path]['sha'] != entry['sha'] or blob_sha(files[path]['content']) != entry['sha']:
        raise ValueError('Workspace source no longer matches its pinned tree')
    return files[path]


def act(backend, job, action):
    if not isinstance(action, dict) or action.get('head_sha') != job['data']['head_sha']:
        raise ValueError('Workspace action must name the current pinned head_sha')
    kind = action.get('action')
    receipt = {'action': kind, 'head_sha': job['data']['head_sha']}
    if kind == 'paths':
        query = action.get('query', '')
        offset = action.get('offset', 0)
        if not isinstance(query, str) or len(query) > 200 or type(offset) is not int or offset < 0:
            raise ValueError('Invalid workspace path search')
        paths = sorted(path for path, entry in job['data']['tree'].items()
                       if query.casefold() in path.casefold() and entry['mode'] in {'100644', '100755'})
        receipt.update(paths=paths[offset:offset + 80], total=len(paths), next_offset=offset + 80 if offset + 80 < len(paths) else None)
    elif kind == 'read':
        path = action.get('path')
        original = load_file(backend, job, path)
        start, count = action.get('start_line', 1), action.get('line_count', 80)
        if type(start) is not int or type(count) is not int or start < 1 or not 1 <= count <= 200:
            raise ValueError('Read 1–200 lines from a positive start_line')
        lines = original['content'].splitlines(True)
        if start > len(lines) + 1:
            raise ValueError('Read starts beyond the source file')
        selected, size = [], 0
        for line in lines[start - 1:start - 1 + count]:
            if size + len(line) > 12000:
                break
            selected.append(line)
            size += len(line)
        if not selected and start <= len(lines):
            raise ValueError('A single source line exceeds the range allowance')
        text = ''.join(selected)
        original.setdefault('inspected_ranges', []).append({'start_line': start, 'end_line': start + len(selected) - 1})
        original['inspected_ranges'] = original['inspected_ranges'][-128:]
        receipt.update(path=path, blob_sha=original['sha'], start_line=start, content=text,
                       total_lines=len(lines), next_line=start + len(selected) if start + len(selected) <= len(lines) else None,
                       content_sha256=hashlib.sha256(text.encode()).hexdigest())
    elif kind == 'search':
        query, paths = action.get('query'), action.get('paths')
        if not isinstance(query, str) or not 1 <= len(query) <= 200 or not isinstance(paths, list) or not 1 <= len(paths) <= 10:
            raise ValueError('Search a literal query across 1–10 observed paths')
        matches = []
        for path in paths:
            original = load_file(backend, job, path)
            for number, line in enumerate(original['content'].splitlines(), 1):
                if query.casefold() in line.casefold():
                    matches.append({'path': path, 'blob_sha': original['sha'], 'line': number, 'text': line[:300]})
                    if len(matches) >= 40:
                        break
            if len(matches) >= 40:
                break
        receipt.update(matches=matches, truncated=len(matches) >= 40)
    else:
        raise ValueError('Unknown workspace action; use paths, search or read')
    receipt['receipt_sha256'] = hashlib.sha256(json.dumps(receipt, sort_keys=True).encode()).hexdigest()
    return receipt


def view(data):
    # Full source remains in the encrypted artifact store; each model call receives
    # a small current window plus its durable task/revision and prior receipts.
    windows = data.get('workspace_receipts', [])[-3:]
    files = {path: {'sha': value['sha'], 'content': value['content'][:min(value.get('visible_characters', 0), 2400)],
                    'partial': True, 'total_lines': len(value['content'].splitlines())}
             for path, value in list(data['files'].items())[:20]}
    return {'head_sha': data['head_sha'], 'plan': data['plan'], 'files': files,
            'workspace_receipts': windows, 'iteration': data.get('workspace_iterations', 0),
            'ci': data.get('ci'), 'review': data.get('review')}
