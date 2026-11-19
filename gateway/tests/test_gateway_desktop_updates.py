"""Drive real update-cycle decisions with controlled catalog and staging completion."""
from concurrent.futures import Future
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import atomic_json
from desktop.manager import UpdateCycle


class UpdateCycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        atomic_json(root / 'current.json', {'version': '17.0.2'})
        self.host = SimpleNamespace(root=root, status={}, _request_payload={}, respond=Mock(), log=Mock(),
                                    notify=Mock(), stage=Mock(), activate=Mock(return_value=True))
        self.host.publish = self.host.status.update
        self.worker = Mock()
        self.future = Future()
        self.worker.submit.return_value = self.future
        self.cycle = UpdateCycle(self.host, self.worker)
        self.cfg = {'update_mode': 'notify', 'update_interval_hours': 24}

    def tick(self, action=None):
        return self.cycle.tick(action, 'receipt' if action else None, self.cfg, False)

    def test_manual_install_waits_for_catalog_staging_and_idle_without_second_click(self):
        self.tick('install')
        self.assertEqual('checking', self.host.status['updateState'])
        self.tick()
        self.host.respond.assert_called_once()
        self.worker.submit.assert_called_once()
        self.future.set_result({'version': '18.0.0'})
        staged = Future()
        self.worker.submit.return_value = staged
        self.tick()
        self.assertEqual('downloading', self.host.status['updateState'])
        self.host.activate.assert_not_called()
        (self.host.root / 'releases/18.0.0').mkdir(parents=True)
        staged.set_result(None)
        self.host.activate.side_effect = [False, True]
        self.assertFalse(self.tick())
        self.assertFalse(self.tick())
        self.cycle.next_activate = 0
        self.assertTrue(self.tick())
        self.assertEqual(2, self.host.activate.call_count)
        self.host.respond.assert_called_once()

    def test_queued_install_survives_restart_with_automatic_checks_off(self):
        atomic_json(self.host.root / 'pending-update.json', {'from': '17.0.2', 'target': '18.0.0'})
        self.cycle = UpdateCycle(self.host, self.worker)
        self.cfg['update_mode'] = 'off'
        self.tick()
        self.assertEqual('checking', self.host.status['updateState'])
        self.future.set_result({'version': '18.0.0'})
        self.worker.submit.return_value = Future()
        self.tick()
        self.assertEqual('downloading', self.host.status['updateState'])

    def test_phone_target_change_requires_new_selection_in_notify_mode(self):
        self.host._request_payload = {'target': '18.0.0'}
        self.tick('install')
        self.future.set_result({'version': '18.0.1'})
        self.tick()
        self.tick()
        self.assertEqual('available', self.host.status['updateState'])
        self.worker.submit.assert_called_once()
        self.host.activate.assert_not_called()

    def test_failed_lookup_reports_retryable_error_and_releases_slot(self):
        self.tick('check')
        self.future.set_exception(ValueError('Incomplete catalog'))
        self.tick()
        self.assertEqual('error', self.host.status['updateState'])
        self.worker.submit.return_value = Future()
        self.tick('check')
        self.assertEqual('checking', self.host.status['updateState'])
        self.assertIsNone(self.host.status['updateError'])
