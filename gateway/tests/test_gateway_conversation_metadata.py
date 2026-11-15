"""Real journal/API regressions for v17 saved-message organization."""
import importlib
import sqlite3
import unittest
from contextlib import closing
from unittest.mock import patch

import test_gateway_v14_jobs as fixtures
from v14.conversation_metadata import bookmark, saved_messages, update
from v14.conversations import Conversations


class MetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    setUp = fixtures.JournalTests.setUp
    client = fixtures.JournalTests.client
    completion = fixtures.JournalTests.completion

    def create(self, client, ident='one', text='Search this transcript 100%_exact'):
        payload = {'model': 'local', 'stream': False, 'messages': [{'role': 'user', 'content': text}],
                   'gateway': {'client_conversation_id': ident, 'command_id': 'command-' + ident,
                               'profile_id': 'desktop-profile', 'store_conversation': True}}
        result = client.post('/v1/chat/completions', json=payload)
        self.assertEqual(200, result.status_code, result.text)
        return result.headers['x-gateway-conversation-id']

    def test_organization_does_not_change_transcript_revision_or_dispatch_work(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            ident = self.create(client)
            before = client.get('/gateway/conversations/' + ident).json()
            changed = client.patch('/gateway/conversations/' + ident + '/metadata', json={
                'expected_metadata_revision': 0, 'title': 'Repository repair', 'archived': True,
                'favorite': True, 'tags': ['Work', 'Work', ' Review ']}).json()
            self.assertEqual(1, changed['metadata_revision'])
            self.assertEqual(['Work', 'Review'], changed['tags'])
            after = client.get('/gateway/conversations/' + ident).json()
            self.assertEqual(before['revision'], after['revision'])
            self.assertEqual('Repository repair', after['title'])
            self.assertTrue(after['favorite'])
            self.assertEqual([], client.get('/gateway/conversations?archived=false').json()['items'])
            self.assertEqual(1, len(client.get('/gateway/conversations?archived=true&favorite=true').json()['items']))
            self.assertEqual(1, len(client.get('/gateway/conversations').json()['items']))
            stale = client.patch('/gateway/conversations/' + ident + '/metadata', json={'expected_metadata_revision': 0, 'title': 'stale'})
            self.assertEqual(409, stale.status_code)
            self.assertEqual(1, model.call_count)

    def test_search_treats_wildcards_literally_and_cursor_binds_filters(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            self.create(client)
            self.create(client, 'two', 'No percentage here')
            exact = client.get('/gateway/conversations', params={'query': '100%_exact'}).json()
            self.assertEqual(1, len(exact['items']))
            self.assertEqual([], client.get('/gateway/conversations', params={'query': '100%_absent'}).json()['items'])
            page = client.get('/gateway/conversations?limit=1&archived=false').json()
            self.assertTrue(page['has_more'])
            altered = client.get('/gateway/conversations', params={'cursor': page['next_cursor'], 'archived': 'true'})
            self.assertEqual(400, altered.status_code)
            self.assertEqual(400, client.get('/gateway/conversations?archived=maybe').status_code)

    def test_bookmarks_are_idempotent_principal_scoped_and_paginated(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            ident = self.create(client)
            messages = client.get('/gateway/conversations/' + ident + '/messages').json()['messages']
            for item in messages:
                url = '/gateway/conversations/' + ident + '/bookmarks/' + item['logical_message_id']
                self.assertEqual(200, client.put(url, json={'saved': True}).status_code)
                self.assertEqual(200, client.put(url, json={'saved': True}).status_code)
            first = client.get('/gateway/saved-messages?limit=1').json()
            second = client.get('/gateway/saved-messages', params={'limit': 1, 'cursor': first['next_cursor']}).json()
            self.assertNotEqual(first['items'][0]['message_id'], second['items'][0]['message_id'])
            self.assertFalse(second['has_more'])
            self.assertEqual(1, len(client.get('/gateway/saved-messages', params={'query': 'SQLite reply'}).json()['items']))
            store = Conversations(self.runtime)
            self.assertEqual([], saved_messages(store, 'unrelated')['items'])
            self.assertIsNone(bookmark(store, 'unrelated', ident, messages[0]['logical_message_id'], False))
            self.assertIsNone(update(store, 'unrelated', ident, {'expected_metadata_revision': 0, 'title': 'Foreign'}))
            self.assertEqual(2, len(client.get('/gateway/saved-messages').json()['items']))
            client.put('/gateway/conversations/' + ident + '/bookmarks/' + messages[0]['logical_message_id'], json={'saved': False})
            self.assertEqual(1, len(client.get('/gateway/saved-messages').json()['items']))
            self.assertEqual(1, model.call_count)

    def test_mutations_reject_changed_identity_and_invalid_fields(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            ident = self.create(client)
            url = '/gateway/conversations/' + ident + '/metadata'
            data = {'expected_metadata_revision': 0, 'favorite': True}
            wrong = {'X-Gateway-Installation-ID': 'other', 'X-Gateway-Principal-ID': 'local:single-user'}
            self.assertEqual(409, client.patch(url, json=data, headers=wrong).status_code)
            for value in ({'expected_metadata_revision': True, 'favorite': True},
                          {'expected_metadata_revision': 0, 'title': 'invalid\nname'},
                          {'expected_metadata_revision': 0, 'favorite': 1},
                          {'expected_metadata_revision': 0, 'tags': ['x' * 33]}):
                self.assertEqual(400, client.patch(url, json=value).status_code)
            self.assertFalse(client.get('/gateway/conversations/' + ident).json()['favorite'])

    def test_additive_migration_preserves_saved_transcript_and_read_only_polling(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            ident = self.create(client)
            original = client.get('/gateway/conversations/' + ident + '/messages').json()['messages']
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute('DROP TABLE gateway_message_bookmarks')
                db.execute('DROP TABLE gateway_conversation_metadata')
                db.execute("DELETE FROM gateway_installation WHERE key='conversation_metadata_schema'")
            self.assertEqual(200, client.get('/gateway/conversations').status_code)
            self.assertEqual(original, client.get('/gateway/conversations/' + ident + '/messages').json()['messages'])
            with patch.object(Conversations, 'tx', side_effect=AssertionError('Ordinary polling must be read-only')):
                self.assertEqual(200, client.get('/gateway/conversations').status_code)
                self.assertEqual(200, client.get('/gateway/saved-messages').status_code)

    def test_remote_organization_stays_inside_the_existing_principal_and_scope(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from types import SimpleNamespace
        from v14.config import Config
        from v14.identity import Admission
        from v14.job_catalog import install_routes
        from v14.service import ServiceState
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            ident = self.create(client)
            message = client.get('/gateway/conversations/' + ident + '/messages').json()['messages'][0]['logical_message_id']
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute('UPDATE gateway_conversations SET principal=? WHERE id=?', ('tailnet:owner', ident))
            users = {'owner@test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools']},
                     'other@test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools']},
                     'no-history@test': {'principalId': 'owner', 'scopes': ['chat', 'tools']}}
            app = FastAPI()
            app.add_middleware(Admission, config=Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(), trusted_users=users))
            install_routes(app, Conversations(self.runtime), self.gateway.engineering, ServiceState(), SimpleNamespace())
            with TestClient(app, client=('127.0.0.1', 12000)) as remote:
                owner = {'Tailscale-User-Login': 'owner@test'}
                path = '/gateway/conversations/' + ident
                self.assertEqual(200, remote.patch(path + '/metadata', json={'expected_metadata_revision': 0, 'favorite': True}, headers=owner).status_code)
                self.assertEqual(200, remote.put(path + '/bookmarks/' + message, json={'saved': True}, headers=owner).status_code)
                self.assertEqual(1, len(remote.get('/gateway/saved-messages', headers=owner).json()['items']))
                other = {'Tailscale-User-Login': 'other@test'}
                self.assertEqual([], remote.get('/gateway/saved-messages', headers=other).json()['items'])
                self.assertEqual(404, remote.put(path + '/bookmarks/' + message, json={'saved': False}, headers=other).status_code)
                self.assertEqual(404, remote.patch(path + '/metadata', json={'expected_metadata_revision': 1, 'favorite': False}, headers=other).status_code)
                self.assertEqual(403, remote.get('/gateway/saved-messages', headers={'Tailscale-User-Login': 'no-history@test'}).status_code)


if __name__ == '__main__':
    unittest.main()
