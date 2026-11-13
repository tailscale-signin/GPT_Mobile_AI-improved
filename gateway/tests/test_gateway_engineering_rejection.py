import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_rejection import state_or_input_rejection


class EngineeringRejectionTests(unittest.TestCase):
    def test_known_guard_preserves_actionable_reason_without_inventing_dispatch(self):
        result = state_or_input_rejection(ValueError('Invalid branch'))
        self.assertEqual('ENGINEERING_BRANCH_INVALID', result['reasonCode'])
        self.assertEqual(['branch'], result['invalid_fields'])
        self.assertFalse(result['retryable'])
        self.assertEqual('request_validation', result['failed_stage'])
        self.assertNotIn('dispatch_state', result)
        self.assertNotIn('conversation or job changed', result['message'])

    def test_unknown_exception_text_and_private_field_names_are_not_exported(self):
        for error in (ValueError('private token and repository'), KeyError('private-field'), TypeError('private payload')):
            result = state_or_input_rejection(error, 'provider_admission')
            self.assertEqual('ENGINEERING_INPUT_OR_STATE_REJECTED', result['reasonCode'])
            self.assertEqual([], result['invalid_fields'])
            self.assertNotIn('private', str(result))
            self.assertEqual('provider_admission', result['failed_stage'])

    def test_unknown_guard_does_not_invent_validation_phase_or_export_arbitrary_stage(self):
        for stage in (None, 'private-stage-name'):
            result = state_or_input_rejection(KeyError('private-field'), stage)
            self.assertEqual('engineering_guard', result['failed_stage'])
            self.assertNotIn('private', str(result))

    def test_batch_policy_failures_retain_the_exact_known_field(self):
        self.assertEqual(['batch_policy_version'], state_or_input_rejection(ValueError('Unsupported batch policy protocol'))['invalid_fields'])
        self.assertEqual(['batch_preferences'], state_or_input_rejection(ValueError('Invalid batch preferences'))['invalid_fields'])


if __name__ == '__main__':
    unittest.main()
