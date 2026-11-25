"""Revision/attempt-bound artifact downloads and host-approved release checks."""
from datetime import datetime
import hashlib
import json
import re
from urllib.parse import urlsplit

import requests


def contract(value, command_count):
    if value is None:
        return {'artifacts': [], 'installation_checks': [], 'smoke_checks': []}
    if not isinstance(value, dict) or set(value) != {'artifacts', 'installation_checks', 'smoke_checks'}:
        raise ValueError('Release checks need artifacts, installation_checks and smoke_checks')
    if not isinstance(value['artifacts'], list) or len(value['artifacts']) > 8:
        raise ValueError('Declare at most eight release artifacts')
    seen = set()
    for item in value['artifacts']:
        if (not isinstance(item, dict) or set(item) != {'workflow', 'name', 'maximum_bytes'}
                or not isinstance(item['workflow'], str) or not re.fullmatch(r'\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml', item['workflow'])
                or not isinstance(item['name'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}', item['name'])
                or item['name'] in seen or type(item['maximum_bytes']) is not int or not 1 <= item['maximum_bytes'] <= 128 * 1024 * 1024):
            raise ValueError('Each release artifact needs a unique safe name, workflow path and bounded byte allowance')
        seen.add(item['name'])
    if sum(item['maximum_bytes'] for item in value['artifacts']) > 256 * 1024 * 1024:
        raise ValueError('Release artifacts exceed the combined 256 MiB allowance')
    for key in ('installation_checks', 'smoke_checks'):
        if (not isinstance(value[key], list) or len(value[key]) > 10
                or any(type(index) is not int or not 0 <= index < command_count for index in value[key])
                or len(set(value[key])) != len(value[key])):
            raise ValueError('Select distinct approved command indexes for release checks')
    return json.loads(json.dumps(value))


def download(backend, job, artifact_id, maximum):
    return download_github_artifact(backend.token(), backend.github_governor, backend.root(job),
                                    artifact_id, maximum, backend.cancellation(job))


def download_github_artifact(token, governor, root, artifact_id, maximum, cancel):
    if type(artifact_id) is not int or artifact_id <= 0:
        raise ValueError('Artifact identity is invalid')
    if cancel.is_set():
        raise InterruptedError('Artifact verification cancelled')
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    url = 'https://api.github.com' + root + '/actions/artifacts/' + str(artifact_id) + '/zip'
    with governor.lease(token, cancelled=cancel.is_set):
        with requests.get(url, headers=headers, timeout=(10, 30), allow_redirects=False, stream=True) as response:
            governor.observe(token, response.status_code, response.headers)
            response.raise_for_status()
            if response.status_code != 302:
                raise ValueError('GitHub did not provide an artifact download')
            location = response.headers.get('Location', '')
    parsed = urlsplit(location)
    if (parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443)
            or not parsed.hostname or not (parsed.hostname.endswith('.blob.core.windows.net')
                                          or parsed.hostname.endswith('.actions.githubusercontent.com'))):
        raise ValueError('Untrusted artifact storage destination')
    # Never forward GitHub credentials to signed object storage.
    raw = bytearray()
    if cancel.is_set():
        raise InterruptedError('Artifact verification cancelled')
    with requests.get(location, timeout=(10, 30), allow_redirects=False, stream=True) as response:
        response.raise_for_status()
        if response.status_code != 200:
            raise ValueError('Artifact storage returned an unexpected response')
        for block in response.iter_content(65536):
            if cancel.is_set():
                raise InterruptedError('Artifact verification cancelled')
            if len(raw) + len(block) > maximum:
                raise ValueError('Artifact exceeded its approved byte allowance')
            raw.extend(block)
    return bytes(raw)


def verify(backend, job, runs):
    from v14.engineering_source import prepare_source
    from v14.engineering_validation import load_policy, validate
    requested = job['data'].get('admission', {}).get('post_merge_contract')
    result = {'status': 'PASSED', 'artifact_verification': 'NOT_REQUESTED',
              'installation_verification': 'NOT_REQUESTED', 'smoke_verification': 'NOT_REQUESTED',
              'device_tested': False, 'artifacts': [], 'checks': []}
    if not requested or not any(requested.values()):
        return result
    policy, _, _, commands, _ = load_policy(job['request']['repository'])
    policy_sha = hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if policy_sha != job['data']['admission'].get('policy_sha256') or requested != contract(policy.get('post_merge'), len(commands)):
        return dict(result, status='BLOCKED', reason='MERGE_RELEASE_POLICY_CHANGED')
    payloads = {}
    for expected in requested['artifacts']:
        run = runs.get(expected['workflow'])
        if not run:
            return dict(result, status='BLOCKED', reason='MERGE_ARTIFACT_WORKFLOW_UNAVAILABLE')
        artifacts = backend.paged(backend.root(job) + '/actions/runs/' + str(run['id']) + '/artifacts', 'artifacts', job)
        selected = []
        for item in artifacts:
            if item.get('name') != expected['name'] or item.get('expired') is not False:
                continue
            try:
                created = datetime.fromisoformat(item['created_at'].replace('Z', '+00:00'))
                started = datetime.fromisoformat(run['run_started_at'].replace('Z', '+00:00'))
            except (KeyError, TypeError, ValueError):
                continue
            if created.tzinfo is not None and started.tzinfo is not None and created >= started:
                selected.append(item)
        if len(selected) != 1:
            return dict(result, status='BLOCKED', reason='MERGE_ARTIFACT_MISSING_OR_AMBIGUOUS')
        item = selected[0]
        digest = item.get('digest')
        if not isinstance(digest, str) or not re.fullmatch('sha256:[a-f0-9]{64}', digest):
            return dict(result, status='BLOCKED', reason='MERGE_ARTIFACT_DIGEST_UNAVAILABLE')
        raw = download(backend, job, item['id'], expected['maximum_bytes'])
        if len(raw) != item.get('size_in_bytes') or hashlib.sha256(raw).hexdigest() != digest[7:]:
            return dict(result, status='FAILED', reason='MERGE_ARTIFACT_INTEGRITY_FAILED')
        payloads[expected['name'] + '.zip'] = raw
        result['artifacts'].append({'id': item['id'], 'name': item['name'], 'run_id': run['id'],
            'run_attempt': run.get('run_attempt', 1), 'source_sha': job['data']['merge_sha'], 'digest': digest, 'bytes': len(raw)})
    if requested['artifacts']:
        result['artifact_verification'] = 'PASSED'
    source = dict(job, data=dict(job['data'], head_sha=job['data']['merge_sha']))
    if requested['installation_checks'] or requested['smoke_checks']:
        parent = backend.api(backend.root(job) + '/git/commits/' + source['data']['head_sha'], job=job)
        prepare_source(source, backend.token(), parent['tree']['sha'], backend.cancellation(job))
    for kind in ('installation', 'smoke'):
        for index in requested[kind + '_checks']:
            check = validate(source, [], cancel=backend.cancellation(job), command_index=index, artifacts=payloads)
            result['checks'].append({'kind': kind, 'command_index': index, 'validation': check})
            if check['status'] != 'PASSED':
                return dict(result, status=check['status'], reason='MERGE_' + kind.upper() + '_CHECK_FAILED')
        if requested[kind + '_checks']:
            result[kind + '_verification'] = 'PASSED'
    return result
