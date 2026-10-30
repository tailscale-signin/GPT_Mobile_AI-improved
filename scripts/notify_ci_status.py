#!/usr/bin/env python3
"""Optional signed CI completion delivery. No source, logs or credentials in payloads."""
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def notification(event):
    run = event.get('workflow_run', {})
    repository = event.get('repository', {}).get('full_name', '')
    if run.get('status') != 'completed' or run.get('repository', {}).get('full_name') != repository:
        raise ValueError('Expected a completed run in the selected repository')
    run_id = int(run['id'])
    attempt = int(run.get('run_attempt', 1))
    payload = {'schema_version': 1, 'repository': repository, 'run_id': run_id,
               'run_attempt': attempt, 'head_sha': run['head_sha'],
               'status': 'completed', 'conclusion': run.get('conclusion'),
               'url': f'https://github.com/{repository}/actions/runs/{run_id}'}
    return f'{repository}:{run_id}:{attempt}', json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()


def deliver(event, url, secret, opener=None):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError('CI_STATUS_WEBHOOK_URL must be an HTTPS endpoint without userinfo or fragments')
    if not secret:
        raise ValueError('CI_STATUS_WEBHOOK_SECRET is required')
    delivery_id, body = notification(event)
    signature = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    request = urllib.request.Request(url, data=body, method='POST', headers={
        'Content-Type': 'application/json', 'X-CI-Delivery': delivery_id,
        'X-Hub-Signature-256': 'sha256=' + signature})
    client = opener or urllib.request.build_opener(NoRedirect)
    with client.open(request, timeout=15) as response:
        if not 200 <= response.status < 300:
            raise ValueError('Webhook did not acknowledge delivery')


def main():
    url = os.getenv('CI_STATUS_WEBHOOK_URL', '')
    secret = os.getenv('CI_STATUS_WEBHOOK_SECRET', '')
    if not url and not secret:
        print('CI webhook is not configured; delivery skipped.')
        return 0
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        deliver(event, url, secret)
    except Exception:
        # URLs can contain credential-bearing paths. Never print request/response errors.
        print('::error::CI webhook delivery failed. Check the endpoint and signing secret; no automatic retry was sent.')
        return 1
    print('CI completion delivered.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
