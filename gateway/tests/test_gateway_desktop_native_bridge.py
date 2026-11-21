"""Behavior regressions for native presentation transport and durable recovery."""
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import native_bridge
from desktop.chat_state import ChatState
from desktop.configuration import DEFAULTS, atomic_json
from desktop.conversation_client import ClientError
from desktop.manager import ManagerRequestError
from v14.state_lock import StateDirectoryAlreadyOwned

IDENTITY = {'gateway_id': 'gateway-fixture', 'principal_id': 'local:single-user'}


class NativeBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        atomic_json(self.root / 'current.json', {'version': '17.0.1'})

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def command():
        return {'installation_id': IDENTITY['gateway_id'], 'owner_principal': 'paired:phone-one',
                'expected_revision': 2, 'command_id': 'desktop-command-1', 'changes': {'engineering_enabled': False}}

    def receipt(self, status='applied', **extra):
        return dict(schema='gptmobile.gateway.command.v1', command_id='desktop-command-1',
                    installation_id=IDENTITY['gateway_id'], principal_id='paired:phone-one',
                    revision=3, status=status, **extra)

    def test_phone_setup_keeps_same_account_address_and_private_wifi_link(self):
        """Use the host’s Tailscale offer when present and the TLS-pinned link for Wi-Fi."""
        ui = (Path(__file__).resolve().parents[1] / 'desktop' / 'native' / 'MainWindow.Control.cs').read_text(encoding='utf-8')
        connection = ui.split('private void BuildConnectPhone()', 1)[1].split('private void BuildAndroid()', 1)[0]
        setup, private_wifi = connection.split('private void AddPrivatePhoneLink(', 1)
        account = setup.split('if (direct.Length > 0)', 1)[1].split('else', 1)[0]
        self.assertIn('current.Text("account_connection_url", "")', setup)
        self.assertIn('Copy Tailscale address', account)
        self.assertIn('Clipboard.SetText(direct)', account)
        self.assertNotIn('control-connect-address', setup)
        self.assertIn('AddPrivatePhoneLink(form, hint, host, ip, viewEpoch,', setup.split(account, 1)[1])
        self.assertIn('Copy PC address', private_wifi)
        self.assertIn('Other connection options', private_wifi)
        self.assertIn('Copy secure phone link', private_wifi)
        self.assertIn('Call("control-connect-address", new JsonObject { ["host"] = SelectedHost() })', private_wifi)
        self.assertIn('Clipboard.SetText(invitation)', private_wifi)
        self.assertNotIn('["phone_ip"]', connection)
        self.assertNotIn('Phone Wi-Fi IP', connection)
        self.assertIn('Engineering is enabled automatically', connection)

    def test_unknown_action_and_malformed_frames_do_not_dispatch(self):
        for value in [{}, {'protocol': native_bridge.PROTOCOL, 'action': None},
                      {'protocol': native_bridge.PROTOCOL, 'action': 'execute-shell', 'args': {'command': 'secret'}}]:
            with self.subTest(value=value), patch.object(native_bridge, 'request') as request:
                output = io.StringIO()
                self.assertEqual(1, native_bridge.serve(self.root, io.StringIO(json.dumps(value)), output))
                self.assertEqual('error', json.loads(output.getvalue())['event'])
                self.assertNotIn('secret', output.getvalue())
                request.assert_not_called()

    def test_settings_unknown_or_mismatched_response_retains_original_reference(self):
        for value in [{}, self.receipt(command='unrelated'), dict(self.receipt(), command_id='another'),
                      dict(self.receipt(), installation_id='another-pc'), dict(self.receipt(), principal_id='other-owner'),
                      dict(self.receipt(), status='unknown')]:
            pending = self.root / 'data' / 'native-settings-pending.json'
            pending.unlink(missing_ok=True)
            with self.subTest(value=value), patch.object(native_bridge, 'request', return_value={'result': value}):
                if value.get('command') == 'unrelated':
                    # Unknown optional fields cannot change the exact receipt authority.
                    native_bridge.control(self.root, 'control-settings-patch', self.command())
                    self.assertFalse(pending.exists())
                else:
                    with self.assertRaises(ValueError):
                        native_bridge.control(self.root, 'control-settings-patch', self.command())
                    self.assertEqual('desktop-command-1', json.loads(pending.read_text())['command_id'])

    def test_confirmed_rejection_and_superseded_retire_uncertainty_without_claiming_applied(self):
        for outcome in ['applied', 'waiting_for_jobs', 'setup_required', 'superseded', 'rejected', 'waiting_pc', 'reviewed']:
            extra = {'dispatch_state': 'not_dispatched', 'error': {'code': 'revision_conflict'}} if outcome == 'rejected' else {}
            with self.subTest(outcome=outcome), patch.object(native_bridge, 'request', return_value={'result': self.receipt(outcome, **extra)}):
                result = native_bridge.control(self.root, 'control-settings-patch', self.command())
                self.assertEqual(outcome, result['status'])
                self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_host_review_actions_use_only_the_authoritative_supervisor(self):
        cases = [
            ('control-backend-approve', {'backend': 'http://127.0.0.1:8081'}, {'backend': 'http://127.0.0.1:8081', 'approved': True}),
            ('control-validation-approve', {}, {'profile_id': 'a' * 64, 'label': 'Approved host policy', 'approved': True}),
            ('control-host-requests', {}, {'items': [{'command_id': 'phone-request', 'status': 'waiting_pc'}]}),
            ('control-host-request-review', {'installation_id': IDENTITY['gateway_id'], 'principal_id': 'paired:phone-one',
                                             'command_id': 'phone-request'}, self.receipt('reviewed')),
        ]
        for action, payload, receipt in cases:
            with self.subTest(action=action), patch.object(native_bridge, 'request', return_value={'result': receipt}) as request:
                result = native_bridge.dispatch(self.root, action, payload, io.StringIO())
                self.assertEqual(receipt, result)
                request.assert_called_once_with(self.root, action, payload=payload)
        self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_waiting_pc_is_acknowledged_without_blocking_later_settings_changes(self):
        original = dict(self.command(), changes={'host_request': 'Review pending restart'})
        with patch.object(native_bridge, 'request', return_value={'result': self.receipt('waiting_pc')}) as request:
            result = native_bridge.dispatch(self.root, 'control-settings-patch', original, io.StringIO())
            self.assertEqual('waiting_pc', result['status'])
            request.assert_called_once_with(self.root, 'control-settings-patch', payload=original)
        changed = dict(self.command(), command_id='desktop-command-2', expected_revision=3)
        with patch.object(native_bridge, 'request', return_value={'result': dict(self.receipt(), command_id='desktop-command-2')}) as request:
            native_bridge.dispatch(self.root, 'control-settings-patch', changed, io.StringIO())
            request.assert_called_once_with(self.root, 'control-settings-patch', payload=changed)
        self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_settings_timeout_reconciliation_never_reissues_patch(self):
        with patch.object(native_bridge, 'request', side_effect=TimeoutError('unknown')) as request:
            with self.assertRaises(TimeoutError):
                native_bridge.control(self.root, 'control-settings-patch', self.command())
            with self.assertRaises(ValueError):
                native_bridge.control(self.root, 'control-settings-patch', dict(self.command(), command_id='new'))
            self.assertEqual(1, request.call_count)
        with patch.object(native_bridge, 'request', return_value={'result': self.receipt()}) as request:
            result = native_bridge.control(self.root, 'control-settings-command',
                {'owner_principal': 'paired:phone-one', 'command_id': 'desktop-command-1'})
            self.assertEqual('applied', result['status'])
            self.assertEqual('control-settings-command', request.call_args.args[1])

    def retry_target(self):
        return {key: self.command()[key] for key in ('command_id', 'installation_id', 'owner_principal')}

    def save_lost_settings(self):
        with patch.object(native_bridge, 'request', side_effect=TimeoutError('reply unavailable')):
            with self.assertRaises(TimeoutError):
                native_bridge.control(self.root, 'control-settings-patch', self.command())

    def test_explicit_original_retry_recovers_loss_before_server_dispatch(self):
        self.save_lost_settings()
        pending = json.loads((self.root / 'data' / 'native-settings-pending.json').read_text())
        self.assertEqual(self.command(), pending['payload'])
        absent = ManagerRequestError({'error_code': 'command_not_found', 'http_status': 404})
        snapshot = {'schema': 'gptmobile.gateway.settings.v1', 'installation_id': IDENTITY['gateway_id'],
                    'principal_id': 'paired:phone-one', 'revision': 2}
        with patch.object(native_bridge, 'request', side_effect=[absent, {'result': snapshot}, {'result': self.receipt()}]) as request:
            result = native_bridge.control(self.root, 'control-settings-retry', self.retry_target())
        self.assertEqual('applied', result['status'])
        self.assertEqual(['control-settings-command', 'control-settings-read', 'control-settings-patch'], [call.args[1] for call in request.call_args_list])
        self.assertEqual(self.command(), request.call_args_list[-1].kwargs['payload'])
        self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_explicit_original_retry_after_commit_only_reads_existing_receipt(self):
        committed = {}
        patch_count = 0
        def gateway(root, action, payload=None):
            nonlocal patch_count
            if action == 'control-settings-patch':
                patch_count += 1
                committed[payload['command_id']] = self.receipt()
                raise TimeoutError('The committed reply was lost')
            self.assertEqual('control-settings-command', action)
            return {'result': committed[payload['command_id']]}
        with patch.object(native_bridge, 'request', side_effect=gateway):
            with self.assertRaises(TimeoutError):
                native_bridge.control(self.root, 'control-settings-patch', self.command())
            result = native_bridge.control(self.root, 'control-settings-retry', self.retry_target())
        self.assertEqual('applied', result['status'])
        self.assertEqual(1, patch_count)
        self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_original_retry_retains_pending_on_ambiguous_error_or_changed_installation(self):
        for error in [TimeoutError(), RuntimeError('command_not_found'),
                      ManagerRequestError({'error_code': 'command_not_found', 'http_status': 500}),
                      ManagerRequestError({'error_code': 'device_revoked', 'http_status': 404})]:
            (self.root / 'data' / 'native-settings-pending.json').unlink(missing_ok=True)
            self.save_lost_settings()
            with self.subTest(error=type(error)), patch.object(native_bridge, 'request', side_effect=error) as request:
                with self.assertRaises(type(error)):
                    native_bridge.control(self.root, 'control-settings-retry', self.retry_target())
                self.assertEqual(['control-settings-command'], [call.args[1] for call in request.call_args_list])
                self.assertTrue((self.root / 'data' / 'native-settings-pending.json').exists())
        absent = ManagerRequestError({'error_code': 'command_not_found', 'http_status': 404})
        changed = {'schema': 'gptmobile.gateway.settings.v1', 'installation_id': 'another-gateway', 'principal_id': 'paired:phone-one'}
        with patch.object(native_bridge, 'request', side_effect=[absent, {'result': changed}]) as request:
            with self.assertRaises(ValueError):
                native_bridge.control(self.root, 'control-settings-retry', self.retry_target())
        self.assertEqual(['control-settings-command', 'control-settings-read'], [call.args[1] for call in request.call_args_list])
        self.assertTrue((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_legacy_pending_reference_supports_receipt_lookup_but_never_retry(self):
        atomic_json(self.root / 'data' / 'native-settings-pending.json', self.retry_target())
        with patch.object(native_bridge, 'request') as request:
            with self.assertRaises(ValueError):
                native_bridge.control(self.root, 'control-settings-retry', self.retry_target())
            request.assert_not_called()
        with patch.object(native_bridge, 'request', return_value={'result': self.receipt()}):
            native_bridge.control(self.root, 'control-settings-command', self.retry_target())
        self.assertFalse((self.root / 'data' / 'native-settings-pending.json').exists())

    def test_concurrent_windows_cannot_overwrite_the_pending_command(self):
        entered, release = threading.Event(), threading.Event()
        errors = []
        def wait(*_, **__):
            entered.set()
            if not release.wait(5):
                raise TimeoutError()
            return {'result': self.receipt()}
        def first():
            try:
                native_bridge.control(self.root, 'control-settings-patch', self.command())
            except Exception as error:
                errors.append(error)
        with patch.object(native_bridge, 'request', side_effect=wait):
            worker = threading.Thread(target=first)
            worker.start()
            try:
                self.assertTrue(entered.wait(5))
                with self.assertRaises(StateDirectoryAlreadyOwned):
                    native_bridge.control(self.root, 'control-settings-patch', dict(self.command(), command_id='second'))
                pending = json.loads((self.root / 'data' / 'native-settings-pending.json').read_text())
                self.assertEqual('desktop-command-1', pending['command_id'])
            finally:
                release.set(); worker.join(5)
        self.assertEqual([], errors)

    def test_search_forwards_retrieved_sources_and_replays_without_resubmission(self):
        from desktop.public_activity import PublicActivity
        args = dict(identity=IDENTITY, command_id='search-turn', model='selected', prompt='Find sources', mode='Search')
        client = Mock()
        receipt = dict(command_id='search-turn', conversation_id='thread', job_id='job')
        sources = [{'title':'Observed document','url':'https://example.org/source','snippet':'Public excerpt'}]
        def complete(model, prompt, command, **kwargs):
            self.assertEqual(sources, kwargs['sources'])
            kwargs['on_event']('accepted',receipt)
            kwargs['on_event']('delta','An answer [1].')
            return receipt
        client.send.side_effect = complete
        with patch.object(native_bridge,'connected',return_value=client), patch.object(native_bridge,'request',
                  return_value={'result':{'engine':'web_search','sources':sources}}):
            native_bridge.chat_send(self.root,args,io.StringIO())
            events = PublicActivity(self.root,IDENTITY).replay('thread')['items']
            self.assertEqual('https://example.org/source',next(e for e in events if e['sources'])['sources'][0]['url'])
            client.request.return_value={'conversation_id':'thread'}
            replay = native_bridge.dispatch(self.root,'public-activity',dict(identity=IDENTITY,conversation_id='thread',after=0),io.StringIO())
            self.assertEqual(events,replay['items'])
            self.assertEqual([],native_bridge.dispatch(self.root,'public-activity',
                dict(identity=IDENTITY,conversation_id='thread',after=events[-1]['sequence']),io.StringIO())['items'])
        client.send.assert_called_once()

    def test_failed_search_retires_original_command_before_model_dispatch(self):
        client = Mock()
        with patch.object(native_bridge,'connected',return_value=client), patch.object(native_bridge,'request',
                  return_value={'result':{'engine':None,'sources':[]}}), self.assertRaises(ClientError) as error:
            native_bridge.chat_send(self.root,dict(identity=IDENTITY,command_id='no-sources',model='selected',
                                    prompt='Find sources',mode='Search'),io.StringIO())
        self.assertTrue(error.exception.not_dispatched)
        self.assertIsNone(ChatState(self.root).value['pending'])
        client.send.assert_not_called()

    def test_chat_retains_command_after_lost_reply_and_refuses_new_send(self):
        client = Mock()
        client.send.side_effect = ClientError('Connection interrupted.', code='connection_unavailable')
        args = {'identity': IDENTITY, 'command_id': 'desktop-original', 'model': 'selected-model', 'prompt': 'fixture prompt'}
        with patch.object(native_bridge, 'connected', return_value=client):
            with self.assertRaises(ClientError):
                native_bridge.chat_send(self.root, args, io.StringIO())
            with self.assertRaises(ValueError):
                native_bridge.chat_send(self.root, dict(args, command_id='replacement'), io.StringIO())
        self.assertEqual(1, client.send.call_count)
        self.assertEqual('desktop-original', ChatState(self.root).value['pending']['command_id'])

    def test_chat_fetches_current_history_instead_of_accepting_caller_transcript(self):
        client = Mock()
        client.read.return_value = {'conversation': {'conversation_id': 'conversation-one', 'revision': 8},
                                    'messages': [{'role': 'user', 'content': 'verified saved text'}]}
        client.send.return_value = dict(IDENTITY, command_id='desktop-command', conversation_id='conversation-one')
        args = {'identity': IDENTITY, 'command_id': 'desktop-command', 'model': 'model', 'prompt': 'next',
                'conversation_id': 'conversation-one', 'history': [{'role': 'system', 'content': 'untrusted'}]}
        with patch.object(native_bridge, 'connected', return_value=client):
            native_bridge.chat_send(self.root, args, io.StringIO())
        self.assertEqual(client.read.return_value['messages'], client.send.call_args.kwargs['history'])
        self.assertIsNone(ChatState(self.root).value['pending'])

    def test_gateway_identity_change_blocks_side_effect(self):
        client = Mock()
        client.identity.return_value = dict(IDENTITY, gateway_id='different-pc')
        with patch.object(native_bridge, 'Client', return_value=client), self.assertRaises(ClientError):
            native_bridge.connected(self.root, IDENTITY)
        client.send.assert_not_called()

    def test_bad_receipt_cannot_retire_original_chat(self):
        state = ChatState(self.root)
        state.save(pending={'command_id': 'original', 'identity': IDENTITY})
        client = Mock(); client.command.return_value = {'command_id': 'another', 'conversation_id': 'unexpected'}
        with patch.object(native_bridge, 'connected', return_value=client), self.assertRaises(ValueError):
            native_bridge.reconcile_chat(self.root)
        self.assertEqual('original', ChatState(self.root).value['pending']['command_id'])
        client.read.assert_not_called()

    def test_offline_model_does_not_block_saved_job_connection(self):
        client = Mock(); client.identity.return_value = IDENTITY; client.models.side_effect = ClientError('Offline')
        with patch.object(native_bridge, 'Client', return_value=client):
            result = native_bridge.dispatch(self.root, 'connect', {}, io.StringIO())
        self.assertEqual(IDENTITY, result['identity']); self.assertEqual([], result['models'])

    def test_utility_launch_uses_argument_vector_and_closed_command_set(self):
        with patch.object(native_bridge.subprocess, 'Popen') as launch:
            native_bridge.dispatch(self.root, 'open-utility', {'command': 'dashboard', 'view': 'Tools'}, io.StringIO())
            self.assertIn('--legacy-ui', launch.call_args.args[0])
            self.assertEqual(['--view', 'Tools'], launch.call_args.args[0][-2:])
            for stream in ('stdin', 'stdout', 'stderr'):
                self.assertEqual(native_bridge.subprocess.DEVNULL, launch.call_args.kwargs[stream])
            with self.assertRaises(ValueError):
                native_bridge.dispatch(self.root, 'open-utility', {'command': 'powershell'}, io.StringIO())
            self.assertEqual(1, launch.call_count)

    def test_retained_engineering_receipt_uses_supervisor_without_owner_substitution(self):
        for prefix in ('eng_', 'cp_'):
            job_id = prefix + 'a' * 32
            original = {'job': {'id': job_id}, 'principal_id': 'paired:original-owner', 'gateway_id': IDENTITY['gateway_id']}
            with self.subTest(prefix=prefix), patch.object(native_bridge, 'request', return_value={'result': original}) as request, patch.object(native_bridge, 'connected') as connected:
                result = native_bridge.dispatch(self.root, 'job-read', {'job_id': job_id}, io.StringIO())
                self.assertEqual(original, result)
                request.assert_called_once_with(self.root, 'read-retained-work', payload={'job_id': job_id})
                connected.assert_not_called()

    def test_saved_draft_cannot_replace_an_ambiguous_send(self):
        state = ChatState(self.root)
        state.save(draft='original', model='model', pending={'command_id': 'original-command', 'identity': IDENTITY})
        with self.assertRaises(ValueError):
            native_bridge.dispatch(self.root, 'draft-save', {'draft': 'replacement', 'model': 'other'}, io.StringIO())
        restored = ChatState(self.root).value
        self.assertEqual('original', restored['draft'])
        self.assertEqual('original-command', restored['pending']['command_id'])


if __name__ == '__main__':
    unittest.main()
