import base64
import copy
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from v14.engineering_backend import blob_sha
from v14.workspace_board import rank_candidates, source_page, tree_page


class WorkspaceSourceTests(unittest.TestCase):
    def setUp(self):
        self.content = ''.join(f'line {number}\n' for number in range(1, 231))
        self.sha = blob_sha(self.content)
        self.job = {'id': 'job', 'principal': 'owner', 'revision': 4, 'data': {
            'head_sha': 'a' * 40, 'tree': {'src/a.py': {'mode': '100644', 'sha': self.sha},
                                         'link': {'mode': '120000', 'sha': 'b' * 40}}, 'files': {}}}
        self.backend = SimpleNamespace(root=lambda job: '/repos/owner/repo', api=Mock(return_value={
            'encoding': 'base64', 'size': len(self.content.encode()),
            'content': base64.b64encode(self.content.encode()).decode()}))
        self.service = SimpleNamespace(backend=self.backend, evidence_access=Mock(side_effect=lambda principal, job: copy.deepcopy(self.job)),
                                       artifacts=SimpleNamespace(restore_stage=Mock()))

    def test_source_pages_preserve_observed_blob_and_line_cursor(self):
        first = source_page(self.service, 'owner', 'job', 'a' * 40, 'src/a.py', 1, 200)
        second = source_page(self.service, 'owner', 'job', 'a' * 40, 'src/a.py', first['next_line'], 200)
        self.assertEqual(self.content, first['content'] + second['content'])
        self.assertEqual((self.sha, 200, 201, 230, None),
                         (first['blob_sha'], first['end_line'], first['next_line'], second['end_line'], second['next_line']))
        self.assertEqual(4, self.service.evidence_access.call_count)

    def test_blob_mismatch_nonregular_unseen_and_moved_head_are_rejected(self):
        for path in ('link', '../src/a.py', 'missing.py'):
            with self.assertRaises(ValueError):
                source_page(self.service, 'owner', 'job', 'a' * 40, path)
        with self.assertRaises(ValueError):
            source_page(self.service, 'owner', 'job', 'c' * 40, 'src/a.py')
        self.backend.api.return_value['content'] = base64.b64encode(b'changed source').decode()
        with self.assertRaises(ValueError):
            source_page(self.service, 'owner', 'job', 'a' * 40, 'src/a.py')

    def test_reauthorization_after_read_rejects_source_change(self):
        moved = copy.deepcopy(self.job)
        moved['data']['head_sha'] = 'c' * 40
        self.service.evidence_access.side_effect = [copy.deepcopy(self.job), moved]
        with self.assertRaises(ValueError):
            source_page(self.service, 'owner', 'job', 'a' * 40, 'src/a.py')

    def test_imported_modification_and_deletion_read_exact_base_before_rendering_diff(self):
        from v14.engineering_checkpoints import candidate_details
        check_id = 'd' * 64
        self.job['data']['workspace_checks'] = {check_id: {'status': 'UNVERIFIED'}}
        for content in (self.content.replace('line 1\n', 'fixed line\n', 1), None):
            with self.subTest(deleted=content is None), patch('v14.engineering_checkpoints.retained_candidate',
                    return_value=[{'path': 'src/a.py', 'content': content, 'expected_blob_sha': self.sha}]):
                result = candidate_details(self.service, 'owner', 'job', check_id)
            self.assertIn('-line 1\n', result['diff'])
            if content is not None:
                self.assertIn('+fixed line\n', result['diff'])
                self.assertNotIn('+line 200\n', result['diff'])
            else:
                self.assertIn('-line 200\n', result['diff'])
            self.assertEqual('UNVERIFIED', result['status'])

    def test_tree_paging_is_complete_literal_and_excludes_symlinks(self):
        self.job['data']['tree'].update({f'src/z{n:03}.py': {'sha': 'b' * 40, 'mode': '100644'} for n in range(99)})
        first = tree_page(self.service, 'owner', 'job', 'a' * 40, 'SRC/')
        second = tree_page(self.service, 'owner', 'job', 'a' * 40, 'src/', first['next_offset'])
        paths = first['paths'] + second['paths']
        self.assertEqual(100, len(paths))
        self.assertEqual(100, len({row['path'] for row in paths}))
        self.assertIsNone(second['next_offset'])
        self.assertEqual([], tree_page(self.service, 'owner', 'job', 'a' * 40, '*')['paths'])
        self.assertTrue(all(row['blob_sha'] in {self.sha, 'b' * 40} for row in paths))
        with self.assertRaises(ValueError):
            tree_page(self.service, 'owner', 'job', 'a' * 40, '', 102)


class CandidateComparisonTests(unittest.TestCase):
    def test_imported_unvalidated_agents_never_count_as_the_same_executed_check(self):
        checks = {key: {'source_sha': 'a' * 40, 'policy_sha256': 'policy', 'check_index': None,
                        'patch_identity': key, 'status': 'UNVERIFIED'} for key in ('a', 'b')}
        job = {'data': {'head_sha': 'a' * 40, 'workspace_checks': checks}}
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [{'index': 0}])), \
                patch('v14.engineering_workspace_checks.retained_candidate', return_value=[{'path': 'shared.py'}]):
            result = rank_candidates(None, job, {'check_ids': ['a', 'b']})
        self.assertFalse(result['comparable'])
        self.assertEqual('NOT_EXECUTED', result['reason'])
        self.assertTrue(all(not row['executed'] for row in result['candidates']))
        self.assertIsNone(result['selected_candidate'])

    def test_different_check_contracts_cannot_be_ranked_as_equal(self):
        checks = {key: {'source_sha': 'a' * 40, 'policy_sha256': 'policy', 'check_index': index,
                        'patch_identity': key, 'status': 'PASSED', 'exit_code': 0}
                  for key, index in [('a', 0), ('b', 1)]}
        job = {'data': {'head_sha': 'a' * 40, 'workspace_checks': checks}}
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [{'index': 0}, {'index': 1}])), \
                patch('v14.engineering_workspace_checks.retained_candidate', return_value=[{'path': 'shared.py'}]):
            result = rank_candidates(None, job, {'check_ids': ['a', 'b']})
        self.assertFalse(result['comparable'])
        self.assertEqual('DIFFERENT_CHECK_CONTRACTS', result['reason'])
        self.assertEqual(['shared.py'], result['overlaps'][0]['paths'])
        self.assertIsNone(result['selected_candidate'])
        self.assertTrue(all(not row['complete_validation'] for row in result['candidates']))
        self.assertTrue(all(row['model_quality_score'] is None for row in result['candidates']))

    def test_equal_checks_rank_actual_pass_without_selecting_or_merging(self):
        checks = {key: {'source_sha': 'a' * 40, 'policy_sha256': 'policy', 'check_index': 0,
                        'patch_identity': key, 'status': status, 'exit_code': code}
                  for key, status, code in [('fail', 'FAILED', 1), ('pass', 'PASSED', 0)]}
        job = {'data': {'head_sha': 'a' * 40, 'workspace_checks': checks}}
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [{'index': 0}, {'index': 1}])), \
                patch('v14.engineering_workspace_checks.retained_candidate', side_effect=lambda backend, job, key: [{'path': key + '.py'}]):
            result = rank_candidates(None, job, {'check_ids': ['fail', 'pass']})
        self.assertTrue(result['comparable'])
        self.assertEqual(['pass', 'fail'], [row['check_id'] for row in result['candidates']])
        self.assertFalse(result['automatic_merge'])
        self.assertIsNone(result['selected_candidate'])


if __name__ == '__main__':
    unittest.main()
