import copy
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from cryptography.exceptions import InvalidTag

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringStore
from v14.engineering_memory import EngineeringMemory, requirements
from v14.evidence import EvidenceLedger
from v14.engineering_backup import backup, restore


class EngineeringMemoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.database = self.root / 'jobs.db'
        self.runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(), LLAMA_BASE='http://127.0.0.1:8080',
                                       _durable_connect=lambda: sqlite3.connect(self.database))
        self.store = EngineeringStore(self.runtime, lambda: 1000.)
        self.memory = EngineeringMemory(self.store, self.runtime)
        self.job = {'id': 'job', 'principal': 'owner', 'revision': 1,
                    'request': {'repository': 'owner/repo', 'branch': 'fix', 'model': 'local', 'profile_id': 'profile', 'objective': 'fix tests'},
                    'data': {'actor_id': 123, 'head_sha': 'a' * 40}}

    def tearDown(self):
        self.temp.cleanup()

    def test_encrypted_restart_retrieval_and_every_permission_scope(self):
        self.memory.remember(self.job, 'decision', {'content': 'private repository architecture secret sample'})
        self.assertNotIn(b'private repository architecture', self.database.read_bytes())
        restored = EngineeringMemory(self.store, self.runtime)
        recall = restored.recall(self.job, 'architecture')
        self.assertEqual(1, len(recall['records']))
        self.assertFalse(recall['current_evidence'])
        for section, key, value in [('request', 'repository', 'other/repo'), ('request', 'branch', 'other'), ('request', 'profile_id', 'other'), ('data', 'actor_id', 456), ('data', 'permission_scope', 'changed')]:
            other = copy.deepcopy(self.job)
            other[section][key] = value
            self.assertEqual([], restored.recall(other, 'architecture')['records'])
        other = dict(self.job, principal='other')
        self.assertEqual([], restored.recall(other, 'architecture')['records'])
        changed = copy.deepcopy(self.job)
        changed['data']['head_sha'] = 'b' * 40
        self.assertTrue(restored.recall(changed, 'architecture')['records'][0]['refresh_required'])

    def test_cloud_and_session_only_do_not_recall_repository_memory(self):
        self.memory.remember(self.job, 'code', {'content': 'sensitive source'})
        self.runtime.LLAMA_BASE = 'https://cloud.example.com'
        self.assertEqual([], self.memory.recall(self.job, 'source')['records'])
        self.runtime.LLAMA_BASE = 'http://localhost:8080'
        self.job['request']['memory_mode'] = 'SESSION_ONLY'
        self.assertEqual([], self.memory.recall(self.job, 'source')['records'])
        self.memory.remember(self.job, 'code', {'content': 'never save'})
        self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_memory').fetchone()[0]))

    def test_key_loss_fails_closed(self):
        self.memory.remember(self.job, 'code', {'content': 'private'})
        key = self.database.with_suffix('.engineering.key')
        if os.name != 'nt':
            self.assertEqual(0o600, key.stat().st_mode & 0o777)
        key.unlink()
        with self.assertRaises(ValueError):
            self.memory.recall(self.job, 'private')
        with self.assertRaises(ValueError):
            self.memory.remember(self.job, 'code', {'content': 'new'})
        self.assertFalse(key.exists())

    def test_modified_encrypted_memory_is_not_recalled(self):
        self.memory.remember(self.job, 'code', {'content': 'private'})
        def modify(db):
            record = json.loads(db.execute('SELECT payload FROM engineering_memory').fetchone()[0])
            record['ciphertext'] = ('A' if record['ciphertext'][0] != 'A' else 'B') + record['ciphertext'][1:]
            db.execute('UPDATE engineering_memory SET payload=?', (json.dumps(record),))
        self.store.tx(modify)
        with self.assertRaises(InvalidTag):
            self.memory.recall(self.job, 'private')

    def test_password_backup_restores_database_ledger_and_encryption_metadata(self):
        self.memory.remember(self.job, 'code', {'content': 'sensitive source'})
        receipt = EvidenceLedger(self.store).append(self.job, 'api', {'result': {'count': 7}})
        archive = self.root / 'saved.enc'
        backup(self.database, archive, 'long private password')
        self.assertNotIn(b'sensitive source', archive.read_bytes())
        with self.assertRaises(InvalidTag):
            restore(archive, self.root / 'wrong', 'wrong private password')
        self.assertFalse((self.root / 'wrong').exists())
        recovered = restore(archive, self.root / 'restored', 'long private password')
        self.runtime._durable_connect = lambda: sqlite3.connect(recovered)
        self.assertEqual(7, EvidenceLedger(self.store).read('job', receipt['receipt_id'])['payload']['result']['count'])
        self.assertEqual(1, len(self.memory.recall(self.job, 'source')['records']))
        with self.assertRaises(ValueError):
            restore(archive, self.root / 'restored', 'long private password')

    def test_requirements_are_preserved_without_inventing_acceptance(self):
        task = '\n'.join('requirement ' + str(i) for i in range(100))
        rows = requirements(task, [])
        self.assertEqual(task, rows[0]['requirement'])
        self.assertEqual('PLANNED', rows[0]['status'])

    def test_remote_embedding_destination_is_never_called(self):
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'https://cloud.example.com', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'embed'}), patch('requests.post') as post:
            self.assertIsNone(self.memory.semantic_scores('query', [('id', {'content': 'source'})]))
            post.assert_not_called()
