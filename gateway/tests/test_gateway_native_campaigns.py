"""Adversarial controller tests using synthetic data and injected offline ports only.

These tests do not launch products, contact Gateway, or qualify product outcomes.
"""
from __future__ import annotations

import json
import contextlib
import io
import os
from pathlib import Path
import sqlite3
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import campaign as kit
from benchmarks import campaign_synthetic as synthetic


class ControllerEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='benchkit-adversarial-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.inputs = synthetic.build_inputs(self.base / 'inputs', task_count=1)
        self.session = self.base / 'session'
        kit.initialize(self.inputs['config'], self.inputs['campaign'], self.inputs['tasks'], self.session,
                       self.inputs['observer_key'], synthetic=True)

    def paths_for(self, index):
        return self.base / f'measurements-{index}.json', self.base / f'capture-{index}.raw'

    def import_next(self, answer=' SYNTHETIC answer\r\n ', *, bom=False, **updates):
        pending = kit.next_slot(self.session)
        index = pending['slot']['scheduleIndex']
        row = synthetic.measurement(self.session, index, **updates)
        measurements, capture_path = self.paths_for(index)
        kit.write_json_new(measurements, row)
        if row['state'] == 'DELIVERED':
            raw = synthetic.capture(row['product'], answer, f'SYNTHETIC-session-{index}')
            if bom:
                raw = b'\xef\xbb\xbf' + raw
            kit.write_new(capture_path, raw)
        else:
            capture_path = None
        kit.import_capture(self.session, index, measurements, self.inputs['observer_key'],
                           capture_path, attest_origin=True)
        return index, row, capture_path

    def test_outer_bom_and_inner_utf8_crlf_bytes_preserved_for_every_product(self):
        # The answer is intentionally malformed as a proposal and begins with a
        # real U+FEFF character. Only the *outer* capture BOM may be consumed.
        answer = '\ufeff  malformed proposal café 🔬\r\n\r\n  '
        observed = set()
        for _ in range(len(kit.PRODUCTS)):
            index, row, capture_path = self.import_next(answer, bom=True)
            observed.add(row['product'])
            folder = self.session / 'slots' / f'{index:06d}'
            self.assertEqual(answer.encode('utf-8'), (folder / 'candidate.txt').read_bytes())
            self.assertEqual(capture_path.read_bytes(), (folder / 'capture.raw').read_bytes())
            payload = kit.read_json(folder / 'observation.json')['payload']
            self.assertEqual(kit.sha(answer.encode('utf-8')), payload['artifactDigest'])
            self.assertEqual(kit.sha(capture_path.read_bytes()), payload['captureDigest'])
        self.assertEqual(set(kit.PRODUCTS), observed)

    def test_malformed_container_is_retained_without_minting_a_product_outcome(self):
        row = synthetic.measurement(self.session, 0)
        measurements, capture_path = self.paths_for(0)
        kit.write_json_new(measurements, row)
        bad = b'{"this": "is an interrupted native capture"'
        kit.write_new(capture_path, bad)
        with self.assertRaises((ValueError, KeyError, TypeError)):
            kit.import_capture(self.session, 0, measurements, self.inputs['observer_key'],
                               capture_path, attest_origin=True)
        self.assertFalse((self.session / 'slots/000000').exists())
        rejected = list((self.session / 'rejected').iterdir())
        self.assertEqual(1, len(rejected))
        self.assertEqual(bad, (rejected[0] / 'capture.raw').read_bytes())
        self.assertEqual(measurements.read_bytes(), (rejected[0] / 'measurements.json').read_bytes())
        self.assertEqual('INVALID_IMPORT_NOT_A_PRODUCT_OUTCOME',
                         kit.read_json(rejected[0] / 'rejection.json')['classification'])
        kit.make_report(self.session)
        report = kit.read_json(self.session / 'reports/scoreboard.json')
        self.assertTrue(all(p['observed'] == 0 and p['verifiedRate'] is None for p in report['products'].values()))

    def test_signatures_capture_bytes_and_candidates_cannot_be_replaced(self):
        index, _, _ = self.import_next()
        folder = self.session / 'slots' / f'{index:06d}'
        original = (folder / 'candidate.txt').read_bytes()
        (folder / 'candidate.txt').write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, 'Candidate changed'):
            kit.make_report(self.session)
        (folder / 'candidate.txt').write_bytes(original)
        raw = (folder / 'capture.raw').read_bytes()
        (folder / 'capture.raw').write_bytes(raw + b' ')
        with self.assertRaisesRegex(ValueError, 'capture changed'):
            kit.make_report(self.session)
        (folder / 'capture.raw').write_bytes(raw)
        observed = kit.read_json(folder / 'observation.json')
        observed['payload']['seconds'] += 1
        (folder / 'observation.json').write_bytes(kit.json_bytes(observed))
        with self.assertRaisesRegex(ValueError, 'signature'):
            kit.make_report(self.session)

    def test_controls_and_task_bytes_are_checked_before_report(self):
        controls = self.session / 'controls.json'
        original = controls.read_bytes()
        changed = kit.read_json(controls)
        changed['matchedControls']['hardwareClass'] = 'tampered'
        controls.write_bytes(kit.json_bytes(changed))
        with self.assertRaisesRegex(ValueError, 'controls'):
            kit.make_report(self.session)
        controls.write_bytes(original)
        task = next((self.session / 'tasks').glob('*.json'))
        task.write_bytes(task.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'task bytes'):
            kit.make_report(self.session)

    def test_candidate_packets_cannot_drift_from_the_frozen_public_task(self):
        for packet in (self.session / 'packets').iterdir():
            with self.subTest(packet=packet.name):
                original = packet.read_bytes()
                packet.write_bytes(original + b'\nDIFFERENT TASK INSTRUCTIONS')
                with self.assertRaises(ValueError):
                    kit.next_slot(self.session)
                packet.write_bytes(original)

    def test_unsigned_status_cannot_supply_qualified_metrics(self):
        index, _, _ = self.import_next()
        area = self.session / 'grader' / f'{index:06d}'
        kit.write_json_new(area / 'status.json', {'request_id': 'forged', 'qualified': True,
            'outcomeComplete': True, 'resolved': 1, 'score': 1000})
        kit.make_report(self.session)
        row = kit.read_json(self.session / 'reports/scoreboard.json')['slots'][index]
        self.assertIsNone(row['rawExecutableResolved'])
        self.assertIsNone(row['rubricScoreOutOf1000'])
        self.assertIsNone(row['independentGrade'])

    def test_unknown_cost_and_harness_states_prevent_a_comparative_win(self):
        for i in range(len(kit.PRODUCTS)):
            if i == 0:
                index, product, _ = self.import_next(costUsd=None)
                synthetic.fixture_grade(self.session, index, self.inputs['grader_key'], 'PASS')
                unknown_product = product['product']
            elif i == 1:
                self.import_next(state='HARNESS_FAILURE', runId=kit.digest(['synthetic-dispatch', i]), costUsd=None)
            elif i == 2:
                self.import_next(state='UNSUPPORTED', runId=kit.digest(['synthetic-dispatch', i]), costUsd=None)
            else:
                index, _, _ = self.import_next()
                synthetic.fixture_grade(self.session, index, self.inputs['grader_key'], 'FAIL')
        kit.make_report(self.session)
        report = kit.read_json(self.session / 'reports/scoreboard.json')
        self.assertEqual('INCOMPLETE', report['nativeComparison']['verdict'])
        self.assertEqual({'COST_UNOBSERVED': 1}, report['coverage'][unknown_product])
        self.assertIsNone(report['products'][unknown_product]['verifiedRate'])
        self.assertIsNone(report['githubIntelligencePointsOutOf100'])
        self.assertEqual('NOT_MEASURED', report['comparativeModelQuality'])

    def test_corrupted_audit_blocks_new_observation_before_it_is_committed(self):
        row = synthetic.measurement(self.session, 0)
        measurements, capture_path = self.paths_for(0)
        kit.write_json_new(measurements, row)
        kit.write_new(capture_path, synthetic.capture(row['product'], 'synthetic'))
        audit = self.session / 'audit.jsonl'
        lines = audit.read_text(encoding='utf-8').splitlines()
        first = json.loads(lines[0])
        first['details']['slots'] = -1
        audit.write_text(json.dumps(first) + '\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Audit log integrity'):
            kit.import_capture(self.session, 0, measurements, self.inputs['observer_key'],
                               capture_path, attest_origin=True)
        self.assertFalse((self.session / 'slots/000000').exists())

    def test_duplicate_run_identity_cannot_fill_another_slot(self):
        run_id = kit.digest('synthetic-duplicate-run')
        self.import_next(state='PRODUCT_FAILURE', runId=run_id)
        with self.assertRaisesRegex(ValueError, 'duplicate native trial'):
            self.import_next(state='PRODUCT_FAILURE', runId=run_id)
        self.assertFalse((self.session / 'slots/000001').exists())

    def test_known_failure_is_retained_and_cannot_be_replaced_with_a_retry(self):
        index, row, _ = self.import_next(state='PRODUCT_FAILURE', runId=kit.digest('synthetic-failed-dispatch'))
        measurements, _ = self.paths_for(index)
        with self.assertRaisesRegex(ValueError, 'immutable'):
            kit.import_capture(self.session, index, measurements, self.inputs['observer_key'], attest_origin=True)
        report = kit.read_json(self.session / 'reports/scoreboard.json')
        self.assertEqual({'PRODUCT_FAILURE': 1}, report['coverage'][row['product']])
        self.assertEqual(0, report['products'][row['product']]['verifiedRate'])

    def test_grader_key_substitution_is_rejected(self):
        other = kit.keygen('grader', self.base / 'other-authority')
        replacement = Path(other['publicKey']).read_bytes()
        (self.session / 'public/grader.pem').write_bytes(replacement)
        with self.assertRaisesRegex(ValueError, 'public-key binding'):
            kit.make_report(self.session)

    def test_duplicate_keys_and_nonfinite_values_are_not_valid_measurements(self):
        row = synthetic.measurement(self.session, 0)
        measurements, capture_path = self.paths_for(0)
        kit.write_new(capture_path, synthetic.capture(row['product'], 'synthetic'))
        ordinary = json.dumps(row)
        malformed = ordinary[:-1] + ', "seconds": 0}'
        kit.write_new(measurements, malformed.encode())
        with self.assertRaises(ValueError):
            kit.import_capture(self.session, 0, measurements, self.inputs['observer_key'],
                               capture_path, attest_origin=True)
        self.assertFalse((self.session / 'slots/000000').exists())
        measurements.write_bytes(ordinary.replace('2.5', 'NaN').encode())
        with self.assertRaises(ValueError):
            kit.import_capture(self.session, 0, measurements, self.inputs['observer_key'],
                               capture_path, attest_origin=True)
        self.assertFalse((self.session / 'slots/000000').exists())

    def test_unobserved_interventions_have_no_zero_default(self):
        pending = kit.next_slot(self.session)
        self.assertIsNone(pending['measurementsTemplate']['seconds'])
        self.assertIsNone(pending['measurementsTemplate']['costUsd'])
        self.assertIsNone(pending['measurementsTemplate']['interventions'])

    def test_synthetic_lane_never_reaches_a_gateway_port(self):
        self.import_next()
        calls = []
        with self.assertRaisesRegex(ValueError, 'Synthetic sessions'):
            kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                              bridge_call=lambda *args: calls.append(args))
        self.assertEqual([], calls)

    def test_output_guard_prevents_repo_modification(self):
        fake_checkout = self.base / 'fake-checkout'
        (fake_checkout / '.git').mkdir(parents=True)
        target = fake_checkout / 'reports'
        with self.assertRaisesRegex(ValueError, 'outside every Git checkout'):
            kit.make_report(self.session, target)
        self.assertFalse(target.exists())
        with self.assertRaisesRegex(ValueError, 'outside every Git checkout'):
            kit.write_json_new(fake_checkout / 'measurements.json', {})
        self.assertFalse((fake_checkout / 'measurements.json').exists())

    def test_status_only_recovery_never_prepares_or_submits_missing_trials(self):
        self.import_next()
        calls = []
        # The real-lane gate is bypassed only around an injected Python test port.
        # No session is relabelled as real and no bridge subprocess can execute.
        with patch.object(kit, 'verify_cfg_session', return_value=None):
            result = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      slot_index=0, recovery=True,
                                      bridge_call=lambda *args: calls.append(args))
        self.assertEqual([], calls)
        self.assertEqual('NOT_PREPARED', result['receipts'][0]['status'])
        self.assertFalse((self.session / 'grader/000000/trial.sqlite').exists())

    def test_lost_submission_uses_original_trial_and_only_status_afterward(self):
        index, row, _ = self.import_next()
        calls = []
        request_id = 'extref_' + 'a' * 32
        trial = self.session / 'grader' / f'{index:06d}' / 'trial.sqlite'
        candidate = self.session / 'slots' / f'{index:06d}' / 'candidate.txt'

        def offline_port(cfg, operation, body):
            calls.append(operation)
            if operation == 'prepare':
                saved = {'request': {'request_id': request_id, 'fixture_digest': row['taskDigest'],
                                    'proposal_digest': kit.sha(candidate.read_bytes())}}
                with sqlite3.connect(body['trial']) as db:
                    db.execute('CREATE TABLE trial(id INTEGER PRIMARY KEY, plan TEXT, attempted INTEGER)')
                    db.execute('INSERT INTO trial VALUES(1, ?, 0)', (json.dumps(saved),))
                return {'request_id': request_id}
            if operation == 'submit':
                with sqlite3.connect(body['trial']) as db:
                    db.execute('UPDATE trial SET attempted=1 WHERE id=1')
                raise kit.KitError('SYNTHETIC transport interruption after durable intent')
            if operation == 'status':
                return {'request_id': request_id, 'proposal_digest': kit.sha(candidate.read_bytes()),
                        'submissionState': 'NOT_OBSERVED'}
            self.fail('Unexpected operation on an offline port: ' + operation)

        # This explicitly tests controller recovery decisions, not Gateway's
        # separate submission-journal implementation or any real model outcome.
        with patch.object(kit, 'verify_cfg_session', return_value=None):
            first = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      slot_index=index, bridge_call=offline_port)
            second = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                       slot_index=index, bridge_call=offline_port)
            third = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      slot_index=index, recovery=True, bridge_call=offline_port)
        self.assertEqual(['prepare', 'submit', 'status', 'status'], calls)
        self.assertEqual('UNRESOLVED', first['receipts'][0]['status'])
        self.assertEqual('status', second['receipts'][0]['operation'])
        self.assertEqual('STATUS_ONLY_RECOVERY', third['mode'])
        with sqlite3.connect(trial) as db:
            saved, attempted = db.execute('SELECT plan, attempted FROM trial WHERE id=1').fetchone()
        self.assertEqual(1, attempted)
        self.assertEqual(request_id, json.loads(saved)['request']['request_id'])

    def test_interrupted_trial_preparation_remains_unresolved_and_is_never_recreated(self):
        index, _, _ = self.import_next()
        trial = self.session / 'grader' / f'{index:06d}' / 'trial.sqlite'
        kit.write_new(trial, b'')
        calls = []
        with patch.object(kit, 'verify_cfg_session', return_value=None):
            result = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      slot_index=index, recovery=True,
                                      bridge_call=lambda *args: calls.append(args))
        self.assertEqual([], calls)
        self.assertEqual('UNRESOLVED', result['receipts'][0]['status'])
        self.assertEqual(b'', trial.read_bytes())

    def test_inbox_preserves_committed_slots_imports_consecutively_and_stops_at_gap(self):
        self.import_next('already committed synthetic answer')
        committed = self.session / 'slots/000000'
        original = {p.name: p.read_bytes() for p in committed.iterdir() if p.is_file()}
        inbox = self.base / 'inbox'
        inbox.mkdir()
        # A stale inbox entry must never overwrite an already committed slot.
        kit.write_json_new(inbox / '000000/measurements.json', {'invalid': 'replacement'})

        def put(index):
            row = synthetic.measurement(self.session, index)
            kit.write_json_new(inbox / f'{index:06d}' / 'measurements.json', row)
            kit.write_new(inbox / f'{index:06d}' / 'capture.raw',
                          synthetic.capture(row['product'], f'SYNTHETIC inbox answer {index}',
                                            f'SYNTHETIC-inbox-session-{index}'))

        put(1)
        put(3)
        first = kit.import_inbox(self.session, inbox, self.inputs['observer_key'], attest_origin=True)
        self.assertEqual([1], [row['scheduleIndex'] for row in first['imported']])
        self.assertEqual(2, first['next']['slot']['scheduleIndex'])
        self.assertFalse((self.session / 'slots/000002').exists())
        self.assertFalse((self.session / 'slots/000003').exists())
        self.assertEqual(original, {p.name: p.read_bytes() for p in committed.iterdir() if p.is_file()})
        repeat = kit.import_inbox(self.session, inbox, self.inputs['observer_key'], attest_origin=True)
        self.assertEqual([], repeat['imported'])
        slot_one = (self.session / 'slots/000001/observation.json').read_bytes()
        put(2)
        resumed = kit.import_inbox(self.session, inbox, self.inputs['observer_key'], attest_origin=True)
        self.assertEqual([2, 3], [row['scheduleIndex'] for row in resumed['imported']])
        self.assertEqual('ALL_OBSERVATIONS_PRESENT', resumed['next']['status'])
        self.assertEqual(slot_one, (self.session / 'slots/000001/observation.json').read_bytes())
        self.assertEqual(original, {p.name: p.read_bytes() for p in committed.iterdir() if p.is_file()})

    def test_bounded_recovery_polls_only_readback_and_stops_on_terminal(self):
        calls, waits = [], []
        states = [{'submissionState': 'NOT_OBSERVED'},
                  {'submissionState': 'OBSERVED', 'state': 'RUNNING'},
                  {'submissionState': 'OBSERVED', 'state': 'COMPLETED'}]

        def poll(session, config, grader_key, slot_index, **kwargs):
            self.assertEqual((self.session, self.inputs['config'], self.inputs['grader_key'], 0),
                             (session, config, grader_key, slot_index))
            self.assertEqual({'recovery': True}, kwargs)
            calls.append(kwargs)
            return {'mode': 'STATUS_ONLY_RECOVERY', 'modelRequests': 0,
                    'receipts': [dict(scheduleIndex=0, operation='status', **states[len(calls) - 1])]}

        with contextlib.redirect_stderr(io.StringIO()), patch.object(kit, 'bridge', side_effect=AssertionError('No live bridge allowed')):
            result = kit.recover_campaign(self.session, self.inputs['config'], self.inputs['grader_key'], 0,
                                          polls=5, interval_seconds=0.25, grade_call=poll, sleep=waits.append)
        self.assertEqual(3, result['pollsCompleted'])
        self.assertEqual(3, len(calls))
        self.assertEqual([0.25, 0.25], waits)
        self.assertEqual('COMPLETED', result['receipts'][0]['state'])
        self.assertTrue(all(row['receipts'][0]['operation'] == 'status' for row in result['pollHistory']))

        calls.clear()
        waits.clear()

        def unresolved(*args, **kwargs):
            self.assertEqual({'recovery': True}, kwargs)
            calls.append(kwargs)
            return {'mode': 'STATUS_ONLY_RECOVERY', 'modelRequests': 0,
                    'receipts': [{'scheduleIndex': 0, 'status': 'UNRESOLVED'}]}

        with contextlib.redirect_stderr(io.StringIO()), patch.object(kit, 'bridge', side_effect=AssertionError('No live bridge allowed')):
            bounded = kit.recover_campaign(self.session, self.inputs['config'], self.inputs['grader_key'], 0,
                                           polls=2, interval_seconds=0, grade_call=unresolved, sleep=waits.append)
        self.assertEqual(2, bounded['pollsCompleted'])
        self.assertEqual(2, len(calls))
        self.assertEqual([0], waits)
        self.assertEqual('UNRESOLVED', bounded['receipts'][0]['status'])
        for polls, interval in ((0, 1), (121, 1), (2, -1), (2, 61)):
            with self.subTest(polls=polls, interval=interval), self.assertRaises(ValueError):
                kit.recover_campaign(self.session, self.inputs['config'], self.inputs['grader_key'], 0,
                                     polls=polls, interval_seconds=interval,
                                     grade_call=unresolved, sleep=waits.append)
        self.assertEqual(2, len(calls))

    def test_application_upgrade_retains_original_id_for_readback_without_new_grade(self):
        index, row, _ = self.import_next()
        request_id = 'extref_' + 'b' * 32
        trial = self.session / 'grader' / f'{index:06d}' / 'trial.sqlite'
        candidate = self.session / 'slots' / f'{index:06d}' / 'candidate.txt'
        trial.parent.mkdir(parents=True)
        saved = {'request': {'request_id': request_id, 'fixture_digest': row['taskDigest'],
                            'proposal_digest': kit.sha(candidate.read_bytes())}}
        with sqlite3.connect(trial) as db:
            db.execute('CREATE TABLE trial(id INTEGER PRIMARY KEY, plan TEXT, attempted INTEGER)')
            db.execute('INSERT INTO trial VALUES(1, ?, 1)', (json.dumps(saved),))
        calls = []

        def offline_port(cfg, operation, body):
            calls.append((operation, body))
            self.assertEqual('status', operation)
            return {'request_id': request_id, 'proposal_digest': saved['request']['proposal_digest'],
                    'submissionState': 'OBSERVED', 'state': 'COMPLETED'}

        upgraded = dict(kit.runtime_identity(), runtimeDigest='f' * 64)
        # The fixture retains its synthetic lane. Only the lane check is bypassed
        # around an injected offline port; no real transport is reachable.
        with patch.object(kit, 'verify_cfg_session', return_value=None), \
             patch.object(kit, 'runtime_identity', return_value=upgraded):
            result = kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      index, recovery=True, bridge_call=offline_port)
            with self.assertRaisesRegex(ValueError, 'different installed runtime'):
                kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                  index, bridge_call=offline_port)
        self.assertEqual([('status', {'trial': str(trial)})], calls)
        receipt = result['receipts'][0]
        self.assertEqual('RUNTIME_CHANGED_READBACK_ONLY', receipt['status'])
        self.assertEqual(request_id, receipt['requestId'])
        self.assertFalse((trial.parent / 'grade.json').exists())
        self.assertEqual(request_id, kit.read_json(trial.parent / 'status.json')['request_id'])

    def test_changed_runtime_adapter_cannot_submit_or_sign(self):
        cfg = kit.read_json(self.inputs['config'])
        changed = dict(cfg['source'], runtimeDigest='f' * 64)
        with patch.object(kit, 'runtime_identity', return_value=changed), \
             patch('benchmarks.campaign_gateway.execute', return_value={'readback': True}) as port:
            self.assertEqual({'readback': True}, kit.bridge(cfg, 'status', {'trial': 'original.sqlite'}))
            for operation in ('export', 'prepare', 'submit', 'grade', 'retire', 'read-capture'):
                with self.subTest(operation=operation), self.assertRaisesRegex(ValueError, 'runtime changed'):
                    kit.bridge(cfg, operation, {})
            self.assertEqual(1, port.call_count)

    def test_oversized_answer_is_quarantined_without_consuming_the_slot(self):
        pending = kit.next_slot(self.session)
        row = synthetic.measurement(self.session, 0)
        measurements, capture_path = self.paths_for(0)
        raw = synthetic.capture(row['product'], 'é' * 32769)
        kit.write_new(capture_path, raw)
        kit.write_json_new(measurements, row)
        with self.assertRaisesRegex(ValueError, 'invalid final answer'):
            kit.import_capture(self.session, 0, measurements, self.inputs['observer_key'],
                               capture_path, attest_origin=True)
        self.assertFalse((self.session / 'slots/000000').exists())
        rejected = list((self.session / 'rejected').iterdir())
        self.assertEqual(1, len(rejected))
        self.assertEqual(raw, (rejected[0] / 'capture.raw').read_bytes())
        self.assertEqual(pending, kit.next_slot(self.session))
        kit.make_report(self.session)
        self.assertEqual('NOT_MEASURED', kit.read_json(self.session / 'reports/scoreboard.json')['comparativeModelQuality'])

    def test_corrupt_native_evidence_cannot_reach_a_grading_request(self):
        index, _, _ = self.import_next()
        folder = self.session / 'slots' / f'{index:06d}'
        for name in ('capture.raw', 'observation.json'):
            path = folder / name
            original = path.read_bytes()
            if name == 'capture.raw':
                path.write_bytes(original + b' changed')
            else:
                changed = kit.read_json(path)
                changed['payload']['scheduleIndex'] = 1
                path.write_bytes(kit.json_bytes(changed))
            with self.subTest(name=name), patch.object(kit, 'verify_cfg_session', return_value=None):
                calls = []
                with self.assertRaises(ValueError):
                    kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'],
                                      bridge_call=lambda *args: calls.append(args))
                self.assertEqual([], calls)
            path.write_bytes(original)

    def test_installed_client_bytes_and_packaged_source_bind_runtime_identity(self):
        package = self.base / 'installed-source'
        package.mkdir()
        manifest = {'sourceCommit': 'a' * 40}
        (package / 'manifest-v14.json').write_bytes(kit.json_bytes(manifest))
        for name in ('benchmarks/contracts.py', 'benchmarks/product_trials.py',
                     'benchmarks/product_cli.py', 'grade_external_reference.py',
                     'benchmarks/campaign.py', 'benchmarks/campaign_gateway.py',
                     'desktop/conversation_client.py', 'desktop/configuration.py'):
            target = package / name
            target.parent.mkdir(exist_ok=True)
            shutil.copy2(kit.PACKAGE / name, target)
        with patch.object(kit, 'PACKAGE', package):
            baseline = kit.runtime_identity()
            self.assertEqual('a' * 40, baseline['commit'])
            (package / 'build-info.json').write_bytes(kit.json_bytes({'sourceCommit': 'b' * 40}))
            built = kit.runtime_identity()
            self.assertEqual('b' * 40, built['commit'])
            self.assertEqual(baseline['runtimeDigest'], built['runtimeDigest'])
            path = package / 'desktop/conversation_client.py'
            path.write_bytes(path.read_bytes() + b'\n# changed client\n')
            self.assertNotEqual(built['runtimeDigest'], kit.runtime_identity()['runtimeDigest'])

    def test_batch_audit_validates_once_and_appends_durable_hash_chained_rows(self):
        initial_tail, initial_count = kit.validate_audit(self.session)
        with kit.session_lock(self.session), patch.object(kit, 'validate_audit', wraps=kit.validate_audit) as validate:
            writer = kit.audit_writer(self.session)
            with patch.object(kit.os, 'fsync', wraps=kit.os.fsync) as sync:
                writer('fixture_batch_started', requestId='extref_' + 'a' * 32)
                writer('fixture_batch_readback', state='NOT_OBSERVED')
            self.assertEqual(1, validate.call_count)
            self.assertEqual(2, sync.call_count)
        _, final_count = kit.validate_audit(self.session)
        rows = [kit.parse_json(row) for row in (self.session / 'audit.jsonl').read_bytes().splitlines()]
        self.assertEqual(initial_count + 2, final_count)
        self.assertEqual(initial_tail, rows[-2]['previousDigest'])
        self.assertEqual(rows[-2]['entryDigest'], rows[-1]['previousDigest'])

    def test_batch_audit_stops_after_an_external_append_or_same_size_edit(self):
        path = self.session / 'audit.jsonl'
        original = path.read_bytes()
        for change in ('append', 'same-size'):
            path.write_bytes(original)
            with self.subTest(change=change), kit.session_lock(self.session):
                writer = kit.audit_writer(self.session)
                if change == 'append':
                    # Simulate a writer ignoring the already-held controller lock.
                    kit.audit(self.session, 'external_writer')
                else:
                    before = path.stat()
                    changed = original.replace(b'"slots": 4', b'"slots": 5', 1)
                    self.assertNotEqual(original, changed)
                    self.assertEqual(len(original), len(changed))
                    path.write_bytes(changed)
                    os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns + 1_000_000))
                changed = path.read_bytes()
                with self.assertRaisesRegex(ValueError, 'Audit log changed'):
                    writer('must_not_append')
                self.assertEqual(changed, path.read_bytes())

    def test_audit_change_during_grading_stops_before_another_trial_or_grade(self):
        first, _, _ = self.import_next()
        self.import_next()
        calls = []
        audit_path = self.session / 'audit.jsonl'

        def offline_port(cfg, operation, body):
            calls.append(operation)
            trial = Path(body['trial'])
            index = int(trial.parent.name)
            observation = kit.read_json(self.session / 'slots' / f'{index:06d}' / 'observation.json')['payload']
            request_id = 'extref_' + format(index + 1, '032x')
            if operation == 'prepare':
                saved = {'request': {'request_id': request_id, 'fixture_digest': observation['taskDigest'],
                                    'proposal_digest': observation['artifactDigest']}}
                with sqlite3.connect(trial) as db:
                    db.execute('CREATE TABLE trial(id INTEGER PRIMARY KEY, plan TEXT, attempted INTEGER)')
                    db.execute('INSERT INTO trial VALUES(1, ?, 0)', (json.dumps(saved),))
                return {'request_id': request_id}
            self.assertEqual('submit', operation)
            with sqlite3.connect(trial) as db:
                db.execute('UPDATE trial SET attempted=1')
            with audit_path.open('ab') as stream:
                stream.write(b'SYNTHETIC direct audit mutation\n')
            return {'request_id': request_id, 'proposal_digest': observation['artifactDigest'],
                    'submissionState': 'OBSERVED', 'state': 'COMPLETED'}

        with patch.object(kit, 'verify_cfg_session', return_value=None):
            with self.assertRaisesRegex(ValueError, 'Audit log changed'):
                kit.grade_session(self.session, self.inputs['config'], self.inputs['grader_key'], bridge_call=offline_port)
        self.assertEqual(['prepare', 'submit'], calls)
        self.assertFalse((self.session / 'grader/000001/trial.sqlite').exists())
        self.assertFalse((self.session / 'grader/000000/grade.json').exists())
        self.assertTrue(audit_path.read_bytes().endswith(b'SYNTHETIC direct audit mutation\n'))
        with sqlite3.connect(self.session / 'grader' / f'{first:06d}' / 'trial.sqlite') as db:
            self.assertEqual(1, db.execute('SELECT attempted FROM trial').fetchone()[0])

    def test_inbox_reports_once_after_partial_success_and_preserves_the_failed_slot(self):
        inbox = self.base / 'partial-inbox'
        for index in (0, 1):
            row = synthetic.measurement(self.session, index)
            kit.write_json_new(inbox / f'{index:06d}' / 'measurements.json', row)
            raw = synthetic.capture(row['product'], 'SYNTHETIC partial inbox', f'SYNTHETIC-partial-{index}')
            kit.write_new(inbox / f'{index:06d}' / 'capture.raw', raw if index == 0 else b'{"interrupted":')
        with patch.object(kit, 'make_report', wraps=kit.make_report) as report:
            with self.assertRaises(ValueError):
                kit.import_inbox(self.session, inbox, self.inputs['observer_key'], attest_origin=True)
            self.assertEqual(1, report.call_count)
        self.assertTrue((self.session / 'slots/000000/observation.json').is_file())
        self.assertFalse((self.session / 'slots/000001').exists())
        snapshot = kit.read_json(self.session / 'reports/scoreboard.json')
        self.assertEqual(1, sum(row['observed'] for row in snapshot['products'].values()))
        self.assertEqual('MISSING_OBSERVATION', snapshot['slots'][1]['observationState'])
        self.assertEqual(1, kit.next_slot(self.session)['slot']['scheduleIndex'])

    def test_inbox_generates_one_report_for_multiple_successful_observations(self):
        inbox = self.base / 'complete-inbox'
        for index in range(4):
            row = synthetic.measurement(self.session, index)
            kit.write_json_new(inbox / f'{index:06d}' / 'measurements.json', row)
            kit.write_new(inbox / f'{index:06d}' / 'capture.raw',
                          synthetic.capture(row['product'], 'SYNTHETIC inbox', f'SYNTHETIC-complete-{index}'))
        with patch.object(kit, 'make_report', wraps=kit.make_report) as report:
            result = kit.import_inbox(self.session, inbox, self.inputs['observer_key'], attest_origin=True)
            self.assertEqual(1, report.call_count)
        self.assertEqual(4, len(result['imported']))
        snapshot = kit.read_json(self.session / 'reports/scoreboard.json')
        self.assertEqual(4, sum(row['observed'] for row in snapshot['products'].values()))
        self.assertTrue(all(row['verifiedRate'] is None for row in snapshot['products'].values()))

    def test_nonobject_campaign_audit_and_observation_are_rejected_cleanly(self):
        invalid = self.base / 'nonobject-campaign.json'
        for value in (None, [], 42):
            invalid.write_bytes(kit.json_bytes(value))
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'Unsupported campaign'):
                kit.campaign_config(invalid)
        audit_path = self.session / 'audit.jsonl'
        original = audit_path.read_bytes()
        audit_path.write_bytes(b'null\n')
        with self.assertRaisesRegex(ValueError, 'Audit log entry is invalid'):
            kit.audit_writer(self.session)
        self.assertEqual(b'null\n', audit_path.read_bytes())
        audit_path.write_bytes(original)
        kit.write_json_new(self.session / 'slots/000000/observation.json', None)
        with self.assertRaisesRegex(ValueError, 'different schedule position'):
            kit.comparison(self.session)


if __name__ == '__main__':
    unittest.main()
