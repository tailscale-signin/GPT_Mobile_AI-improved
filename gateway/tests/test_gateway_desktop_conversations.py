import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.chat_state import ChatState
from desktop.configuration import atomic_json, DEFAULTS
from desktop.conversation_client import Client, ClientError, Transcript, privacy_text


IDENTITY = {'gateway_id': 'gateway-one', 'principal_id': 'local:single-user', 'identity_scope': 'local-machine'}


def message(content, *, ident='message-one', revision=1, part=0, parts=1, whole=None, role='assistant'):
    return {'message_id': ident + str(part), 'logical_message_id': ident, 'revision': revision,
            'part_index': part, 'part_count': parts, 'content': content,
            'content_sha256': hashlib.sha256((content if whole is None else whole).encode()).hexdigest(),
            'role': role, 'created_at': 1, 'turn_id': 'turn-one', 'status': 'completed'}


class Response:
    def __init__(self, value=None, status=200, *, raw=None, headers=None):
        self.status_code, self.headers = status, headers or {}
        self.raw = json.dumps(value).encode() if raw is None else raw

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def iter_content(self, _):
        for start in range(0, len(self.raw), 13):
            yield self.raw[start:start + 13]


class Session:
    def __init__(self, responses):
        self.responses, self.calls, self.headers, self.trust_env = iter(responses), [], {}, True

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def request(self, method, url, **options):
        self.calls.append((method, url, options))
        result = next(self.responses)
        if isinstance(result, Exception):
            raise result
        return result

    def post(self, url, **options):
        return self.request('POST', url, **options)


class DesktopConversationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)

    def tearDown(self):
        self.directory.cleanup()

    def client(self, responses):
        session = Session(responses)
        return Client(self.root, IDENTITY, session_factory=lambda: session), session

    def test_multipart_history_checks_utf8_hash_and_replaces_newer_revision(self):
        transcript = Transcript()
        transcript.add(message('old'))
        whole = 'Hello 🚀 world'
        transcript.add(message('Hello ', revision=2, parts=2, whole=whole))
        with self.assertRaisesRegex(ClientError, 'incomplete'):
            transcript.messages()
        transcript.add(message('🚀 world', revision=2, part=1, parts=2, whole=whole))
        transcript.add(message('stale', revision=1))
        self.assertEqual(whole, transcript.messages()[0]['content'])
        self.assertEqual('message-one', transcript.messages()[0]['message_id'])
        self.assertEqual(len(whole.encode()), transcript.size)

    def test_message_integrity_failure_is_not_presented_as_a_saved_answer(self):
        transcript = Transcript()
        transcript.add(message('wrong', whole='right'))
        with self.assertRaisesRegex(ClientError, 'integrity'):
            transcript.messages()

    def test_imported_history_uses_publication_order_instead_of_lexical_turn_ids(self):
        transcript = Transcript()
        for index in (10, 2, 1):
            transcript.add(dict(message(str(index), ident='message-' + str(index), revision=index + 1), turn_id='command:history:' + str(index)))
        self.assertEqual(['1', '2', '10'], [item['content'] for item in transcript.messages()])

    def test_read_follows_all_cursors_before_returning_logical_messages(self):
        whole = 'first second'
        client, session = self.client([
            Response(dict(IDENTITY, conversation_id='conversation', model='original', revision=2)),
            Response(dict(IDENTITY, messages=[message('first ', parts=2, whole=whole)], has_more=True, next_cursor='continuation', revision=2)),
            Response(dict(IDENTITY, messages=[message('second', part=1, parts=2, whole=whole)], has_more=False, next_cursor='tail', revision=2, history_complete=True)),
        ])
        result = client.read('conversation')
        self.assertEqual(whole, result['messages'][0]['content'])
        self.assertTrue(result['conversation']['history_complete'])
        self.assertEqual('continuation', session.calls[-1][2]['params']['after_cursor'])
        self.assertFalse(session.trust_env)
        self.assertTrue(all(call[2]['allow_redirects'] is False for call in session.calls))

    def test_repeating_cursor_and_foreign_identity_fail_closed(self):
        client, _ = self.client([
            Response(dict(IDENTITY, conversation_id='conversation')),
            Response(dict(IDENTITY, messages=[], has_more=True, next_cursor='repeat')),
            Response(dict(IDENTITY, messages=[], has_more=True, next_cursor='repeat')),
        ])
        with self.assertRaisesRegex(ClientError, 'did not progress'):
            client.read('conversation')
        client, _ = self.client([Response(dict(IDENTITY, principal_id='other', items=[]))])
        with self.assertRaisesRegex(ClientError, 'account changed'):
            client.conversations()

    def test_metadata_uses_account_preconditions_and_independent_revision(self):
        client, session = self.client([Response({'conversation_id': 'c', 'metadata_revision': 4, 'archived': True})])
        client.metadata('c', 3, archived=True)
        method, _, options = session.calls[0]
        self.assertEqual('PATCH', method)
        self.assertEqual({'expected_metadata_revision': 3, 'archived': True}, options['json'])
        self.assertEqual(IDENTITY['principal_id'], options['headers']['X-Gateway-Principal-ID'])
        self.assertEqual(IDENTITY['gateway_id'], options['headers']['X-Gateway-Installation-ID'])
        self.assertNotIn('X-Gateway-Management', options['headers'])

    def test_lost_send_is_not_replayed_and_its_durable_pending_identity_survives(self):
        state = ChatState(self.root)
        state.save(draft='Keep my work', model='model', pending={'command_id': 'stable', 'identity': IDENTITY})
        client, session = self.client([requests.ConnectionError('private transport details')])
        with self.assertRaisesRegex(ClientError, 'will not be sent again'):
            client.send('model', 'Keep my work', 'stable')
        self.assertEqual(1, len(session.calls))
        recovered = ChatState(self.root)
        self.assertEqual('stable', recovered.value['pending']['command_id'])
        self.assertEqual('Keep my work', recovered.value['draft'])

    def test_complete_stream_preserves_original_profile_model_and_history(self):
        events = []
        wire = b'data: {"choices":[{"delta":{"content":"Answer"},"finish_reason":null}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'
        headers = {'X-Gateway-ID': IDENTITY['gateway_id'], 'X-Gateway-Principal-ID': IDENTITY['principal_id'],
                   'X-Gateway-Conversation-ID': 'conv', 'X-Gateway-Command-ID': 'stable', 'X-Gateway-Job-ID': 'job', 'X-Gateway-Detach-Safe': 'true'}
        client, session = self.client([Response(raw=wire, headers=headers)])
        conversation = dict(conversation_id='conv', profile_id='android-profile', model='selected-model', revision=7, history_complete=True, active_job_kind='generation')
        receipt = client.send('selected-model', 'Next', 'stable', conversation=conversation,
                              history=[{'role': 'user', 'content': 'Earlier'}], on_event=lambda *event: events.append(event))
        payload = session.calls[0][2]['json']
        self.assertEqual('android-profile', payload['gateway']['profile_id'])
        self.assertEqual(7, payload['gateway']['expected_revision'])
        self.assertEqual([{'role': 'user', 'content': 'Earlier'}, {'role': 'user', 'content': 'Next'}], payload['messages'])
        self.assertEqual('stable', session.calls[0][2]['headers']['Idempotency-Key'])
        self.assertTrue(receipt['detach_safe'])
        self.assertIn(('delta', 'Answer'), events)

    def test_opening_history_and_checking_receipt_never_submit_a_new_prompt(self):
        client, session = self.client([Response(dict(IDENTITY, command_id='stable', conversation_id='conv'))])
        client.command('stable')
        self.assertEqual(['GET'], [call[0] for call in session.calls])
        self.assertEqual({'include_outcome': 'true'}, session.calls[0][2]['params'])

    def test_wrong_model_or_incomplete_history_cannot_dispatch(self):
        client, session = self.client([])
        for conversation in ({'model': 'other', 'history_complete': True}, {'model': 'selected', 'history_complete': False}):
            with self.assertRaises(ClientError) as caught:
                client.send('selected', 'prompt', 'command', conversation=conversation)
            self.assertTrue(caught.exception.not_dispatched)
        self.assertEqual([], session.calls)

    def test_changed_endpoint_requires_reconnect_before_mutation(self):
        client, session = self.client([])
        atomic_json(self.root / 'settings.json', dict(DEFAULTS, port=8091))
        with self.assertRaisesRegex(ClientError, 'settings changed'):
            client.bookmark('conversation', 'message')
        self.assertEqual([], session.calls)

    def test_shared_network_history_is_never_labeled_private(self):
        self.assertIn('devices using this Gateway can view', privacy_text({'identity_scope': 'shared-network'}))
        self.assertNotIn('private', privacy_text({'identity_scope': 'shared-network'}).lower())

    def test_unreadable_draft_is_preserved_instead_of_silently_replaced(self):
        path = self.root / 'data/desktop-chat-state.json'
        path.parent.mkdir()
        path.write_text('{broken')
        state = ChatState(self.root)
        with self.assertRaises(ValueError):
            state.save(draft='replacement')
        self.assertEqual('{broken', path.read_text())

    def test_explicit_recovery_retains_original_bytes_and_resumes_draft_saves(self):
        path = self.root / 'data/desktop-chat-state.json'
        path.parent.mkdir()
        path.write_text('{broken')
        state = ChatState(self.root)
        backup = state.recover()
        self.assertEqual('{broken', (path.parent / backup).read_text())
        self.assertIsNone(state.error)
        state.save(draft='New work')
        self.assertEqual('New work', ChatState(self.root).value['draft'])

    def test_recovery_keeps_readable_pending_identity_and_bounds_backups_without_deletion(self):
        path = self.root / 'data/desktop-chat-state.json'
        pending = {'command_id': 'prior-send', 'identity': IDENTITY}
        atomic_json(path, {'schema': 1, 'draft': None, 'model': '', 'pending': pending})
        state = ChatState(self.root)
        state.recover()
        self.assertEqual(pending, state.value['pending'])
        for index in range(2):
            (path.parent / f'desktop-chat-recovery-{index}.json').write_text('preserved')
        path.write_text('{broken-again')
        state = ChatState(self.root)
        with self.assertRaisesRegex(ValueError, 'Three draft recovery'):
            state.recover()
        self.assertEqual('{broken-again', path.read_text())

    def test_delayed_draft_write_cannot_replace_newer_text_or_restore_a_cleared_draft(self):
        state = ChatState(self.root)
        old = state.reserve_draft()
        latest = state.reserve_draft()
        state.save(draft='Latest edit', model='model', draft_version=latest)
        state.save(draft='Old edit', model='old-model', draft_version=old)
        self.assertEqual('Latest edit', ChatState(self.root).value['draft'])
        cleared = state.reserve_draft()
        state.save(draft='', draft_version=cleared)
        state.save(draft='Latest edit', draft_version=latest)
        self.assertEqual('', ChatState(self.root).value['draft'])

    def test_later_edit_survives_command_settlement_even_if_its_write_wins_the_race(self):
        state = ChatState(self.root)
        state.save(draft='Sent prompt', pending={'command_id': 'command', 'identity': IDENTITY})
        settlement = state.reserve_draft()
        newer = state.reserve_draft()
        state.save(draft='Next prompt', draft_version=newer)
        state.save(draft='', pending=None, draft_version=settlement)
        restored = ChatState(self.root)
        self.assertEqual('Next prompt', restored.value['draft'])
        self.assertIsNone(restored.value['pending'])

    def test_invalid_pending_identity_requires_explicit_recovery(self):
        path = self.root / 'data/desktop-chat-state.json'
        original = {'schema': 1, 'draft': 'Kept', 'model': '', 'pending': {'command_id': 'unknown'}}
        atomic_json(path, original)
        state = ChatState(self.root)
        self.assertIsNotNone(state.error)
        with self.assertRaises(ValueError):
            state.save(pending=None)
        backup = state.recover()
        self.assertEqual(original, json.loads((path.parent / backup).read_text()))

    def settlement_panel(self, draft):
        from desktop.chat_panel import ChatPanel
        state = ChatState(self.root)
        state.save(draft=draft, pending={'command_id': 'command', 'identity': IDENTITY,
                   'draft_sha256': hashlib.sha256(b'Sent prompt').hexdigest()})
        composer = Mock()
        composer.get.return_value = draft
        actions = []
        client = Mock()
        panel = SimpleNamespace(state=state, sending=False, status=Mock(), composer=composer,
            model=Mock(get=lambda: 'model'), draft_timer=None, draft_version=0,
            client=lambda: client, launch=lambda name, action, **options: actions.append((name, action)), after_cancel=Mock())
        return ChatPanel, panel, actions, client

    def test_reconciliation_preserves_revised_prompt_and_reads_without_resending(self):
        panel_type, panel, actions, client = self.settlement_panel('Revised prompt')
        panel_type.settle_send(panel, {'command_id': 'command', 'conversation_id': 'saved'})
        panel.composer.delete.assert_not_called()
        self.assertEqual('opened', actions[0][0])
        actions[0][1]()
        self.assertEqual('Revised prompt', ChatState(self.root).value['draft'])
        self.assertIsNone(ChatState(self.root).value['pending'])
        client.read.assert_called_once_with('saved')
        client.send.assert_not_called()

    def test_reconciliation_only_clears_the_matching_original_prompt_and_command(self):
        panel_type, panel, actions, _ = self.settlement_panel('Sent prompt')
        panel_type.settle_send(panel, {'command_id': 'foreign', 'conversation_id': 'saved'})
        self.assertEqual([], actions)
        self.assertIsNotNone(panel.state.value['pending'])
        panel_type.settle_send(panel, {'command_id': 'command', 'conversation_id': 'saved'})
        actions[0][1]()
        panel.composer.delete.assert_called_once_with('1.0', 'end')
        self.assertEqual('', ChatState(self.root).value['draft'])

    def test_known_rejection_clears_pending_without_erasing_draft_or_resending(self):
        panel_type, panel, actions, client = self.settlement_panel('Corrected prompt')
        panel_type.settle_send(panel, {'command_id': 'command', 'admission_state': 'rejected',
                                     'dispatch_state': 'not_dispatched'})
        self.assertEqual('rejected-cleared', actions[0][0])
        actions[0][1]()
        restored = ChatState(self.root)
        self.assertIsNone(restored.value['pending'])
        self.assertEqual('Corrected prompt', restored.value['draft'])
        panel.composer.delete.assert_not_called()
        client.read.assert_not_called()
        client.send.assert_not_called()

    def test_new_prompt_cannot_stop_the_previous_conversations_job(self):
        from desktop.chat_panel import ChatPanel
        panel = SimpleNamespace(sending=False, busy=False, epoch=1, active_job_id='old-active-job',
            poll_timer='old-timer', after_cancel=Mock(), title=Mock(), binding=Mock(), render_text=Mock(),
            controls=Mock(), composer=Mock())
        ChatPanel.new_chat(panel)
        self.assertIsNone(panel.active_job_id)
        self.assertIsNone(panel.poll_timer)
        panel.after_cancel.assert_called_once_with('old-timer')

    def test_close_cancels_ui_callbacks_and_persists_draft_without_touching_pending_send(self):
        from desktop.chat_panel import ChatPanel
        state = ChatState(self.root)
        pending = {'command_id': 'original', 'identity': IDENTITY}
        state.save(draft='Earlier draft', pending=pending)
        panel = SimpleNamespace(closed=False, sending=False, state=state, draft_timer='draft-callback',
            poll_timer='conversation-callback', event_timer='event-callback', after_cancel=Mock(),
            composer=Mock(get=lambda *_: 'Latest draft'), model=Mock(get=lambda: 'model'))
        ChatPanel.close(panel)
        self.assertTrue(panel.closed)
        self.assertEqual({'draft-callback', 'conversation-callback', 'event-callback'},
                         {call.args[0] for call in panel.after_cancel.call_args_list})
        restored = ChatState(self.root)
        self.assertEqual('Latest draft', restored.value['draft'])
        self.assertEqual(pending, restored.value['pending'])
        ChatPanel.close(panel)
        self.assertEqual(3, panel.after_cancel.call_count)

    def test_lifecycle_watcher_ignores_child_destroy_and_cancels_its_own_callback(self):
        from desktop.window_lifecycle import watch
        scheduled, handlers = {}, []
        def after(_delay, callback):
            ident = 'watch-' + str(len(scheduled))
            scheduled[ident] = callback
            return ident
        window = SimpleNamespace(after=after, after_cancel=lambda ident: scheduled.pop(ident),
            bind=lambda _event, handler, **_options: handlers.append(handler), winfo_exists=lambda: True)
        close = Mock()
        stop = watch(window, self.root, close)
        callback = next(iter(scheduled.values()))
        handlers[0](SimpleNamespace(widget=object()))
        self.assertEqual(1, len(scheduled))
        handlers[0](SimpleNamespace(widget=window))
        self.assertEqual({}, scheduled)
        callback()
        self.assertEqual({}, scheduled)
        close.assert_not_called()
        stop()


if __name__ == '__main__':
    unittest.main()
