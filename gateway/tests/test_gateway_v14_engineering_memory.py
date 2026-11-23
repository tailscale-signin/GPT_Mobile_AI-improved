import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from cryptography.exceptions import InvalidTag

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringStore
from v14.engineering_crypto import EngineeringCipher
from v14.engineering_memory import EngineeringMemory, embedding_configuration, requirements
from v14.evidence import EvidenceLedger
from v14.engineering_backup import backup, restore


class EmbeddingResponse:
    def __init__(self, inputs):
        self.body = {'data': [{'index': i, 'embedding': [1., float(i + 1)]} for i, _ in enumerate(inputs)]}
        self.chunks = iter(self.iter_content(8192))
        self.raw = self
    def read1(self, size, decode_content=True):
        return next(self.chunks, b'')
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass
    def raise_for_status(self):
        pass
    def iter_content(self, size):
        yield json.dumps(self.body).encode()


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
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'https://cloud.example.com', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'embed'}), patch('requests.Session.post') as post:
            self.assertIsNone(self.memory.semantic_scores('query', [('id', {'content': 'source'})]))
            post.assert_not_called()

    def test_local_embedding_transport_ignores_environment_proxy_and_netrc(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': '', 'HTTP_PROXY': 'http://unapproved.example',
                  'HTTPS_PROXY': 'http://unapproved.example', 'ALL_PROXY': 'http://unapproved.example',
                  'NO_PROXY': '', 'NETRC': '/unapproved/credentials'}
        def local_post(session, url, **kwargs):
            self.assertFalse(session.trust_env)
            self.assertEqual({}, session.proxies)
            self.assertEqual('http://127.0.0.1:8080/v1/embeddings', url)
            self.assertFalse(kwargs['allow_redirects'])
            return EmbeddingResponse(kwargs['json']['input'])
        with patch.dict(os.environ, config), patch('requests.Session.post', autospec=True, side_effect=local_post) as post:
            self.assertIsNotNone(self.memory.semantic_scores('query', [('one', {'content': 'private'})], namespace='scope'))
            self.assertEqual(1, post.call_count)

    def test_real_slow_socket_preserves_cancellation_deadline_and_read_timeout(self):
        for mode in ('cancel', 'deadline', 'silent', 'cancel_silent'):
            with self.subTest(mode=mode):
                stop, received = threading.Event(), threading.Event()
                class Handler(BaseHTTPRequestHandler):
                    def do_POST(self):
                        self.rfile.read(int(self.headers.get('Content-Length', '0')))
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', '32768')
                        self.end_headers()
                        if mode in {'silent', 'cancel_silent'}:
                            received.set()
                            stop.wait(3)
                            return
                        for _ in range(256):
                            if stop.is_set():
                                break
                            received.set()
                            try:
                                self.wfile.write(b' ' * 128)
                                self.wfile.flush()
                            except (BrokenPipeError, ConnectionResetError):
                                break
                            stop.wait(.04)
                    def log_message(self, *args):
                        pass
                server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:' + str(server.server_port),
                          'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-fixture',
                          'GATEWAY_ENGINEERING_EMBEDDING_REVISION': ''}
                def validate():
                    if mode.startswith('cancel') and received.is_set():
                        raise InterruptedError('Stopped fixture response')
                try:
                    with patch.dict(os.environ, config):
                        started = time.monotonic()
                        if mode.startswith('cancel'):
                            with self.assertRaises(InterruptedError):
                                self.memory.semantic_scores('query', [('one', {'content': 'public fixture'})], validate=validate)
                        else:
                            self.assertIsNone(self.memory.semantic_scores('query', [('one', {'content': 'public fixture'})], validate=validate))
                        elapsed = time.monotonic() - started
                    self.assertLess(elapsed, 2.2 if mode == 'deadline' else 1.5)
                finally:
                    stop.set()
                    server.shutdown()
                    server.server_close()
                    thread.join(2)

    def test_old_architecture_is_found_beyond_the_recent_record_window(self):
        self.memory.remember(self.job, 'decision', {'content': 'The neutron scheduler owns repository arbitration.'})
        for index in range(140):
            self.store.now = lambda i=index: 1001. + i
            self.memory.remember(self.job, 'ci_history', {'content': 'Unrelated completed build ' + str(index)})
        result = self.memory.recall(self.job, 'neutron repository arbitration')
        self.assertTrue(any('neutron scheduler' in row['record'].get('content', '') for row in result['records']))
        self.assertNotIn(b'neutron', self.database.read_bytes())
        # Existing installations get a bounded oldest-first index upgrade.
        self.store.tx(lambda db: (db.execute('DELETE FROM engineering_memory_terms'), db.execute('DELETE FROM engineering_memory_indexed')))
        result = self.memory.recall(self.job, 'neutron scheduler')
        self.assertTrue(any('neutron scheduler' in row['record'].get('content', '') for row in result['records']))
        self.assertLessEqual(self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_memory_indexed').fetchone()[0]), 16)

    def test_embedding_vectors_reuse_content_and_stay_scoped_and_model_bound(self):
        calls = []
        def post(url, **kwargs):
            inputs = kwargs['json']['input']
            calls.append(inputs)
            self.assertLessEqual(kwargs['timeout'][1], .6)
            self.assertFalse(kwargs['allow_redirects'])
            return EmbeddingResponse(inputs)
        rows = [('one', {'content': 'private architecture'}), ('two', {'content': 'private caller'})]
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
        with patch.dict(os.environ, config), patch('requests.Session.post', side_effect=post):
            first = self.memory.semantic_scores('query', rows, namespace='authorized-one')
            self.assertEqual({'one', 'two'}, set(first))
            restarted = EngineeringMemory(self.store, self.runtime)
            self.assertEqual(first, restarted.semantic_scores('query', rows, namespace='authorized-one'))
            self.assertEqual(1, len(calls))
            restarted.semantic_scores('new-query', rows, namespace='authorized-one')
            self.assertEqual(1, len(calls[-1]))
            restarted.semantic_scores('query', rows, namespace='authorized-two')
            self.assertEqual(3, len(calls[-1]))
            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'new-embed'}):
                restarted.semantic_scores('query', rows, namespace='authorized-one')
            self.assertEqual(3, len(calls[-1]))
            before = len(calls)
            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'b' * 64}):
                restarted.semantic_scores('query', rows, namespace='authorized-one')
            self.assertEqual(before + 1, len(calls))
            self.assertEqual(3, len(calls[-1]))
        self.assertNotIn(b'private architecture', self.database.read_bytes())
        self.assertNotIn(b'new-query', self.database.read_bytes())

    def test_mutable_encoder_identity_ranks_without_durable_vector_reuse(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed'}
        for revision in ('', 'latest', 'main'):
            with self.subTest(revision=revision), patch.dict(os.environ, dict(config, GATEWAY_ENGINEERING_EMBEDDING_REVISION=revision)), \
                    patch('requests.Session.post', side_effect=lambda url, **kwargs: EmbeddingResponse(kwargs['json']['input'])) as post:
                for _ in range(2):
                    self.assertEqual({'one'}, set(self.memory.semantic_scores('query', [('one', {'content': 'private'})], namespace='scope')))
                self.assertEqual(2, post.call_count)
        self.assertFalse(self.database.with_suffix('.engineering.key').exists())
        def count(db):
            self.memory.schema(db)
            return db.execute('SELECT COUNT(*) FROM engineering_embedding_cache').fetchone()[0]
        self.assertEqual(0, self.store.tx(count))

    def test_corrupt_derived_vectors_are_rebuilt_without_discarding_protected_history(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
        rows = [('one', {'content': 'private'})]
        self.memory.remember(self.job, 'decision', {'content': 'Retained private architecture'})
        with patch.dict(os.environ, config), patch('requests.Session.post', side_effect=lambda url, **kwargs: EmbeddingResponse(kwargs['json']['input'])) as post:
            self.assertIsNotNone(self.memory.semantic_scores('query', rows, namespace='scope'))
            for malformed in ('invalid envelope', 'invalid authenticated data', 'invalid JSON', 'invalid vector'):
                with self.subTest(malformed=malformed):
                    def corrupt(db):
                        scope, identity, payload = db.execute('SELECT scope,id,payload FROM engineering_embedding_cache LIMIT 1').fetchone()
                        if malformed == 'invalid authenticated data':
                            envelope = json.loads(payload)
                            envelope['ciphertext'] = ('A' if envelope['ciphertext'][0] != 'A' else 'B') + envelope['ciphertext'][1:]
                            payload = json.dumps(envelope)
                        elif malformed in {'invalid JSON', 'invalid vector'}:
                            payload = EngineeringCipher.encrypt(db, 'embedding:' + scope + ':' + identity,
                                                                '{invalid' if malformed == 'invalid JSON' else '{"not":"vector"}')
                        else:
                            payload = 'invalid envelope'
                        db.execute('UPDATE engineering_embedding_cache SET payload=? WHERE scope=? AND id=?', (payload, scope, identity))
                    self.store.tx(corrupt)
                    before = post.call_count
                    self.assertIsNotNone(self.memory.semantic_scores('query', rows, namespace='scope'))
                    self.assertEqual(before + 1, post.call_count)
                    self.assertEqual(1, len(post.call_args.kwargs['json']['input']))
                    self.assertIsNotNone(self.memory.semantic_scores('query', rows, namespace='scope'))
                    self.assertEqual(before + 1, post.call_count)
            self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_memory').fetchone()[0]))

    def test_vector_cache_key_loss_fails_before_network_even_after_encoder_revision_change(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
        with patch.dict(os.environ, config), patch('requests.Session.post', side_effect=lambda url, **kwargs: EmbeddingResponse(kwargs['json']['input'])) as post:
            self.memory.semantic_scores('query', [('one', {'content': 'private'})], namespace='scope')
            key = self.database.with_suffix('.engineering.key')
            key.unlink()
            for revision in ('a' * 64, 'b' * 64):
                with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDING_REVISION': revision}):
                    with self.assertRaisesRegex(ValueError, 'key is missing'):
                        self.memory.semantic_scores('query', [('one', {'content': 'private'})], namespace='scope')
            self.assertEqual(1, post.call_count)
            self.assertFalse(key.exists())

    def test_frozen_encoder_and_admission_are_rechecked_before_and_during_transport(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
        rows = [('one', {'content': 'private'})]
        with patch.dict(os.environ, config), patch('requests.Session.post') as post:
            selected = embedding_configuration()
            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'b' * 64}):
                with self.assertRaises(PermissionError):
                    self.memory.semantic_scores('query', rows, namespace='scope', configuration=selected)
            for error in (PermissionError('revoked'), InterruptedError('cancelled'), ValueError('binding changed')):
                with self.subTest(error=error):
                    with self.assertRaises((PermissionError, InterruptedError)):
                        self.memory.semantic_scores('query', rows, namespace='scope', configuration=selected,
                                                    validate=unittest.mock.Mock(side_effect=error))
            post.assert_not_called()
            class ChangedResponse(EmbeddingResponse):
                def iter_content(self, size):
                    yield b'{"data":'
                    os.environ['GATEWAY_ENGINEERING_EMBEDDING_REVISION'] = 'b' * 64
                    yield b'[]}'
            post.return_value = ChangedResponse([])
            with self.assertRaises(PermissionError):
                self.memory.semantic_scores('query', rows, namespace='scope', configuration=selected)
            self.assertEqual(1, post.call_count)
            self.assertEqual(0, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_embedding_cache').fetchone()[0]))

    def test_admission_is_rechecked_even_when_all_vectors_are_cached(self):
        config = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8080', 'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'local-embed',
                  'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
        rows = [('one', {'content': 'private'})]
        with patch.dict(os.environ, config), patch('requests.Session.post', side_effect=lambda url, **kwargs: EmbeddingResponse(kwargs['json']['input'])) as post:
            self.memory.semantic_scores('query', rows, namespace='scope')
            guard = unittest.mock.Mock(side_effect=[None, PermissionError('revoked')])
            with self.assertRaises(PermissionError):
                self.memory.semantic_scores('query', rows, namespace='scope', validate=guard)
            self.assertEqual(2, guard.call_count)
            self.assertEqual(1, post.call_count)

    def test_selected_only_and_session_only_never_run_auxiliary_embeddings(self):
        self.memory.semantic_scores = unittest.mock.Mock()
        self.job['execution_binding'] = {'routing_policy': 'SELECTED_ONLY'}
        self.assertIsNone(self.memory.task_scores(self.job, 'query', [('one', {'content': 'code'})]))
        self.job.pop('execution_binding')
        self.job['request']['memory_mode'] = 'SESSION_ONLY'
        self.assertIsNone(self.memory.task_scores(self.job, 'query', [('one', {'content': 'code'})]))
        self.memory.semantic_scores.assert_not_called()
