"""Bounded official Jules and Cursor transports; no prompt-selected URLs.

Every write calls the owner's durable dispatch fence immediately before I/O.
Unknown writes are never retried here. Provider success is candidate evidence,
not a passing test or permission to update the shared branch.
"""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

import requests

from v14.agent_config import AgentError, ORIGINS, credential
from v14.agent_http_worker import HttpFailure, perform

MAX_RESPONSE = 4 * 1024 * 1024


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', value):
        raise AgentError('AGENT_RESPONSE_INVALID', 'The agent did not return a valid session identifier.')
    return value


def cursor(value):
    if value is not None and (not isinstance(value, str) or not 1 <= len(value) <= 2048 or any(ord(c) < 32 for c in value)):
        raise AgentError('AGENT_CURSOR_INVALID', 'Use the exact retained agent event cursor.')
    return value


class CloudAgent:
    def __init__(self, backend, config, *, request=None, environment=None, cancel=None, deadline=None):
        if backend not in ORIGINS:
            raise AgentError('AGENT_BACKEND_INVALID', 'Unsupported cloud agent.')
        self.backend, self.config = backend, config
        self.request = request
        self.environment = environment
        self.cancel, self.deadline = cancel, deadline

    def active(self):
        if self.cancel is not None and self.cancel.is_set():
            raise HttpFailure('AGENT_PARENT_STOPPED')
        if self.deadline is not None and time.time() >= self.deadline:
            raise HttpFailure('AGENT_RESPONSE_DEADLINE')

    def exchange(self, payload):
        """A socket inactivity timeout cannot bound trickling headers/bodies.

        A short-lived process makes the entire HTTP exchange killable, including
        DNS, headers and buffered reads. It is a transport, not a job scheduler.
        """
        self.active()
        if self.request is not None:
            return perform(payload, request=self.request)
        encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
        if len(encoded) > 256 * 1024:
            raise HttpFailure('AGENT_REQUEST_TOO_LARGE')
        environment = {key: value for key, value in os.environ.items()
                       if key in {'PATH', 'SYSTEMROOT', 'SYSTEMDRIVE', 'WINDIR', 'TEMP', 'TMP', 'SSL_CERT_FILE', 'SSL_CERT_DIR', 'REQUESTS_CA_BUNDLE'}
                       or key.startswith('_PYI_')}
        command = ([sys.executable, 'agent-http-worker'] if getattr(sys, 'frozen', False) else
                   [sys.executable, '-I', str(Path(__file__).with_name('agent_http_worker.py'))])
        process = subprocess.Popen(command,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment)
        until = time.monotonic() + (20 if payload.get('kind') == 'events' else 45)
        pending_input = encoded
        try:
            while True:
                self.active()
                remaining = until - time.monotonic()
                if remaining <= 0:
                    raise HttpFailure('AGENT_RESPONSE_DEADLINE')
                try:
                    raw, _ = process.communicate(pending_input, timeout=min(.2, remaining))
                    break
                except subprocess.TimeoutExpired:
                    pending_input = None
            if process.returncode != 0 or len(raw) > MAX_RESPONSE + 65536:
                raise HttpFailure('AGENT_HTTP_UNCONFIRMED')
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise HttpFailure('AGENT_RESPONSE_INVALID')
            if value.get('error_code'):
                code = str(value['error_code'])
                raise HttpFailure(code if re.fullmatch('AGENT_[A-Z0-9_]{1,80}', code) else 'AGENT_HTTP_UNCONFIRMED')
            if not isinstance(value.get('result'), dict):
                raise HttpFailure('AGENT_RESPONSE_INVALID')
            return value['result']
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)

    def headers(self):
        key = credential(self.config.get('credential_reference'), self.environment)
        if key is None:
            raise AgentError('AGENT_CREDENTIAL_UNAVAILABLE', 'Configure the agent API credential reference.')
        auth = {'x-goog-api-key': key} if self.backend == 'jules' else {
            'Authorization': 'Basic ' + base64.b64encode((key + ':').encode()).decode()}
        return dict(auth, Accept='application/json', **{'Content-Type': 'application/json'})

    def api(self, method, path, *, body=None, params=None, dispatch=None):
        if method not in {'GET', 'POST'} or not path.startswith('/') or path.startswith('//') or '..' in path or '\\' in path:
            raise AgentError('AGENT_ENDPOINT_INVALID', 'Invalid agent operation path.')
        headers = self.headers()
        mutating = method != 'GET'
        sent = False
        try:
            self.active()
            if mutating:
                if dispatch is None:
                    raise AgentError('AGENT_DISPATCH_FENCE_REQUIRED', 'Agent writes need a durable operation receipt.')
                dispatch()
                sent = True
            return self.exchange({'kind': 'json', 'backend': self.backend, 'method': method, 'path': path,
                                  'headers': headers, 'body': body, 'params': params})
        except HttpFailure as error:
            code = error.code
            if code == 'AGENT_HTTP_UNCONFIRMED':
                code = 'AGENT_OUTCOME_UNKNOWN' if sent else 'AGENT_READ_UNAVAILABLE'
            raise AgentError(code, 'The provider did not confirm the bounded agent operation; observe its retained receipt before retrying.', dispatched=sent) from None
        except AgentError:
            raise
        except (requests.RequestException, OSError, ValueError):
            raise AgentError('AGENT_OUTCOME_UNKNOWN' if sent else 'AGENT_READ_UNAVAILABLE',
                             'The agent operation needs observation before any retry.' if sent else 'The agent service could not be read.',
                             dispatched=sent) from None

    def qualify(self, repository):
        if self.backend == 'cursor':
            response = self.api('GET', '/v1/models')
            rows = response.get('items')
            if not isinstance(rows, list) or not any(isinstance(v, dict) and v.get('id') == self.config['model_id'] for v in rows):
                raise AgentError('AGENT_MODEL_UNAVAILABLE', 'The selected Cursor model is not advertised by this account.')
            return {'state': 'READY', 'model_selection': 'EXPLICIT', 'model_id': self.config['model_id'],
                    'source_binding': 'EXACT_COMMIT', 'cost_limit_supported': False}
        if self.config['model_id'] != 'provider-default':
            raise AgentError('AGENT_MODEL_NOT_SELECTABLE', 'Jules currently manages its model; configure provider-default explicitly.')
        page, seen = None, set()
        for _ in range(10):
            response = self.api('GET', '/v1alpha/sources', params={'pageSize': 100, **({'pageToken': page} if page else {})})
            rows = response.get('sources')
            if not isinstance(rows, list):
                raise AgentError('AGENT_RESPONSE_INVALID', 'Jules did not return a recognized source inventory.')
            for row in rows:
                repo = row.get('githubRepo', {}) if isinstance(row, dict) else {}
                if (str(repo.get('owner', '')) + '/' + str(repo.get('repo', ''))).lower() == repository.lower():
                    name = row.get('name', '')
                    if not isinstance(name, str) or not re.fullmatch(r'sources/[A-Za-z0-9_-]{1,256}', name):
                        raise AgentError('AGENT_SOURCE_INVALID', 'Jules returned an invalid source identity.')
                    return {'state': 'READY', 'model_selection': 'PROVIDER_MANAGED', 'model_id': 'provider-default',
                            'source': name, 'source_binding': 'BRANCH_PREFLIGHT', 'cost_limit_supported': False}
            page = cursor(response.get('nextPageToken'))
            if not page:
                raise AgentError('AGENT_SOURCE_NOT_CONNECTED', 'Connect this repository to Jules before delegation.')
            if page in seen:
                break
            seen.add(page)
        raise AgentError('AGENT_SOURCE_INVENTORY_INCOMPLETE', 'The bounded Jules source inventory did not establish repository access.')

    def start(self, request, qualification, dispatch):
        if self.backend == 'cursor':
            body = {'name': request['correlation'], 'prompt': {'text': request['objective']},
                    'model': {'id': request['model_id']},
                    'repos': [{'url': 'https://github.com/' + request['repository'], 'startingRef': request['source_sha']}],
                    'autoCreatePR': False, 'workOnCurrentBranch': False}
            result = self.api('POST', '/v1/agents', body=body, dispatch=dispatch)
            agent, run = result.get('agent', {}), result.get('run', {})
            session, turn = identifier(agent.get('id')), identifier(run.get('id'))
            if run.get('agentId') != session:
                raise AgentError('AGENT_RESPONSE_IDENTITY_MISMATCH', 'The created run is not bound to the returned agent.', dispatched=True)
            return {'session_id': session, 'remote_run_id': turn, 'state': normalize_state(run.get('status')), 'response': result}
        body = {'title': request['correlation'], 'prompt': request['objective'],
                'sourceContext': {'source': qualification['source'], 'githubRepoContext': {'startingBranch': request['source_branch']}},
                'requirePlanApproval': True}
        result = self.api('POST', '/v1alpha/sessions', body=body, dispatch=dispatch)
        name = result.get('name')
        if not isinstance(name, str) or not name.startswith('sessions/'):
            raise AgentError('AGENT_RESPONSE_INVALID', 'Jules did not confirm a session identity.', dispatched=True)
        session = identifier(name.removeprefix('sessions/'))
        return {'session_id': session, 'remote_run_id': session, 'state': normalize_state(result.get('state')), 'response': result}

    def status(self, saved):
        session = identifier(saved['session_id'])
        if self.backend == 'cursor':
            turn = identifier(saved['remote_run_id'])
            result = self.api('GET', '/v1/agents/' + session + '/runs/' + turn)
            if result.get('id') != turn or result.get('agentId') != session:
                raise AgentError('AGENT_RESPONSE_IDENTITY_MISMATCH', 'Cursor returned a different run identity.')
            return {'state': normalize_state(result.get('status')), 'response': result}
        result = self.api('GET', '/v1alpha/sessions/' + session)
        if result.get('name') != 'sessions/' + session:
            raise AgentError('AGENT_RESPONSE_IDENTITY_MISMATCH', 'Jules returned a different session identity.')
        return {'state': normalize_state(result.get('state')), 'response': result}

    def control(self, saved, kind, message, dispatch):
        session = identifier(saved['session_id'])
        if self.backend == 'jules':
            if kind == 'approve_plan':
                return self.api('POST', '/v1alpha/sessions/' + session + ':approvePlan', body={}, dispatch=dispatch)
            if kind == 'steer':
                return self.api('POST', '/v1alpha/sessions/' + session + ':sendMessage', body={'prompt': message}, dispatch=dispatch)
            raise AgentError('AGENT_CAPABILITY_UNAVAILABLE', 'Jules does not advertise a cancellation operation; its remote session may continue.')
        if kind == 'steer':
            # Cursor steering does not have an expected-run precondition. The
            # controller records that limitation and rechecks before dispatch.
            return self.api('POST', '/v1/agents/' + session + '/runs',
                            body={'prompt': {'text': message}, 'delivery': 'steer'}, dispatch=dispatch)
        if kind == 'cancel':
            return self.api('POST', '/v1/agents/' + session + '/runs/' + identifier(saved['remote_run_id']) + '/cancel', body={}, dispatch=dispatch)
        raise AgentError('AGENT_CAPABILITY_UNAVAILABLE', 'This agent does not advertise the requested control.')

    def events(self, saved, after=None):
        session, after = identifier(saved['session_id']), cursor(after)
        if self.backend == 'jules':
            result = self.api('GET', '/v1alpha/sessions/' + session + '/activities',
                              params={'pageSize': 50, **({'pageToken': after} if after else {})})
            rows = result.get('activities')
            if not isinstance(rows, list) or len(rows) > 100 or any(not isinstance(v, dict) or not str(v.get('name', '')).startswith('sessions/' + session + '/activities/') for v in rows):
                raise AgentError('AGENT_EVENTS_INVALID', 'Jules did not return a session-bound activity page.')
            following = cursor(result.get('nextPageToken'))
            return {'events': rows, 'next_cursor': following, 'complete': following is None}
        turn = identifier(saved['remote_run_id'])
        headers = self.headers()
        headers['Accept'] = 'text/event-stream'
        if after:
            headers['Last-Event-ID'] = after
        try:
            result = self.exchange({'kind': 'events', 'backend': 'cursor', 'method': 'GET',
                                    'path': '/v1/agents/' + session + '/runs/' + turn + '/stream',
                                    'headers': headers, 'after': after})
            result['next_cursor'] = cursor(result.get('next_cursor'))
            return result
        except HttpFailure as error:
            if error.code == 'AGENT_RESPONSE_DEADLINE':
                return {'events': [], 'next_cursor': after, 'complete': False, 'waiting': True}
            raise AgentError(error.code, 'The provider event stream could not be resumed within its bounded window.') from None
        except (ValueError, UnicodeError):
            raise AgentError('AGENT_EVENTS_INVALID', 'The provider event stream contains an invalid event.') from None

    def artifacts(self, saved):
        if self.backend == 'jules':
            return self.events(saved, saved.get('artifact_cursor'))
        session = identifier(saved['session_id'])
        return self.api('GET', '/v1/agents/' + session + '/artifacts')


def normalize_state(value):
    return {
        'QUEUED': 'QUEUED', 'CREATING': 'QUEUED', 'PLANNING': 'RUNNING', 'IN_PROGRESS': 'RUNNING',
        'RUNNING': 'RUNNING', 'WAITING_FOR_BACKGROUND_WORK': 'RUNNING', 'AWAITING_PLAN_APPROVAL': 'WAITING_FOR_INPUT',
        'AWAITING_USER_FEEDBACK': 'WAITING_FOR_INPUT', 'PAUSED': 'WAITING_FOR_INPUT',
        'COMPLETED': 'COMPLETED', 'FINISHED': 'COMPLETED', 'FAILED': 'FAILED', 'ERROR': 'FAILED',
        'CANCELLED': 'CANCELLED', 'EXPIRED': 'TIMED_OUT'
    }.get(value, 'STATE_UNVERIFIED')
