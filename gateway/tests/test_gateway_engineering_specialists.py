from copy import deepcopy
import sys
from pathlib import Path
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_specialists import investigate
from v14.execution_binding import ExecutionBindingError


class SpecialistTests(unittest.TestCase):
    def setUp(self):
        self.job = {'id': 'job', 'request': {'execution_mode': 'full'},
                    'data': {'head_sha': 'a' * 40, 'tree': {'file.py': {'mode': '100644', 'sha': 'b' * 40}}}}
        self.backend = SimpleNamespace(model=Mock(), cancellation=lambda job: threading.Event(), save_specialists=self.save)
        self.tasks = [{'role': 'source explorer', 'evidence': {'question': 'implementation'}},
                      {'role': 'test investigator', 'evidence': {'question': 'tests'}}]

    def save(self, job, value):
        job['data']['specialists'] = deepcopy(value)

    def test_two_workers_use_isolated_contexts_and_reuse_durable_findings(self):
        barrier = threading.Barrier(2)
        identities = []
        def model(local, role, evidence):
            identities.append(id(local['data']))
            local['data']['private_' + role] = True
            barrier.wait(timeout=2)
            self.assertEqual(1, len([key for key in local['data'] if key.startswith('private_')]))
            return {'findings': [role], 'paths': ['file.py']}
        self.backend.model.side_effect = model
        result = investigate(self.backend, self.job, self.tasks)
        self.assertEqual(2, len(set(identities)))
        self.assertEqual(2, len(result))
        self.assertTrue(all(row['authority'] == 'SUGGESTIONS_ONLY' for row in result))
        self.assertEqual(result, investigate(self.backend, self.job, self.tasks))
        self.assertEqual(2, self.backend.model.call_count)
        self.assertFalse(any(key.startswith('private_') for key in self.job['data']))

    def test_worker_cannot_execute_commands_or_edit(self):
        for action in ('check', 'commit', 'write'):
            with self.subTest(action=action):
                self.backend.model.return_value = {'workspace_action': {'action': action, 'head_sha': 'a' * 40}}
                self.job['data'].pop('specialists', None)
                with patch('v14.engineering_specialists.act') as workspace:
                    result = investigate(self.backend, self.job, self.tasks[:1])
                workspace.assert_not_called()
                self.assertEqual('ACTION_REJECTED', result[0]['status'])

    def test_typed_model_and_workspace_failures_escape_without_format_repair(self):
        for boundary in ('model', 'workspace'):
            for code in ('EXECUTION_CONTEXT_EXHAUSTED', 'EXECUTION_BINDING_REVOKED', 'EXECUTION_OWNER_CHANGED'):
                with self.subTest(boundary=boundary, code=code):
                    self.job['data'].pop('specialists', None)
                    failure = ExecutionBindingError(code, 'Typed stop', observation={'reasonCode': code})
                    self.backend.model = Mock()
                    self.backend.root = Mock(return_value='/repos/owner/repo')
                    self.backend.api = Mock(side_effect=failure)
                    if boundary == 'model':
                        self.backend.model.side_effect = failure
                    else:
                        self.backend.model.return_value = {'workspace_action': {
                            'action': 'read', 'head_sha': self.job['data']['head_sha'], 'path': 'file.py'}}
                    with self.assertRaises(ExecutionBindingError) as raised:
                        investigate(self.backend, self.job, self.tasks[:1])
                    self.assertIs(failure, raised.exception)
                    self.assertEqual({'reasonCode': code}, raised.exception.observation)
                    self.assertFalse(raised.exception.retryable)
                    self.assertEqual(1, self.backend.model.call_count)
                    self.assertFalse(self.backend.model.call_args.args[2]['format_repair'])
                    self.assertEqual(int(boundary == 'workspace'), self.backend.api.call_count)
                    self.assertNotIn('specialists', self.job['data'])

    def test_inspection_is_bounded_and_revision_change_invalidates_saved_work(self):
        self.backend.model.return_value = {'workspace_action': {'action': 'paths', 'query': 'file', 'head_sha': 'a' * 40}}
        first = investigate(self.backend, self.job, self.tasks[:1])
        self.assertEqual('LIMIT_REACHED', first[0]['status'])
        self.assertEqual(3, self.backend.model.call_count)
        self.job['data']['head_sha'] = 'c' * 40
        self.backend.model.return_value = {'findings': ['new revision'], 'paths': ['file.py']}
        second = investigate(self.backend, self.job, self.tasks[:1])
        self.assertEqual('c' * 40, second[0]['head_sha'])
        self.assertEqual(4, self.backend.model.call_count)

    def test_standard_mode_and_cancellation_dispatch_no_model(self):
        self.job['request']['execution_mode'] = 'standard'
        self.assertEqual([], investigate(self.backend, self.job, self.tasks))
        self.job['request']['execution_mode'] = 'full'
        cancelled = threading.Event()
        cancelled.set()
        self.backend.cancellation = lambda job: cancelled
        with self.assertRaises(InterruptedError):
            investigate(self.backend, self.job, self.tasks)
        self.backend.model.assert_not_called()


if __name__ == '__main__':
    unittest.main()
