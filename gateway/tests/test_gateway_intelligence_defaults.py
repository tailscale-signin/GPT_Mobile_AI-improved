"""New tasks use evidence-guided work; accepted work keeps its original policy."""
import json
import unittest

import test_gateway_v14_engineering as fixtures
from v14.conversations import fingerprint
from v14.engineering import digest, validate_request
from v14.engineering_defaults import new_request_defaults, retained_request_defaults


class IntelligenceDefaultsTests(unittest.TestCase):
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown

    def finish(self, job):
        claimed, lease, _ = self.store.claim(job['id'])
        self.assertTrue(self.store.complete(claimed, lease, 'COMPLETED', {}))
        return self.store.conversations.snapshot('owner', job['conversation_id'])

    def test_new_admission_and_lost_response_retry_share_active_policy(self):
        job = self.store.create('owner', self.request)
        self.assertEqual('ACTIVE', job['request'].get('next_action_policy'))
        self.assertEqual('ACTIVE', self.store.conversations.snapshot('owner', job['conversation_id'])['next_action_policy'])
        self.assertEqual(job, self.store.create('owner', dict(self.request)))
        self.assertEqual(1, len(self.store.list('owner')))
        self.assertEqual([], self.backend.calls)

    def test_explicit_baseline_choices_remain_frozen(self):
        for mode in ('LEGACY', 'SHADOW', 'ACTIVE'):
            request = dict(self.request, request_id=mode, next_action_policy=mode)
            job = self.store.create('owner', request)
            self.assertEqual(mode, job['request']['next_action_policy'])
            self.assertEqual(job, self.store.create('owner', request))

    def test_omitted_followup_policy_inherits_exact_conversation(self):
        for by_remote_id in (False, True):
            for mode in ('LEGACY', 'SHADOW'):
                request = dict(self.request, request_id=mode + str(by_remote_id),
                               client_conversation_id='chat-' + mode + str(by_remote_id), next_action_policy=mode)
                job = self.store.create('owner', request)
                snapshot = self.finish(job)
                followup = dict(request, request_id=request['request_id'] + '-followup', expected_revision=snapshot['revision'])
                followup.pop('next_action_policy')
                if by_remote_id:
                    followup['conversation_id'] = job['conversation_id']
                admitted = self.store.create('owner', followup)
                self.assertEqual(job['conversation_id'], admitted['conversation_id'])
                self.assertEqual(mode, admitted['request']['next_action_policy'])

    def test_legacy_accepted_request_without_policy_keeps_digest_on_retry(self):
        job = self.store.create('owner', dict(self.request, next_action_policy='LEGACY'))
        original = dict(job['request'])
        original.pop('next_action_policy')
        def restore_legacy(db):
            job['request'] = original
            db.execute('UPDATE engineering_jobs SET data=?,request_digest=? WHERE id=?',
                       (json.dumps(job), digest(original), job['id']))
        self.store.tx(restore_legacy)
        retried = self.store.create('owner', dict(self.request))
        self.assertEqual(job['id'], retried['id'])
        self.assertNotIn('next_action_policy', retried['request'])
        self.assertNotIn('next_action_policy', retained_request_defaults(self.request, original))
        self.assertEqual([], self.backend.calls)

    def test_old_binding_and_unscoped_client_key_keep_legacy_followup_policy(self):
        request = dict(self.request, next_action_policy='LEGACY', client_conversation_id='old-client')
        job = self.store.create('owner', request)
        snapshot = self.finish(job)
        def old_binding(db):
            row = db.execute('SELECT binding FROM gateway_conversations WHERE id=?', (job['conversation_id'],)).fetchone()
            binding = json.loads(row[0])
            binding.pop('next_action_policy')
            db.execute('UPDATE gateway_conversations SET binding=?,client_key=? WHERE id=?',
                       (json.dumps(binding), 'old-client', job['conversation_id']))
        self.store.tx(old_binding)
        followup = dict(self.request, request_id='old-followup', client_conversation_id='old-client', expected_revision=snapshot['revision'])
        continued = self.store.create('owner', followup)
        self.assertEqual(job['conversation_id'], continued['conversation_id'])
        self.assertEqual('LEGACY', continued['request']['next_action_policy'])

    def test_policy_lookup_cannot_inherit_another_owner_or_profile(self):
        request = dict(self.request, next_action_policy='SHADOW', client_conversation_id='same-client')
        job = self.store.create('owner', request)
        lookup = self.store.conversations.engineering_next_action_policy
        self.assertEqual('ACTIVE', self.store.conversations.read(lambda db: lookup(db, 'other-owner', self.request)))
        self.assertEqual('ACTIVE', self.store.conversations.read(lambda db: lookup(db, 'other-owner',
                         dict(self.request, conversation_id=job['conversation_id']))))
        self.assertEqual('ACTIVE', self.store.conversations.read(lambda db: lookup(db, 'owner',
                         dict(self.request, profile_id='other-profile', client_conversation_id='same-client'))))

    def test_rejected_command_default_is_stable_and_has_no_dispatch(self):
        receipt = self.store.conversations.reject_command('owner', 'rejected', self.request, 'queue_full', 429)
        self.assertEqual(receipt, self.store.conversations.reject_command('owner', 'rejected', self.request, 'queue_full', 429))
        expected = fingerprint(validate_request(new_request_defaults(self.request)))
        actual = self.store.tx(lambda db: db.execute(
            'SELECT fingerprint FROM gateway_command_rejections WHERE principal=? AND id=?', ('owner', 'rejected')).fetchone()[0])
        self.assertEqual(expected, actual)
        self.assertEqual('not_dispatched', receipt['dispatch_state'])
        self.assertEqual([], self.backend.calls)

    def test_old_rejection_without_policy_stays_readable(self):
        receipt = self.store.conversations.reject_command('owner', 'legacy-rejection', self.request, 'queue_full', 429)
        original = new_request_defaults(self.request)
        original.pop('next_action_policy', None)
        self.store.tx(lambda db: db.execute(
            'UPDATE gateway_command_rejections SET fingerprint=? WHERE principal=? AND id=?',
            (fingerprint(validate_request(original)), 'owner', 'legacy-rejection')))
        self.assertEqual(receipt, self.store.conversations.reject_command('owner', 'legacy-rejection', self.request, 'queue_full', 429))


if __name__ == '__main__':
    unittest.main()
