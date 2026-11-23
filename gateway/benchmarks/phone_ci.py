"""Trusted exact-revision Actions evaluator for provisioned phone submissions.

Execute this file from the separately checked-out immutable fixture base. The
submitted checkout supplies only JSON data and an exact trusted workflow. No
candidate source is imported into this host process.
"""
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

if __package__ in {None, ''}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import coding, fixtures, sandbox
from benchmarks.calibration import self_check, task_check
from benchmarks.contracts import content_digest, digest
from benchmarks.external import environment_receipt, evaluate_request, select_task
from benchmarks.phone_provision import (PREFIX, RESULT_SCHEMA, SCHEMA, SUBMISSION_SCHEMA, SHA,
    json_bytes, raw_digest, render_workflow, submission_path, workflow_path)
from benchmarks.settings import validate as validate_settings


class FrozenFixtureError(ValueError):
    pass


def bounded_file(root, relative, maximum=262144):
    if (not isinstance(relative, str) or not relative or '\\' in relative or Path(relative).is_absolute()
            or any(part in {'', '.', '..', '.git'} for part in relative.split('/'))):
        raise FrozenFixtureError('PHONE_FIXTURE_PATH_INVALID')
    root, path = Path(root).resolve(), Path(root)
    for part in relative.split('/'):
        path /= part
        if path.is_symlink():
            raise FrozenFixtureError('PHONE_FIXTURE_PATH_LINKED')
    if not path.resolve().is_relative_to(root) or not path.is_file() or path.stat().st_size > maximum:
        raise FrozenFixtureError('PHONE_FIXTURE_FILE_UNAVAILABLE')
    raw = path.read_bytes()
    if len(raw) > maximum:
        raise FrozenFixtureError('PHONE_FIXTURE_FILE_UNAVAILABLE')
    return raw


def parse_json(raw):
    return json.loads(raw, object_pairs_hook=coding.unique_object,
                      parse_constant=coding.reject_json_constant, parse_float=coding.finite_json_float)


def verify_bundle(trusted_root):
    catalog = parse_json(bounded_file(trusted_root, PREFIX + 'catalog.json', 1024 * 1024))
    if not isinstance(catalog, dict) or catalog.get('schema') != SCHEMA or SHA.fullmatch(str(catalog.get('sourceCommit', ''))) is None:
        raise FrozenFixtureError('PHONE_CATALOG_INVALID')
    manifest = catalog.get('bundleManifest')
    if (not isinstance(manifest, dict) or not 1 <= len(manifest) <= 4096
            or catalog.get('bundleDigest') != digest(manifest)):
        raise FrozenFixtureError('PHONE_BUNDLE_IDENTITY_CHANGED')
    total = 0
    for path, expected in manifest.items():
        if (not isinstance(path, str) or not path.startswith(PREFIX) or path == PREFIX + 'catalog.json'
                or not isinstance(expected, str) or re.fullmatch(r'[a-f0-9]{64}', expected) is None):
            raise FrozenFixtureError('PHONE_BUNDLE_MANIFEST_INVALID')
        raw = bounded_file(trusted_root, path, 2 * 1024 * 1024)
        total += len(raw)
        if total > 32 * 1024 * 1024 or raw_digest(raw) != expected:
            raise FrozenFixtureError('PHONE_BUNDLE_CONTENT_CHANGED')
    return catalog


def git(root, *args):
    environment = {'PATH': os.defpath, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull}
    try:
        result = subprocess.run(['git', '--no-optional-locks', '-C', str(Path(root).resolve()), *args],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=30, check=True, env=environment)
    except (OSError, subprocess.SubprocessError) as error:
        raise FrozenFixtureError('PHONE_REVISION_READBACK_UNAVAILABLE') from error
    if len(result.stdout) > 1024 * 1024:
        raise FrozenFixtureError('PHONE_REVISION_READBACK_INCOMPLETE')
    return result.stdout.decode('utf-8').strip()


