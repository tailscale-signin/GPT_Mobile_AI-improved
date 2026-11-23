"""Disposable GitHub Actions verification of a locally qualified benchmark artifact.

This is a benchmark-specific mutation authority, not an engineering merge path.
It can create immutable Git objects and one new, run-owned branch. It cannot
update an existing ref, open a production PR, merge, publish a release or change
repository settings. Every dispatch is checkpointed before touching GitHub.
"""
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
from urllib.parse import quote

from benchmarks.contracts import digest
from benchmarks.settings import load_settings

SCHEMA = 'gateway.live-coding-fixture.v1'
CHECKOUT = '3d3c42e5aac5ba805825da76410c181273ba90b1'
SHA = re.compile(r'[a-f0-9]{40}')
TERMINAL = {'completed'}


class LiveFixtureUnavailable(RuntimeError):
    pass


class LiveFixtureInterrupted(InterruptedError):
    def __init__(self, cancellation):
        super().__init__('Live fixture stopped; retain the exact remote execution receipt')
        self.cancellation = cancellation


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False)


def fixture_bundle(task, candidate, image, run_id):
    """No model-controlled shell or workflow text is interpolated into execution."""
    if not re.fullmatch(r'bench_[a-f0-9]{32}', run_id):
        raise ValueError('LIVE_FIXTURE_RUN_ID_INVALID')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:_-]{0,180}@sha256:[a-f0-9]{64}', image):
        raise ValueError('LIVE_FIXTURE_IMAGE_UNPINNED')
    if task.get('taskPack') == 'repository':
        from benchmarks.repository_sandbox import remote_bundle
        return remote_bundle(task, candidate, image, run_id)
    from benchmarks.fixtures import expected
    from benchmarks.grader_manifest import grader_digest
    from benchmarks import verified_python
    inputs = list(task['vectors']) + [{}, {'items': 'invalid'}]
    # Preserve the exact fixture interface, including invalid-request boundaries.
    targets = [expected(task, vector) for vector in inputs]
    values = {'sources': candidate, 'inputs': inputs + list(reversed(inputs)),
              'expected': targets + list(reversed(targets)), 'taskDigest': task['taskDigest'],
              'graderDigest': grader_digest(), 'interpreterPolicy': verified_python.VERSION}
    directory = '.benchmark-fixtures/' + run_id
    driver = '''import hashlib
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
sys.path.insert(0, str(root))
from verified_python import evaluate, VERSION

payload = json.loads((root / 'candidate.json').read_text(encoding='utf-8'))
result = evaluate(payload['sources'], payload['inputs'])
rows = result['outputs']
assert VERSION == payload['interpreterPolicy'], 'INTERPRETER_POLICY_CHANGED'
assert len(rows) == len(payload['expected']), 'CHECK_COVERAGE_MISSING'
assert all(row.get('input_preserved') is True for row in rows), 'CALLER_INPUT_MUTATED'
assert all('output' in row and json.dumps(row['output'], sort_keys=True, separators=(',', ':'), allow_nan=False) == json.dumps(expected, sort_keys=True, separators=(',', ':'), allow_nan=False) for row, expected in zip(rows, payload['expected'])), 'BEHAVIOR_MISMATCH'
print('BENCHMARK_ARTIFACT_VERIFIED ' + payload['taskDigest'] + ' ' + payload['graderDigest'])
'''
    workflow_path = '.github/workflows/benchmark-' + run_id + '.yml'
    workflow = f'''name: Immutable benchmark {run_id}
on:
  push:
    branches: ['benchmark/**']
    paths: ['{directory}/**', '{workflow_path}']
permissions:
  contents: read
concurrency:
  group: immutable-benchmark-{run_id}
  cancel-in-progress: false
jobs:
  verify:
    name: Independent coding verification
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Read exact candidate revision
        uses: actions/checkout@{CHECKOUT}
        with:
          persist-credentials: false
          fetch-depth: 1
      - name: Verify immutable artifact
        env:
          BENCHMARK_IMAGE: '{image}'
        run: |
          docker run --rm --pull=always --network=none --cap-drop=ALL --security-opt=no-new-privileges --read-only --pids-limit=64 --memory=512m --cpus=2 --user=65534:65534 --tmpfs=/tmp:rw,nosuid,nodev,size=64m --mount "type=bind,source=$GITHUB_WORKSPACE/{directory},target=/fixture,readonly" --workdir=/tmp --env=HOME=/tmp "$BENCHMARK_IMAGE" python3 -I -B /fixture/verify.py
'''
    interpreter = Path(verified_python.__file__).read_text(encoding='utf-8')
    return {directory + '/candidate.json': _canonical(values), directory + '/verify.py': driver,
            directory + '/verified_python.py': interpreter, workflow_path: workflow}, workflow_path


