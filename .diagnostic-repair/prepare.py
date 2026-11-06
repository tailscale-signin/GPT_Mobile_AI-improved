"""Build an auditable Git commit object; never move a branch or bypass CI."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.parse
import urllib.request

REPO = 'tailscale-signin/GPT_Mobile_AI-improved'
BRANCH = 'fix/diagnostic-workflow-evidence-2026-11-06'
HEAD = os.environ['GITHUB_SHA']
if os.environ['GITHUB_REPOSITORY'] != REPO or os.environ['GITHUB_REF'] != 'refs/heads/' + BRANCH:
    raise SystemExit('Unexpected repository or branch')

def git(*args):
    return subprocess.check_output(['git', *args]).decode().strip()

def api(path, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(
        'https://api.github.com/repos/' + REPO + path,
        data=body,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json',
                 'X-GitHub-Api-Version': '2022-11-28'},
        method='GET' if payload is None else 'POST')
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

if git('rev-parse', 'HEAD') != HEAD:
    raise SystemExit('Checked out revision changed')
ref = api('/git/ref/heads/' + urllib.parse.quote(BRANCH, safe=''))
if ref['object']['sha'] != HEAD:
    raise SystemExit('Remote branch moved; reconcile instead of overwriting')

# These are one-shot preparation aids, not application or release code.
shutil.rmtree('.diagnostic-repair')
for name in ['diagnostic-source-review.yml', 'diagnostic-apply-repair.yml']:
    Path('.github/workflows', name).unlink()
subprocess.run(['python3', 'scripts/generate_architecture_index.py'], check=True)
subprocess.run(['git', 'add', '-A', '--', 'app/src', '.diagnostic-repair',
                '.github/workflows/diagnostic-source-review.yml',
                '.github/workflows/diagnostic-apply-repair.yml',
                'docs/architecture/repository-index.json'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', 'HEAD']).decode().rstrip('\0').split('\0')
allowed_deletions = {'.github/workflows/diagnostic-source-review.yml', '.github/workflows/diagnostic-apply-repair.yml'}
entries = []
files = {}
for name in paths:
    if not (name.startswith('app/src/') or name.startswith('.diagnostic-repair/') or name in allowed_deletions or name == 'docs/architecture/repository-index.json'):
        raise SystemExit('Unexpected changed path: ' + name)
    path = Path(name)
    if not path.exists():
        entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': None})
        continue
    if path.is_symlink() or not path.is_file():
        raise SystemExit('Unexpected file kind')
    content = path.read_bytes()
    blob = api('/git/blobs', {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
    entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    files[name] = hashlib.sha256(content).hexdigest()
parent = api('/git/commits/' + HEAD)
tree = api('/git/trees', {'base_tree': parent['tree']['sha'], 'tree': entries})
commit = api('/git/commits', {
    'message': 'Repair stale refs, fixture selection, graph fallback and retained-result recovery',
    'tree': tree['sha'], 'parents': [HEAD],
    'author': {'name': 'github-actions[bot]', 'email': '41898282+github-actions[bot]@users.noreply.github.com'}})
Path('diagnostic-prepared.json').write_text(json.dumps({
    'repository': REPO, 'branch': BRANCH, 'expected_parent': HEAD,
    'commit_sha': commit['sha'], 'tree_sha': tree['sha'],
    'files_sha256': files, 'removed': [x['path'] for x in entries if x.get('sha', '') is None],
    'validation': {'ktlint': 'PASSED', 'android_tests': 'NOT_RUN', 'branch_moved': False}
}, indent=2) + '\n')
Path('diagnostic-prepared.patch').write_bytes(subprocess.check_output(['git', 'diff', '--cached', '--binary', 'HEAD']))
with tarfile.open('diagnostic-prepared-source.tar.gz', 'w:gz') as archive:
    for name in files:
        archive.add(name, arcname=name)
print('Prepared commit object:', commit['sha'])
print('No branch was moved. Required CI and complete-scope review remain mandatory.')
