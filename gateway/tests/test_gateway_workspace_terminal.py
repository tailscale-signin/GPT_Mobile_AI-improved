import os
from itertools import product
from pathlib import Path
import sys
import tarfile
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from v14 import engineering_validation, workspace_terminal
from v14.workspace_process import Processes


class StagingCancellationTests(unittest.TestCase):
    def test_cancelled_or_expired_staging_removes_only_its_container_and_preserves_failure(self):
        for phase in ('stage', 'inspect', 'ready'):
            for stopped, cleanup in product(('cancelled', 'expired'), ('removed', 'unavailable', 'changed_id', 'changed_label', 'deadline')):
                with self.subTest(phase=phase, stopped=stopped, cleanup=cleanup):
                    cancel = threading.Event()
                    clock = [1000]
                    calls = []
                    container = 'a' * 64
                    observed_identity = []
                    expected = InterruptedError if stopped == 'cancelled' else TimeoutError
                    job = {'id': 'job', 'principal': 'paired:fixture', 'deadline': 1100,
                           'request': {'repository': 'owner/repo'}, 'data': {'head_sha': 'b' * 40}}
                    backend = SimpleNamespace(cancellation=lambda job: cancel, token=lambda: '', root=lambda job: '',
                                              api=lambda *args, **kwargs: {'tree': {'sha': 'c' * 40}})

                    def materialize(job, mirror, changes, root, event):
                        checkout = root / 'checkout'
                        checkout.mkdir()
                        (checkout / 'source.py').write_text('pass', encoding='utf-8')
                        return checkout

                    def stop():
                        if stopped == 'cancelled':
                            cancel.set()
                        else:
                            clock[0] = 1200
                        engineering_validation.check_cancellation()

                    def execute(argv, root, timeout, **kwargs):
                        # Exercise the same cancellation gate as the real process
                        # runner before treating any Docker command as launched.
                        engineering_validation.check_cancellation()
                        calls.append((argv, timeout))
                        if argv[1:3] == ['container', 'inspect']:
                            if not observed_identity:
                                return {'exit_code': 1, 'output_tail': 'No such object'}
                            if argv[-1] != container:
                                if phase == 'inspect':
                                    stop()
                                return {'exit_code': 0, 'output_tail': container + ' ' + observed_identity[0] + ' true'}
                            self.assertEqual(container, argv[-1])
                            self.assertEqual(30, timeout)
                            if cleanup == 'unavailable':
                                return {'exit_code': 1, 'output_tail': 'cleanup unavailable'}
                            if cleanup == 'deadline':
                                clock[0] += 31
                            current_id = 'f' * 64 if cleanup == 'changed_id' else container
                            current_label = 'f' * 64 if cleanup == 'changed_label' else observed_identity[0]
                            return {'exit_code': 0, 'output_tail': current_id + ' ' + current_label + ' true'}
                        if argv[1] == 'run':
                            self.assertIn('--read-only', argv)
                            self.assertIn('--network=none', argv)
                            self.assertIn('--cap-drop=ALL', argv)
                            self.assertFalse(any(arg.startswith('--mount') for arg in argv))
                            observed_identity.append(argv[argv.index('--label') + 1].removeprefix('gpt.workspace='))
                            return {'exit_code': 0, 'output_tail': container}
                        if argv[1:3] == ['exec', '-i']:
                            self.assertEqual(container, argv[3])
                            with tarfile.open(fileobj=kwargs['stdin']) as archive:
                                self.assertEqual(b'pass', archive.extractfile('checkout/source.py').read())
                            if phase == 'stage':
                                stop()
                            return {'exit_code': 0, 'output_tail': ''}
                        if argv[1:3] == ['exec', container]:
                            self.assertEqual(['test', '-f', '/control/ready'], argv[3:])
                            self.assertEqual('ready', phase)
                            stop()
                        self.assertEqual(['docker', 'rm', '-f', container], argv)
                        self.assertEqual(30, timeout)
                        self.assertEqual('removed', cleanup)
                        return {'exit_code': 0, 'output_tail': ''}

                    with patch.object(workspace_terminal, 'catalog', return_value=('d' * 64, [])), \
                            patch.object(workspace_terminal, 'load_policy', return_value=(
                                {'workspace_terminal': True}, None, 'fixture@sha256:' + 'e' * 64, [], [])), \
                            patch.object(workspace_terminal, 'materialize_checkout', side_effect=materialize), \
                            patch('v14.engineering_source.prepare_source'), \
                            patch.object(workspace_terminal, 'run_bounded', side_effect=execute), \
                            patch.object(engineering_validation.time, 'time', side_effect=lambda: clock[0]), \
                            engineering_validation.cancellation_scope(cancel, job['deadline']):
                        with self.assertRaises(expected):
                            workspace_terminal.act(backend, job, {'action': 'terminal_open', 'session_id': 'fixture'})
                        # Cleanup cannot make any subsequent job work admissible.
                        with self.assertRaises(expected):
                            workspace_terminal.docker(['exec', container, 'true'], Path('.'))
                    if cleanup == 'removed':
                        self.assertEqual(['docker', 'rm', '-f', container], calls[-1][0])
                        self.assertEqual(5 + ('stage', 'inspect', 'ready').index(phase), len(calls))
                    else:
                        self.assertEqual(['docker', 'container', 'inspect'], calls[-1][0][:3])
                        self.assertEqual(4 + ('stage', 'inspect', 'ready').index(phase), len(calls))
                    self.assertNotIn('terminal_session', job['data'])

    def test_cancelled_readiness_read_does_not_remove_an_existing_session(self):
        cancel = threading.Event()
        calls = []
        head, policy, container = 'b' * 40, 'd' * 64, 'a' * 64
        identity = workspace_terminal.hashlib.sha256(workspace_terminal.json.dumps([
            'paired:fixture', 'job', head, policy, engineering_validation.patch_identity(head, []), 'fixture'
        ]).encode()).hexdigest()
        saved = {'session_id': 'fixture', 'name': 'gpt-work-' + identity[:48], 'container': container,
                 'head_sha': head, 'policy_sha256': policy, 'identity': identity}
        job = {'id': 'job', 'principal': 'paired:fixture', 'deadline': time.time() + 30,
               'request': {'repository': 'owner/repo'}, 'data': {'head_sha': head, 'terminal_session': saved}}

        def execute(argv, root, timeout, **kwargs):
            engineering_validation.check_cancellation()
            calls.append(argv)
            if argv[1:3] == ['container', 'inspect']:
                return {'exit_code': 0, 'output_tail': container + ' ' + identity + ' true'}
            self.assertEqual(['docker', 'exec', container, 'test', '-f', '/control/ready'], argv)
            cancel.set()
            engineering_validation.check_cancellation()

        with patch.object(workspace_terminal, 'catalog', return_value=(policy, [])), \
                patch.object(workspace_terminal, 'load_policy', return_value=(
                    {'workspace_terminal': True}, None, 'fixture@sha256:' + 'e' * 64, [], [])), \
                patch.object(workspace_terminal, 'run_bounded', side_effect=execute), self.assertRaises(InterruptedError):
            workspace_terminal.act(SimpleNamespace(cancellation=lambda job: cancel), job,
                                   {'action': 'terminal_open', 'session_id': 'fixture'})
        self.assertEqual(2, len(calls))
        self.assertIs(saved, job['data']['terminal_session'])


