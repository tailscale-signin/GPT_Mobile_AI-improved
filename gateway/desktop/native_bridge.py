"""Local JSONL transport for the native Windows shell.

This is a presentation adapter, not a second runtime. Commands use the existing
supervisor queue, principal-bound chat client and gateway-owned benchmark jobs.
The adapter never exposes the supervisor management credential to a window.
"""
import hashlib
import base64
import io
import json
import re
import subprocess
import time
import sys
from pathlib import Path

from desktop.configuration import atomic_json, load
from desktop.conversation_client import Client, ClientError, privacy_text, scope_key
from desktop.chat_state import ChatState
from desktop.public_activity import PublicActivity
from desktop.dashboard_model import read_snapshot
from desktop.manager import ManagerRequestError, executable, read_pointer, request
from v14.state_lock import StateDirectoryLock

MAX_INPUT = 512 * 1024
PROTOCOL = 'gptmobile.desktop.v1'
CONTROL_ACTIONS = {
    'control-settings-read', 'control-settings-patch', 'control-settings-command', 'control-settings-retry',
    'control-connect-address', 'control-connect-create', 'control-pairing-create', 'control-pairing-list', 'control-pairing-confirm',
    'control-devices', 'control-device-revoke', 'control-enable',
    'control-validation-approve', 'control-host-requests', 'control-host-request-review',
    'control-backend-approve',
}
UTILITY_VIEWS = {'Overview', 'Chat', 'Saved', 'Jobs', 'Tools', 'Benchmarks'}


def emit(event, value, output):
    output.write(json.dumps({'protocol': PROTOCOL, 'event': event, 'value': value}, ensure_ascii=False) + '\n')
    output.flush()


def bound_text(value, maximum=512, *, blank=False):
    if not isinstance(value, str) or (not blank and not value) or len(value) > maximum:
        raise ValueError('This action has an invalid text field.')
    return value


def connected(root, identity):
    if not isinstance(identity, dict) or any(not isinstance(identity.get(key), str) for key in ('gateway_id', 'principal_id')):
        raise ValueError('Connect to this Gateway before performing that action.')
    client = Client(root, identity)
    observed = client.identity()
    if scope_key(observed) != scope_key(identity):
        raise ClientError('Gateway identity changed. Reconnect to the original Gateway.', code='identity_changed', not_dispatched=True)
    return client


def retained_work(root, action, args):
    """Host actions bind to this installation; the original owner is metadata."""
    fields = {'identity'} | ({'cursor'} if action == 'job-owner-list' and 'cursor' in args else set())
    if action != 'job-owner-list':
        fields.add('job_id')
    if action in {'job-owner-cancel', 'job-owner-cancel-receipt'}:
        fields.add('command_id')
    if action == 'job-owner-cancel':
        fields.add('target')
    if set(args) != fields:
        raise ValueError('Use the original retained job and cancellation command.')
    connected(root, args.get('identity'))
    payload = {}
    if action == 'job-owner-list':
        if args.get('cursor') is not None:
            payload['cursor'] = bound_text(args['cursor'], 4096)
        manager_action = 'list-retained-work'
    else:
        job_id = bound_text(args.get('job_id'), 100)
        if not re.fullmatch(r'[A-Za-z0-9_-]+', job_id):
            raise ValueError('Invalid retained job identity.')
        payload['job_id'] = job_id
        manager_action = 'read-retained-work'
        if action in {'job-owner-cancel', 'job-owner-cancel-receipt'}:
            command = bound_text(args.get('command_id'), 128)
            if not re.fullmatch(r'[A-Za-z0-9_.:-]+', command):
                raise ValueError('Invalid cancellation command identity.')
            payload['command_id'] = command
            manager_action = 'read-retained-work-cancel'
        if action == 'job-owner-cancel':
            target = args['target']
            if (not isinstance(target, dict) or set(target) != {'gateway_id', 'principal_id', 'job_kind', 'revision', 'generation'} or
                    target['gateway_id'] != args['identity']['gateway_id']):
                raise ValueError('Read this exact Gateway job before stopping it.')
            payload['target'] = target
            manager_action = 'cancel-retained-work'
    result = request(root, manager_action, payload=payload)['result']
    valid = isinstance(result, dict) and result.get('gateway_id') == args['identity']['gateway_id']
    if action in {'job-owner-cancel', 'job-owner-cancel-receipt'}:
        receipt = result.get('receipt') if isinstance(result, dict) else None
        target = receipt.get('target') if isinstance(receipt, dict) else None
        valid = (valid and result.get('job_id') == job_id and result.get('command_id') == command and
                 isinstance(receipt, dict) and receipt.get('actor') == 'local:manager' and receipt.get('command_id') == command and
                 receipt.get('job_id') == job_id and isinstance(target, dict) and
                 all(result.get(key) == target.get(key) for key in ('gateway_id', 'principal_id', 'job_kind')) and
                 (action != 'job-owner-cancel' or target == args['target']))
    elif action == 'job-owner-read':
        job = result.get('job') if isinstance(result, dict) else None
        valid = valid and isinstance(job, dict) and job.get('id', job.get('job_id')) == job_id
    elif action == 'job-owner-list':
        valid = valid and result.get('scope') == 'local_supervisor' and isinstance(result.get('items'), list)
    if not valid:
        raise ClientError('The returned receipt does not match this Gateway and original job. Read the saved cancellation command before another action.',
                          code='retained_work_identity_changed')
    return result