def verify_revisions(trusted_root, submitted_root, base_sha, head_sha, ident):
    if SHA.fullmatch(base_sha or '') is None or SHA.fullmatch(head_sha or '') is None:
        raise FrozenFixtureError('PHONE_REVISION_IDENTITY_INVALID')
    if git(trusted_root, 'rev-parse', 'HEAD') != base_sha or git(submitted_root, 'rev-parse', 'HEAD') != head_sha:
        raise FrozenFixtureError('PHONE_CHECKOUT_REVISION_CHANGED')
    for root in (trusted_root, submitted_root):
        git(root, 'diff', '--no-ext-diff', '--no-textconv', '--exit-code', 'HEAD', '--')
    parents = [line[7:] for line in git(submitted_root, 'cat-file', '-p', head_sha).split('\n\n', 1)[0].splitlines() if line.startswith('parent ')]
    if parents != [base_sha]:
        raise FrozenFixtureError('PHONE_PROPOSAL_PARENT_CHANGED')
    # Import only the already checked-out local base object, never a network ref.
    git(submitted_root, '-c', 'protocol.file.allow=always', 'fetch', '--no-tags', '--depth=1', str(Path(trusted_root).resolve()), base_sha)
    changed = git(submitted_root, 'diff', '--no-ext-diff', '--no-textconv', '--name-status', '--no-renames', base_sha, head_sha)
    expected = {'A\t' + submission_path(ident), 'A\t' + workflow_path(ident)}
    if set(changed.splitlines()) != expected or len(changed.splitlines()) != 2:
        raise FrozenFixtureError('PHONE_PROPOSAL_SCOPE_CHANGED')
    for path in (submission_path(ident), workflow_path(ident)):
        mode = git(submitted_root, 'ls-tree', head_sha, '--', path).split(' ', 1)[0]
        if mode != '100644':
            raise FrozenFixtureError('PHONE_PROPOSAL_MODE_INVALID')


def validate_submission(value, catalog, trusted_root, submitted_root, ident, base_sha, context):
    keys = {'schema', 'request_id', 'task_id', 'task_pack', 'seed', 'fixture_digest', 'grader_digest',
            'environment_digest', 'bundle_digest', 'base_sha', 'repository', 'branch', 'workflow_path',
            'proposal_digest', 'proposal', 'generation_receipt', 'comparison_context'}
    if not isinstance(value, dict) or set(value) - keys or value.get('schema') != SUBMISSION_SCHEMA or value.get('request_id') != ident:
        raise FrozenFixtureError('PHONE_SUBMISSION_CONTRACT_INVALID')
    pack = value.get('task_pack')
    packs = catalog.get('packs', {})
    if pack not in {'python', 'repository'} or pack not in packs:
        raise FrozenFixtureError('PHONE_TASK_PACK_UNAVAILABLE')
    selected = packs[pack]
    if (value.get('bundle_digest') != catalog['bundleDigest'] or value.get('base_sha') != base_sha
            or not isinstance(value.get('repository'), str) or value['repository'].lower() != context['repository'].lower()
            or value.get('branch') != context['branch']
            or value.get('workflow_path') != workflow_path(ident)):
        raise FrozenFixtureError('PHONE_SUBMISSION_IDENTITY_CHANGED')
    seed, task_id = value.get('seed'), value.get('task_id')
    if type(seed) is not int or seed not in selected['seeds'] or not isinstance(task_id, str):
        raise FrozenFixtureError('PHONE_TASK_NOT_PROVISIONED')
    metadata = selected['tasksBySeed'].get(str(seed), {}).get(task_id)
    if not isinstance(metadata, dict):
        raise FrozenFixtureError('PHONE_TASK_NOT_PROVISIONED')
    path = selected['fixturePathTemplate'].replace('{seed}', str(seed)).replace('{task_id}', task_id)
    raw = bounded_file(trusted_root, path)
    descriptor = parse_json(raw)
    if (raw_digest(raw) != metadata['descriptorDigest'] or descriptor['taskDigest'] != metadata['fixtureDigest']
            or value.get('fixture_digest') != descriptor['taskDigest']
            or value.get('grader_digest') != descriptor['graderDigest']
            or value.get('environment_digest') != descriptor['environmentDigest']):
        raise FrozenFixtureError('PHONE_FIXTURE_IDENTITY_CHANGED')
    template = bounded_file(trusted_root, selected['workflowTemplatePath'])
    if raw_digest(template) != selected['workflowTemplateDigest']:
        raise FrozenFixtureError('PHONE_WORKFLOW_TEMPLATE_CHANGED')
    actual = bounded_file(submitted_root, workflow_path(ident))
    expected = render_workflow(template.decode('utf-8'), ident, base_sha, context['branch']).encode('utf-8')
    if actual != expected:
        raise FrozenFixtureError('PHONE_WORKFLOW_CONTENT_CHANGED')
    settings = validate_settings(selected['settings'])
    environment = environment_receipt(settings, pack, descriptor['graderDigest'])
    if environment['environmentDigest'] != descriptor['environmentDigest']:
        raise FrozenFixtureError('PHONE_RUNTIME_IDENTITY_CHANGED')
    request, proposal = evaluate_request({key: value[key] for key in (
        'request_id', 'task_id', 'task_pack', 'seed', 'fixture_digest', 'grader_digest', 'environment_digest',
        'proposal_digest', 'proposal', 'generation_receipt', 'comparison_context') if key in value})
    task = select_task(task_id, seed, pack)
    if task['taskDigest'] != descriptor['taskDigest'] or task['graderDigest'] != descriptor['graderDigest']:
        raise FrozenFixtureError('PHONE_INSTALLED_GRADER_CHANGED')
    return task, request, proposal, settings, descriptor