@unittest.skipIf(os.name == 'nt', 'The process service runs inside a Linux container')
class ProcessTests(unittest.TestCase):
    def setUp(self):
        root = tempfile.TemporaryDirectory()
        self.addCleanup(root.cleanup)
        self.root = Path(root.name)
        self.control = self.root / 'control'
        self.control.mkdir()
        self.checkout = self.root / 'checkout'
        self.checkout.mkdir()
        self.server = Processes(self.checkout, self.control, time.time() + 30)

    def command(self, name, code, **kwargs):
        return self.server.dispatch(dict(action='terminal_start', process_id=name,
                                         argv=[sys.executable, '-c', code], **kwargs))

    def finished(self, name):
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            row = self.server.dispatch({'action': 'terminal_poll', 'process_id': name})
            if row['exit_code'] is not None:
                return row
            time.sleep(.03)
        self.fail('Process did not finish')

    def test_targeted_repair_persists_checkout_and_command_identity(self):
        (self.checkout / 'target.py').write_text('assert 2 + 2 == 5')
        run = 'exec(open("target.py").read())'
        self.command('before', run)
        self.assertNotEqual(0, self.finished('before')['exit_code'])
        fix = 'from pathlib import Path; Path("target.py").write_text("assert 2 + 2 == 4")'
        first = self.command('edit', fix)
        self.assertEqual(0, self.finished('edit')['exit_code'])
        self.assertEqual(first['pid'], self.command('edit', fix)['pid'])
        with self.assertRaises(ValueError):
            self.command('edit', run)
        self.command('after', run)
        self.assertEqual(0, self.finished('after')['exit_code'])

    def test_output_paging_deadlines_and_cancellation(self):
        self.command('text', 'print("z" * 10000)')
        row = self.finished('text')
        offset = row['next_offset']
        while offset < row['output_bytes']:
            result = self.server.dispatch(dict(action='terminal_poll', process_id='text', offset=offset))
            self.assertGreater(result['next_offset'], offset)
            offset = result['next_offset']
        self.assertEqual(10001, offset)
        self.command('wait', 'import time; time.sleep(30)', timeout_seconds=1)
        self.assertEqual('TIMED_OUT', self.finished('wait')['state'])
        self.command('cancel', 'import time; time.sleep(30)')
        self.server.dispatch(dict(action='terminal_cancel', process_id='cancel'))
        self.assertEqual('CANCELLED', self.finished('cancel')['state'])
        with self.assertRaises(ValueError):
            self.command('outside', 'pass', cwd='..')
