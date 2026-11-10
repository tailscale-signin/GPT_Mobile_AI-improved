"""Cross-platform metadata contract and durable support receipt regressions."""
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.diagnostic_events import DiagnosticSessionJournal, event_identity, sanitize_event
from v14.diagnostic_schema import ENUM_ATTRIBUTES, NUMBER_ATTRIBUTES, SCHEMA_DIGEST
from v14.debug_projection import ROUTES, route_kind
from v14.diagnostic_support import terminal_receipt, engineering_inventory


class GatewaySessionContractTests(unittest.TestCase):
    def test_every_approved_field_survives_ingestion_restart_and_export(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.db'
            journal = DiagnosticSessionJournal(path, now=lambda: 1800000000)
            expected = [{name: value} for name, values in ENUM_ATTRIBUTES.items() for value in sorted(values)]
            expected += [{name: 3} for name in sorted(NUMBER_ATTRIBUTES)]
            rows = [dict(schema_version=2, origin_device_pseudonym='a'*32, origin_boot_id='b'*32,
                         origin_sequence=index, event_id=event_identity('a'*32, 'b'*32, index),
                         occurred_at_utc=1800000000000, bounded_attributes=attrs)
                    for index, attrs in enumerate(expected, 1)]
            for index in range(0, len(rows), 64):
                journal.ingest('owner', {'schema_version': 2, 'session_id': 'c'*32,
                    'capture_started_at_utc': 1800000000000, 'events': rows[index:index+64], 'gaps': []})
            journal.close()
            journal = DiagnosticSessionJournal(path, now=lambda: 1800000000)
            try:
                with zipfile.ZipFile(io.BytesIO(journal.export('owner', 'c'*32))) as archive:
                    exported = [json.loads(line) for line in archive.read('events.jsonl').splitlines()]
                    manifest = json.loads(archive.read('manifest.json'))
                self.assertEqual(expected, [row['bounded_attributes'] for row in exported])
                self.assertEqual(SCHEMA_DIGEST, manifest['schema_digest'])
                self.assertTrue(all(sanitize_event(row)['bounded_attributes'] == attrs for row, attrs in zip(exported, expected)))
            finally:
                journal.close()

    def test_routes_and_schema_are_the_reviewed_shared_contract(self):
        contract = Path(__file__).resolve().parents[2] / 'docs/contracts/gateway-diagnostic-schema-v2.json'
        self.assertEqual(hashlib.sha256(contract.read_bytes()).hexdigest(), SCHEMA_DIGEST)
        self.assertEqual(ROUTES, ENUM_ATTRIBUTES['route'])
        for path, route in [('/v1/gateway/capabilities', 'capabilities'), ('/gateway/identity','identity'),
                            ('/gateway/job-catalog','job_catalog'), ('/gateway/conversations/id/messages','messages'),
                            ('/gateway/commands/id','commands'), ('/gateway/engineering/jobs/id','engineering')]:
            self.assertEqual(route, route_kind(path))

    def test_success_requires_acceptance_and_counts_remain_explicit(self):
        job = {'id': 'eng_'+'a'*32, 'revision': 4, 'data': {}}
        missing = terminal_receipt(job, 'COMPLETED', {'passed': False, 'requirements': [{'status':'BEHAVIOR_VERIFIED'}, {'status':'UNAVAILABLE'}]})
        self.assertEqual('EVIDENCE_MISSING', missing['status'])
        self.assertFalse(missing['acceptance_verified'])
        self.assertEqual(1, missing['remaining_criteria'])
        passed = terminal_receipt(job, 'COMPLETED', {'passed': True, 'requirements': [{'status':'BEHAVIOR_VERIFIED'}]})
        self.assertEqual('COMPLETED', passed['status'])
        self.assertTrue(passed['acceptance_verified'])
        self.assertEqual('BLOCKED', terminal_receipt(job, 'NEEDS_INPUT', {})['status'])
        self.assertEqual('CANCELLED', terminal_receipt(job, 'CANCELLED', {})['status'])
        job['state'] = 'CI'
        job['data']['last_completed_stage'] = 'open_pr'
        job['data']['blocked_reason'] = {'code': 'ENGINEERING_AUTHENTICATION_REQUIRED', 'retryable': False, 'private': 'not-exported'}
        receipt = terminal_receipt(job, 'NEEDS_INPUT', {})
        self.assertEqual('ENGINEERING_AUTHENTICATION_REQUIRED', receipt['reason_code'])
        self.assertEqual('authorize', receipt['next_action'])
        self.assertEqual('ci', receipt['failed_stage'])
        self.assertEqual('open_pr', receipt['last_completed_stage'])
        self.assertNotIn('not-exported', json.dumps(receipt))
        job['data']['blocked_reason'] = {'code': {'malformed': True}}
        self.assertEqual('BLOCKED', terminal_receipt(job, 'NEEDS_INPUT', {})['status'])

    def test_inventory_is_bound_to_owner_capture_and_current_authorization(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.execute('CREATE TABLE engineering_jobs(id,state,revision,data,principal,next_at)')
        for owner, session, job in [('owner','session','eng_'+'a'*32),('other','session','eng_'+'b'*32),('owner','other','eng_'+'c'*32)]:
            db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,?)', (job,'CI',4,json.dumps({'diagnostic_capture':{'session_id':session},'request':{'model':'private-model','profile_id':'private-profile'},'data':{}}),owner,1))
        service = SimpleNamespace(store=SimpleNamespace(tx=lambda fn: fn(db)), authorized=lambda job: True)
        inventory = engineering_inventory(service, 'owner', 'session')
        self.assertEqual(['eng_'+'a'*32], [row['job_id'] for row in inventory['jobs']])
        self.assertFalse(inventory['jobs'][0]['terminal'])
        self.assertEqual(4, inventory['jobs'][0]['checkpoint_revision'])
        self.assertNotIn('private-', json.dumps(inventory))
        service.authorized = lambda job: False
        self.assertEqual('access_denied', engineering_inventory(service, 'owner', 'session')['availability'])