def _http_status(error):
    return getattr(getattr(error, 'response', None), 'status_code', None)


def reconcile_interrupted(service, owner_id, run):
    """Read/cancel only the original workflow. Unknown remote dispatch never becomes absence."""
    receipt = dict(run.get('liveGitHubReceipt') or {})
    cleanup = dict(kind='github_actions', outcome='unknown', repository=receipt.get('repository'),
                   revision=receipt.get('publishedRevision'), workflowPath=receipt.get('workflowPath'))
    ref_attempted = any(row.get('path', '').endswith('/git/refs') for row in receipt.get('mutations', []))
    if not ref_attempted:
        cleanup['outcome'] = 'terminal'
    else:
        try:
            request = run['request']
            repository, head = receipt['repository'], receipt['publishedRevision']
            if (repository != request.get('repository') or receipt.get('baseRevision') != request.get('base_sha')
                    or not isinstance(head, str) or SHA.fullmatch(head) is None
                    or service.store.owner(run['id']) != owner_id
                    or not service.engineering.authorized({'principal': owner_id})):
                raise PermissionError('LIVE_FIXTURE_AUTHORITY_CHANGED')
            backend = service.engineering.backend
            if backend.api('/user', read_only=True).get('id') != receipt.get('actorId'):
                raise PermissionError('LIVE_FIXTURE_ACCOUNT_CHANGED')
            root = '/repos/' + '/'.join(quote(part, safe='') for part in repository.split('/'))
            runs = backend.api(root + '/actions/runs?head_sha=' + head + '&per_page=100', read_only=True)
            if runs.get('total_count', 0) > 100:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_READBACK_INCOMPLETE')
            matching = [row for row in runs.get('workflow_runs', []) if row.get('head_sha') == head
                        and row.get('path', '').split('@')[0] == receipt.get('workflowPath')
                        and row.get('head_branch') == receipt.get('branch') and row.get('event') == 'push']
            if len(matching) != 1:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_ADMISSION_UNSETTLED')
            observed = matching[0]
            run_id = observed['id']
            if type(run_id) is not int or run_id <= 0:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_IDENTITY_INVALID')
            cleanup['workflowRunId'] = run_id
            if observed.get('status') == 'completed':
                cleanup.update(outcome='terminal', conclusion=observed.get('conclusion'))
            else:
                cancellation = receipt.get('cancellationMutation')
                if cancellation is None:
                    receipt['cancellationMutation'] = dict(workflowRunId=run_id, state='DISPATCHED')
                    receipt['cleanup'] = cleanup
                    service.store.update(run['id'], liveGitHubReceipt=receipt)
                    # A single run-specific cancel is authorized by the retained fixture ownership.
                    # Its response can be lost; only subsequent reads determine shutdown.
                    try:
                        backend.api(root + '/actions/runs/' + str(run_id) + '/cancel', method='POST', body={}, read_only=False)
                    except Exception:
                        pass
                elif cancellation.get('workflowRunId') != run_id:
                    raise LiveFixtureUnavailable('LIVE_FIXTURE_CANCELLATION_IDENTITY_CHANGED')
                for _ in range(4):
                    observed = backend.api(root + '/actions/runs/' + str(run_id), read_only=True)
                    if observed.get('head_sha') != head or observed.get('path', '').split('@')[0] != receipt.get('workflowPath'):
                        raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_IDENTITY_CHANGED')
                    if observed.get('status') == 'completed':
                        cleanup.update(outcome='confirmed' if observed.get('conclusion') == 'cancelled' else 'terminal',
                                       conclusion=observed.get('conclusion'))
                        break
                    time.sleep(.25)
        except Exception:
            pass
    receipt['cleanup'] = cleanup
    service.store.update(run['id'], liveGitHubReceipt=receipt)
    return [cleanup]


