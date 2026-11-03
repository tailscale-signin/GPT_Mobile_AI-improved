#!/usr/bin/env python3
"""Opt-in acceptance against an isolated repository/device; never counted as mocked tests.

Tokens are read only from environment variables and are never written to the report.
Missing live inputs produce BLOCKED results, never a passing simulated result.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True, help='Dedicated private acceptance owner/repository')
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-fixture-writes', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--emulator-serial')
    parser.add_argument('--allow-apk-install', action='store_true')
    parser.add_argument('--instrumentation', help='Acceptance test package/runner; runs only on the selected emulator')
    args = parser.parse_args()
    import re
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository) or not re.fullmatch('[a-f0-9]{40}', args.source_sha):
        parser.error('Supply an exact repository and full source SHA')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    records = []
    token = os.getenv('GITHUB_ACCEPTANCE_TOKEN', '')
    rotated = os.getenv('GITHUB_ACCEPTANCE_ROTATED_TOKEN', '')

    def record(name, status, **evidence):
        records.append(dict(name=name, status=status, observed_at_ms=int(time.time() * 1000), evidence=evidence))

    def request(path, credential=token, method='GET', body=None):
        if not credential:
            raise ValueError('Required acceptance credential was not supplied')
        with requests.request(method, 'https://api.github.com' + path, headers={'Authorization': 'Bearer ' + credential,
                              'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'},
                              json=body, timeout=30, allow_redirects=False, stream=True) as response:
            raw = bytearray()
            for block in response.iter_content(8192):
                if len(raw) + len(block) > 1024 * 1024:
                    raise ValueError('Acceptance response exceeded its byte budget')
                raw.extend(block)
            return response.status_code, json.loads(raw) if raw else {}

    root = '/repos/' + args.repository
    writable = False
    if token:
        try:
            status, account = request('/user')
            account_verified = status == 200 and bool(account.get('id'))
            record('authenticated_account', 'PASSED' if status == 200 and account.get('id') else 'FAILED', http_status=status, account_id=account.get('id'))
            status, repository = request(root)
            writable = status == 200 and repository.get('private') is True and repository.get('permissions', {}).get('push') is True
            record('private_repository', 'PASSED' if status == 200 and repository.get('private') is True else 'FAILED', http_status=status, repository=args.repository)
            if rotated:
                status, next_account = request('/user', rotated)
                same = account_verified and status == 200 and next_account.get('id') == account.get('id')
                record('same_account_token_rotation', 'PASSED' if same else 'FAILED', same_account=same)
                writable = writable and same
            else:
                record('same_account_token_rotation', 'BLOCKED', reason='GITHUB_ACCEPTANCE_ROTATED_TOKEN is absent')
            revoked = os.getenv('GITHUB_ACCEPTANCE_REVOKED_TOKEN', '')
            if revoked:
                status, _ = request(root, revoked)
                record('revoked_access', 'PASSED' if status in (401, 403, 404) else 'FAILED', http_status=status)
            else:
                record('revoked_access', 'BLOCKED', reason='Supply a previously revoked fixture credential; this suite does not revoke accounts')
            if args.allow_fixture_writes and writable and rotated:
                branch = 'acceptance/codex-' + uuid.uuid4().hex
                # Persist the intended write before dispatch. A lost response is reconciled by read, never by another POST.
                checkpoint = args.output.with_suffix('.checkpoint.json')
                checkpoint.write_text(json.dumps({'branch': branch, 'sha': args.source_sha, 'state': 'DISPATCHING'}))
                existing, _ = request(root + '/git/ref/heads/' + branch)
                if existing != 404:
                    raise ValueError('Fixture absence could not be established')
                dispatched = False
                try:
                    dispatched = True
                    try:
                        request(root + '/git/refs', method='POST', body={'ref': 'refs/heads/' + branch, 'sha': args.source_sha})
                    except requests.RequestException:
                        pass  # An ambiguous response permits reconciliation only, never another POST.
                    intent = json.loads(checkpoint.read_text())
                    status, observed = request(root + '/git/ref/heads/' + intent['branch'], rotated)
                    record('lost_response_rotation_reconciliation', 'PASSED' if status == 200 and observed.get('object', {}).get('sha') == args.source_sha else 'FAILED', branch=branch, source_sha=args.source_sha, write_dispatches=1)
                finally:
                    if dispatched:
                        try:
                            status, observed = request(root + '/git/ref/heads/' + branch, rotated)
                            if status == 200 and observed.get('object', {}).get('sha') == args.source_sha:
                                deleted, _ = request(root + '/git/refs/heads/' + branch, rotated, 'DELETE')
                                missing, _ = request(root + '/git/ref/heads/' + branch, rotated)
                                record('owned_fixture_cleanup', 'PASSED' if deleted == 204 and missing == 404 else 'FAILED', branch=branch)
                            elif status == 404:
                                record('owned_fixture_cleanup', 'PASSED', branch=branch, reason='Fixture is absent')
                            else:
                                record('owned_fixture_cleanup', 'BLOCKED', branch=branch, reason='Fixture changed or cannot be read; no deletion performed')
                        except Exception as error:
                            record('owned_fixture_cleanup', 'BLOCKED', branch=branch, error_type=type(error).__name__, reason='Read reconciliation is required before any cleanup retry')
            else:
                record('lost_response_rotation_reconciliation', 'BLOCKED', reason='Requires explicit fixture-write flag, private repository push access and a same-account rotated credential')
        except Exception as error:
            record('live_api_execution', 'FAILED', error_type=type(error).__name__)
    else:
        for name in ('authenticated_account', 'private_repository', 'same_account_token_rotation', 'revoked_access', 'lost_response_rotation_reconciliation'):
            record(name, 'BLOCKED', reason='GITHUB_ACCEPTANCE_TOKEN is absent')

    apk_digest = None
    if args.apk and args.emulator_serial and args.allow_apk_install:
        try:
            adb = ['adb', '-s', args.emulator_serial]
            emulator = subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], text=True, timeout=15).strip()
            if emulator != '1':
                raise ValueError('Device lifecycle acceptance requires an isolated emulator')
            with args.apk.open('rb') as apk:
                apk_digest = hashlib.file_digest(apk, 'sha256').hexdigest()
            result = subprocess.run(adb + ['install', '-r', str(args.apk)], capture_output=True, timeout=180)
            record('apk_installation', 'PASSED' if result.returncode == 0 else 'FAILED', apk_sha256=apk_digest, exit_code=result.returncode)
            if result.returncode == 0 and args.instrumentation:
                run = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', args.instrumentation], capture_output=True, timeout=900)
                output = run.stdout.decode('utf-8', 'replace')
                passed = run.returncode == 0 and 'OK (' in output and 'FAILURES' not in output
                record('android_instrumentation', 'PASSED' if passed else 'FAILED', output_sha256=hashlib.sha256(run.stdout).hexdigest(), apk_sha256=apk_digest, runner=args.instrumentation)
            else:
                record('android_instrumentation', 'BLOCKED', reason='A successful APK installation and device acceptance instrumentation are required')
        except Exception as error:
            record('android_device_execution', 'FAILED', error_type=type(error).__name__)
    else:
        record('apk_installation', 'BLOCKED', reason='Supply the APK, an isolated emulator serial and explicit installation flag')
        record('android_instrumentation', 'BLOCKED', reason='No live device execution; host API checks do not validate the Android UI, process death or reboot')
    for name in ('android_sign_in', 'android_refresh', 'android_revoked_private_access', 'android_interrupted_mutation', 'android_process_death', 'android_reboot_recovery', 'real_app_installation_token'):
        record(name, 'BLOCKED', reason='Requires case-specific live acceptance evidence; generic instrumentation success and host API checks do not establish this behavior')
    report = {'schema': 'github.live-acceptance.v1', 'scope': 'LIVE_EXTERNAL_AND_DEVICE', 'inspected_source_sha': args.source_sha, 'running_app_source_sha': None,
              'running_apk_sha256': apk_digest, 'mocked_results_included': False, 'results': records,
              'complete': bool(records) and all(row['status'] == 'PASSED' for row in records)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['complete'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