def grade_task(task, proposal, settings, pack):
    cancel = threading.Event()
    if pack == 'repository':
        from benchmarks.repository_sandbox import grade
        result = grade(task, proposal, settings, cancel)
        result.pop('_candidate_sources', None)
        return result
    calibration, calibrated = self_check(), task_check(task)
    if calibration.get('status') != 'PASSED' or calibrated.get('status') != 'PASSED':
        raise FrozenFixtureError('PHONE_GRADER_CALIBRATION_FAILED')
    try:
        coding.proposal(proposal, task)
    except (ValueError, KeyError, TypeError, RecursionError, OverflowError):
        result = coding.grade(task, proposal, settings, cancel)
    else:
        docker = sandbox.preflight(settings)
        reference = sandbox.execute(task['referenceSources'], task['vectors'], settings, cancel, docker=docker)
        rows = reference.get('outputs', [])
        if (reference.get('status') != 'COMPLETED' or len(rows) != len(task['vectors'])
                or any('output' not in row or not coding.same(row['output'], fixtures.expected(task, vector))
                       for row, vector in zip(rows, task['vectors']))):
            raise FrozenFixtureError('PHONE_GRADER_REFERENCE_FAILED')
        result = coding.grade(task, proposal, settings, cancel, docker=docker, execute=sandbox.execute)
    return dict(result, calibration=calibration, taskCalibration=calibrated)


