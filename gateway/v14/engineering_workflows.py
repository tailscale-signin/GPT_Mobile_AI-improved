"""Conservative, revision-pinned discovery of applicable push workflow contracts.

This intentionally accepts a small literal YAML trigger syntax. Unsupported
trigger syntax is a qualification blocker, never permission to omit a workflow.
It does not evaluate workflow expressions or execute repository code.
"""
import base64
import re


class WorkflowContractError(ValueError):
    pass


def literal(value):
    value = value.strip()
    if not value or any(ch in value for ch in '\n\r\t{}&|>\\,') or '${{' in value:
        raise WorkflowContractError('Unsupported workflow trigger scalar')
    if value[0] in '\"\'':
        if value[-1] != value[0] or value[0] in value[1:-1]:
            raise WorkflowContractError('Unsupported workflow trigger quoting')
        return value[1:-1]
    if '#' in value or value.startswith(('*', '!')):
        raise WorkflowContractError('Workflow patterns with YAML syntax must be quoted')
    return value


def sequence(value):
    if not value.startswith('[') or not value.endswith(']'):
        raise WorkflowContractError('Expected a literal workflow trigger list')
    return [literal(part) for part in value[1:-1].split(',')] if value[1:-1].strip() else []


def push_filters(source):
    # Only the top-level on section is interpreted; all job content stays data.
    lines = source.splitlines()
    starts = [i for i, line in enumerate(lines) if re.match(r'^(?:on|"on"|\'on\')\s*:', line)]
    if len(starts) != 1:
        raise WorkflowContractError('Workflow needs one literal trigger section')
    start = starts[0]
    inline = lines[start].split(':', 1)[1].strip()
    if inline:
        events = sequence(inline) if inline.startswith('[') else [literal(inline)]
        return {} if 'push' in events else None
    block = []
    for line in lines[start + 1:]:
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if not line.startswith(' '):
            break
        if '\t' in line:
            raise WorkflowContractError('Workflow trigger indentation is unsupported')
        block.append(line)
    event_indent = min((len(line) - len(line.lstrip()) for line in block), default=0)
    push, selected = False, []
    for line in block:
        indent = len(line) - len(line.lstrip())
        if indent == event_indent:
            match = re.fullmatch(r'\s*([a-z_]+):\s*(.*)', line)
            if not match:
                raise WorkflowContractError('Unsupported workflow trigger declaration')
            if push:
                break
            push = match[1] == 'push'
            if push and match[2] not in ('', '{}', 'null', '~'):
                raise WorkflowContractError('Unsupported push trigger declaration')
        elif push:
            selected.append(line)
    if not push:
        return None
    fields, key = {}, None
    field_indent = min((len(line) - len(line.lstrip()) for line in selected), default=0)
    for line in selected:
        indent = len(line) - len(line.lstrip())
        if indent == field_indent:
            match = re.fullmatch(r'\s*([a-z-]+):\s*(.*)', line)
            if not match or match[1] not in {'branches', 'branches-ignore', 'paths', 'paths-ignore', 'tags', 'tags-ignore'} or match[1] in fields:
                raise WorkflowContractError('Unsupported push filter')
            key = match[1]
            fields[key] = sequence(match[2]) if match[2] else []
        elif key and line.strip().startswith('- '):
            fields[key].append(literal(line.strip()[2:]))
        else:
            raise WorkflowContractError('Unsupported push filter list')
    return fields


def matches(pattern, value):
    if any(ch in pattern for ch in '[]+?{}\\') or not pattern:
        raise WorkflowContractError('Unsupported workflow filter pattern')
    expression, index = '', 0
    while index < len(pattern):
        if pattern[index:index + 3] == '**/':
            expression += '(?:.*/)?'
            index += 3
        elif pattern[index:index + 2] == '**':
            expression += '.*'
            index += 2
        elif pattern[index] == '*':
            expression += '[^/]*'
            index += 1
        else:
            expression += re.escape(pattern[index])
            index += 1
    return re.fullmatch(expression, value) is not None


def included(patterns, value):
    answer = False
    for pattern in patterns:
        negative = pattern.startswith('!')
        if matches(pattern[1:] if negative else pattern, value):
            answer = not negative
    return answer


def applies(filters, branch, paths):
    if filters is None:
        return False
    for kind in ('branches', 'paths'):
        if kind in filters and kind + '-ignore' in filters:
            raise WorkflowContractError('Conflicting workflow filters')
    if ('tags' in filters or 'tags-ignore' in filters) and not any(key in filters for key in ('branches', 'branches-ignore')):
        return False
    if 'branches' in filters and not included(filters['branches'], branch):
        return False
    if 'branches-ignore' in filters and included(filters['branches-ignore'], branch):
        return False
    if 'paths' in filters and not any(included(filters['paths'], path) for path in paths):
        return False
    if 'paths-ignore' in filters and all(included(filters['paths-ignore'], path) for path in paths):
        return False
    return True


def expected(backend, job):
    from v14.engineering_backend import blob_sha
    sha, branch = job['data']['merge_sha'], job['request']['base']
    root = backend.root(job)
    commit = backend.api(root + '/commits/' + sha, job=job)
    if commit.get('sha') != sha or not commit.get('parents'):
        raise WorkflowContractError('Merge parent is unavailable')
    parents = commit['parents']
    # A rebase can push multiple commits. Its final first parent is not the
    # push-before revision. A requested method alone also proves nothing after
    # a lost write response: only a confirmed squash result or exact two-parent
    # merge identity supplies this boundary.
    ordinary_merge = len(parents) == 2 and parents[1]['sha'] == job['data']['head_sha']
    confirmed_squash = len(parents) == 1 and job['data'].get('merge_method_receipt') == {'method': 'squash', 'merge_sha': sha, 'confirmed': True}
    if not ordinary_merge and not confirmed_squash:
        raise WorkflowContractError('The complete merge push-before revision is not proved')
    parent = parents[0]['sha']
    diff = backend.api(root + '/compare/' + parent + '...' + sha, job=job)
    if diff.get('status') != 'ahead' or diff.get('merge_base_commit', {}).get('sha') != parent or 'files' not in diff or len(diff['files']) >= 300:
        raise WorkflowContractError('Complete merge path coverage is unavailable')
    changed = [row['filename'] for row in diff['files']]
    changed.extend(row['previous_filename'] for row in diff['files'] if row.get('previous_filename'))
    tree = backend.tree(job, sha)
    paths = sorted(path for path in tree if re.fullmatch(r'\.github/workflows/[^/]+\.ya?ml', path))
    if not paths or len(paths) > 100:
        raise WorkflowContractError('Workflow source inventory is unavailable or exceeds its bound')
    result = []
    for path in paths:
        blob = tree[path]['sha']
        response = backend.api(root + '/git/blobs/' + blob, job=job)
        if response.get('encoding') != 'base64' or response.get('size', 0) > 128 * 1024:
            raise WorkflowContractError('Workflow source exceeds its read bound')
        raw = base64.b64decode(response['content'])
        if len(raw) > 128 * 1024 or blob_sha(raw) != blob:
            raise WorkflowContractError('Workflow source identity changed')
        if applies(push_filters(raw.decode('utf-8')), branch, changed):
            result.append({'path': path, 'blob_sha': blob, 'event': 'push', 'branch': branch})
    if not result:
        raise WorkflowContractError('No applicable push workflow contract was found')
    return {'merge_sha': sha, 'branch': branch, 'workflows': result, 'source': 'PINNED_WORKFLOW_TRIGGERS'}
