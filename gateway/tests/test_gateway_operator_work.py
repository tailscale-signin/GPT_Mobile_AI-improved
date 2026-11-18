"""Exercise the durable timer with real journal files and analysis handoff contracts."""
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from desktop.analysis_panel import analyze, import_data
from v14.operator_schedule import OperatorSchedule


class OperatorWorkTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.clock = 100
        self.open = lambda: OperatorSchedule(self.root, lambda: self.clock)
        self.jobs = self.root / 'jobs.sqlite3'

    def test_restart_and_concurrent_ticks_do_not_repeat_overdue_work(self):
        key = self.open().add('Check job archive', 'Check saved job storage', 1, 1)
        self.clock = 8000
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda _: self.open().tick(self.jobs), range(2)))
        row, = self.open().rows()
        self.assertEqual((key, 160, 10960), (row['id'], row['last_due'], row['due']))
        self.assertTrue(row['result'].startswith('Blocked'))
        self.assertFalse(self.jobs.exists())
        db = sqlite3.connect(self.jobs)
        db.execute('CREATE TABLE gateway_jobs(job_id TEXT PRIMARY KEY)')
        db.close()
        self.clock = row['due']
        self.open().tick(self.jobs)
        self.assertTrue(self.open().rows()[0]['result'].startswith('Passed'))

    def test_one_time_reminder_is_retained_and_removable(self):
        key = self.open().add('Review results', 'Reminder', 1, 0)
        self.clock = 159
        self.open().tick(self.jobs)
        self.assertIsNone(self.open().rows()[0]['result'])
        self.clock = 160
        self.open().tick(self.jobs)
        row, = self.open().rows()
        self.assertEqual((None, 'Reminder due'), (row['due'], row['result']))
        self.open().remove(key)
        self.assertEqual([], self.open().rows())

    def test_csv_is_data_and_code_only_goes_to_the_sandbox(self):
        csv = self.root / 'data.csv'
        csv.write_text('Name,Value\n"A,B",3\n', encoding='utf-8')
        source = import_data(csv)
        code = 'def handle(data):\n    return data[1]'
        with patch('desktop.analysis_panel.execute', return_value={'status': 'COMPLETED', 'outputs': [{'output': ['A,B', '3']}]}) as runner:
            self.assertEqual(['A,B', '3'], json.loads(analyze(self.root, code, source, None)))
            self.assertEqual(({'service.py': code}, [[['Name', 'Value'], ['A,B', '3']]]), runner.call_args.args[:2])
        with self.assertRaises(ValueError):
            analyze(self.root, code, 'x' * 131073, None)