def evaluate(trusted_root, submitted_root, ident, base_sha, context, *, grader=grade_task):
    """Return a typed artifact. A red workflow or absent artifact is never a model failure."""
    started = time.monotonic()
    result = {'schema': RESULT_SCHEMA, 'requestId': ident, 'repository': context.get('repository'),
              'branch': context.get('branch'), 'baseSha': base_sha, 'testedRevision': context.get('head_sha'),
              'publishedRevision': context.get('head_sha'), 'workflowPath': workflow_path(ident),
              'workflowRunId': context.get('run_id'), 'workflowRunAttempt': context.get('run_attempt'),
              'status': 'BLOCKED', 'qualified': False, 'resolved': False, 'outcomeKnown': False,
              'graded': False, 'executed': False, 'failureDomain': 'HARNESS', 'liveGitHub': 'OBSERVED',
              'modelIdentityScope': 'AUTHENTICATED_CLIENT_OBSERVATION', 'independentProviderIdentityVerified': False}
    try:
        if (not isinstance(context.get('repository'), str)
                or re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', context['repository']) is None
                or type(context.get('run_id')) is not int or context['run_id'] < 1
                or type(context.get('run_attempt')) is not int or context['run_attempt'] < 1
                or context.get('event') != 'push'):
            raise FrozenFixtureError('PHONE_ACTIONS_CONTEXT_INVALID')
        verify_revisions(trusted_root, submitted_root, base_sha, context.get('head_sha'), ident)
        catalog = verify_bundle(trusted_root)
        value = parse_json(bounded_file(submitted_root, submission_path(ident)))
        if not isinstance(value, dict):
            raise FrozenFixtureError('PHONE_SUBMISSION_CONTRACT_INVALID')
        # These are source-of-request identities, not an asserted successful grade.
        result.update(taskId=value.get('task_id'), taskPack=value.get('task_pack'), seed=value.get('seed'),
                      proposalDigest=content_digest(value['proposal']) if isinstance(value.get('proposal'), str) else None,
                      fixtureDigest=value.get('fixture_digest'), graderDigest=value.get('grader_digest'),
                      environmentDigest=value.get('environment_digest'), bundleDigest=catalog['bundleDigest'])
        task, request, proposal, settings, descriptor = validate_submission(value, catalog, trusted_root, submitted_root, ident, base_sha, context)
        case = grader(task, proposal, settings, request['task_pack'])
        case.pop('_candidate_sources', None)
        case.update(proposalDigest=request['proposal_digest'], receiptId=ident,
                    taskDigest=task['taskDigest'], graderDigest=task['graderDigest'],
                    environmentDigest=descriptor['environmentDigest'],
                    generationReceipt=deepcopy(request['generation_receipt']), requestedModel=request['model'],
                    observedModel=request['generation_receipt'].get('observed_model'),
                    modelIdentityScope='AUTHENTICATED_CLIENT_OBSERVATION', independentProviderIdentityVerified=False,
                    selectedAttempt=1, deliveredAttempt=1, repairBudget=0)
        result.update(case=case, generationReceipt=deepcopy(request['generation_receipt']),
                      patchDigest=case.get('patchDigest'), calibration=case.get('calibration'),
                      taskCalibration=case.get('taskCalibration'), score=case.get('score'),
                      fixtureDigest=task['taskDigest'], graderDigest=task['graderDigest'],
                      environmentDigest=descriptor['environmentDigest'],
                      executionScope='PHONE_GITHUB_ACTIONS', localExecutionScope=case.get('executionScope', 'ISOLATED_PYTHON_CONTRACT'))
        result.update({key: case.get(key) for key in ('status', 'graded', 'executed', 'qualified', 'resolved', 'outcomeKnown', 'failureDomain')})
        if case.get('error'):
            result['errorCode'] = case['error']
    except sandbox.SandboxUnavailable as error:
        result.update(failureDomain='SETUP', errorCode=error.reason)
    except (FrozenFixtureError, ValueError, KeyError, TypeError, OSError, RecursionError, OverflowError, sandbox.SandboxInterrupted) as error:
        code = str(error) if isinstance(error, FrozenFixtureError) and re.fullmatch(r'[A-Z_]{1,96}', str(error)) else 'PHONE_EVALUATION_UNAVAILABLE'
        result.update(failureDomain='HARNESS', errorCode=code)
    result['validationDurationMs'] = round((time.monotonic() - started) * 1000)
    result['receiptDigest'] = digest(result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trusted-root', required=True)
    parser.add_argument('--submitted-root', required=True)
    parser.add_argument('--submission-id', required=True)
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args(argv)
    context = {'repository': os.environ.get('GITHUB_REPOSITORY'), 'branch': os.environ.get('GITHUB_REF_NAME'),
               'head_sha': os.environ.get('GITHUB_SHA'), 'event': os.environ.get('GITHUB_EVENT_NAME'),
               'run_id': int(os.environ.get('GITHUB_RUN_ID', '0')), 'run_attempt': int(os.environ.get('GITHUB_RUN_ATTEMPT', '0'))}
    result = evaluate(args.trusted_root, args.submitted_root, args.submission_id, args.base_sha, context)
    path = Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = json_bytes(result)
    if len(raw) > 262144:
        raise ValueError('The trusted result artifact exceeds its bound')
    path.write_bytes(raw)
    print(json.dumps({'schema': RESULT_SCHEMA, 'requestId': args.submission_id,
                      'status': result['status'], 'qualified': result['qualified'], 'receiptDigest': result['receiptDigest']}))


if __name__ == '__main__':
    main()
