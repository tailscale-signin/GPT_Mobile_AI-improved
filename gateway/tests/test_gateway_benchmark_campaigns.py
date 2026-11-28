"""Native desktop campaigns retain signed evidence and the original authority."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import campaign
from benchmarks.campaign_synthetic import build_inputs, capture, measurement
from desktop import benchmark_campaigns as service
from desktop import native_bridge
from desktop.configuration import DEFAULTS, atomic_json
from desktop.conversation_client import ClientError
from v14.state_lock import StateDirectoryAlreadyOwned


OWNER = {'gateway_id': 'synthetic-gateway', 'principal_id': 'synthetic-owner'}


class FakeClient:
    origin = 'http://127.0.0.1:8090'
    observed = OWNER

    def __init__(self, root):
        self.root = root

    def identity(self):
        return dict(self.observed)


class NativeCampaignDesktopTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.area = Path(temporary.name)
        self.root = self.area / 'gateway-state'
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.inputs = build_inputs(self.area / 'fixture-inputs', task_count=1)
        manifest = campaign.read_json(self.inputs['tasks'] / 'export-manifest.json')
        for item in manifest['files']:
            task_path = self.inputs['tasks'] / item['file']
            task = campaign.read_json(task_path)
            task['gatewayOwnerDigest'] = campaign.digest((OWNER['gateway_id'], OWNER['principal_id']))
            atomic_json(task_path, task)
            item['sha256'] = hashlib.sha256(task_path.read_bytes()).hexdigest()
        atomic_json(self.inputs['tasks'] / 'export-manifest.json', manifest)
        self.ident = 'campaign_' + 'a' * 32
        self.workspace = service._workspace(self.root, self.ident)
        self.workspace.mkdir(parents=True)
        self.session = self.workspace / 'session'
        config = campaign.read_json(self.inputs['config'])
        config['gateway'] = {'root': str(self.root), 'allowedOrigin': FakeClient.origin}
        atomic_json(self.workspace / 'lab.json', config)
        shutil.copyfile(self.inputs['campaign'], self.workspace / 'campaign.json')
        campaign.initialize(self.workspace / 'lab.json', self.workspace / 'campaign.json', self.inputs['tasks'],
                            self.session, self.inputs['observer_key'], synthetic=True)
        self.metadata = {'schema': service.SCHEMA, 'id': self.ident, 'title': 'Synthetic fixture only',
                         'createdAt': campaign.now(), 'owner': dict(OWNER), 'origin': FakeClient.origin, 'state': 'READY',
                         'files': {name: hashlib.sha256((self.workspace / name).read_bytes()).hexdigest()
                                   for name in ('lab.json', 'campaign.json')}}
        service._save_metadata(self.workspace, self.metadata)
        client = patch.object(service, 'Client', FakeClient)
        client.start()
        self.addCleanup(client.stop)
        network = patch('socket.socket.connect', side_effect=AssertionError('No network is permitted in synthetic tests'))
        network.start()
        self.addCleanup(network.stop)

    def test_read_is_offline_and_missing_product_results_remain_unknown(self):
        with patch.object(FakeClient, 'identity', side_effect=AssertionError('Offline read cannot connect')):
            result = service.read(self.root, self.ident)
            history = service.history(self.root)
        self.assertEqual('SYNTHETIC_ONLY', result['evidenceLane'])
        self.assertEqual('NOT_MEASURED', result['report']['comparativeModelQuality'])
        self.assertIsNone(result['report']['githubIntelligencePointsOutOf100'])
        self.assertEqual(4, len(result['schedule']))
        self.assertEqual(0, result['next']['slot']['scheduleIndex'])
        self.assertIn('SYNTHETIC', result['next']['promptText'])
        self.assertIsNone(result['next']['measurementsTemplate']['seconds'])
        self.assertTrue(all(item['verifiedRate'] is None for item in result['report']['products'].values()))
        self.assertEqual(self.ident, history[0]['id'])
        self.assertEqual(0, history[0]['observed'])

    def test_refresh_does_not_overwrite_an_observers_work_in_progress(self):
        before = service.read(self.root, self.ident)
        path = Path(before['next']['measurementsPath'])
        value = campaign.read_json(path)
        value.update(seconds=18.25, costUsd=None, originEvidence='Independent observer note')
        atomic_json(path, value)
        after = service.read(self.root, self.ident)
        self.assertTrue(Path(after['next']['measurementsPath']).samefile(path))
        self.assertEqual(value, campaign.read_json(path))
        self.assertEqual(value, after['next']['measurements'])

    def test_native_import_uses_exact_current_slot_and_retains_unknown_cost(self):
        row = measurement(self.session, 0, costUsd=None)
        measurements = self.area / 'observed.json'
        raw = self.area / 'capture.json'
        campaign.write_json_new(measurements, row)
        raw.write_bytes(capture(row['product'], ' {"synthetic":true}\r\n ', 'synthetic-native-session-one'))
        result = service.operate(self.root, self.ident, 'import', slot_index=0,
            observer_key=str(self.inputs['observer_key']), measurements_path=str(measurements),
            capture_path=str(raw), attest_origin=True)
        self.assertEqual(1, result['next']['slot']['scheduleIndex'])
        self.assertIsNone(result['schedule'][0]['costUsd'])
        self.assertEqual(b' {"synthetic":true}\r\n ', (self.session / 'slots/000000/candidate.txt').read_bytes())
        self.assertEqual('NOT_MEASURED', result['report']['comparativeModelQuality'])

    def test_missing_observer_attestation_never_reaches_import(self):
        with patch.object(campaign, 'import_capture') as imported:
            with self.assertRaisesRegex(ValueError, 'explicitly attest'):
                service.operate(self.root, self.ident, 'import', slot_index=0, attest_origin=False)
            imported.assert_not_called()

    def test_changed_owner_and_origin_block_grading_before_dispatch(self):
        for changes in ({'observed': dict(OWNER, principal_id='different-owner')},
                        {'origin': 'http://127.0.0.1:8999'}):
            with patch.multiple(FakeClient, **changes), patch.object(campaign, 'grade_session') as grade:
                with self.assertRaises(ClientError):
                    service.operate(self.root, self.ident, 'grade', grader_key=str(self.inputs['grader_key']))
                grade.assert_not_called()

    def test_recovery_is_one_status_only_pass_under_the_original_workspace(self):
        with patch.object(campaign, 'recover_campaign', return_value={'receipts': [], 'mode': 'STATUS_ONLY_RECOVERY'}) as recover:
            result = service.operate(self.root, self.ident, 'recover', grader_key=str(self.inputs['grader_key']))
        recover.assert_called_once_with(self.session, self.workspace / 'lab.json', self.inputs['grader_key'], None,
                                        polls=1, interval_seconds=0)
        self.assertEqual('STATUS_ONLY_RECOVERY', result['operation']['mode'])
        self.assertEqual(0, result['modelRequests'])

    def test_changed_frozen_configuration_blocks_grading(self):
        cfg = campaign.read_json(self.workspace / 'lab.json')
        cfg['gateway']['allowedOrigin'] = 'http://127.0.0.1:8999'
        atomic_json(self.workspace / 'lab.json', cfg)
        with patch.object(campaign, 'grade_session') as grade:
            with self.assertRaisesRegex(ValueError, 'setup changed'):
                service.operate(self.root, self.ident, 'grade', grader_key=str(self.inputs['grader_key']))
            grade.assert_not_called()

    def test_concurrent_process_cannot_enter_the_same_campaign_mutation(self):
        with service._lock(self.root, self.ident):
            with self.assertRaises(StateDirectoryAlreadyOwned):
                service.operate(self.root, self.ident, 'grade', grader_key=str(self.inputs['grader_key']))

    def test_private_keys_in_gateway_state_are_rejected_before_grading(self):
        unsafe = self.root / 'copied-private-key.pem'
        shutil.copyfile(self.inputs['grader_key'], unsafe)
        with patch.object(campaign, 'grade_session') as grade:
            with self.assertRaisesRegex(ValueError, 'Keep private keys outside'):
                service.operate(self.root, self.ident, 'grade', grader_key=str(unsafe))
        grade.assert_not_called()

    def test_path_escape_and_symlink_session_are_rejected(self):
        for ident in ('../../outside', 'campaign_' + 'a' * 32 + '/../../outside', None):
            with self.assertRaises(ValueError):
                service.read(self.root, ident)
        relocated = self.area / 'relocated-session'
        self.session.rename(relocated)
        try:
            self.session.symlink_to(relocated, target_is_directory=True)
        except OSError:
            self.skipTest('Directory symlinks unavailable on this host')
        with self.assertRaisesRegex(ValueError, 'original workspace'):
            service.read(self.root, self.ident)

    def test_export_contains_scoreboards_without_capture_or_private_key_material(self):
        target = self.area / 'report-export'
        with patch.object(FakeClient, 'identity', side_effect=AssertionError('Offline export cannot connect')):
            result = service.export(self.root, self.ident, target)
        # Windows can expand the temporary directory's 8.3 alias when resolving
        # an exported path. The contract is file identity, not path spelling.
        self.assertTrue(Path(result['scoreboard']).samefile(target / 'scoreboard.html'))
        self.assertEqual({'scoreboard.html', 'scoreboard.md', 'scoreboard.json', 'scoreboard.csv',
                          'trial-details.csv', 'comparison.json'}, {p.name for p in target.iterdir()})
        self.assertTrue(all(b'PRIVATE KEY' not in file.read_bytes() for file in target.iterdir()))
        with self.assertRaisesRegex(ValueError, 'existing files'):
            service.export(self.root, self.ident, target)
        with self.assertRaisesRegex(ValueError, 'outside'):
            service.export(self.root, self.ident, self.root / 'reports')

    def test_templates_use_current_installed_runtime_without_connecting(self):
        with patch.object(FakeClient, 'identity', side_effect=AssertionError('Templates cannot connect')):
            result = service.templates(self.root, self.area / 'templates')
        config = campaign.read_json(result['configPath'])
        self.assertEqual(campaign.runtime_identity(), config['source'])
        self.assertTrue(Path(config['gateway']['root']).samefile(self.root))
        self.assertIsNone(config['controls']['startingRevision'])
        self.assertIsNone(config['products']['ChatGPT']['version'])
        self.assertEqual(0, result['modelRequests'])

    def test_setup_rejects_cross_owner_export_before_freezing_a_plan(self):
        custom = self.area / 'create-lab.json'
        atomic_json(custom, campaign.read_json(self.workspace / 'lab.json'))

        def export_other_owner(config_path, campaign_path, target):
            config = campaign.read_json(config_path)
            self.assertEqual(campaign.digest((OWNER['gateway_id'], OWNER['principal_id'])),
                             config['gateway']['expectedOwnerDigest'])
            shutil.copytree(self.inputs['tasks'], target)
            manifest = campaign.read_json(target / 'export-manifest.json')
            task_path = target / manifest['files'][0]['file']
            task = campaign.read_json(task_path)
            task['gatewayOwnerDigest'] = campaign.digest('different-owner')
            atomic_json(task_path, task)

        with patch.object(campaign, 'export_tasks', side_effect=export_other_owner), patch.object(campaign, 'initialize') as initialized:
            with self.assertRaisesRegex(ClientError, 'another Gateway or account'):
                service.create(self.root, custom, campaign_path=self.inputs['campaign'],
                               observer_key=self.inputs['observer_key'])
        initialized.assert_not_called()

    def test_saved_capture_is_not_written_after_owner_changes(self):
        target = self.area / 'new-mobile-capture.json'
        observed = json.loads(capture('GPT Mobile AI', '{"synthetic":true}'))
        with patch.object(FakeClient, 'identity', side_effect=[dict(OWNER), dict(OWNER, principal_id='different-owner')]), \
                patch.object(campaign, 'verify_cfg_session'), patch.object(campaign, 'bridge', return_value=observed):
            with self.assertRaises(ClientError):
                service.read_mobile(self.root, self.ident, 'existing-synthetic-conversation', target)
        self.assertFalse(target.exists())

    def test_snapshot_bounds_schedule_without_truncating_saved_evidence(self):
        result = service.read(self.root, self.ident)
        path = self.session / 'reports/scoreboard.json'
        report = campaign.read_json(path)
        report['slots'] = [dict(report['slots'][0], scheduleIndex=index) for index in range(1344)]
        atomic_json(path, report)
        with patch.object(campaign, 'make_report', return_value={'directory': str(path.parent), 'scoreboard': result['reportPath']}):
            snapshot = service.read(self.root, self.ident)
        self.assertEqual(1344, snapshot['scheduleTotal'])
        self.assertEqual(500, len(snapshot['schedule']))
        self.assertTrue(snapshot['scheduleTruncated'])
        self.assertTrue(Path(snapshot['schedulePath']).samefile(self.session / 'schedule.csv'))
        self.assertNotIn('slots', snapshot['report'])
        self.assertEqual(1344, len(campaign.read_json(path)['slots']))

    def test_invalid_measurements_draft_does_not_hide_verified_report(self):
        result = service.read(self.root, self.ident)
        Path(result['next']['measurementsPath']).write_text('{invalid json', encoding='utf-8')
        snapshot = service.read(self.root, self.ident)
        self.assertIsNone(snapshot['next']['measurements'])
        self.assertIn('measurementsError', snapshot['next'])
        self.assertEqual('NOT_MEASURED', snapshot['report']['comparativeModelQuality'])

    def test_setup_failure_retains_recoverable_original_identity(self):
        cfg = campaign.read_json(self.workspace / 'lab.json')
        custom = self.area / 'create-lab.json'
        atomic_json(custom, cfg)
        with patch.object(campaign, 'export_tasks', side_effect=TimeoutError('Synthetic read timeout')):
            with self.assertRaises(TimeoutError):
                service.create(self.root, custom, campaign_path=self.inputs['campaign'],
                               observer_key=self.inputs['observer_key'])
        pending = next(row for row in service.history(self.root) if row['id'] != self.ident)
        self.assertEqual('SETUP_INCOMPLETE', pending['state'])
        workspace, metadata = service._read_metadata(self.root, pending['id'])
        self.assertEqual(OWNER, metadata['owner'])
        self.assertNotIn('observerKeyPath', metadata)
        # Resume only reconciles an already atomically frozen session when present.
        shutil.copytree(self.session, workspace / 'session')
        with patch.object(campaign, 'export_tasks') as exported, patch.object(campaign, 'initialize') as initialized:
            resumed = service.resume_setup(self.root, pending['id'], self.inputs['observer_key'])
        exported.assert_not_called()
        initialized.assert_not_called()
        self.assertEqual(pending['id'], resumed['id'])

    def test_native_bridge_routes_campaign_actions_without_legacy_model_runner(self):
        with patch.object(service, 'dispatch', return_value={'modelRequests': 0}) as dispatch:
            result = native_bridge.dispatch(self.root, 'benchmark-campaign-recover',
                                             {'id': self.ident, 'graderKeyPath': 'selected-key'}, io.StringIO())
        dispatch.assert_called_once_with(self.root, 'recover', {'id': self.ident, 'graderKeyPath': 'selected-key'})
        self.assertEqual(0, result['modelRequests'])
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            service.dispatch(self.root, 'generate', {})

    def test_malformed_evidence_remains_visible_and_bridge_returns_a_safe_error(self):
        with (self.session / 'audit.jsonl').open('a', encoding='utf-8') as stream:
            stream.write('null\n')
        rows = service.history(self.root)
        self.assertEqual(self.ident, rows[0]['id'])
        self.assertEqual('UNAVAILABLE', rows[0]['state'])
        output = io.StringIO()
        request = {'protocol': native_bridge.PROTOCOL, 'action': 'benchmark-campaign-read', 'args': {'id': self.ident}}
        code = native_bridge.serve(self.root, io.StringIO(json.dumps(request) + '\n'), output)
        self.assertEqual(1, code)
        self.assertEqual('error', json.loads(output.getvalue())['event'])
        self.assertNotIn('Traceback', output.getvalue())

    def test_invalid_top_level_campaign_is_a_bounded_user_error(self):
        invalid = self.area / 'invalid-campaign.json'
        invalid.write_text('[]', encoding='utf-8')
        with self.assertRaises(ValueError):
            service.dispatch(self.root, 'create', {'configPath': str(self.workspace / 'lab.json'),
                'campaignPath': str(invalid), 'observerKeyPath': str(self.inputs['observer_key'])})


if __name__ == '__main__':
    unittest.main()
