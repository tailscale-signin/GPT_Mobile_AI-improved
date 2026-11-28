"""Engineering is built in; owner grants and per-job cancellation remain authoritative."""
import unittest
from types import SimpleNamespace

import test_gateway_v14_engineering as engineering_fixtures
import test_gateway_control_settings as control_fixtures
from desktop.control_store import ControlError, ControlStore
from v14.engineering import EngineeringStore
from v14.execution_binding import ExecutionBindingError


class EngineeringAlwaysOnTests(unittest.TestCase):
    setUp = engineering_fixtures.DurableEngineeringTests.setUp
    tearDown = engineering_fixtures.DurableEngineeringTests.tearDown

    def test_legacy_disabled_flag_cannot_block_an_authorized_new_task(self):
        self.store.tx(lambda db: db.execute(
            'INSERT INTO engineering_admission_settings VALUES (?,?,?)', ('owner', 0, 7)))
        self.assertEqual({'enabled': True, 'revision': 7}, self.store.tx(
            lambda db: self.store.admission_settings(db, 'owner')))
        job = self.store.create('owner', self.request)
        self.assertEqual('PLAN', job['state'])
        self.assertEqual([], self.backend.calls)

    def test_disable_is_rejected_without_changing_existing_job_or_request(self):
        job = self.store.create('owner', self.request)
        before = self.store.get(job['id'])
        with self.assertRaises(ExecutionBindingError) as rejected:
            self.store.change_admission_settings('owner', {'enabled': False, 'revision': 0})
        self.assertEqual('ENGINEERING_ALWAYS_ON', rejected.exception.code)
        self.assertEqual(before, self.store.get(job['id']))
        self.assertTrue(self.store.tx(lambda db: self.store.admission_settings(db, 'owner'))['enabled'])

    def test_compatibility_enable_is_idempotent_and_revision_checked(self):
        expected = {'enabled': True, 'revision': 0}
        for _ in range(3):
            self.assertEqual(expected, self.store.change_admission_settings('owner', expected))
        with self.assertRaises(ExecutionBindingError) as rejected:
            self.store.change_admission_settings('owner', {'enabled': True, 'revision': 1})
        self.assertEqual('ENGINEERING_SETTINGS_CHANGED', rejected.exception.code)
        self.assertEqual([], self.backend.calls)


class ControlAlwaysOnTests(unittest.TestCase):
    setUp = control_fixtures.ControlSettingsTests.setUp
    paired = control_fixtures.ControlSettingsTests.paired
    command = control_fixtures.ControlSettingsTests.command

    def test_disable_command_has_a_durable_rejection_and_does_not_pause_owner(self):
        _, owner = self.paired()
        command = self.command({'engineering_enabled': False})
        refusal = self.store.patch(owner['principal_id'], command)
        self.assertEqual('rejected', refusal['status'])
        self.assertEqual('engineering_always_on', refusal['error']['code'])
        self.assertEqual('not_dispatched', refusal['dispatch_state'])
        reopened = ControlStore(self.root)
        self.assertEqual(refusal, reopened.patch(owner['principal_id'], command))
        self.assertEqual(refusal, reopened.receipt(owner['principal_id'], command['command_id']))
        snapshot = reopened.snapshot(owner['principal_id'])
        self.assertTrue(snapshot['desired']['engineering_enabled'])
        self.assertTrue(snapshot['effective']['engineering_enabled'])
        self.assertTrue(snapshot['capabilities']['engineeringAlwaysOn.v1'])
        self.assertEqual(0, snapshot['revision'])

    def test_upgrade_removes_old_pause_without_changing_viewer_or_revoked_authority(self):
        _, owner = self.paired()
        _, viewer = self.paired(role='viewer')
        _, revoked = self.paired()
        with self.store.tx() as db:
            db.execute("DELETE FROM gateway_control_migrations WHERE name='engineering_always_on_v1'")
            db.execute('UPDATE engineering_admission_settings SET enabled=0,revision=3 WHERE principal=?',
                       (owner['principal_id'],))
            db.execute('UPDATE gateway_control_devices SET revoked=1 WHERE id=?', (revoked['principal_id'][7:],))
        reopened = ControlStore(self.root)
        snapshot = reopened.snapshot(owner['principal_id'])
        self.assertTrue(snapshot['desired']['engineering_enabled'])
        self.assertTrue(snapshot['effective']['engineering_enabled'])
        adapter = SimpleNamespace(admission_settings=EngineeringStore.admission_settings)
        with reopened.tx() as db:
            EngineeringStore.require_new_jobs(adapter, db, owner['principal_id'])
            for grant in (viewer, revoked):
                with self.subTest(principal=grant['principal_id']), self.assertRaises(ExecutionBindingError) as error:
                    EngineeringStore.require_new_jobs(adapter, db, grant['principal_id'])
                self.assertEqual('PAIRED_OWNER_REQUIRED', error.exception.code)
        self.assertFalse(reopened.snapshot(viewer['principal_id'])['effective']['engineering_enabled'])
        with self.assertRaises(ControlError):
            reopened.snapshot(revoked['principal_id'])
        # Current-schema polling must remain read-only after the one-time upgrade.
        with reopened.tx() as db:
            before = db.total_changes
            reopened.schema(db)
            reopened._snapshot(db, owner['principal_id'])
            self.assertEqual(before, db.total_changes)

    def test_builtin_engineering_still_reports_missing_runtime_prerequisites(self):
        _, owner = self.paired()
        self.store.observe_runtime(False, ['github_connection'])
        snapshot = self.store.snapshot(owner['principal_id'])
        self.assertTrue(snapshot['desired']['engineering_enabled'])
        self.assertFalse(snapshot['effective']['engineering_enabled'])
        self.assertIn('github_connection', snapshot['engineering_prerequisites'])
        self.assertTrue(snapshot['capabilities']['engineeringAlwaysOn.v1'])


if __name__ == '__main__':
    unittest.main()
