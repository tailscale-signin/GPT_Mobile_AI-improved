from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from v14 import workspace_hooks, workspace_workers
from v14.engineering_validation import patch_identity


class LifecycleTests(unittest.TestCase):
    def test_hook_uses_exact_patch_and_command_evidence(self):
        head = 'a' * 40
        original = [{'path': 'src/a.py', 'content': 'print(1)  \r\npass', 'expected_blob_sha': None}]
        job = {'data': {'head_sha': head, 'changes': original}}
        with patch.object(workspace_hooks, 'admitted', return_value=(['*.py'], [0])):
            changes, receipt = workspace_hooks.after_edit(job, original)
            self.assertEqual('print(1)\r\npass\r\n', changes[0]['content'])
            self.assertEqual(['src/a.py'], receipt['formatted'])
            self.assertNotEqual(changes, original)
            val = {'commands': [{'command_index': 0, 'exit_code': 0, 'output_sha256': 'd' * 64}],
                   'patch_identity': patch_identity(head, changes)}
            self.assertFalse(workspace_hooks.before_publish(job, val)['passed'])
            job['data']['changes'] = changes
            self.assertTrue(workspace_hooks.before_publish(job, val)['passed'])
            self.assertFalse(workspace_hooks.before_publish(job, dict(val, commands=[]))['passed'])

    def test_worker_continues_private_context_without_publishing(self):
        job = {'id': 'job', 'data': {'head_sha': 'a' * 40, 'tree': {}, 'files': {}, 'plan': []}}
        model = Mock(side_effect=[{'workspace_action': {'action': 'paths', 'head_sha': 'a' * 40}},
                                  {'findings': 'The candidate has no matching source paths.'}])
        store = Mock()
        store.put_json.side_effect = lambda job, value, kind: value
        store.json_value.side_effect = lambda job, value: value
        backend = SimpleNamespace(private_artifacts=store, model=model)
        first = workspace_workers.advance(backend, job, dict(worker_id='trace', role='investigator', objective='Find target', budget=2))
        self.assertEqual('paths', first['last_action'])
        self.assertFalse('context_ref' in first)
        result = workspace_workers.advance(backend, job, dict(worker_id='trace'))
        self.assertEqual('COMPLETED', result['state'])
        self.assertEqual(result, workspace_workers.advance(backend, job, dict(worker_id='trace')))
        self.assertEqual(2, model.call_count)
        self.assertNotIn('commit', result['permitted_tools'])
        self.assertEqual(first['last_receipt'], model.call_args.args[2]['workspace_receipts'][0]['receipt_sha256'])
        with self.assertRaises(ValueError):
            workspace_workers.advance(backend, job, dict(worker_id='trace', role='implementation worker'))

    def test_issue_workflow_resumes_from_its_dependency_checkpoints(self):
        from v14.workspace_guidance import batch_workflow
        job = {'data': {'head_sha': 'head'}}
        create = dict(action='workflow_batch', items=[{'id':'issue_1'}, {'id':'issue_2','depends_on':['issue_1']}])
        state = batch_workflow(job, create)
        self.assertFalse(state['items'][1]['ready'])
        for step in range(len(state['steps'])):
            receipt = dict(head_sha='head', receipt_sha256=str(step), action='check')
            job['data']['workspace_receipts'] = [receipt]
            event = dict(action='workflow_batch_step', id='issue_1', step=step, receipt_sha256=str(step))
            result = batch_workflow(job, event)
            self.assertEqual(result, batch_workflow(job, event))
        self.assertEqual(1, batch_workflow(job, create)['completed_items'])
        self.assertTrue(batch_workflow(job, {'action':'workflow_batch_status'})['items'][1]['ready'])
