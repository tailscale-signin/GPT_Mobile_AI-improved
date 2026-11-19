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
import sys
from pathlib import Path

from desktop.configuration import atomic_json, load
from desktop.conversation_client import Client, ClientError, privacy_text, scope_key
from desktop.chat_state import ChatState
from desktop.dashboard_model import read_snapshot
from desktop.manager import ManagerRequestError, executable, read_pointer, request
from v14.state_lock import StateDirectoryLock

MAX_INPUT = 512 * 1024
PROTOCOL = 'gptmobile.desktop.v1'
CONTROL_ACTIONS = {
    'control-settings-read', 'control-settings-patch', 'control-settings-command', 'control-settings-retry',
    'control-pairing-create', 'control-pairing-list', 'control-pairing-confirm',
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
        pending = {'command_id': command, 'identity': identity,
                   'draft_sha256': hashlib.sha256(prompt.encode()).hexdigest()}
        state.save(draft=prompt, model=model, pending=pending)

        def progress(event, value):
            if event == 'accepted':
                state.save(pending=dict(pending, receipt=value))
            emit(event, value, output)

        try:
            receipt = client.send(model, prompt, command, conversation=conversation,
                                  history=opened.get('messages', []), on_event=progress)
        except ClientError as error:
            if error.not_dispatched:
                state.retire_pending(command, identity)
            raise
        if receipt.get('command_id') != command or not receipt.get('conversation_id'):
            raise ValueError('The response needs reconciliation. Check the original send.')
        state.save(draft='', pending=None, last_conversation_id=receipt['conversation_id'])
        return receipt
    finally:
        lock.release()


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
        if action == 'control-pairing-create':
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
    if action == 'draft-save':
        lock = StateDirectoryLock(root / 'native-chat-send' / 'owner')
        lock.acquire()
        try:
            state = ChatState(root)
            if state.value.get('pending'):
                raise ValueError('The original send must be checked before replacing its saved draft.')
            state.save(draft=bound_text(args.get('draft'), 32000, blank=True), model=bound_text(args.get('model'), 512, blank=True))
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
        return request(root, selected)
    if action == 'runtime-start':
        from desktop.native_launch import start_supervisor
        start_supervisor(root)
        return {'requested': True}
    if action.startswith('benchmark-'):
        from desktop import benchmark
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
            from benchmarks.contracts import validate_request
            validate_request(payload)
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
