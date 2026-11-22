import unittest
import json
import time
from types import SimpleNamespace
from unittest.mock import Mock

from v14.workspace_discovery import instruction, tools


class WorkspaceDiscoveryTests(unittest.TestCase):
    def job(self, profile):
        return {'data': {'head_sha': 'a' * 40, 'admission': {'policy_sha256': 'policy'},
                         'workspace_tool_profile': {'profile': profile, 'head_sha': 'a' * 40}}}

    def test_review_and_retrieval_do_not_disclose_execution_controls(self):
        for profile in ('review', 'retrieve', 'plan'):
            job = self.job(profile)
            text = instruction(job)
            self.assertIn('read', tools(job)['actions'])
            self.assertNotIn('check', tools(job)['actions'])
            self.assertNotIn('terminal_start', text)
            self.assertNotIn('agent_start', text)
            self.assertNotIn('run_test', text)
            self.assertFalse(tools(job)['grants_authority'])
        self.assertIn('compare_candidates', instruction(self.job('review')))

    def test_editor_and_test_profiles_expose_only_their_approved_check_workflow(self):
        edit = instruction(self.job('edit'))
        test = instruction(self.job('test'))
        self.assertIn('check_index', edit)
        self.assertIn('discover_tests', test)
        self.assertNotIn('terminal_start', edit)
        self.assertNotIn('agent_start', test)
        self.assertIn('requirements', edit)

    def test_moved_source_does_not_reuse_a_stale_profile(self):
        job = self.job('review')
        job['data']['head_sha'] = 'b' * 40
        self.assertEqual('default', tools(job)['profile'])
        text = instruction(job)
        self.assertIn('Never manufacture consent', text)
        self.assertIn('Only host-approved commands execute', text)

    def test_specialist_prompt_does_not_add_hidden_parent_history_graph_or_agent_context(self):
        from v14.engineering_backend import GitHubEngineeringBackend
        backend = GitHubEngineeringBackend(SimpleNamespace())
        backend.api = Mock(return_value={'permissions': {'pull': True}})
        backend.recall_memory = Mock(return_value={'private': 'outside worker scope'})
        backend.recall_graph = Mock(return_value={'source': 'outside worker scope'})
        backend.agent_runs = SimpleNamespace(list=Mock(return_value=[{'private': 'outside worker scope'}]))
        captured = []
        def execute(job, role, request, cancellation):
            captured.append(request)
            return {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({
                'protocol_version': 1, 'head_sha': 'a' * 40, 'findings': 'Inspected the admitted source'})}}]}
        backend.model_executor = execute
        job = self.job('review')
        job.update(id='scope-test', deadline=time.time() + 60, conversation_context={'private': 'outside worker scope'},
                   request={'repository': 'owner/repo', 'objective': 'Review scoped source', 'model': 'local'})
        job['data'].update(tree={}, files={}, active_instructions=[])
        backend._model_once(job, 'workspace specialist', {'worker_objective': 'Review scoped source', 'permitted_tools': ['read']})
        backend.recall_memory.assert_not_called()
        backend.recall_graph.assert_not_called()
        backend.agent_runs.list.assert_not_called()
        self.assertNotIn('outside worker scope', json.dumps(captured))


if __name__ == '__main__':
    unittest.main()
