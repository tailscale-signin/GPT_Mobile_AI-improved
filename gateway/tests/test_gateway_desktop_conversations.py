import hashlib
import json
import queue
from pathlib import Path
import sys
import tempfile
import threading
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

    def test_desktop_engineering_prepares_exact_route_then_explicitly_submits(self):
        from desktop.conversation_client import profile_id
        model = 'selected-model'
        binding = 'bind_' + 'b' * 32
        client, session = self.client([
            Response(self.binding_receipt(model, binding)),
            Response(self.preflight_receipt(True)),
            Response(dict(IDENTITY, command_id='task', execution_binding_id=binding, conversation_id='conversation',
                          repository='owner/repo', model=model, profile_id=profile_id(model), source_sha='a' * 40))])
        values = {'repository': 'owner/repo', 'branch': 'feature', 'source_sha': 'a' * 40,
                  'provider': 'LLAMA', 'task_scope': 'implement', 'objective': 'Fix validation'}
        payload, preflight = client.prepare_engineering(model, values, 'task')
        self.assertTrue(preflight['ready'])
        self.assertEqual(2, len(session.calls))
        self.assertTrue(session.calls[0][1].endswith('/bindings'))
        self.assertTrue(session.calls[1][1].endswith('/preflight'))
        selection = session.calls[0][2]['json']['selection']
        self.assertEqual(model, selection['requested_model_id'])
        self.assertEqual('a' * 40, payload['source_sha'])
        self.assertEqual('conversation', client.submit_engineering(payload)['conversation_id'])
        self.assertEqual(payload, session.calls[2][2]['json'])

    def test_desktop_engineering_continuation_pins_saved_head_and_binding(self):
        job_id = 'eng_' + 'c' * 32
        binding = 'bind_' + 'a' * 32
        source = 'd' * 40
        stored = dict(IDENTITY, id=job_id, conversation_id='conversation', model='saved-model',
                      repository='owner/repo', base='main', branch='feature', head_sha=source,
                      profile_id='saved-profile', execution_binding_id=binding, state='NEEDS_INPUT')
        client, session = self.client([Response(stored), Response(self.preflight_receipt(True, source=source, objective='Follow up'))])
        conversation = {'conversation_id': 'conversation', 'active_job_id': job_id,
                        'active_job_kind': 'engineering', 'revision': 17}
        payload, _ = client.prepare_engineering('saved-model', {'provider': None, 'objective': 'Follow up'}, 'next-task', conversation)
        self.assertEqual(binding, payload['execution_binding_id'])
        self.assertEqual(source, payload['source_sha'])
        self.assertEqual(17, payload['expected_revision'])
        self.assertEqual('feature', payload['source_branch'])
        self.assertEqual(['GET', 'POST'], [row[0] for row in session.calls])

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

    def engineering_chain(self, depth=2):
        jobs = []
        for index in range(depth + 1):
            jobs.append(dict(IDENTITY, id=f'eng_{index + 1:032x}', repository='owner/repository', base='main',
                branch='repair-' + str(index), revision=index + 1, pr=index + 10, head_sha=f'{index + 1:040x}',
                execution_binding_id='binding-original', conversation_id='conversation' if index == 0 else f'recovery-{index}',
                state='MERGE_READY' if index == depth else 'VERIFY', merge_ready=index == depth,
                recovery_of=jobs[-1]['id'] if jobs else None, recovery_job_id=None))
            if index:
                jobs[index - 1]['recovery_job_id'] = jobs[index]['id']
        conversation = dict(conversation_id='conversation', active_job_id=jobs[0]['id'], active_job_kind='engineering')
        return conversation, jobs

    def test_engineering_review_follows_bounded_recovery_chain_without_mutation(self):
        for depth in (2, 5):
            with self.subTest(depth=depth):
                conversation, jobs = self.engineering_chain(depth)
                client, session = self.client([Response(job) for job in jobs])
                result = client.engineering_review(conversation)
                self.assertEqual(jobs[-1], result)
                self.assertNotEqual(conversation['conversation_id'], result['conversation_id'])
                self.assertEqual(['GET'] * len(jobs), [call[0] for call in session.calls])

    def test_engineering_review_rejects_cycle_depth_and_cross_binding_links(self):
        for fault in ('cycle', 'depth', 'binding', 'parent', 'repository', 'principal'):
            with self.subTest(fault=fault):
                conversation, jobs = self.engineering_chain(6 if fault == 'depth' else 2)
                if fault == 'cycle':
                    jobs[1]['recovery_job_id'] = jobs[0]['id']
                elif fault == 'binding':
                    jobs[1]['execution_binding_id'] = 'another-binding'
                elif fault == 'parent':
                    jobs[2]['recovery_of'] = jobs[0]['id']
                elif fault == 'repository':
                    jobs[1]['repository'] = 'other/repository'
                elif fault == 'principal':
                    jobs[1]['principal_id'] = 'other-principal'
                client, session = self.client([Response(job) for job in jobs])
                with self.assertRaises(ClientError):
                    client.engineering_review(conversation)
                self.assertTrue(all(call[0] == 'GET' for call in session.calls))
                self.assertLessEqual(len(session.calls), 6)

    def test_engineering_approval_refreshes_exact_leaf_and_sends_explicit_action(self):
        conversation, jobs = self.engineering_chain()
        client, session = self.client([*[Response(job) for job in jobs], Response({'accepted': True})])
        self.assertEqual({'accepted': True}, client.approve_engineering(conversation, dict(jobs[-1])))
        method, url, options = session.calls[-1]
        self.assertEqual(['GET'] * len(jobs) + ['POST'], [call[0] for call in session.calls])
        self.assertEqual('POST', method)
        self.assertTrue(url.endswith('/' + jobs[-1]['id'] + '/approve'))
        self.assertEqual({key: jobs[-1][key] for key in ('revision', 'pr', 'head_sha')}, options['json'])
        self.assertEqual('approve-and-merge', options['headers']['X-Gateway-User-Action'])
        self.assertEqual(IDENTITY['gateway_id'], options['headers']['X-Gateway-Installation-ID'])
        self.assertEqual(IDENTITY['principal_id'], options['headers']['X-Gateway-Principal-ID'])

    def test_engineering_approval_rejects_changed_review_binding_without_post(self):
        conversation, jobs = self.engineering_chain(1)
        for field in ('id', 'revision', 'pr', 'head_sha', 'execution_binding_id', 'gateway_id',
                      'principal_id', 'repository', 'base', 'branch'):
            with self.subTest(field=field):
                reviewed = dict(jobs[-1], **{field: -1 if field in {'revision', 'pr'} else 'changed'})
                client, session = self.client([*[Response(job) for job in jobs], Response({'accepted': True})])
                with self.assertRaises(ClientError):
                    client.approve_engineering(conversation, reviewed)
                self.assertTrue(all(call[0] == 'GET' for call in session.calls))

    def engineering_panel(self):
        from desktop.chat_panel import ChatPanel
        conversation, jobs = self.engineering_chain(0)
        session = Session([Response(jobs[0]), Response(jobs[0]), Response({'accepted': True})])
        actions = []
        panel = SimpleNamespace(root=self.root, conversation=conversation, identity=dict(IDENTITY),
            visible=True, closed=False, epoch=1,
            launch=lambda name, action, **options: actions.append((name, action)))
        panel.client = lambda: Client(self.root, panel.identity, session_factory=lambda: session)
        ChatPanel.review_engineering(panel)
        result = actions.pop()[1]()
        return ChatPanel, panel, actions, session, result

    def test_engineering_confirmation_keeps_original_connection_snapshot(self):
        panel_type, panel, actions, session, result = self.engineering_panel()
        def confirm(*_args, **_kwargs):
            atomic_json(self.root / 'settings.json', dict(DEFAULTS, port=8091))
            return True
        with patch('desktop.chat_panel.messagebox.askyesno', side_effect=confirm):
            panel_type.show_engineering_review(panel, *result)
        self.assertEqual(['engineering-approved'], [name for name, _ in actions])
        with self.assertRaisesRegex(ClientError, 'settings changed'):
            actions[0][1]()
        self.assertEqual(['GET'], [call[0] for call in session.calls])

    def test_engineering_confirmation_rechecks_current_view_before_approval(self):
        for changed in ('conversation', 'job', 'identity', 'closed', 'epoch'):
            with self.subTest(changed=changed):
                panel_type, panel, actions, _session, result = self.engineering_panel()
                def confirm(*_args, **_kwargs):
                    if changed == 'conversation':
                        panel.conversation = dict(panel.conversation, conversation_id='other')
                    elif changed == 'job':
                        panel.conversation = dict(panel.conversation, active_job_id='eng_' + 'f' * 32)
                    elif changed == 'identity':
                        panel.identity['principal_id'] = 'other-principal'
                    elif changed == 'closed':
                        panel.closed = True
                    else:
                        panel.epoch += 1
                    return True
                with patch('desktop.chat_panel.messagebox.askyesno', side_effect=confirm):
                    panel_type.show_engineering_review(panel, *result)
                self.assertEqual([], actions)

    def task_values(self):
        return {'repository': 'owner/repo', 'base': 'main', 'branch': 'feature', 'source_branch': 'main',
                'source_sha': 'a' * 40, 'provider': 'LLAMA', 'task_scope': 'implement', 'objective': 'Fix validation'}

    def binding_receipt(self, model='selected-model', binding=None):
        from desktop.configuration import load
        from desktop.conversation_client import profile_id
        from v14.execution_binding import endpoint, fingerprint
        backend = load(self.root)['backend']
        return {'binding': {'binding_id': binding or 'bind_' + 'b' * 32, 'profile_uid': profile_id(model),
            'profile_revision': hashlib.sha256(model.encode()).hexdigest(),
            'provider_connection_id': 'desktop-configured-model',
            'provider_connection_revision': hashlib.sha256(backend.encode()).hexdigest(),
            'provider_kind': 'LLAMA', 'requested_model_id': model, 'generation_parameters': {},
            'privacy_scope': 'desktop-gateway', 'tool_owner': 'GATEWAY', 'routing_policy': 'SELECTED_ONLY',
            'required_capability_policy': sorted(['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET', 'JSON_OBJECT']),
            'configured_endpoint_identity': fingerprint(endpoint(backend)),
            'generation_parameters_digest': fingerprint({})}}

    def preflight_receipt(self, ready, *, source='a' * 40, objective='Fix validation'):
        result = {'ready': ready, 'source_sha': source,
                  'objective_sha256': hashlib.sha256(objective.encode()).hexdigest()}
        if not ready:
            result['acceptance_preparation'] = {'status': 'REVIEW_REQUIRED', 'source_sha': source,
                'executed': False, 'allowed_checks': ['unit'], 'candidates': [
                    {'case_id': 'test_behavior.Behavior.test_answer', 'requirement_ids': ['behavior'],
                     'source_sha': source, 'blob_sha': 'd' * 40, 'path': 'tests/test_behavior.py'}]}
        return result

    def composer_panel(self, *, acceptance=False, submit_error=None):
        from desktop.chat_panel import ChatPanel
        from desktop.conversation_client import profile_id
        atomic_json(self.root / 'settings.json', DEFAULTS)
        command = 'c' * 32
        job = dict(IDENTITY, command_id=command, execution_binding_id='bind_' + 'b' * 32,
                   conversation_id='admitted-conversation', repository='owner/repo', model='selected-model',
                   profile_id=profile_id('selected-model'), source_sha='a' * 40, base='main', source_branch='main')
        responses = [Response(self.binding_receipt()), Response(self.preflight_receipt(not acceptance))]
        if acceptance:
            responses.append(Response(self.preflight_receipt(True)))
        responses.append(submit_error if submit_error else Response(job))
        session = Session(responses)
        state = ChatState(self.root)
        state.save(draft='Unsent Chat draft', pending=None)
        model = SimpleNamespace(value='selected-model')
        model.get = lambda: model.value
        actions, timers = [], []
        panel = SimpleNamespace(root=self.root, state=state, model=model, identity=dict(IDENTITY),
            conversation={'conversation_id': 'prior-chat', 'active_job_kind': 'generation', 'revision': 1},
            visible=True, closed=False, busy=False, sending=False, epoch=1, status=Mock(), controls=Mock(),
            events=queue.Queue(), poll_timer='refresh', event_timer=None)
        def launch(name, action, *, quiet=False, **options):
            if panel.busy and not quiet:
                return False
            if not quiet:
                panel.busy = True
            actions.append((name, action))
            return True
        def after(delay, callback):
            timers.append((delay, callback))
            return 'timer-' + str(len(timers))
        panel.launch, panel.after = launch, after
        panel.refresh_conversation = lambda: ChatPanel.refresh_conversation(panel)
        panel.poll = lambda: ChatPanel.poll(panel)
        panel.client = lambda: Client(self.root, panel.identity, session_factory=lambda: session)
        direct_client = patch('desktop.chat_panel.Client',
                              side_effect=lambda root, identity: Client(root, identity, session_factory=lambda: session))
        direct_client.start()
        self.addCleanup(direct_client.stop)
        return ChatPanel, panel, actions, session, command, timers

    def finish_action(self, panel, actions, name):
        self.assertTrue(actions, name)
        actual, action = actions.pop(0)
        self.assertEqual(name, actual)
        result = action()
        panel.busy = False  # The real poll clears busy before delivering this result.
        return result

    def prepare_composer(self, panel_type, panel, actions, command, *, form=None):
        factory = form or (lambda *_: SimpleNamespace(result=self.task_values()))
        with patch('desktop.engineering_composer.EngineeringForm', side_effect=factory), \
                patch('desktop.chat_panel.uuid.uuid4', return_value=SimpleNamespace(hex=command)):
            panel_type.compose_engineering(panel)
        return self.finish_action(panel, actions, 'engineering-preflight')

    def test_engineering_composer_serializes_modals_and_submits_reviewed_request(self):
        panel_type, panel, actions, session, command, timers = self.composer_panel(acceptance=True)
        def form(*_):
            panel.refresh_conversation()
            return SimpleNamespace(result=self.task_values())
        result = self.prepare_composer(panel_type, panel, actions, command, form=form)
        accepted = {'id': 'behavior', 'paths': ['src/feature.py'], 'checks': ['unit'],
                    'evidence': [{'kind': 'behavior_test', 'name': 'test_behavior.Behavior.test_answer'}]}
        def acceptance(*_):
            panel.refresh_conversation()
            return SimpleNamespace(result=accepted)
        with patch('desktop.engineering_composer.AcceptanceForm', side_effect=acceptance):
            panel_type.show_engineering_preflight(panel, *result)
        result = self.finish_action(panel, actions, 'engineering-preflight')
        self.assertEqual([accepted], result[0]['acceptance'])
        def confirm(*_args, **_kwargs):
            panel.refresh_conversation()
            return True
        with patch('desktop.chat_panel.messagebox.askyesno', side_effect=confirm):
            panel_type.show_engineering_preflight(panel, *result)
        self.assertEqual(3, len(session.calls), 'No job may be submitted before explicit confirmation')
        job = self.finish_action(panel, actions, 'engineering-submitted')
        self.assertEqual('admitted-conversation', job['conversation_id'])
        self.assertEqual(['bindings', 'preflight', 'preflight', 'jobs'], [call[1].rsplit('/', 1)[-1] for call in session.calls])
        submitted = session.calls[-1][2]['json']
        self.assertEqual({key: value for key, value in self.task_values().items() if key != 'provider'},
                         {key: submitted[key] for key in self.task_values() if key != 'provider'})
        self.assertEqual('selected-model', submitted['model'])
        self.assertEqual([accepted], submitted['acceptance'])
        self.assertEqual('bind_' + 'b' * 32, submitted['execution_binding_id'])
        self.assertEqual(command, panel.state.value['pending']['command_id'])
        self.assertEqual('Unsent Chat draft', panel.state.value['draft'])
        self.assertEqual(3, len(timers), 'Each modal must defer its existing refresh callback')

    def change_task_context(self, panel, change):
        if change == 'model':
            panel.model.value = 'another-model'
        elif change == 'connection':
            atomic_json(self.root / 'settings.json', dict(DEFAULTS, port=8091))
        elif change == 'conversation':
            panel.conversation = dict(panel.conversation, conversation_id='other')
        elif change == 'identity':
            panel.identity['principal_id'] = 'other-principal'
        elif change == 'closed':
            panel.closed = True
        else:
            panel.epoch += 1

    def test_engineering_composer_rejects_context_drift_after_form(self):
        for change in ('model', 'connection', 'conversation', 'identity', 'closed', 'epoch'):
            with self.subTest(change=change):
                panel_type, panel, actions, session, command, _ = self.composer_panel()
                def form(*_):
                    self.change_task_context(panel, change)
                    return SimpleNamespace(result=self.task_values())
                with patch('desktop.engineering_composer.EngineeringForm', side_effect=form):
                    panel_type.compose_engineering(panel)
                self.assertEqual([], actions)
                self.assertEqual([], session.calls)

    def test_engineering_composer_rechecks_context_after_acceptance_and_start(self):
        for stage in ('acceptance', 'start'):
            for change in ('model', 'connection', 'conversation', 'identity', 'closed'):
                with self.subTest(stage=stage, change=change):
                    panel_type, panel, actions, session, command, _ = self.composer_panel(acceptance=stage == 'acceptance')
                    result = self.prepare_composer(panel_type, panel, actions, command)
                    def confirm(*_args, **_kwargs):
                        self.change_task_context(panel, change)
                        return SimpleNamespace(result={'id': 'behavior'}) if stage == 'acceptance' else True
                    target = ('desktop.engineering_composer.AcceptanceForm' if stage == 'acceptance'
                              else 'desktop.chat_panel.messagebox.askyesno')
                    with patch(target, side_effect=confirm):
                        panel_type.show_engineering_preflight(panel, *result)
                    self.assertEqual([], actions)
                    self.assertEqual(2, len(session.calls))
                    self.assertIsNone(panel.state.value['pending'])

    def test_engineering_review_modal_defers_refresh_without_losing_approval(self):
        panel_type, panel, actions, _session, result = self.engineering_panel()
        panel.busy = panel.sending = False
        panel.poll_timer, panel.after = 'refresh', Mock(return_value='deferred-refresh')
        panel.refresh_conversation = lambda: panel_type.refresh_conversation(panel)
        def launch(name, action, **_options):
            if panel.busy:
                return False
            panel.busy = True
            actions.append((name, action))
            return True
        panel.launch = launch
        def confirm(*_args, **_kwargs):
            panel.refresh_conversation()
            return True
        with patch('desktop.chat_panel.messagebox.askyesno', side_effect=confirm):
            panel_type.show_engineering_review(panel, *result)
        self.assertEqual(['engineering-approved'], [name for name, _ in actions])
        self.assertEqual({'accepted': True}, actions[0][1]())
        panel.after.assert_called_once_with(2000, panel.refresh_conversation)

    def test_engineering_binding_receipt_matches_complete_requested_selection(self):
        for field in ('provider_kind', 'provider_connection_revision', 'profile_revision',
                      'configured_endpoint_identity', 'routing_policy', 'generation_parameters', 'required_capability_policy'):
            with self.subTest(field=field):
                binding = self.binding_receipt()
                binding['binding'][field] = 'changed'
                client, session = self.client([Response(binding), Response(self.preflight_receipt(True))])
                with self.assertRaises(ClientError) as caught:
                    client.prepare_engineering('selected-model', self.task_values(), 'command')
                self.assertTrue(caught.exception.not_dispatched)
                self.assertEqual(1, len(session.calls))

    def test_engineering_receipts_remain_bound_to_source_objective_and_admission(self):
        from desktop.conversation_client import profile_id
        payload = dict(self.task_values(), model='selected-model', command_id='command',
                       execution_binding_id='bind_' + 'b' * 32, profile_id=profile_id('selected-model'))
        payload.pop('provider')
        for field in ('source_sha', 'objective_sha256'):
            with self.subTest(preflight=field):
                reply = dict(self.preflight_receipt(True), **{field: 'changed'})
                client, session = self.client([Response(reply)])
                with self.assertRaises(ClientError) as caught:
                    client.engineering_preflight(payload)
                self.assertTrue(caught.exception.not_dispatched)
                self.assertEqual(1, len(session.calls))
        for field in ('repository', 'profile_id', 'model', 'source_sha'):
            with self.subTest(admission=field):
                reply = dict(payload, **IDENTITY, conversation_id='admitted')
                reply[field] = 'changed'
                client, session = self.client([Response(reply)])
                with self.assertRaises(ClientError) as caught:
                    client.submit_engineering(payload)
                self.assertFalse(caught.exception.not_dispatched, 'A mismatched response cannot prove non-admission')
                self.assertEqual(1, len(session.calls))

    def test_engineering_nondispatch_clears_only_matching_pending(self):
        from desktop.chat_panel import ChatPanel
        for outcome in ('known', 'unknown', 'other-command', 'other-identity'):
            with self.subTest(outcome=outcome):
                _kind, panel, actions, _session, command, _ = self.composer_panel()
                pending = {'command_id': command, 'identity': dict(IDENTITY)}
                if outcome == 'other-command':
                    pending['command_id'] = 'other'
                if outcome == 'other-identity':
                    pending['identity']['principal_id'] = 'other'
                panel.state.save(pending=pending)
                error = ClientError('Controlled dispatch result', not_dispatched=outcome != 'unknown')
                error.command_id, error.command_identity = command, dict(IDENTITY)
                panel.events.put(('error', ('engineering-submitted', error, False), panel.epoch))
                ChatPanel.poll(panel)
                for _name, action in actions:
                    action()
                self.assertEqual(None if outcome == 'known' else pending, panel.state.value['pending'])
                self.assertEqual('Unsent Chat draft', panel.state.value['draft'])

    def test_engineering_dispatch_retains_unknown_and_settles_definite_nondispatch(self):
        for outcome in ('configuration-race', 'unknown-transport'):
            with self.subTest(outcome=outcome):
                transport = requests.Timeout('Controlled lost response') if outcome == 'unknown-transport' else None
                panel_type, panel, actions, session, command, _ = self.composer_panel(submit_error=transport)
                result = self.prepare_composer(panel_type, panel, actions, command)
                with patch('desktop.chat_panel.messagebox.askyesno', return_value=True):
                    panel_type.show_engineering_preflight(panel, *result)
                actual, dispatch = actions.pop(0)
                self.assertEqual('engineering-submitted', actual)
                save = panel.state.save
                def racing_save(**values):
                    save(**values)
                    if outcome == 'configuration-race':
                        atomic_json(self.root / 'settings.json', dict(DEFAULTS, port=8091))
                with patch.object(panel.state, 'save', side_effect=racing_save), self.assertRaises(ClientError) as caught:
                    dispatch()
                self.assertEqual(command, panel.state.value['pending']['command_id'])
                panel.events.put(('error', ('engineering-submitted', caught.exception, False), panel.epoch))
                panel_type.poll(panel)
                for _name, action in actions:
                    action()
                self.assertEqual(outcome == 'configuration-race', panel.state.value['pending'] is None)
                self.assertEqual('Unsent Chat draft', panel.state.value['draft'])
                self.assertEqual(2 if outcome == 'configuration-race' else 3, len(session.calls))

    def test_engineering_dialog_cancellation_never_dispatches(self):
        for stage in ('form', 'acceptance', 'start'):
            with self.subTest(stage=stage):
                panel_type, panel, actions, session, command, _ = self.composer_panel(acceptance=stage == 'acceptance')
                if stage == 'form':
                    with patch('desktop.engineering_composer.EngineeringForm', return_value=SimpleNamespace(result=None)):
                        panel_type.compose_engineering(panel)
                else:
                    result = self.prepare_composer(panel_type, panel, actions, command)
                    target = ('desktop.engineering_composer.AcceptanceForm' if stage == 'acceptance'
                              else 'desktop.chat_panel.messagebox.askyesno')
                    with patch(target, return_value=SimpleNamespace(result=None) if stage == 'acceptance' else False):
                        panel_type.show_engineering_preflight(panel, *result)
                self.assertEqual([], actions)
                self.assertIsNone(panel.state.value['pending'])
                self.assertEqual(0 if stage == 'form' else 2, len(session.calls))


    def test_delayed_rejection_cannot_erase_reconciled_successor_command(self):
        for origin in ('engineering-submitted', 'sent', 'reconciled'):
            for replacement in ('command', 'principal', 'gateway'):
                with self.subTest(origin=origin, replacement=replacement):
                    panel_type, panel, actions, session, command, _ = self.composer_panel()
                    original = {'command_id': command, 'identity': dict(IDENTITY)}
                    panel.state.save(pending=original)
                    rejected = dict(IDENTITY, command_id=command, admission_state='rejected',
                                    dispatch_state='not_dispatched')
                    if origin == 'reconciled':
                        panel_type.settle_send(panel, rejected)
                    else:
                        error = ClientError('Rejected before dispatch', not_dispatched=True)
                        error.command_id, error.command_identity = command, dict(IDENTITY)
                        panel.events.put(('error', (origin, error, False), panel.epoch))
                        panel_type.poll(panel)
                    name, first_retirement = actions.pop(0)
                    self.assertEqual('rejected-cleared', name)
                    self.assertFalse(panel.busy, 'Quiet retirement can overlap a later Check send')
                    at_lock, release = threading.Event(), threading.Event()
                    actual_lock = panel.state.lock
                    failures = []

                    class DelayedLock:
                        def __enter__(self):
                            if threading.current_thread() is delayed:
                                at_lock.set()
                                if not release.wait(5):
                                    raise AssertionError('Retirement was not released')
                            return actual_lock.__enter__()

                        def __exit__(self, *args):
                            return actual_lock.__exit__(*args)

                    def retire():
                        try:
                            first_retirement()
                        except BaseException as error:
                            failures.append(error)

                    panel.state.lock = DelayedLock()
                    delayed = threading.Thread(target=retire, name='delayed-rejection')
                    delayed.start()
                    try:
                        self.assertTrue(at_lock.wait(5), 'The first retirement did not reach durable state')
                        session.responses = iter([Response(rejected)])
                        panel_type.reconcile(panel)
                        receipt = self.finish_action(panel, actions, 'reconciled')
                        panel_type.settle_send(panel, receipt)
                        self.finish_action(panel, actions, 'rejected-cleared')
                        self.assertIsNone(ChatState(self.root).value['pending'])
                        successor = {'command_id': 'next-command' if replacement == 'command' else command,
                                     'identity': dict(IDENTITY)}
                        if replacement != 'command':
                            successor['identity'][replacement + '_id'] = 'next-' + replacement
                        panel.state.save(pending=successor, draft='Newer unsent draft', model='next-model')
                    finally:
                        release.set()
                        delayed.join(5)
                    self.assertFalse(delayed.is_alive(), 'The retirement worker did not settle')
                    self.assertEqual([], failures)
                    restored = ChatState(self.root)
                    self.assertEqual(successor, restored.value['pending'])
                    self.assertEqual(successor, panel.state.value['pending'])
                    self.assertEqual('Newer unsent draft', restored.value['draft'])
                    self.assertEqual('next-model', restored.value['model'])
                    self.assertEqual(['GET'], [call[0] for call in session.calls])

    def test_chat_send_rejection_retains_its_origin_and_unknown_outcome(self):
        for outcome in ('known', 'unknown'):
            with self.subTest(outcome=outcome):
                panel_type, panel, actions, session, command, _ = self.composer_panel()
                panel.composer = Mock(get=lambda *_: 'Chat prompt to preserve')
                panel.transcript, panel.draft_timer, panel.draft_version = [], None, 0
                panel.conversation = {'model': 'different-model', 'history_complete': True} if outcome == 'known' else None
                session.responses = iter([requests.ConnectionError('Controlled lost response')])
                with patch('desktop.chat_panel.uuid.uuid4', return_value=SimpleNamespace(hex=command)):
                    panel_type.send(panel)
                name, send = actions.pop(0)
                self.assertEqual('sent', name)
                with self.assertRaises(ClientError) as caught:
                    send()
                self.assertEqual(command, getattr(caught.exception, 'command_id', None))
                self.assertEqual(IDENTITY, getattr(caught.exception, 'command_identity', None))
                self.assertEqual(outcome == 'known', caught.exception.not_dispatched)
                panel.events.put(('error', ('sent', caught.exception, False), panel.epoch))
                panel_type.poll(panel)
                for name, action in actions:
                    self.assertEqual('rejected-cleared', name)
                    action()
                restored = ChatState(self.root)
                self.assertEqual(outcome == 'known', restored.value['pending'] is None)
                if outcome == 'unknown':
                    self.assertEqual(command, restored.value['pending']['command_id'])
                self.assertEqual('Chat prompt to preserve', restored.value['draft'])
                self.assertEqual(0 if outcome == 'known' else 1, len(session.calls))


if __name__ == '__main__':
    unittest.main()