def chat_send(root, args, output):
    # One in-flight desktop send, including other native windows/processes.
    lock = StateDirectoryLock(root / 'native-chat-send' / 'owner')
    lock.acquire()
    try:
        state = ChatState(root)
        if state.error:
            raise ValueError(state.error)
        if state.value.get('pending'):
            raise ValueError('Check the original send before starting another request.')
        identity = args.get('identity')
        client = connected(root, identity)
        command = bound_text(args.get('command_id'), 256)
        prompt = bound_text(args.get('prompt'), 32000)
        model = bound_text(args.get('model'), 512)
        # Never accept a supplied transcript or revision as authority.
        opened = client.read(bound_text(args['conversation_id'])) if args.get('conversation_id') else {}
        conversation = opened.get('conversation')
        pending = {'command_id': command, 'identity': identity, 'draft_conversation': args.get('conversation_id') or '',
                   'draft_sha256': hashlib.sha256(prompt.encode()).hexdigest()}
        state.save(draft=prompt, model=model, pending=pending)

        journal = PublicActivity(root, identity)
        def observe(key, kind, text, sources=(), receipt=None, fact=None):
            binding = receipt or {}
            emit('activity', journal.append(command, key, kind, text, sources,
                 binding.get('conversation_id'), binding.get('job_id'), fact=fact), output)

        first_text_start = None
        accepted_job = None
        accepted_receipt = None
        def progress(event, value):
            nonlocal first_text_start, accepted_job, accepted_receipt
            if event == 'delta' and value and first_text_start is not None and accepted_job:
                observe('answer', 'final', '', receipt=accepted_receipt)
                journal.timing(accepted_job, round((time.monotonic()-first_text_start)*1000))
                first_text_start = None
            if event == 'accepted':
                first_text_start, accepted_job = time.monotonic(), value.get('job_id')
                accepted_receipt = value
                state.save(pending=dict(pending, receipt=value))
                observe('admission', 'activity', 'Gateway accepted the request.', receipt=value)
            emit(event, value, output)

        try:
            kwargs = {}
            if args.get('mode') == 'Search':
                # Search is a read before admission. Failure here proves no model send occurred.
                try:
                    if len(prompt) > 4096:
                        raise ValueError('Search queries can contain up to 4,096 characters.')
                    observe('search', 'tool_start', 'Searching configured web sources…')
                    found = request(root, 'desktop-search', payload={'query': prompt})['result']
                    if not found.get('sources'):
                        raise ValueError('No source records returned. Check the web search service in Tools → Add-ons / MCP.')
                    kwargs['sources'] = found['sources']
                    sources = [{k: item[k] for k in ('url', 'title')} for item in found['sources']]
                    fingerprint = hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()
                    fact = dict(scope_id=command, criterion_id='search', subject_id='retrieved-sources',
                                revision_id=fingerprint, value=str(len(sources)), evidence_id=fingerprint)
                    observe('search', 'finding', f"Retrieved {len(sources)} sources using {found['engine']}.", sources, fact=fact)
                except (OSError, RuntimeError, ValueError, KeyError) as error:
                    raise ClientError(str(error), not_dispatched=True) from None
            receipt = client.send(model, prompt, command, conversation=conversation,
                                  history=opened.get('messages', []), on_event=progress, **kwargs)
        except ClientError as error:
            if error.not_dispatched:
                state.retire_pending(command, identity)
            raise
        if receipt.get('command_id') != command or not receipt.get('conversation_id'):
            raise ValueError('The response needs reconciliation. Check the original send.')
        journal.append(command, 'answer', 'final', '', conversation=receipt['conversation_id'], job=receipt.get('job_id'))
        journal.save_draft(pending['draft_conversation'], '', model)
        state.save(draft='', pending=None, last_conversation_id=receipt['conversation_id'])
        return receipt
    finally:
        lock.release()


