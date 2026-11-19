import base64
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from v14.engineering_backend import blob_sha
from v14.workspace_terminal import act as terminal
from v14.workspace_timeline import corrective_candidate


class TimelineTests(TestCase):
    def test_comparison_pages_are_complete_and_bound_to_both_test_results(self):
        from v14.engineering_workspace_checks import compare_page
        first = [{'path':'main.kt', 'content':'old\n'}, {'path':'removed.txt', 'content':'gone\n'}]
        second = [{'path':'main.kt', 'content':'new\n' + 'continued\n'*4000}]
        checks = {'left':{'status':'FAILED', 'patch_identity':'old-patch'}, 'right':{'status':'PASSED', 'patch_identity':'new-patch'}}
        job = {'data':{'workspace_checks':checks, 'tree':{}}}
        with patch('v14.engineering_workspace_checks.retained_candidate', side_effect=lambda b,j,key: {'left':first, 'right':second}[key]):
            offset, parts = 0, []
            while offset is not None:
                row = compare_page(None, job, 'left', 'right', offset)
                self.assertEqual(('FAILED', 'PASSED'), (row['left_status'], row['status']))
                self.assertEqual(('old-patch', 'new-patch'), (row['left_patch_identity'], row['right_patch_identity']))
                self.assertLessEqual(len(row['diff']), 16000)
                parts.append(row['diff'])
                offset = row['next_offset']
            patch_text = ''.join(parts)
            self.assertGreater(len(parts), 2)
            self.assertEqual(4000, patch_text.count('+continued\n'))
            self.assertIn('-gone\n', patch_text)
            with self.assertRaises(ValueError):
                compare_page(None, job, 'left', 'right', len(patch_text)+1)

    def test_corrective_commit_preserves_concurrent_edits_and_file_modes(self):
        target, parent, head = (letter * 40 for letter in 'abc')
        old = {'x': {'sha': blob_sha('old'), 'mode': '100755'}}
        new = {'x': {'sha': blob_sha('new'), 'mode': '100644'}}
        current = dict(new, unrelated={'sha': blob_sha('external'), 'mode': '100644'})
        def api(path, **kwargs):
            if '/compare/' in path:
                return {'status': 'ahead', 'merge_base_commit': {'sha': target}}
            if '/blobs/' in path:
                text = 'old' if path.endswith(old['x']['sha']) else 'new'
                return {'encoding': 'base64', 'content': base64.b64encode(text.encode()).decode(), 'size': len(text)}
            return {'sha': target, 'parents': [{'sha': parent}]}
        backend = SimpleNamespace(root=lambda job: '/repo', api=api, tree=lambda job, sha: old if sha == parent else new)
        job = {'data': {'head_sha': head, 'tree': current, 'files': {}}}
        self.assertEqual([dict(path='x', content='old', expected_blob_sha=new['x']['sha'], mode='100755')],
                         corrective_candidate(backend, job, target))
        current['x'] = {'sha': blob_sha('external'), 'mode': '100644'}
        with self.assertRaisesRegex(ValueError, 'later edit'):
            corrective_candidate(backend, job, target)

    def test_two_checkouts_can_switch_only_on_matching_policy_and_revision(self):
        a, b = (dict(session_id=key, head_sha='ref', policy_sha256='policy') for key in ('a', 'b'))
        job = {'request': {'task_scope': 'implement', 'repository': 'a/b'}, 'data': {
            'head_sha': 'ref', 'terminal_session': a, 'terminal_branches': {'a': a, 'b': b}}, 'deadline': 1000}
        with patch('v14.workspace_terminal.catalog', return_value=('policy', [])), patch(
                'v14.workspace_terminal.load_policy', return_value=({'workspace_terminal': True}, None, None, None, None)):
            self.assertEqual('a', terminal(Mock(), job, {'action': 'terminal_timeline'})['active'])
            self.assertEqual('SELECTED', terminal(Mock(), job, {'action': 'terminal_switch', 'session_id': 'b'})['state'])
            self.assertEqual('b', terminal(Mock(), job, {'action': 'terminal_timeline'})['active'])
            job['data']['head_sha'] = 'changed'
            with self.assertRaises(ValueError):
                terminal(Mock(), job, {'action': 'terminal_switch', 'session_id': 'a'})
