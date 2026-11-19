import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

from v14.workspace_process import Processes


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