def _engineering_activity(phase, previous_kind):
    messages = {'PLAN': 'Planning the repository work.', 'READ': 'Reading the selected repository sources.',
                'IMPLEMENT': 'Preparing the requested changes.', 'REVIEW': 'Reviewing the candidate changes.',
                'COMMIT': 'Recording the candidate changes.', 'OPEN_PR': 'Preparing the pull request.',
                'CI': 'Checking the pull request runs.', 'PROMOTE': 'Checking readiness for review.',
                'MERGE_READY': 'The pull request is ready for merge review.', 'MERGE': 'Processing the merge.',
                'VERIFY': 'Verifying the resulting revision.', 'COMPLETED': 'The engineering run finished.',
                'CANCELLING': 'Cancellation is in progress.', 'CANCELLED': 'The engineering run was cancelled.',
                'FAILED': 'The engineering run stopped with a failure.', 'NEEDS_INPUT': 'The engineering job is waiting for input.'}
    if phase not in messages:
        return 'unsupported', ''
    if phase in {'NEEDS_INPUT', 'FAILED'}:
        return 'blocker', messages[phase]
    if previous_kind == 'blocker' and phase not in {'CANCELLED', 'CANCELLING'}:
        return 'recovery', 'Work resumed. ' + messages[phase]
    return 'activity', messages[phase]


def engineering_findings(journal, job, turn, cid, jid):
    revision = job.get('revision')
    if type(revision) is not int or revision < 0:
        return
    ci = job.get('ci_observation') or {}
    sha, verdict, pr = ci.get('revision'), ci.get('verdict'), ci.get('pr')
    statuses = {'PASSED': 'passed', 'FAILED': 'failed', 'BLOCKED': 'are blocked', 'PENDING': 'are pending'}
    if isinstance(sha, str) and re.fullmatch('(?:[a-f0-9]{40}|[a-f0-9]{64})', sha) and verdict in statuses and type(pr) is int and pr > 0:
        fact = dict(scope_id=turn, criterion_id='ci', subject_id=f'pr-{pr}', revision_id=sha,
                    value=verdict, evidence_id=f'{jid}/{revision}')
        repository = job.get('repository')
        sources = []
        if isinstance(repository, str) and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            sources = [{'url': f'https://github.com/{repository}/commit/{sha}', 'title': f'Checked commit {sha[:12]}'}]
        journal.append(turn, 'ci', 'finding', f'Checks for PR #{pr} {statuses[verdict]} on {sha[:12]}.',
                       sources, conversation=cid, job=jid, fact=fact, observation_version=revision)
    for item in job.get('workspace_tests', [])[-12:]:
        check, status, sha = item.get('check_id'), item.get('status'), item.get('revision')
        if (isinstance(check, str) and re.fullmatch('[a-f0-9]{64}', check) and status in {'PASSED', 'FAILED', 'BLOCKED'}
                and isinstance(sha, str) and re.fullmatch('(?:[a-f0-9]{40}|[a-f0-9]{64})', sha)):
            fact = dict(scope_id=turn, criterion_id='workspace-check', subject_id=check, revision_id=sha,
                        value=status, evidence_id=check)
            journal.append(turn, 'test-' + check, 'finding', f'Workspace check {check[:12]} returned {status.lower()} on {sha[:12]}.',
                           conversation=cid, job=jid, fact=fact, observation_version=revision)


def reconcile_chat(root):
    lock = StateDirectoryLock(root / 'native-chat-send' / 'owner')
    lock.acquire()
    try:
        return _reconcile_chat(root)
    finally:
        lock.release()


def _reconcile_chat(root):
    state = ChatState(root)
    pending = state.value.get('pending')
    if not ChatState.valid_pending(pending):
        raise ValueError('There is no saved send to reconcile.')
    client = connected(root, pending['identity'])
    value = client.command(pending['command_id'])
    if value.get('command_id') != pending['command_id']:
        raise ValueError('The receipt does not identify the original send.')
    if value.get('admission_state') == 'rejected':
        state.retire_pending(pending['command_id'], pending['identity'])
    elif value.get('conversation_id'):
        # Read and verify saved history before retiring an ambiguous send.
        opened = client.read(value['conversation_id'])
        if 'draft_conversation' in pending:
            PublicActivity(root, pending['identity']).save_draft(pending['draft_conversation'], '', '')
        state.save(pending=None, draft='', last_conversation_id=value['conversation_id'])
        value = dict(value, opened=opened)
    return value