def qualify(service, owner_id, run, task, candidate_sources, grade_result, cancel_event):
    """Return live qualification only after the exact remote artifact's checks pass."""
    request = run['request']
    result = dict(grade_result)
    if not (result.get('qualified') is True and result.get('resolved') is True):
        return result
    repository, base, cohort = request.get('repository'), request.get('base_sha'), request.get('cohort')
    if (not isinstance(repository, str) or not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', repository)
            or not isinstance(base, str) or not SHA.fullmatch(base)
            or not isinstance(cohort, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,96}', cohort)):
        raise ValueError('LIVE_FIXTURE_IDENTITY_INVALID')
    root = '/repos/' + '/'.join(quote(part, safe='') for part in repository.split('/'))
    backend = service.engineering.backend
    branch = 'benchmark/' + cohort[:16] + '/' + run['id']
    receipt = dict(schema=SCHEMA, repository=repository, baseRevision=base, branch=branch,
                   taskDigest=task['taskDigest'], graderDigest=task['graderDigest'],
                   proposalDigest=request['proposal_digest'], modelIdentity=request['generation_receipt'],
                   resetPolicy='NEW_ISOLATED_BRANCH_PER_SUBMISSION', remoteReadbackVerified=False,
                   testedRevision=None, publishedRevision=None, replayPerformed=False, mutations=[])
    deadline = time.monotonic() + 600
    actor = None

    def checkpoint(stage):
        receipt['stage'] = stage
        service.store.update(run['id'], liveGitHubReceipt=receipt, progress='Live GitHub: ' + stage)

    def guard():
        if cancel_event.is_set() or time.monotonic() >= deadline:
            raise InterruptedError('LIVE_FIXTURE_INTERRUPTED')
        if service.store.owner(run['id']) != owner_id or not service.engineering.authorized({'principal': owner_id}):
            raise PermissionError('LIVE_FIXTURE_AUTHORITY_CHANGED')
        current = service.store.get(run['id'], owner_id, cases=False)
        if current is None or digest(current['request']) != digest(request):
            raise PermissionError('LIVE_FIXTURE_REQUEST_CHANGED')

    def api(path, method='GET', body=None, *, reconcile=None):
        guard()
        mutating = method != 'GET'
        if mutating:
            identity = backend.api('/user', read_only=True)
            if identity.get('id') != actor:
                raise PermissionError('LIVE_FIXTURE_ACCOUNT_CHANGED')
            # This authority is deliberately narrower than the engineering API.
            allowed = {root + '/git/blobs', root + '/git/trees', root + '/git/commits', root + '/git/refs'}
            if method != 'POST' or path not in allowed:
                raise PermissionError('LIVE_FIXTURE_MUTATION_OUT_OF_SCOPE')
            if path.endswith('/git/refs') and body.get('ref') != 'refs/heads/' + branch:
                raise PermissionError('LIVE_FIXTURE_REF_OUT_OF_SCOPE')
            mutation = {'method': method, 'path': path, 'bodyDigest': digest(body), 'state': 'DISPATCHED'}
            receipt['mutations'].append(mutation)
            checkpoint('MUTATION_DISPATCHED')
        try:
            value = backend.api(path, method=method, body=body, read_only=not mutating)
        except Exception:
            if not mutating:
                raise
            mutation['state'] = 'UNKNOWN_OUTCOME'
            checkpoint('RECONCILIATION_REQUIRED')
            if reconcile is None:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_MUTATION_OUTCOME_UNKNOWN') from None
            recovered = reconcile()
            if recovered is None:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_MUTATION_OUTCOME_UNKNOWN') from None
            mutation['state'] = 'RECONCILED'
            mutation['remoteResultDigest'] = digest(recovered)
            receipt['lostResponseReconciled'] = True
            checkpoint('MUTATION_RECONCILED')
            return recovered
        if mutating:
            mutation['state'] = 'COMPLETED'
            mutation['remoteResultDigest'] = digest(value)
            checkpoint('MUTATION_CONFIRMED')
        return value

    try:
        guard()
        actor = backend.api('/user', read_only=True).get('id')
        if type(actor) is not int or actor <= 0:
            raise LiveFixtureUnavailable('LIVE_FIXTURE_ACCOUNT_UNVERIFIED')
        metadata = api(root)
        if metadata.get('full_name', '').lower() != repository.lower() or metadata.get('permissions', {}).get('push') is not True:
            raise PermissionError('LIVE_FIXTURE_REPOSITORY_NOT_WRITABLE')
        original = api(root + '/git/commits/' + base)
        if original.get('sha') != base or not SHA.fullmatch(original.get('tree', {}).get('sha', '')):
            raise LiveFixtureUnavailable('LIVE_FIXTURE_BASE_CHANGED')
        settings = load_settings(service.root)
        image = settings['repository_image' if task.get('taskPack') == 'repository' else 'sandbox_image']
        files, workflow_path = fixture_bundle(task, candidate_sources, image, run['id'])
        receipt['bundleDigest'] = digest(files)
        receipt['actorId'] = actor
        receipt['workflowPath'] = workflow_path
        checkpoint('BUNDLE_FROZEN')
        tree = []
        for path, content in files.items():
            blob = api(root + '/git/blobs', 'POST', {'content': content, 'encoding': 'utf-8'})
            if not SHA.fullmatch(blob.get('sha', '')):
                raise LiveFixtureUnavailable('LIVE_FIXTURE_BLOB_RECEIPT_INVALID')
            tree.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
        created_tree = api(root + '/git/trees', 'POST', {'base_tree': original['tree']['sha'], 'tree': tree})
        timestamp = datetime.fromtimestamp(run['createdAt'], timezone.utc).isoformat()
        identity = {'name': 'Benchmark fixture', 'email': 'benchmark@users.noreply.github.com', 'date': timestamp}
        commit = api(root + '/git/commits', 'POST', {
            'message': 'Verify disposable benchmark ' + run['id'], 'tree': created_tree['sha'], 'parents': [base],
            'author': identity, 'committer': identity})
        head = commit.get('sha')
        if not isinstance(head, str) or not SHA.fullmatch(head):
            raise LiveFixtureUnavailable('LIVE_FIXTURE_COMMIT_RECEIPT_INVALID')
        receipt['publishedRevision'] = head
        checkpoint('COMMIT_CREATED')
        ref_path = root + '/git/ref/heads/' + quote(branch, safe='/')

        def reconcile_ref():
            try:
                ref = api(ref_path)
                return ref if ref.get('object', {}).get('sha') == head else None
            except Exception:
                return None

        # Existing branches, including ones with another head, are never moved.
        try:
            existing = api(ref_path)
        except Exception as error:
            if _http_status(error) != 404:
                raise
            existing = None
        if existing is not None:
            raise LiveFixtureUnavailable('LIVE_FIXTURE_BRANCH_ALREADY_EXISTS')
        api(root + '/git/refs', 'POST', {'ref': 'refs/heads/' + branch, 'sha': head}, reconcile=reconcile_ref)
        if reconcile_ref() is None:
            raise LiveFixtureUnavailable('LIVE_FIXTURE_REF_READBACK_MISMATCH')
        remote_tree = api(root + '/git/trees/' + created_tree['sha'] + '?recursive=1')
        if remote_tree.get('truncated') is True:
            raise LiveFixtureUnavailable('LIVE_FIXTURE_TREE_READBACK_INCOMPLETE')
        entries = {row.get('path'): row for row in remote_tree.get('tree', [])}
        if any(entries.get(row['path'], {}).get('sha') != row['sha'] for row in tree):
            raise LiveFixtureUnavailable('LIVE_FIXTURE_ARTIFACT_READBACK_MISMATCH')
        checkpoint('WAITING_FOR_EXACT_ACTIONS_RUN')
        observed = None
        while observed is None or observed.get('status') not in TERMINAL:
            guard()
            runs = api(root + '/actions/runs?head_sha=' + head + '&per_page=100')
            if runs.get('total_count', 0) > 100:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_READBACK_INCOMPLETE')
            matching = [row for row in runs.get('workflow_runs', []) if row.get('head_sha') == head and
                        row.get('path', '').split('@')[0] == workflow_path and row.get('head_branch') == branch and row.get('event') == 'push']
            if len(matching) > 1:
                raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_IDENTITY_AMBIGUOUS')
            observed = matching[0] if matching else None
            if observed is not None and observed.get('status') == 'completed':
                break
            cancel_event.wait(2)
        if observed.get('conclusion') != 'success':
            raise LiveFixtureUnavailable('LIVE_FIXTURE_ACTIONS_NOT_GREEN')
        jobs = api(root + '/actions/runs/' + str(observed['id']) + '/jobs?filter=latest&per_page=100')
        rows = jobs.get('jobs', [])
        if jobs.get('total_count') != 1 or len(rows) != 1 or rows[0].get('name') != 'Independent coding verification' or rows[0].get('conclusion') != 'success':
            raise LiveFixtureUnavailable('LIVE_FIXTURE_REQUIRED_JOB_MISSING')
        checks = [step for step in rows[0].get('steps', []) if step.get('name') == 'Verify immutable artifact']
        if len(checks) != 1 or checks[0].get('conclusion') != 'success':
            raise LiveFixtureUnavailable('LIVE_FIXTURE_REQUIRED_STEP_NOT_GREEN')
        if reconcile_ref() is None:
            raise LiveFixtureUnavailable('LIVE_FIXTURE_HEAD_CHANGED_DURING_CI')
        receipt.update(remoteReadbackVerified=True, testedRevision=head, workflowRunId=observed['id'],
                       workflowRunAttempt=observed.get('run_attempt'), jobId=rows[0]['id'],
                       workflowConclusion='success', observedAt=time.time())
        checkpoint('VERIFIED')
        result.update(liveGitHub='OBSERVED', executionScope='DISPOSABLE_GITHUB_ACTIONS', liveGitHubReceipt=receipt)
        return result
    except InterruptedError:
        checkpoint('INTERRUPTED_RETAINED_FOR_READBACK')
        cleanup = reconcile_interrupted(service, owner_id, dict(run, liveGitHubReceipt=receipt))[0]
        raise LiveFixtureInterrupted(cleanup) from None
    except Exception as error:
        code = str(error) if isinstance(error, (LiveFixtureUnavailable, PermissionError)) and re.fullmatch(r'[A-Z_]{1,96}', str(error)) else 'LIVE_FIXTURE_REMOTE_VERIFICATION_UNAVAILABLE'
        receipt['failureCode'] = code
        checkpoint('NOT_VERIFIED')
        cleanup = reconcile_interrupted(service, owner_id, dict(run, liveGitHubReceipt=receipt))[0]
        if cleanup['outcome'] == 'unknown':
            raise LiveFixtureInterrupted(cleanup) from None
        result.update(status='BLOCKED', qualified=False, resolved=False, score=None,
                      liveGitHub='PARTIAL_OBSERVATION', error=code, liveGitHubReceipt=receipt)
        return result
