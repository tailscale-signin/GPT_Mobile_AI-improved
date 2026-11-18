"""Exercise the durable timer with real journal files and analysis handoff contracts."""
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

from desktop.analysis_export import NS, export_result
from v14.job_catalog import engineering_summary

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

    def test_waiting_provider_is_never_reported_running_due_to_a_lease(self):
        job = {'id': 'job1', 'state': 'PLAN', 'request': {'repository': 'org/r', 'base': 'main', 'branch': 'work'},
               'revision': 2, 'created_at': 1, 'updated_at': 2, 'provider_wait': {'retry_at': 101}}
        summary = engineering_summary(job, True, 100)
        self.assertEqual(('queued', 'automatic_retry'), (summary['state'], summary['queue_reason']))
        self.assertEqual('running', engineering_summary(job, True, 101)['state'])
        for phase, expected in [('NEEDS_INPUT', 'needs_attention'), ('COMPLETED', 'completed'), ('CANCELLED', 'cancelled')]:
            self.assertEqual(expected, engineering_summary(dict(job, state=phase), True, 100)['state'])

    def test_open_document_exports_preserve_values_without_executable_markup(self):
        cases = [('.odt', 'Example <script>'), ('.ods', [{'Measure': '=2+2', 'Total': 3, 'Flag': True}]),
                 ('.odp', [{'title': 'Review', 'bullets': ['One & two']}])]
        for suffix, data in cases:
            with self.subTest(suffix=suffix), zipfile.ZipFile(io.BytesIO(export_result(json.dumps(data), suffix))) as archive:
                self.assertEqual(archive.namelist()[0], 'mimetype')
                self.assertEqual(archive.getinfo('mimetype').compress_type, zipfile.ZIP_STORED)
                content = ET.fromstring(archive.read('content.xml'))
                paragraphs = [p.text for p in content.findall('.//text:p', NS)]
                if suffix == '.ods':
                    cells = content.findall('.//table:table-cell', NS)[-3:]
                    self.assertEqual([cell.get('{%s}value-type' % NS['office']) for cell in cells], ['string', 'float', 'boolean'])
                    self.assertFalse(any(any('formula' in key for key in cell.attrib) for cell in cells))
                elif suffix == '.odt':
                    self.assertEqual(paragraphs, [data])
                else:
                    self.assertEqual(paragraphs, ['Review', '• One & two'])

    def test_chart_supports_negative_values_and_rejects_non_numeric_series(self):
        root = ET.fromstring(export_result('[["Name","Value"],["<a>",-2],["B",1]]', '.svg'))
        rects = root.findall('{http://www.w3.org/2000/svg}rect')
        self.assertEqual([float(rect.get('width')) for rect in rects], [240, 120])
        self.assertIn('<a>', list(root.itertext()))
        for raw, ext in [('[["A","12"]]', '.svg'), ('{"slides":[{}]}', '.odp'),
                         ('["a"]', '.ods'), ('"a\\u0000"', '.odt'), ('[1e400]', '.ods')]:
            with self.assertRaises(ValueError):
                export_result(raw, ext)