def control(root, action, args):
    pending_path = root / 'data' / 'native-settings-pending.json'
    lock = StateDirectoryLock(root / 'native-control-action' / 'owner')
    lock.acquire()
    try:
        pending = None
        if pending_path.exists():
            try:
                if pending_path.stat().st_size > 32768:
                    raise ValueError('Oversized saved reference')
                pending = json.loads(pending_path.read_text(encoding='utf-8'))
                if (not isinstance(pending, dict) or any(not isinstance(pending.get(key), str) or not pending[key]
                        for key in ('command_id', 'installation_id', 'owner_principal'))):
                    raise ValueError('Invalid saved reference')
            except (ValueError, OSError):
                raise ValueError('The previous settings receipt reference could not be read.') from None
        if action == 'control-settings-patch':
            if pending:
                raise ValueError('Read the original settings receipt before submitting another change.')
            args = settings_payload(args)
            pending = {key: args[key] for key in ('command_id', 'installation_id', 'owner_principal')}
            pending['payload'] = args
            atomic_json(pending_path, pending)
        if action in {'control-settings-command', 'control-settings-retry'} and pending:
            if (args.get('command_id') != pending['command_id'] or
                    args.get('owner_principal', 'local:single-user') != pending['owner_principal']):
                raise ValueError('Read the receipt for the original owner and command.')
        # The supervisor alone accesses its management credential and store.
        if action == 'control-settings-retry':
            result = retry_settings(root, args, pending)
        else:
            value = request(root, action, payload=args)
            result = value.get('result', value)
        if action in {'control-settings-patch', 'control-settings-command', 'control-settings-retry'} and pending:
            if confirmed_settings_receipt(result, pending):
                pending_path.unlink(missing_ok=True)
            else:
                raise ValueError('The settings response did not confirm the original command. Read its receipt before retrying.')
        if action == 'control-settings-read' and pending:
            result = dict(result, native_pending=pending)
        if action in {'control-connect-create', 'control-pairing-create'}:
            # Render the canonical one-time invitation locally. The copyable
            # link remains available when the optional source-build QR package
            # is absent; never create or redeem another invitation to render it.
            try:
                import segno
                link = bound_text(result.get('link'), 8192)
                png = io.BytesIO()
                segno.make(link, micro=False).save(png, kind='png', scale=4, border=4)
                result = dict(result, qr_png_base64=base64.b64encode(png.getvalue()).decode('ascii'))
            except (ImportError, ValueError):
                pass
        return result
    finally:
        lock.release()


def confirmed_settings_receipt(value, pending):
    return (isinstance(value, dict) and value.get('schema') == 'gptmobile.gateway.command.v1' and
            value.get('command_id') == pending['command_id'] and
            value.get('installation_id') == pending['installation_id'] and
            value.get('principal_id') == pending['owner_principal'] and
            (value.get('status') in {'applied', 'waiting_for_jobs', 'setup_required', 'superseded', 'waiting_pc', 'reviewed'} or
             value.get('status') == 'rejected' and value.get('dispatch_state') == 'not_dispatched' and
             isinstance(value.get('error'), dict)) and
            type(value.get('revision')) is int and value['revision'] >= 0)


def settings_payload(args):
    """Retain only the exact bounded, noncredential settings command."""
    from desktop.control_store import FIELDS
    from urllib.parse import urlsplit
    if not isinstance(args, dict) or set(args) - {'command_id', 'installation_id', 'owner_principal', 'expected_revision', 'changes'}:
        raise ValueError('Invalid settings command.')
    original = dict(args, owner_principal=args.get('owner_principal', 'local:single-user'))
    bound_text(original.get('command_id'), 256)
    bound_text(original.get('installation_id'))
    bound_text(original.get('owner_principal'))
    changes = original.get('changes')
    if (type(original.get('expected_revision')) is not int or original['expected_revision'] < 0 or
            not isinstance(changes, dict) or not changes or set(changes) - FIELDS or
            any(type(value) not in (str, int, bool) for value in changes.values())):
        raise ValueError('Invalid settings command.')
    if isinstance(changes.get('backend'), str):
        origin = urlsplit(changes['backend'])
        if origin.username is not None or origin.password is not None or origin.query or origin.fragment:
            raise ValueError('Backend origins must not contain credentials, a query or a fragment.')
    encoded = json.dumps(original, ensure_ascii=False, allow_nan=False)
    if len(encoded.encode('utf-8')) > 16384:
        raise ValueError('The settings command exceeds its saved recovery limit.')
    return json.loads(encoded)


