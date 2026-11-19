"""Build a forward corrective patch from verified trees. Publication stays in REVIEW/COMMIT."""
import re

from v14.engineering_backend import safe_path
from v14.engineering_validation import patch_identity
from v14.engineering_workspace import load_file


def corrective_candidate(backend, job, commit):
    if not isinstance(commit, str) or not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Select one published commit SHA')
    data = job['data']
    head = data['head_sha']
    root = backend.root(job)
    if head != commit:
        relation = backend.api(root + '/compare/' + commit + '...' + head, job=job)
        if relation.get('status') != 'ahead' or relation.get('merge_base_commit', {}).get('sha') != commit:
            raise ValueError('The selected commit is outside the pinned branch history')
    target = backend.api(root + '/git/commits/' + commit, job=job)
    parents = target.get('parents') or []
    if target.get('sha') != commit or len(parents) != 1:
        raise ValueError('Select a verified single-parent commit for corrective review')
    old, new = backend.tree(job, parents[0]['sha']), backend.tree(job, commit)
    paths = sorted(path for path in old.keys() | new.keys() if old.get(path) != new.get(path))
    if not 1 <= len(paths) <= 20:
        raise ValueError('The correction exceeds the supported 1–20 path scope')
    old_job = dict(job, data=dict(data, tree=old, files={}))
    changes, size = [], 0
    for path in paths:
        safe_path(path)
        before, after = old.get(path), new.get(path)
        if after != data['tree'].get(path):
            raise ValueError('A later edit affects ' + path + '. Inspect it and prepare a targeted corrective change.')
        if any(entry and entry['mode'] not in {'100644', '100755'} for entry in (before, after)):
            raise ValueError('Corrective review supports regular source files')
        if after:
            load_file(backend, job, path)
        content = load_file(backend, old_job, path)['content'] if before else None
        size += len((content or '').encode())
        if size > 512 * 1024:
            raise ValueError('The correction exceeds the coordinated text patch allowance')
        changes.append(dict(path=path, expected_blob_sha=after['sha'] if after else None,
                            mode=(before or after)['mode'], content=content))
    return changes


def preview(backend, job, action):
    data = job['data']
    target = action.get('commit_sha')
    changes = corrective_candidate(backend, job, target)
    from v14.workspace_guidance import activate
    paths = [row['path'] for row in changes]
    return dict(commit_sha=target, paths=paths, active_instructions=activate(backend, job, paths),
                patch_identity=patch_identity(data['head_sha'], changes), scope='corrective_preview')
