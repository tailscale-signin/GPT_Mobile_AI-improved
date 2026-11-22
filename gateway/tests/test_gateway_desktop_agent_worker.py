"""The frozen transport child must enter its pipe protocol without desktop setup."""
from contextlib import ExitStack
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import entry
from v14 import agent_http_worker


class AgentHttpEntryTests(unittest.TestCase):
    def test_frozen_worker_dispatches_pipe_request_before_parser_settings_or_ui(self):
        payload = {'backend': 'cursor', 'method': 'GET', 'path': '/v1/models',
                   'headers': {'Authorization': 'fixture credential'}}
        incoming = io.TextIOWrapper(io.BytesIO(json.dumps(payload).encode()), encoding='ascii')
        outgoing = io.TextIOWrapper(io.BytesIO(), encoding='ascii')
        with incoming, outgoing, ExitStack() as stack:
            stack.enter_context(patch.object(sys, 'argv', ['Gateway.exe', 'agent-http-worker']))
            stack.enter_context(patch.object(sys, 'frozen', True, create=True))
            stack.enter_context(patch.object(sys, 'stdin', incoming))
            stack.enter_context(patch.object(sys, 'stdout', outgoing))
            parser = stack.enter_context(patch.object(entry.argparse, 'ArgumentParser', side_effect=AssertionError('Desktop parser entered')))
            desktop = [stack.enter_context(patch.object(entry, name, side_effect=AssertionError('Desktop setup entered')))
                       for name in ('load', 'environment', 'installer_settings', 'read_pointer', 'supervise', 'request')]
            native = stack.enter_context(patch('desktop.native_launch.launch_installed_dashboard', side_effect=AssertionError('Desktop UI entered')))
            run = stack.enter_context(patch.object(agent_http_worker, 'perform', return_value={'model': 'modèle'}))
            self.assertIsNone(entry.main())
            outgoing.flush()
            self.assertEqual({'result': {'model': 'modèle'}}, json.loads(outgoing.buffer.getvalue()))
            self.assertEqual('utf-8', incoming.encoding)
            self.assertEqual('utf-8', outgoing.encoding)
            run.assert_called_once_with(payload)
            parser.assert_not_called()
            native.assert_not_called()
            for call in desktop:
                call.assert_not_called()

    def test_worker_mode_cannot_absorb_desktop_arguments(self):
        with patch.object(sys, 'argv', ['Gateway.exe', 'agent-http-worker', '--root', 'unused']), \
                patch.object(sys, 'stderr', io.StringIO()), patch.object(agent_http_worker, 'main') as worker, \
                patch.object(entry, 'load') as settings, self.assertRaises(SystemExit) as error:
            entry.main()
        self.assertEqual(2, error.exception.code)
        worker.assert_not_called()
        settings.assert_not_called()


if __name__ == '__main__':
    unittest.main()
