"""Upgrade, active-occurrence, replay, draft isolation and diagnostic bundle contracts."""
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.public_activity import PublicActivity
from desktop.workspace_reports import report
from v14.operator_schedule import OperatorSchedule
from v14.scheduled_publications import KIND, run_watch


class DesktopWorkspaces(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.clock = 10000
        self.schedules = OperatorSchedule(self.root, lambda: self.clock)
        self.owner = {'gateway_id': 'desktop', 'principal_id': 'one'}

    def test_existing_database_can_disable_without_dispatch_and_resume_future_slots(self):
        with closing(sqlite3.connect(self.schedules.path)) as db, db:
            db.execute('CREATE TABLE schedule(id TEXT PRIMARY KEY,title TEXT,kind TEXT,due INTEGER,period INTEGER,last_due INTEGER,result TEXT)')
            db.execute("INSERT INTO schedule VALUES('legacy','An existing task','Reminder',10001,3600,NULL,NULL)")
        before = self.schedules.rows()[0]
        self.assertEqual((1, 0), (before['enabled'], before['revision']))
        self.schedules.change('legacy', 0, enabled=False)
        self.clock += 7300
        self.schedules.tick(self.root / 'jobs.db')
        self.assertIsNone(self.schedules.rows()[0]['last_due'])
        with self.assertRaises(ValueError):
            self.schedules.change('legacy', 0, enabled=True)
        resumed = OperatorSchedule(self.root, lambda: self.clock).change('legacy', 1, enabled=True)
        self.assertEqual(20801, resumed['due'])
        self.assertEqual(2, resumed['revision'])

    def test_disabling_a_claimed_occurrence_preserves_its_original_receipt(self):
        key = self.schedules.add('Publications', KIND, 1, 1, 'example')
        self.clock += 61
        def finish_in_flight(query):
            self.assertEqual('example', query)
            self.schedules.change(key, 0, enabled=False)
            return [{'doi': '10.1234/example', 'title': 'Already running'}]
        run_watch(self.schedules, self.clock, finish_in_flight)
        row = self.schedules.rows()[0]
        self.assertEqual(0, row['enabled'])
        self.assertEqual('Baseline recorded · 1 results', row['result'])
        self.clock += 7200
        run_watch(self.schedules, self.clock, lambda query: self.fail('Disabled occurrence was started'))
        self.assertEqual(row['last_due'], self.schedules.rows()[0]['last_due'])

    def test_drafts_are_independent_across_conversations_and_principals(self):
        a = PublicActivity(self.root, self.owner)
        b = PublicActivity(self.root, dict(self.owner, principal_id='two'))
        for thread in ['', 'c1','c2']:
            a.save_draft(thread,'draft '+thread,'model')
        self.assertIsNone(b.draft('c1'))
        a.save_draft('c1','','')
        self.assertIsNone(a.draft('c1'))
        self.assertEqual('draft c2',a.draft('c2')['draft'])
        self.assertEqual('draft ',a.draft('')['draft'])

    def test_replay_is_bounded_scoped_and_idempotent(self):
        a = PublicActivity(self.root, self.owner)
        b = PublicActivity(self.root, dict(self.owner, principal_id='two'))
        a.append('t', 'search', 'tool_start', 'Searching')
        first = a.append('t', 'accepted', 'activity', 'Accepted', conversation='c', job='j')
        repeated = a.append('t', 'accepted', 'activity', 'Accepted', conversation='c', job='j')
        self.assertEqual(first['event_id'], repeated['event_id'])
        self.assertEqual(['Searching', 'Accepted'], [x['public_text'] for x in a.replay('c')['items']])
        self.assertEqual([], a.replay('c', first['sequence'])['items'])
        self.assertEqual([], b.replay('c')['items'])
        with closing(b.connect()) as db, db:
            db.executemany("INSERT INTO activity(gateway,principal,turn,conversation,key,kind,text,observed,sources) "
                           "VALUES('desktop','two','other','other',?,'activity','test',0,'[]')", [(str(i),) for i in range(4096)])
        b.append('other','last','activity','End',conversation='other')
        self.assertTrue(a.replay('c')['gap'])
        self.assertFalse(b.replay('unrelated')['gap'])
        self.assertTrue(b.replay('other')['has_more'])
        self.assertEqual(200, len(b.replay('other')['items']))

    def test_report_filters_use_exact_reviewed_bytes_and_state_missing_sources(self):
        (self.root / 'logs').mkdir()
        now = int(time.time()*1000)
        events = [{'jobDigest':hashlib.sha256(b'my-job').hexdigest(), 'timestampMs':now},
                  {'jobId':'other', 'timestampMs':now}, {'jobId':'my-job', 'timestampMs':now-7200000}]
        with zipfile.ZipFile(self.root / 'logs' / 'gateway-debug.zip','w') as archive:
            archive.writestr('manifest.json', json.dumps({'version':'18.1.0','redaction':'metadata only','files':[]}))
            archive.writestr('gateway-debug.jsonl', ''.join(json.dumps(row)+'\n' for row in events))
        with patch('desktop.workspace_reports.request'):
            preview = report(self.root, 'report-preview', {'hours':1,'job':'my-job'})
        location = self.root / 'data' / 'report-previews' / (preview['sha256']+'.zip')
        self.assertEqual(preview['sha256'], hashlib.sha256(location.read_bytes()).hexdigest())
        self.assertEqual(['diagnostic-events.jsonl'], preview['manifest']['missingSources'])
        with zipfile.ZipFile(location) as archive:
            self.assertEqual(events[0], json.loads(archive.read('gateway-debug.jsonl')))
            self.assertEqual(preview['manifest'], json.loads(archive.read('manifest.json')))