def retry_settings(root, args, pending):
    """An explicit retry may resend only this identical, confirmed-absent command.

    The caller holds the cross-process control lock from first lookup through
    receipt reconciliation. No background action or new command ID is created.
    """
    if not pending or not isinstance(pending.get('payload'), dict):
        raise ValueError('This older saved reference has no original change to retry. It supports receipt lookup only.')
    original = settings_payload(pending['payload'])
    if (args.get('installation_id') != pending['installation_id'] or
            any(original[key] != pending[key] for key in ('command_id', 'installation_id', 'owner_principal'))):
        raise ValueError('The saved change belongs to a different installation, owner or command.')
    lookup = {'command_id': pending['command_id'], 'owner_principal': pending['owner_principal']}
    try:
        value = request(root, 'control-settings-command', payload=lookup)
        return value.get('result', value)
    except ManagerRequestError as error:
        if error.code != 'command_not_found' or error.status != 404:
            raise
    # A missing receipt on a different installation is never permission to send.
    value = request(root, 'control-settings-read', payload={'owner_principal': pending['owner_principal']})
    current = value.get('result', value)
    if (not isinstance(current, dict) or current.get('schema') != 'gptmobile.gateway.settings.v1' or
            current.get('installation_id') != pending['installation_id'] or
            current.get('principal_id') != pending['owner_principal']):
        raise ValueError('Reconnect to the original installation and owner before retrying this saved change.')
    value = request(root, 'control-settings-patch', payload=original)
    return value.get('result', value)


