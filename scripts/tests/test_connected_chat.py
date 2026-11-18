import copy
from pathlib import Path
import sys
import unittest
from types import ModuleType, SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualify_connected_chat import bound_answer, observe, screen_contains


class ConnectedChatTest(unittest.TestCase):
    def setUp(self):
        self.answer = 'A unique short original answer.'
        self.command = {'command_id': 'cmd1', 'job_id': 'job1', 'conversation_id': 'chat1'}
        self.history = {'conversation': {'conversation_id': 'chat1', 'history_complete': True, 'model': 'exact', 'revision': 3},
                        'messages': [{'message_id': 'msg1', 'command_id': 'cmd1', 'role': 'assistant',
                                      'content': self.answer, 'status': 'completed', 'revision': 3}]}
        self.package = 'dev.melo.gptmobile.improved'

    def bind(self, history):
        return bound_answer(self.command, history, 'chat1', 'cmd1', 'exact')

    def test_receipt_has_only_hashed_identifiers(self):
        text, receipt = self.bind(self.history)
        self.assertEqual(self.answer, text)
        for private in ('cmd1', 'job1', 'msg1', self.answer):
            self.assertNotIn(private, str(receipt))

    def test_ambiguous_or_incomplete_history_does_not_qualify(self):
        for field, value in [('model', 'other'), ('conversation_id', 'chat2'), ('history_complete', False)]:
            with self.subTest(field=field):
                history = copy.deepcopy(self.history)
                history['conversation'][field] = value
                with self.assertRaises(ValueError):
                    self.bind(history)
        for field, value in [('command_id', 'cmd2'), ('status', 'streaming'), ('role', 'user')]:
            history = copy.deepcopy(self.history)
            history['messages'][0][field] = value
            with self.assertRaises(ValueError):
                self.bind(history)
        history = copy.deepcopy(self.history)
        history['messages'].append(dict(history['messages'][0], command_id='other'))
        with self.assertRaisesRegex(ValueError, 'DISTINCT'):
            self.bind(history)

    def node(self, package=None, css='android.widget.TextView'):
        return f'<hierarchy><node package="{package or self.package}" class="{css}" text="{self.answer}" /></hierarchy>'

    def test_only_exact_visible_app_text_qualifies(self):
        self.assertTrue(screen_contains(self.node(), self.package, self.answer))
        self.assertFalse(screen_contains(self.node(package='other'), self.package, self.answer))
        self.assertFalse(screen_contains(self.node(css='android.widget.EditText'), self.package, self.answer))
        self.assertFalse(screen_contains(self.node(), self.package, self.answer[1:]))
        self.assertFalse(screen_contains('<!DOCTYPE foo>' + self.node(), self.package, self.answer))

    def test_restart_never_reissues_a_gateway_command(self):
        def adb(cmd, **kwargs):
            self.assertEqual(cmd[:3], ['adb', '-s', 'serial1'])
            args = cmd[3:]
            if args == ['get-state']:
                return 'device'
            if args == ['get-serialno']:
                return 'serial1'
            if args == ['shell', 'ps', '-A']:
                return 'USER PID PPID NAME\nroot 1 0 init'
            if args[0] == 'exec-out':
                return self.node()
            return ''
        app = {'versionCode': 142, 'apkSetSha256': 'a' * 64}
        args = SimpleNamespace(room=3, package=self.package, serial='serial1', gateway_root=Path('.'),
                               command='cmd1', conversation='chat1', model='exact')
        module = ModuleType('desktop.conversation_client')
        module.Client = Mock()
        client = module.Client.return_value
        client.identity.return_value = {'gateway_id': 'g', 'principal_id': 'p'}
        client.read.return_value = self.history
        client.command.return_value = self.command
        with patch.dict(sys.modules, {'desktop.conversation_client': module}), patch('qualify_connected_chat.subprocess.check_output', side_effect=adb) as output, patch('qualify_connected_chat.installed_apks', return_value=app):
            report = {}
            observe(args, report)
        self.assertEqual(report['status'], 'PASSED')
        self.assertEqual([c[0] for c in client.method_calls], ['identity', 'command', 'read', 'command', 'read'])
        self.assertEqual(1, sum('force-stop' in c.args[0] for c in output.call_args_list))
