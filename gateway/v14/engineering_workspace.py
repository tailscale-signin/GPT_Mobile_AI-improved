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
        files[path] = {'sha': entry['sha'], 'content': raw.decode('utf-8'), 'preview_characters': 0}
    if files[path]['sha'] != entry['sha'] or blob_sha(files[path]['content']) != entry['sha']:
        raise ValueError('Workspace source no longer matches its pinned tree')
    return files[path]


def act(backend, job, action):
    if not isinstance(action, dict) or action.get('head_sha') != job['data']['head_sha']:
        raise ValueError('Workspace action must name the current pinned head_sha')
    kind = action.get('action')
    receipt = {'action': kind, 'head_sha': job['data']['head_sha']}
    from v14.workspace_intelligence import ACTIONS, act as intelligence
    from v14.workspace_board import AGENT_ACTIONS
    if kind in ACTIONS:
        receipt.update(intelligence(backend, job, action))
    elif kind in AGENT_ACTIONS:
        receipt.update(backend.agent_runs.act(job, action))
    elif kind == 'environment_qualify':
        from v14.workspace_board import qualify_environment
        receipt.update(qualify_environment(backend, job, action))
    elif kind == 'candidate_rank':
        from v14.workspace_board import rank_candidates
        receipt.update(rank_candidates(backend, job, action))
    elif kind == 'revert_preview':
        from v14.workspace_timeline import preview
        receipt.update(preview(backend, job, action))
    elif kind == 'worker':
        from v14.workspace_workers import advance
        receipt.update(advance(backend, job, action))
    elif kind in {'terminal_open', 'terminal_start', 'terminal_poll', 'terminal_cancel', 'terminal_close', 'terminal_fork', 'terminal_switch', 'terminal_timeline', 'code_intelligence'}:
        from v14.workspace_terminal import act as terminal
        receipt.update(terminal(backend, job, action))
    elif kind in {'workflow_batch', 'workflow_batch_status', 'workflow_batch_step'}:
        from v14.workspace_guidance import batch_workflow
        receipt.update(batch_workflow(job, action))
    elif kind in {'skills', 'skill', 'workflow_step'}:
        from v14.workspace_guidance import workflow
        receipt.update(workflow(backend, job, action))
    elif kind == 'progress':
        fields = {key: action.get(key, '') for key in ('hypothesis', 'next_action', 'tried')}
        if any(not isinstance(value, str) or len(value) > 1000 for value in fields.values()) or not fields['next_action'].strip():
            raise ValueError('Record a next action and up to 1000 characters per working note')
        job['data']['progress_notes'] = dict(fields, head_sha=job['data']['head_sha'])
        receipt.update(job['data']['progress_notes'])
    elif kind == 'instructions':
        from v14.workspace_guidance import activate
        paths = action.get('paths')
        if not isinstance(paths, list) or not 1 <= len(paths) <= 60:
            raise ValueError('Select 1–60 paths for instruction resolution')
        receipt.update(active_instructions=activate(backend, job, paths))
    elif kind == 'requirements':
        offset = action.get('offset', 0)
        if type(offset) is not int or offset < 0:
            raise ValueError('Invalid acceptance requirement offset')
        rows = job['request'].get('acceptance', [])
        receipt.update(requirements=rows[offset:offset + 4], total=len(rows), next_offset=offset + 4 if offset + 4 < len(rows) else None)
    elif kind in {'repo_search', 'glob', 'symbols', 'relationships', 'explore'} or kind == 'search' and 'paths' not in action:
        from v14.workspace_search import WorkspaceSearchService
        receipt.update(WorkspaceSearchService(backend, job).act(action))
    elif kind in {'checks', 'check', 'check_output', 'candidates', 'candidate_files', 'discover_tests', 'run_test', 'compile_module', 'lint_changed_files', 'check_patch', 'inspect_failure', 'compare_candidates'}:
        from v14.engineering_workspace_checks import act as check_action
        receipt.update(check_action(backend, job, action))
    elif kind == 'paths':
        query = action.get('query', '')
        offset = action.get('offset', 0)
        if not isinstance(query, str) or len(query) > 200 or type(offset) is not int or offset < 0:
            raise ValueError('Invalid workspace path search')
        paths = sorted(path for path, entry in job['data']['tree'].items()
                       if query.casefold() in path.casefold() and entry['mode'] in {'100644', '100755'})
        receipt.update(paths=paths[offset:offset + 80], total=len(paths), next_offset=offset + 80 if offset + 80 < len(paths) else None)
    elif kind == 'read':
        path = action.get('path')
        from v14.workspace_guidance import activate
        active = job['data'].get('active_instructions', [])
        # Paging a parent instruction recovers guidance for the affected source;
        # it must not replace that source's more specific directory rules.
        receipt['active_instructions'] = list(active) if path in active else activate(backend, job, [path])
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
        receipt.update(path=path, blob_sha=original['sha'], start_line=start, end_line=start + len(selected) - 1, content=text,
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
        raise ValueError('Unknown workspace action')
    receipt['receipt_sha256'] = hashlib.sha256(json.dumps(receipt, sort_keys=True).encode()).hexdigest()
    return receipt


def view(data):
    # Full source remains in the encrypted artifact store; each model call receives
    # a small current window plus its durable task/revision and prior receipts.
    windows = list(reversed(data.get('workspace_receipts', [])[-3:]))
    files = {}
    for path, value in list(data['files'].items())[:20]:
        limit = value.get('preview_characters', value.get('visible_characters', 0))
        content = value['content'][:min(max(limit, 0), 2400)]
        files[path] = {'sha': value['sha'], 'content': content,
                       'partial': len(content) < len(value['content']), 'total_lines': len(value['content'].splitlines())}
    return {'head_sha': data['head_sha'], 'progress_notes': data.get('progress_notes'), 'workflow': data.get('workflow'),
            'active_instructions': data.get('active_instructions', []), 'workspace_receipts': windows, 'plan': data['plan'],
            'repair_feedback': repair_feedback(data), 'files': files, 'iteration': data.get('workspace_iterations', 0),
            'task_plan': data.get('workspace_task_plan'), 'tool_profile': data.get('workspace_tool_profile'),
            'working_summaries': data.get('workspace_summaries', [])[-2:],
            'worker_summaries': [{key: value.get(key) for key in ('role', 'state', 'calls', 'budget', 'candidate_check_id', 'depends_on')}
                                 for value in data.get('workspace_workers', {}).values()],
            'ci': data.get('ci'), 'review': data.get('review')}


def repair_feedback(data):
    """Project retained patch/test feedback without treating proposals as source."""
    from v14.engineering_validation import patch_identity
    from v14.inference_budget import compact_evidence
    changes = data.get('changes')
    if not isinstance(changes, list) or not changes:
        return None
    head = data['head_sha']
    identity = patch_identity(head, changes)
    validation = data.get('local_validation')
    matches = (isinstance(validation, dict) and validation.get('patch_identity') == identity
               and validation.get('source_sha', head) == head)
    # Separate allocations prevent a large command log from hiding every patch
    # field, or a large replacement from hiding the actual validation failure.
    validation_view, validation_omissions = compact_evidence(validation, 1024, nodes=256)
    changes_view, changes_omissions = compact_evidence(changes, 1024, nodes=256)
    return {'head_sha': head, 'patch_identity': identity, 'validation_matches_patch': matches,
            'local_validation': validation_view, 'changes': changes_view,
            'complete': not validation_omissions and not changes_omissions,
            'omissions': {'local_validation': validation_omissions, 'changes': changes_omissions}}


def exposed_ranges(original, head_sha=None):
    """Return only ranges delivered for this blob, never a planned read allowance."""
    exposure = original.get('source_exposure')
    if isinstance(exposure, dict) and exposure.get('blob_sha') == original['sha'] and (
            head_sha is None or exposure.get('head_sha') == head_sha):
        return [span for span in exposure.get('ranges', [])
                if isinstance(span, list) and len(span) == 2 and all(type(value) is int for value in span)
                and 0 <= span[0] <= span[1] <= len(original['content'])][:128]
    # Pure callers such as the benchmark grader supply their exact displayed
    # source length. Live jobs always pass head_sha and require a model receipt.
    count = original.get('visible_characters', 0)
    if head_sha is None and 'preview_characters' not in original and type(count) is int and count >= 0:
        return [[0, min(count, len(original['content']))]] if count or not original['content'] else []
    return []


def record_model_exposure(data, request):
    """Record source present in the admitted request after evidence compaction.

    Reading or retaining a blob does not grant editing authority. A successful
    bound model invocation calls this with its actual request, so omitted fields
    and partial range excerpts cannot authorize unseen source.
    """
    from v14.engineering_backend import blob_sha
    try:
        evidence = json.loads(request['messages'][-1]['content']).get('evidence')
    except (KeyError, IndexError, TypeError, ValueError):
        return
    if not isinstance(evidence, dict) or evidence.get('head_sha') != data.get('head_sha'):
        return

    def text(value):
        if isinstance(value, str):
            return value
        if isinstance(value, dict) and value.get('complete') is False and isinstance(value.get('excerpt'), str):
            return value['excerpt']
        return None

    def original_for(path, sha):
        original = data.get('files', {}).get(path)
        entry = data.get('tree', {}).get(path)
        if (not isinstance(original, dict) or not isinstance(entry, dict) or entry.get('mode') not in {'100644', '100755'}
                or sha != entry.get('sha') or original.get('sha') != sha or blob_sha(original['content']) != sha):
            return None
        return original

    def retain(original, start, end):
        spans = exposed_ranges(original, data['head_sha']) + [[start, end]]
        merged = []
        for left, right in sorted(spans):
            if merged and left <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], right)
            else:
                merged.append([left, right])
        original['source_exposure'] = {'head_sha': data['head_sha'], 'blob_sha': original['sha'], 'ranges': merged[-128:]}

    files = evidence.get('files')
    for path, value in list(files.items())[:20] if isinstance(files, dict) else []:
        if not isinstance(value, dict):
            continue
        original = original_for(path, value.get('sha'))
        shown = text(value.get('content'))
        if original is not None and shown is not None and original['content'].startswith(shown):
            retain(original, 0, len(shown))
    receipts = evidence.get('workspace_receipts')
    for receipt in receipts[-3:] if isinstance(receipts, list) else []:
        if not isinstance(receipt, dict) or receipt.get('action') != 'read' or receipt.get('head_sha') != data['head_sha']:
            continue
        original = original_for(receipt.get('path'), receipt.get('blob_sha'))
        shown = text(receipt.get('content'))
        start = receipt.get('start_line')
        if original is None or shown is None or type(start) is not int or start < 1:
            continue
        lines = original['content'].splitlines(True)
        if start > len(lines) + 1:
            continue
        offset = sum(map(len, lines[:start - 1]))
        if original['content'][offset:].startswith(shown):
            retain(original, offset, offset + len(shown))