def dispatch(root, action, args, output):
    if action == 'snapshot':
        return dict(read_snapshot(root), settings=load(root), version=read_pointer(root)['version'])
    if action == 'connect':
        client = Client(root)
        identity = client.identity()
        try:
            models = client.models()
        except ClientError:
            models = []
        return {'identity': identity, 'models': models, 'privacy': privacy_text(identity),
                'draft': ChatState(root).value}
    if action == 'search-availability':
        connected(root, args.get('identity'))
        return request(root, 'desktop-search', payload={'available': True})['result']
    if action == 'draft-context':
        connected(root, args.get('identity'))
        cid = bound_text(args.get('conversation_id', ''), 256, blank=True)
        saved = PublicActivity(root, args['identity']).draft(cid)
        state = ChatState(root).value
        if saved is None and not cid and not state.get('contextual_drafts'):
            saved = {'draft': state['draft'], 'model': state['model']}
        return dict(saved or {'draft':'','model':''}, pending=state.get('pending'))
    if action == 'job-catalog':
        client = connected(root, args.get('identity'))
        params = {'limit': 100, 'include_copilot': 'true'}
        if args.get('cursor'):
            params['cursor'] = bound_text(args['cursor'], 8192)
        result = client.request('GET', '/gateway/job-catalog', params=params)
        PublicActivity(root, args['identity']).attach_timings(result.get('items', []))
        return result
    if action in {'job-owner-list', 'job-owner-read', 'job-owner-cancel', 'job-owner-cancel-receipt'}:
        return retained_work(root, action, args)
    if action == 'public-activity':
        client = connected(root, args.get('identity'))
        cid = bound_text(args.get('conversation_id'))
        from urllib.parse import quote
        conversation = client.request('GET', '/gateway/conversations/' + quote(cid, safe=''))
        journal = PublicActivity(root, args['identity'])
        if conversation.get('active_job_kind') == 'engineering' and conversation.get('active_job_id'):
            jid = conversation['active_job_id']
            if not re.fullmatch(r'eng_[a-f0-9]{32}', jid):
                raise ValueError('Invalid engineering job identity.')
            rev, previous_kind = journal.engine_position(jid)
            job = client.request('GET', '/gateway/engineering/jobs/' + jid, params={'after_revision': rev})
            if job.get('conversation_id') != cid or job.get('id') != jid:
                raise ValueError('The original job changed.')
            turn = job.get('command_id') or jid
            for item in job.get('updates', []):
                phase, revision = item.get('state'), item.get('revision')
                if isinstance(phase, str) and re.fullmatch('[A-Z_]{1,48}', phase) and type(revision) is int and revision > rev:
                    kind, text = _engineering_activity(phase, previous_kind)
                    event = journal.append(turn, 'revision-' + str(revision), kind, text,
                                           conversation=cid, job=jid, observed=item.get('created_at'))
                    previous_kind = event['event_type']
            engineering_findings(journal, job, turn, cid, jid)
            if not job.get('has_more_updates') and job.get('state') in {'COMPLETED', 'FAILED', 'CANCELLED'}:
                journal.append(turn, 'answer', 'final', '', conversation=cid, job=jid)
        return journal.replay(cid, args.get('after', 0))
    if action.startswith('engineering-'):
        client = connected(root, args.get('identity'))
        if action == 'engineering-prepare':
            opened = client.read(args['conversation_id'])['conversation'] if args.get('conversation_id') else None
            payload, proposal = client.prepare_engineering(args['model'], args['values'], bound_text(args.get('command_id'), 256), opened)
            return {'payload': payload, 'preflight': proposal}
        if action == 'engineering-preflight':
            return client.engineering_preflight(args['payload'])
        if action == 'engineering-submit':
            lock = StateDirectoryLock(root / 'native-chat-send' / 'owner')
            lock.acquire()
            try:
                state = ChatState(root)
                if state.error or state.value.get('pending'):
                    raise ValueError('Recover the original send first.')
                payload = args['payload']
                if client.engineering_preflight(payload).get('ready') is not True:
                    raise ValueError('The engineering prerequisites changed. Prepare this task again.')
                command = bound_text(payload['command_id'], 256)
                state.save(pending={'command_id': command, 'identity': args['identity']})
                try:
                    result = client.submit_engineering(payload)
                except ClientError as error:
                    if error.not_dispatched:
                        state.retire_pending(command, args['identity'])
                    raise
                state.save(draft='', pending=None, last_conversation_id=result['conversation_id'])
                return result
            finally:
                lock.release()
        job = client.engineering_job(args.get('job_id'))
        conversation = {'conversation_id': job['conversation_id'], 'active_job_id': job['id'], 'active_job_kind': 'engineering'}
        if action.startswith('engineering-workspace'):
            from desktop import workspace_client
            if action == 'engineering-workspace':
                return workspace_client.board(client, job, root)
            if action == 'engineering-workspace-command':
                return workspace_client.submit(client, root, job, args['payload'])
            if action == 'engineering-workspace-receipt':
                return workspace_client.receipt(client, root, job, args['command_id'])
            if action == 'engineering-workspace-retry':
                return workspace_client.retry_original(client, root, job, args['command_id'])
            if action == 'engineering-workspace-dismiss':
                return workspace_client.dismiss_absent(client, root, job, args['command_id'])
            if action in {'engineering-workspace-source', 'engineering-workspace-tree'}:
                params = {key: args[key] for key in ('head_sha', 'path', 'start_line', 'line_count', 'query', 'offset') if key in args}
                suffix = 'source' if action.endswith('-source') else 'tree'
                value = client.request('GET', workspace_client.path(job) + '/' + suffix, params=params)
                if value.get('job_id') != job['id'] or value.get('head_sha') != args.get('head_sha'):
                    raise ClientError('The requested source identity changed.')
                return value
            raise ValueError('Unknown workspace action.')
        if action == 'engineering-inspect':
            if args.get('section') == 'Checkpoints':
                reviewed, history = client.engineering_checkpoints(conversation)
                return {'job': reviewed, 'history': history}
            if args.get('section') == 'Evidence':
                return client.request('GET', '/gateway/engineering/jobs/' + job['id'] + '/evidence')
            if args.get('section') == 'Review':
                return client.engineering_review(conversation)
            return job
        if action == 'engineering-approve':
            return client.approve_engineering(conversation, args['reviewed'])
        if action == 'engineering-candidate':
            return client.candidate_details(job, args['check_id'], args.get('offset', 0), args.get('left_check_id'))
        if job['revision'] != args.get('revision'):
            raise ValueError('The reviewed job revision changed. Inspect it again.')
        if action == 'engineering-restore':
            return client.restore_candidate(job, args['check_id'])
        if action == 'engineering-request-changes':
            return client.request('POST', '/gateway/engineering/jobs/' + job['id'] + '/request_changes', payload={'revision': job['revision']})
        raise ValueError('Unknown engineering action.')
    if action.startswith(('schedule-', 'workspace-', 'report-')):
        from desktop.workspace_actions import dispatch as workspace
        return workspace(root, action, args)
    if action == 'draft-save':
        lock = StateDirectoryLock(root / 'native-chat-send' / 'owner')
        lock.acquire()
        try:
            state = ChatState(root)
            if state.value.get('pending'):
                raise ValueError('The original send must be checked before replacing its saved draft.')
            text = bound_text(args.get('draft'), 32000, blank=True)
            model = bound_text(args.get('model'), 512, blank=True)
            if 'conversation_id' in args:
                connected(root, args.get('identity'))
                cid = bound_text(args['conversation_id'], 256, blank=True)
                PublicActivity(root, args['identity']).save_draft(cid, text, model)
                state.save(contextual_drafts=True, draft='' if cid else text, model=model)
            else:
                state.save(draft=text, model=model)
            return {'saved': True}
        finally:
            lock.release()
    if action == 'chat-send':
        return chat_send(root, args, output)
    if action == 'chat-reconcile':
        return reconcile_chat(root)
    if action in {'conversations', 'saved', 'conversation-read', 'conversation-metadata', 'bookmark', 'job-cancel', 'job-read'}:
        if action == 'job-read':
            job_id = bound_text(args.get('job_id'), 100)
            if re.fullmatch(r'(eng|cp)_[a-f0-9]{32}', job_id):
                return request(root, 'read-retained-work', payload={'job_id': job_id})['result']
        client = connected(root, args.get('identity'))
        if action == 'conversations':
            return client.conversations(query=bound_text(args.get('query', ''), 512, blank=True),
                                        archived=args.get('archived') is True, favorite=args.get('favorite') is True,
                                        cursor=args.get('cursor'))
        if action == 'saved':
            return client.saved(query=bound_text(args.get('query', ''), 512, blank=True), cursor=args.get('cursor'))
        if action == 'conversation-read':
            return client.read(bound_text(args.get('conversation_id')))
        if action == 'conversation-metadata':
            changes = args.get('changes')
            if not isinstance(changes, dict) or set(changes) - {'title', 'favorite', 'archived'}:
                raise ValueError('Unsupported conversation change.')
            if type(args.get('revision')) is not int:
                raise ValueError('Refresh the saved conversation before editing it.')
            return client.metadata(bound_text(args.get('conversation_id')), args['revision'], **changes)
        if action == 'bookmark':
            return client.bookmark(bound_text(args.get('conversation_id')), bound_text(args.get('message_id')), args.get('saved') is True)
        job_id = bound_text(args.get('job_id'), 100)
        if not re.fullmatch(r'[A-Za-z0-9_-]+', job_id):
            raise ValueError('Invalid saved job identity.')
        if action == 'job-cancel':
            if job_id.startswith('cp_'):
                raise ValueError('GitHub cloud tasks do not support cancellation from Gateway. Open the retained task receipt for its GitHub URL.')
            if job_id.startswith('eng_'):
                job = client.engineering_job(job_id)
                if 'revision' in args and job['revision'] != args['revision']:
                    raise ValueError('The selected job changed. Review its current state before cancelling.')
                return client.request('POST', '/gateway/engineering/jobs/' + job_id + '/cancel', payload={'revision': job['revision']})
            return client.cancel(job_id)
        if job_id.startswith('eng_'):
            return client.engineering_job(job_id)
        if job_id.startswith('cp_'):
            return client.request('GET', '/gateway/engineering/copilot/tasks/' + job_id)
        return client.request('GET', '/gateway/jobs/' + job_id)
    if action in CONTROL_ACTIONS:
        return control(root, action, args)
    if action == 'control-firewall':
        if set(args) != {'remove'} or type(args['remove']) is not bool:
            raise ValueError('Choose whether to allow or remove this control listener rule.')
        from desktop.firewall import apply
        apply(root, remove=args['remove'], phone=True)
        return {'changed': True, 'removed': args['remove'], 'profile': 'Private', 'remote': 'LocalSubnet'}
    if action == 'runtime-control':
        selected = args.get('action')
        if selected not in {'stop', 'restart', 'check', 'install'}:
            raise ValueError('Unsupported runtime action.')
        target = args.get('target')
        if selected == 'install' and target:
            from desktop.releases import version
            version(target)
            return request(root, selected, payload={'target': target})
        return request(root, selected)
    if action == 'runtime-start':
        from desktop.native_launch import start_supervisor
        start_supervisor(root)
        return {'requested': True}
    if action.startswith('benchmark-'):
        if action.startswith('benchmark-campaign-'):
            from desktop import benchmark_campaigns
            return benchmark_campaigns.dispatch(root, action.removeprefix('benchmark-campaign-'), args)
        from desktop import benchmark
        if action.startswith('benchmark-qualification-'):
            from desktop import benchmark_qualification as qualification
            if action == 'benchmark-qualification-history':
                return {'qualifications': qualification.history(root)}
            if action == 'benchmark-qualification-start':
                return qualification.start(root, bound_text(args.get('path'), 4096), execute=args.get('execute', False))
            if action == 'benchmark-qualification-read':
                return qualification.refresh(root, args.get('id'))
            if action == 'benchmark-qualification-resume':
                return qualification.refresh(root, args.get('id'), execute=True)
            raise ValueError('Unsupported qualification action.')
        if action.startswith('benchmark-compare-'):
            from desktop import benchmark_comparison as comparison
            if action == 'benchmark-compare-history':
                return {'comparisons': comparison.history(root)}
            if action == 'benchmark-compare-export':
                return comparison.export(root, args.get('id'), bound_text(args.get('path'), 4096))
            if action == 'benchmark-compare-create':
                result = comparison.create(root, args.get('models'), preset=args.get('preset', 'quick'),
                    suite=args.get('suite', 'coding'), repeats=args.get('repeats', 3),
                    ordering=args.get('ordering', 'SELECTED_ORDER'), seed=args.get('seed', 17), task_pack=args.get('task_pack', 'python'))
            elif action == 'benchmark-compare-read':
                result = comparison.read(root, args.get('id'))
            elif action == 'benchmark-compare-advance':
                result = comparison.advance(root, args.get('id'))
            elif action == 'benchmark-compare-stop':
                result = comparison.stop(root, args.get('id'))
            else:
                raise ValueError('Unsupported comparison action.')
            return comparison.public_snapshot(result)
        if action == 'benchmark-history':
            return benchmark.history(root, args.get('before'))
        if action == 'benchmark-read':
            return benchmark.run_snapshot(root, args.get('id'))
        if action == 'benchmark-stop':
            return benchmark.stop_run(root, args.get('id'))
        if action == 'benchmark-start':
            if benchmark.load_pending(root):
                raise ValueError('Reconcile the original benchmark submission before starting another.')
            binding = benchmark.execution_binding(root)
            command = bound_text(args.get('request_id'), 256)
            payload = {'request_id': command, 'model': bound_text(args.get('model'), 512),
                       'suite': args.get('suite', 'quick_check'), 'preset': args.get('preset', 'quick'),
                       'seed': 17, 'concurrency': 2}
            if args.get('task_pack', 'python') != 'python':
                payload['task_pack'] = args['task_pack']
            for key in ('context_variants', 'context_repeats', 'context_budget_bytes'):
                if key in args:
                    payload[key] = args[key]
            from benchmarks.contracts import validate_request
            payload = validate_request(payload)
            benchmark.save_pending(root, dict(payload, _binding=binding))
            try:
                result = benchmark.start_run(root, binding=binding, **{k: v for k, v in payload.items() if k != 'seed'})
            except benchmark.BenchmarkRejected:
                benchmark.save_pending(root, None)
                raise
            benchmark.save_pending(root, None)
            return result
        if action == 'benchmark-reconcile':
            pending = benchmark.load_pending(root)
            if not pending:
                raise ValueError('No benchmark submission needs reconciliation.')
            result = benchmark.reconcile_run(root, pending['request_id'], pending['_binding'])
            if result.get('run') or result.get('id') or result.get('runs'):
                benchmark.save_pending(root, None)
            return result
    if action == 'open-utility':
        command = args.get('command')
        if command not in {'settings', 'github-settings', 'engineering-setup', 'benchmark-settings', 'dashboard', 'schedules'}:
            raise ValueError('Unknown Gateway utility.')
        call = executable(root, read_pointer(root)['version'], command) + ['--legacy-ui']
        if command in {'settings', 'github-settings'} and 'view' in args:
            view = args['view']
            if view not in ({'Add-ons', 'General', 'Advanced'} if command == 'settings' else {'Repository Validation', 'GitHub'}):
                raise ValueError('Unknown settings section.')
            call += ['--section', view]
        if command == 'dashboard':
            view = args.get('view', 'Overview')
            if view not in UTILITY_VIEWS:
                raise ValueError('Unknown Gateway workspace.')
            call += ['--view', view]
        subprocess.Popen(call, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return {'opened': command}
    raise ValueError('Unsupported native desktop action.')


def serve(root, incoming=sys.stdin, output=sys.stdout):
    # A process handles one command. Long streams cannot block snapshots or UI.
    # No shell interpolation, request files, or command-line prompt contents.
    root = Path(root).resolve()
    try:
        line = incoming.readline(MAX_INPUT + 1)
        if len(line.encode('utf-8')) > MAX_INPUT:
            raise ValueError('Desktop command exceeds its size limit.')
        value = json.loads(line)
        if (not isinstance(value, dict) or value.get('protocol') != PROTOCOL or
                set(value) - {'protocol', 'action', 'args'} or not isinstance(value.get('args', {}), dict) or
                not isinstance(value.get('action'), str)):
            raise ValueError('Invalid native desktop protocol.')
        result = dispatch(root, value.get('action'), value.get('args', {}), output)
        emit('result', result, output)
        return 0
    except (ValueError, RuntimeError, OSError, TimeoutError) as error:
        # Requests/ClientError already produce bounded public summaries. Never
        # emit traceback, raw HTTP response, headers or subprocess stderr.
        safe = str(error)[:700] if isinstance(error, (ValueError, ClientError)) else 'Gateway could not confirm this action. Read its original receipt or inspect Diagnostics.'
        if isinstance(error, ManagerRequestError) and error.code == 'command_not_found' and error.status == 404:
            safe = 'Gateway has no receipt for the original settings command. Review the saved change and use Retry original change to recover it explicitly.'
        emit('error', {'message': safe, 'code': getattr(error, 'code', 'desktop_action_failed')}, output)
        return 1
