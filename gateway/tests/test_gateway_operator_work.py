"""Exercise the durable timer with real journal files and analysis handoff contracts."""
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET
import zipfile

from desktop.analysis_export import NS, export_result
from v14.job_catalog import engineering_summary

from desktop.analysis_panel import analyze, import_data, read_analysis, write_analysis
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

    def test_alerts_survive_restarts_and_acknowledgement_only_covers_displayed_occurrences(self):
        key = self.open().add('Review results', 'Reminder', 1, 1)
        for n in range(35):
            self.clock = 160 + n * 3600
            self.open().tick(self.jobs)
        shown = self.open().notices(key)
        self.assertEqual(32, len(shown))
        self.clock += 3600
        self.open().tick(self.jobs)
        self.open().acknowledge(key, shown[0]['due'])
        self.assertEqual([{'due': self.clock, 'message': 'Reminder due'}], self.open().notices(key))
        self.assertEqual(('Waiting', 1), (self.open().rows()[0]['state'], self.open().rows()[0]['unread']))
        self.open().remove(key)
        self.assertEqual([], self.open().notices(key))

    def test_editable_workspace_round_trip_does_not_execute_or_overwrite_on_invalid_input(self):
        target = self.root / 'review.gptanalysis'
        code = 'def handle(data):\n    return data'
        source = '[1, "é"]'
        with patch('desktop.analysis_panel.execute') as sandbox:
            write_analysis(target, code, source)
            self.assertEqual((code, source), read_analysis(target))
            with self.assertRaises(ValueError):
                write_analysis(target, code, '[')
            self.assertEqual((code, source), read_analysis(target))
            for invalid in ('[]', '{"format":"other"}', 'x' * 1048577):
                target.write_text(invalid)
                with self.assertRaises(ValueError):
                    read_analysis(target)
            sandbox.assert_not_called()

    def test_analysis_cannot_move_selections_or_results_between_conversations(self):
        from desktop.chat_panel import ChatPanel
        panel = SimpleNamespace(root=self.root, identity={'gateway_id':'g', 'principal_id':'p'}, conversation={'conversation_id':'a'},
                                epoch=1, busy=False, closed=False, sending=False, composer=Mock(), text=Mock())
        panel.composer.cget.return_value = 'normal'
        panel.composer.get.return_value = ''
        panel.text.get.return_value = 'def handle(data): return data'
        with patch('desktop.analysis_panel.show_analysis') as show:
            ChatPanel.analyze_selection(panel)
        _, _, code, append, select = show.call_args.args
        self.assertEqual(code, select())
        panel.conversation = {'conversation_id':'b'}
        self.assertFalse(append('private result'))
        self.assertEqual('', select())
        panel.conversation = {'conversation_id':'a'}
        self.assertTrue(append('private result'))
        panel.epoch += 1
        self.assertFalse(append('previous draft'))
        self.assertEqual('', select())
        panel.composer.insert.assert_called_once()

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

    def test_publication_watch_deduplicates_after_restart_and_keeps_previous_results_on_failure(self):
        from v14.scheduled_publications import KIND, run_watch
        key = self.open().add('Training papers', KIND, 1, 1, 'virtual training')
        works = [{'title': 'First work', 'doi': '10.1234/abc'}]
        self.clock = 160
        run_watch(self.open(), self.clock, lambda query: list(works))
        self.assertEqual(self.open().publications(key), works)
        self.assertIn('Baseline', self.open().rows()[0]['result'])
        self.clock += 3600
        works.insert(0, {'title': 'Second work', 'doi': '10.1234/def'})
        run_watch(self.open(), self.clock, lambda _: list(works))
        self.assertEqual(self.open().rows()[0]['result'], '1 new publication')
        self.assertIn('10.1234/def', self.open().notices(key)[0]['message'])
        self.clock += 3600
        run_watch(self.open(), self.clock, lambda _: list(works))
        self.assertIn('No new', self.open().rows()[0]['result'])
        self.clock += 3600
        def offline(_):
            raise OSError('offline')
        run_watch(self.open(), self.clock, offline)
        self.assertTrue(self.open().rows()[0]['result'].startswith('Blocked'))
        self.assertEqual(self.open().publications(key), works)
        self.open().remove(key)
        self.assertEqual([], self.open().publications(key))

    def test_only_one_worker_reads_a_claimed_occurrence_and_removal_wins(self):
        from threading import Event
        from v14.scheduled_publications import KIND, run_watch
        key = self.open().add('Watch', KIND, 1, 0, 'public topic')
        self.clock = 160
        entered, resume = Event(), Event()
        def read(_):
            entered.set()
            self.assertTrue(resume.wait(3))
            return []
        with ThreadPoolExecutor(max_workers=1) as pool:
            worker = pool.submit(run_watch, self.open(), 160, read)
            self.assertTrue(entered.wait(3))
            try:
                run_watch(self.open(), 160, lambda _: self.fail('duplicate read'))
                self.open().remove(key)
            finally:
                resume.set()
            worker.result(3)
        self.assertEqual([], self.open().rows())

    def test_publication_parser_keeps_doi_targets_and_uses_only_public_search_words(self):
        from unittest.mock import MagicMock
        from v14.scheduled_publications import literature, source_url
        session, response = MagicMock(), MagicMock()
        session.get.return_value.__enter__.return_value = response
        response.status_code = 200
        response.iter_content.return_value = [json.dumps({'status': 'ok', 'message': {'items': [
            {'title': ['One & two'], 'DOI': '10.1234/HELLO'}, {'title': ['Duplicate'], 'DOI': '10.1234/hello'},
            {'title': ['Ignored'], 'DOI': 'https://other.example/path'}]}}).encode()]
        with patch('v14.scheduled_publications.requests.Session') as factory:
            factory.return_value.__enter__.return_value = session
            self.assertEqual([{'title': 'One & two', 'doi': '10.1234/hello'}], literature('Public search'))
        self.assertEqual(session.get.call_args.args, ('https://api.crossref.org/works',))
        self.assertFalse(session.get.call_args.kwargs['allow_redirects'])
        self.assertEqual(session.get.call_args.kwargs['params']['query.bibliographic'], 'Public search')
        self.assertEqual(source_url('10.1234/data?next'), 'https://doi.org/10.1234/data%3Fnext')

    def test_scheduled_suite_binds_existing_mirror_commit_and_approved_policy(self):
        from threading import Event
        import subprocess
        from v14.scheduled_validation import KIND, run_tests
        from v14.validation_host import executable
        git = executable('git')
        source, mirror = self.root / 'source', self.root / 'mirror.git'
        def cmd(*args):
            return subprocess.check_output([git, *map(str, args)], stderr=subprocess.PIPE, text=True).strip()
        cmd('init', '-q', source)
        cmd('-C', source, '-c', 'user.name=Test', '-c', 'user.email=test@example.test', 'commit', '--allow-empty', '-qm', 'fixture')
        head = cmd('-C', source, 'rev-parse', 'HEAD')
        cmd('clone', '--bare', source, mirror)
        cmd('--git-dir=' + str(mirror), 'remote', 'set-url', 'origin', 'https://github.com/test/fixture.git')
        policy = {'mirror': str(mirror), 'image': 'python@sha256:' + 'b' * 64, 'commands': [['python', '-V']]}
        snapshot = {'enabled': True, 'error': None, 'repositories': {'test/fixture': policy}}
        with patch('v14.scheduled_validation.load_policy_snapshot', return_value=snapshot), patch('v14.scheduled_validation.load_policy', return_value=(policy, mirror, None, policy['commands'], [])), patch('v14.scheduled_validation.validate', return_value={'status': 'PASSED', 'source_sha': head, 'complete_validation': True, 'network': 'disabled', 'commands': [{'exit_code': 0, 'elapsed_ms': 2, 'output_sha256': 'b' * 64}]}) as check:
            key = self.open().add('Fixed suite', KIND, 1, 1, 'test/fixture')
            self.clock = 160
            run_tests(self.open(), Event())
            job, changes, cancel = check.call_args.args
            self.assertEqual(head, job['data']['head_sha'])
            self.assertEqual('test/fixture', job['request']['repository'])
            self.assertEqual([], changes)
            self.assertEqual('Passed · ' + head[:12], self.open().rows()[0]['result'])
            self.assertEqual(self.open().test_history(key)[0][1]['commands'][0]['output_sha256'], 'b' * 64)
            run_tests(self.open(), Event())
            self.assertEqual(check.call_count, 1)
            self.clock += 3600
            policy['commands'] = [['python', '--help']]
            run_tests(self.open(), Event())
            self.assertEqual('Blocked · validation policy changed', self.open().rows()[0]['result'])
            self.assertEqual(check.call_count, 1)

    def test_interrupted_one_off_validation_is_never_dispatched_again(self):
        from contextlib import closing
        from threading import Event
        from v14.scheduled_validation import KIND, run_tests
        with patch('v14.operator_schedule.admission', return_value=('repo/test', 'hash')):
            key = self.open().add('Run tests', KIND, 1, 0, 'repo/test')
        with closing(self.open().connect()) as db, db:
            db.execute('UPDATE repository_test SET token=?,expires=200 WHERE id=?', ('old', key))
            db.execute('UPDATE schedule SET due=NULL,last_due=160 WHERE id=?', (key,))
        self.clock = 3800
        with patch('v14.scheduled_validation.validate') as check:
            run_tests(self.open(), Event())
            check.assert_not_called()
        self.assertTrue(self.open().rows()[0]['result'].startswith('Interrupted'))
        self.assertEqual('Unverified', self.open().rows()[0]['state'])
        self.assertEqual(1, len(self.open().notices(key)))
