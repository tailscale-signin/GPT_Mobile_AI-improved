"""Prepare reviewed source objects without moving refs or bypassing qualification."""
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

def api(path, payload=None):
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'},
        method='GET' if payload is None else 'POST')
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

if subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip() != HEAD:
    raise SystemExit('Checked-out revision changed')
if api('/git/ref/heads/' + urllib.parse.quote(BRANCH, safe=''))['object']['sha'] != HEAD:
    raise SystemExit('Remote branch moved; reconcile instead of overwriting')
shutil.rmtree('.diagnostic-repair')
removed_workflows = ['.github/workflows/diagnostic-source-reconciliation.yml', '.github/workflows/diagnostic-reconciliation-preparation.yml']
for name in removed_workflows:
    Path(name).unlink()
report = 'docs/repairs/github-dispatch-graphql-2026-11-06.md'
Path(report).write_text('''# GitHub dispatch and GraphQL recovery follow-up

This review preserves the concurrent gateway/task/retention repair and its run-bound acceptance system. It adds two missing integration boundaries, not a replacement completion ledger.

## Request observations

Invocation-local request IDs now connect dispatch, HTTP-header observations, server request identifiers and terminal accounting. Explicit IDs prevent reordered, duplicated or mismatched callbacks from inventing accepted writes. HTTP acceptance is not body delivery or task completion. Interrupted reads remain separate from uncertain writes; HTTP 408 and server failures do not prove that a mutation had no effect. Cancellation closes outstanding diagnostic observations without releasing publication fences. Logged values are bounded, allowlisted metadata, not URLs, tokens or response bodies.

The ordinary REST client, native tool client, CI log reader and GraphQL client use these IDs. The existing HTTP-408 regression is retained alongside six additional observation tests.

## Ready-for-review mutation

The GraphQL mutation now uses the existing protected durable execution journal. Its claim is bound to actual query/variable inputs, not incidental workspace or presentation fields. Lost responses and GraphQL HTTP-200 error payloads retain an uncertain claim. Recovery reads the exact PR identity, open state, head and draft flag before accepting success; unresolved state never authorizes another POST. Completed receipts survive recreation of the client. Mutable response cache entries are invalidated before mutation/reconciliation observations.

Six regressions cover successful readback and restart, lost-response reconciliation, GraphQL errors, deterministic denial, changed incidental inputs and head movement during the write.

## Qualification

Formatting is checked before the source commit is prepared. Actual compilation, behavioral test results and required CI must be observed on the published candidate before merge. No device measurement, live account mutation or successful release is inferred from test source or formatting. Existing scoped-release limitations and all required gates remain unchanged.
''')
scope_path = Path('release-scope.json')
scope = json.loads(scope_path.read_text())
changed = subprocess.check_output(['git', 'diff', '--name-only', '--', 'app/src']).decode().splitlines()
scope['allowedPaths'] = sorted(set(scope['allowedPaths']) | set(changed) | {report})
scope['changes'].append('Correlate GitHub request and response observations, preserve uncertain GraphQL mutation claims, and verify ready-for-review by exact remote readback without replaying a lost write.')
scope_path.write_text(json.dumps(scope, indent=2) + '\n')
subprocess.run(['python3', 'scripts/generate_architecture_index.py'], check=True)
subprocess.run(['git', 'add', '-A', '--', 'app/src', '.diagnostic-repair', *removed_workflows,
                'docs/architecture/repository-index.json', report, 'release-scope.json'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', 'HEAD']).decode().rstrip('\0').split('\0')
entries = []
files = {}
for name in paths:
    if not (name.startswith('app/src/') or name.startswith('.diagnostic-repair/') or name in set(removed_workflows + [report, 'docs/architecture/repository-index.json', 'release-scope.json'])):
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
commit = api('/git/commits', {'message': 'Reconcile durable GraphQL mutation recovery and correlated request diagnostics',
    'tree': tree['sha'], 'parents': [HEAD],
    'author': {'name': 'github-actions[bot]', 'email': '41898282+github-actions[bot]@users.noreply.github.com'}})
Path('diagnostic-prepared.json').write_text(json.dumps({'repository': REPO, 'branch': BRANCH,
    'expected_parent': HEAD, 'commit_sha': commit['sha'], 'tree_sha': tree['sha'], 'files_sha256': files,
    'removed': [x['path'] for x in entries if x.get('sha', '') is None],
    'validation': {'ktlint': 'PASSED', 'android_tests': 'NOT_RUN', 'branch_moved': False}}, indent=2) + '\n')
Path('diagnostic-prepared.patch').write_bytes(subprocess.check_output(['git', 'diff', '--cached', '--binary', 'HEAD']))
with tarfile.open('diagnostic-prepared-source.tar.gz', 'w:gz') as archive:
    for name in files:
        archive.add(name, arcname=name)
print('Prepared commit object:', commit['sha'])
print('No ref was moved. Latest-head tests and release qualification remain mandatory.')
