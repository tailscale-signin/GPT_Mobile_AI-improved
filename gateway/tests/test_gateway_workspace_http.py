import base64
import unittest
from unittest.mock import Mock

import test_gateway_v14_engineering_http as http_fixtures
from v14.engineering_backend import blob_sha


class WorkspaceHTTPTests(unittest.TestCase):
    setUp = http_fixtures.EngineeringHTTPTests.setUp
    tearDown = http_fixtures.EngineeringHTTPTests.tearDown

    def workspace(self):
        created = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, created.status_code, created.text)
        job, lease, _ = self.store.claim(created.json()['id'])
        self.content = 'answer = 42\n'
        job['data'].update(head_sha='a' * 40, tree={'file': {'sha': blob_sha(self.content), 'mode': '100644'}}, files={}, plan={})
        self.store.complete(job, lease, 'IMPLEMENT', {})
        self.backend.root = lambda job: '/repos/owner/repo'
        self.backend.identity = Mock()
        self.backend.api = Mock(side_effect=lambda path, **kwargs: {
            'permissions': {'pull': True}} if path == '/repos/owner/repo' else {
            'encoding': 'base64', 'size': len(self.content), 'content': base64.b64encode(self.content.encode()).decode()})
        self.job = self.store.get(job['id'])
        return '/gateway/engineering/jobs/' + job['id'] + '/workspace'

    def payload(self):
        return {'command_id': 'workspace-http-1', 'head_sha': 'a' * 40, 'revision': self.job['revision'],
                'action': 'review_draft', 'arguments': {'draft_id': 'private-note', 'body': 'Exact source review'}}

    def test_authenticated_board_source_and_queue_are_reachable_in_both_routes(self):
        path = self.workspace()
        for prefix in ('', '/v1'):
            board = self.client.get(prefix + path, headers=self.headers)
            self.assertEqual(200, board.status_code, board.text)
            self.assertEqual(self.job['id'], board.json()['job_id'])
            self.assertIsInstance(board.json()['agents'], list)
            self.assertEqual('GATEWAY', board.json()['execution_owner'])
        header = dict(self.headers, **{'X-Gateway-User-Action': 'workspace-command'})
        saved = self.client.post(path, headers=header, json=self.payload())
        self.assertEqual(200, saved.status_code, saved.text)
        self.assertEqual('COMPLETED', saved.json()['status'])
        observed = self.client.get(path + '/commands/workspace-http-1', headers=self.headers)
        self.assertEqual('COMPLETED', observed.json()['status'])
        self.assertEqual('Exact source review', observed.json()['result']['body'])
        tree = self.client.get(path + '/tree', headers=self.headers, params={'head_sha': 'a' * 40}).json()
        source = self.client.get(path + '/source', headers=self.headers,
                                 params={'head_sha': 'a' * 40, 'path': 'file'}).json()
        self.assertEqual(tree['paths'][0]['blob_sha'], source['blob_sha'])
        self.assertEqual(self.content, source['content'])

    def test_missing_action_marker_foreign_principal_and_stale_source_do_not_write(self):
        path = self.workspace()
        self.assertEqual(403, self.client.post(path, headers=self.headers, json=self.payload()).status_code)
        foreign = {'Tailscale-User-Login': 'other@example.test', 'X-Gateway-User-Action': 'workspace-command'}
        self.assertEqual(403, self.client.post(path, headers=foreign, json=self.payload()).status_code)
        self.assertEqual(403, self.client.get(path, headers=foreign).status_code)
        header = dict(self.headers, **{'X-Gateway-User-Action': 'workspace-command'})
        self.assertEqual(409, self.client.post(path, headers=header, json=dict(self.payload(), head_sha='b' * 40)).status_code)
        self.assertEqual([], self.service.workspace_commands.list(self.job))
        absent = self.client.get(path + '/commands/workspace-http-1', headers=self.headers).json()
        self.assertEqual('NOT_FOUND', absent['status'])
        self.assertTrue(absent['confirmed_absent'])

    def test_repository_revocation_blocks_saved_source_and_draft_readback(self):
        path = self.workspace()
        self.backend.api.return_value = {'permissions': {'pull': False}}
        self.backend.api.side_effect = None
        for suffix in ('', '/tree?head_sha=' + 'a' * 40, '/source?head_sha=' + 'a' * 40 + '&path=file', '/commands/workspace-http-1'):
            self.assertEqual(403, self.client.get(path + suffix, headers=self.headers).status_code)


if __name__ == '__main__':
    unittest.main()
