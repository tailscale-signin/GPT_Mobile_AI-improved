"""Configured local semantic arms through real cache, scheduler and benchmark paths."""
from copy import deepcopy
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import context_experiment, context_semantic, fixtures, sandbox
from benchmarks.context_fixture import blob_sha
from benchmarks.contracts import digest, validate_request
from benchmarks.runner import BenchmarkRunner
from benchmarks.service import BenchmarkService
from benchmarks.verified_python import evaluate
from v14.engineering_memory import EngineeringMemory
from v14.identity import Principal
from v14.inference_scheduler import FairInferenceScheduler


ENCODER = {'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://127.0.0.1:8199',
           'GATEWAY_ENGINEERING_EMBEDDING_MODEL': 'configured-local-encoder',
           'GATEWAY_ENGINEERING_EMBEDDING_REVISION': 'a' * 64}
SETTINGS = {'sandbox_image': 'python@sha256:' + 'a' * 64, 'sandbox_seconds': 30, 'repair_attempts': 0}


def request(**changes):
    return validate_request(dict(dict(request_id='semantic-context', model='exact-generation-model', suite='coding',
                                 preset='quick', seed=17, concurrency=2, context_variants=['CURRENT', 'SEMANTIC'],
                                 context_repeats=1), **changes))


class CacheStore:
    def __init__(self, path):
        self.path, self.transactions = path, 0

    def tx(self, callback):
        self.transactions += 1
        with sqlite3.connect(self.path) as db:
            return callback(db)


class EmbeddingsResponse:
    def __init__(self, texts, *, before_chunk=lambda: None):
        self.texts, self.before_chunk = texts, before_chunk
        self.chunks = iter(self.iter_content(8192))
        self.raw = self

    def read1(self, size, decode_content=True):
        return next(self.chunks, b'')

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def raise_for_status(self):
        return None

    def iter_content(self, _size):
        self.before_chunk()
        # Deterministic transport fixture: promote service.py so actual selector
        # order can be observed. This is not a measured encoder-quality result.
        yield json.dumps({'data': [{'index': index, 'embedding': [1., 0.] if
            not text.startswith('{') or '"path":"service.py"' in text else [0., 1.]}
            for index, text in enumerate(self.texts)]}).encode()


class ContextSemanticTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.environment = patch.dict(os.environ, ENCODER, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.cache = CacheStore(self.root / 'engineering.sqlite3')
        self.scheduler = FairInferenceScheduler(1)
        self.engineering = SimpleNamespace(memory=EngineeringMemory(self.cache, SimpleNamespace()), scheduler=self.scheduler)
        self.run = {'id': 'frozen-run', 'request': request(), 'provenance': {'executionIdentity': 'selected-route'}}
        self.cancelled = threading.Event()
        self.guard = Mock()
        self.task = fixtures.find_task('pagination-1')

    def ranker(self, run=None):
        return context_semantic.ContextSemanticRanker(self.engineering, run or self.run, 'authorized-owner', self.cancelled, self.guard)

    def embed(self, url, **options):
        self.assertEqual(ENCODER['GATEWAY_ENGINEERING_EMBEDDINGS_URL'] + '/v1/embeddings', url)
        self.assertEqual(ENCODER['GATEWAY_ENGINEERING_EMBEDDING_MODEL'], options['json']['model'])
        self.assertFalse(options['allow_redirects'])
        self.assertEqual((.3, .6), options['timeout'])
        self.assertEqual(1, self.scheduler.snapshot()['active'])
        self.assertEqual('PREFILL', self.scheduler.snapshot()['active_states'][0])
        return EmbeddingsResponse(options['json']['input'])

    def test_real_selector_uses_local_encoder_and_scoped_encrypted_cache(self):
        ranker = self.ranker()
        frozen = context_experiment.freeze([self.task], self.run, SETTINGS, semantic_identity=ranker.identity)
        with patch('requests.Session.post', side_effect=self.embed) as network:
            supplement, receipt = context_experiment.prepare(self.task, 'SEMANTIC', 12000, frozen=frozen, semantic_ranker=ranker)
        self.assertEqual(1, network.call_count)
        self.assertEqual('LOCAL_SEMANTIC_AND_LEXICAL', receipt['retrievalMode'])
        self.assertEqual(['RANKED', 'RANKED'], [probe['status'] for probe in receipt['semanticPreparation']])
        self.assertEqual(digest(ranker.identity), receipt['encoderIdentityDigest'])
        selected = json.loads(supplement[supplement.index('{'):])
        self.assertEqual('service.py', selected['selected_ranges'][0]['path'])
        self.assertEqual(0, self.scheduler.snapshot()['active'])
        self.assertEqual(2, self.scheduler.snapshot()['completed'])
        raw = self.cache.path.read_bytes()
        self.assertNotIn(self.task['sources']['core.py'].encode(), raw)
        exported = json.dumps({'identity': frozen, 'receipt': receipt})
        self.assertNotIn(ENCODER['GATEWAY_ENGINEERING_EMBEDDINGS_URL'], exported)
        self.assertNotIn(ENCODER['GATEWAY_ENGINEERING_EMBEDDING_MODEL'], exported)
        self.assertGreater(self.guard.call_count, 2)
        # Another principal never reads this run's cached vectors.
        other = context_semantic.ContextSemanticRanker(self.engineering, self.run, 'other-owner', self.cancelled, self.guard)
        with patch('requests.Session.post', side_effect=self.embed) as network:
            context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=other)
        self.assertEqual(1, network.call_count)

    def test_missing_revision_remote_destination_and_policy_flags_do_not_read_or_send(self):
        cases = [({'GATEWAY_ENGINEERING_EMBEDDING_REVISION': ''}, {}, 'IMMUTABLE_REVISION_REQUIRED'),
                 ({'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'https://external.invalid'}, {}, 'LOCAL_DESTINATION_REQUIRED'),
                 ({'GATEWAY_ENGINEERING_EMBEDDINGS_URL': 'http://:credential@127.0.0.1:8199'}, {}, 'LOCAL_DESTINATION_REQUIRED'),
                 ({}, {'memory_mode': 'SESSION_ONLY'}, 'POLICY_DISABLED')]
        for environment, policy, status in cases:
            with self.subTest(status=status, environment=environment), patch.dict(os.environ, environment):
                run = deepcopy(self.run)
                run['request'].update(policy)
                ranker = self.ranker(run)
                with patch('requests.Session.post') as network:
                    _, receipt = context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
                network.assert_not_called()
                self.assertEqual(status, ranker.identity['status'])
                self.assertEqual('LEXICAL_FALLBACK', receipt['retrievalMode'])
        run = deepcopy(self.run)
        run['provenance']['selectedBinding'] = {'routing_policy': 'SELECTED_ONLY'}
        with patch('requests.Session.post') as network:
            self.assertIsNone(self.ranker(run).scores('query', [('a', {'content': 'public'})], source_sha='b' * 64))
        network.assert_not_called()
        self.assertEqual(0, self.cache.transactions)
        self.assertEqual(0, self.scheduler.snapshot()['completed'])

    def test_encoder_changes_fail_before_cached_read_or_model_dispatch(self):
        ranker = self.ranker()
        frozen = context_experiment.freeze([self.task], self.run, SETTINGS, semantic_identity=ranker.identity)
        self.run['provenance']['contextEncoderIdentity'] = ranker.identity
        for key, changed in [('GATEWAY_ENGINEERING_EMBEDDING_REVISION', 'b' * 64),
                             ('GATEWAY_ENGINEERING_EMBEDDING_MODEL', 'another-encoder'),
                             ('GATEWAY_ENGINEERING_EMBEDDINGS_URL', 'http://127.0.0.1:8299')]:
            with self.subTest(key=key), patch.dict(os.environ, {key: changed}), patch('requests.Session.post') as network:
                with self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_FREEZE'):
                    context_experiment.prepare(self.task, 'SEMANTIC', 12000, frozen=frozen, semantic_ranker=ranker)
                with self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_ADMISSION'):
                    self.ranker()
                network.assert_not_called()
        self.assertEqual(0, self.cache.transactions)

    def test_revoked_or_cancelled_owner_cannot_read_warm_vectors(self):
        ranker = self.ranker()
        with patch('requests.Session.post', side_effect=self.embed):
            context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
        transactions = self.cache.transactions
        self.guard.side_effect = PermissionError('Revoked')
        with patch('requests.Session.post') as network, self.assertRaises(PermissionError):
            context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
        network.assert_not_called()
        self.assertEqual(transactions, self.cache.transactions)
        self.guard.side_effect = None
        self.cancelled.set()
        with self.assertRaises(InterruptedError):
            context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
        self.assertEqual(transactions, self.cache.transactions)

    def test_capacity_fallback_and_inflight_cancellation_release_scheduler(self):
        busy = self.scheduler.acquire('interactive')
        with patch.object(context_semantic, 'QUEUE_SECONDS', .01), patch('requests.Session.post') as network:
            ranker = self.ranker()
            _, receipt = context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
        network.assert_not_called()
        self.assertEqual('LEXICAL_FALLBACK', receipt['retrievalMode'])
        self.assertEqual({'LOCAL_CAPACITY_UNAVAILABLE'}, {probe['status'] for probe in receipt['semanticPreparation']})
        self.assertEqual(0, self.cache.transactions)
        self.scheduler.release(busy)
        ranker = self.ranker()
        def cancelled_response(_url, **options):
            return EmbeddingsResponse(options['json']['input'], before_chunk=self.cancelled.set)
        with patch('requests.Session.post', side_effect=cancelled_response), self.assertRaises(InterruptedError):
            context_experiment.prepare(self.task, 'SEMANTIC', 12000, semantic_ranker=ranker)
        self.assertEqual(0, self.scheduler.snapshot()['active'])
        with sqlite3.connect(self.cache.path) as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM engineering_embedding_cache').fetchone()[0])

    def test_no_valid_ranking_is_lexical_fallback(self):
        for result in (None, {}, {'not-a-selected-range': 1.0}, {'a': float('nan')}):
            with self.subTest(result=result), patch.object(self.engineering.memory, 'semantic_scores', return_value=result):
                ranker = self.ranker()
                self.assertIsNone(ranker.scores('query', [('a', {'content': 'public'})], source_sha='b' * 64))
                self.assertEqual('ENCODER_UNAVAILABLE', ranker.measurements[-1]['status'])
        self.assertEqual(0, self.scheduler.snapshot()['active'])

    def test_service_runs_semantic_arm_without_changing_model_or_leaking_hidden_inputs(self):
        runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=str(self.root / 'data' / 'jobs.sqlite3'),
            GATEWAY_DURABLE_JOBS=True, gateway_draining=False, LLAMA_BASE='http://127.0.0.1:8080',
            gateway_service=SimpleNamespace(lock=threading.RLock(), draining=False, instance_id='instance', authorized=lambda owner: True))
        self.engineering.authorized = lambda job: True
        self.engineering.store = SimpleNamespace(conversations=SimpleNamespace(gateway_id=lambda: 'installation'))
        service = BenchmarkService(runtime, self.engineering)
        with patch('benchmarks.service.load_settings', return_value=SETTINGS):
            run = service.create(Principal('owner', frozenset(), True), request())
            self.assertEqual('READY', run['provenance']['contextEncoderIdentity']['status'])
            prompts, embeddings = [], []
            task = deepcopy(self.task)
            # Additional hidden data cannot enter the source projection.
            task['secret_marker'] = 'NEVER_SEND_HIDDEN_MARKER'
            def embed(url, **options):
                embeddings.extend(options['json']['input'])
                return self.embed(url, **options)
            def execution(_runner, _run, candidate, inputs, *_args, **_kwargs):
                result = evaluate(candidate, inputs)
                return dict(result, status='COMPLETED', executed=True, outputDigest=digest(result), durationMs=1)
            def model(_runner, selected, task_id, turns, *_args, **options):
                self.assertEqual('exact-generation-model', selected['request']['model'])
                self.assertEqual(task['id'], task_id)
                self.assertEqual(0, self.scheduler.snapshot()['active'])
                prompts.append(turns[0]['content'])
                options['on_admitted']('selected-generation-job')
                # Grader reference is used only by this fake provider boundary.
                options['validate'](json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                    'edits': [{'old': source, 'new': task['referenceSources'][path]}]} for path, source in task['sources'].items()],
                    'diagnosis': dict(task['sources']), 'tests': [], 'evidence': {'tests_executed': False, 'changed_files': list(task['sources'])}}))
                return dict(finishReason='stop', totalMs=20, firstMs=2, inputTokens=100, outputTokens=30,
                            observedModel='exact-generation-model', gatewayJobId='selected-generation-job')
            with patch.object(fixtures, 'suite', return_value=[task]), patch.object(sandbox, 'preflight', return_value='docker'), \
                    patch.object(BenchmarkRunner, 'execute', BenchmarkRunner.code), \
                    patch.object(BenchmarkRunner, 'execute_sandbox', execution), patch.object(BenchmarkRunner, 'measure_case', model), \
                    patch('requests.Session.post', side_effect=embed) as network:
                service.run_one(run['id'])
        saved = service.store.get(run['id'])
        self.assertEqual('COMPLETED', saved['state'], saved.get('failure'))
        self.assertEqual(1, network.call_count)
        self.assertEqual(fixtures.prompt(task), prompts[0])
        self.assertGreater(len(prompts[1]), len(prompts[0]))
        self.assertNotIn('NEVER_SEND_HIDDEN_MARKER', ''.join(embeddings))
        self.assertNotIn(task['referenceSources']['core.py'], ''.join(embeddings))
        self.assertEqual(['LOCAL_SEMANTIC_AND_LEXICAL'], saved['summary']['contextArms']['SEMANTIC']['retrievalModes'])
        self.assertEqual(['RANKED'], saved['summary']['contextArms']['SEMANTIC']['semanticRankingStatuses'])
        self.assertEqual(1, saved['summary']['contextComparisons'][0]['matchedPairs'])
        frozen = saved['summary']['contextExperimentIdentity']
        self.assertEqual(run['provenance']['contextEncoderIdentity'], frozen['encoderIdentity'])
        changed = deepcopy(saved['cases'])
        changed[1]['contextReceipt']['encoderIdentityDigest'] = '0' * 64
        report = context_experiment.summary(changed, 2, run['request'], frozen)
        self.assertEqual(0, report['contextComparisons'][0]['matchedPairs'])


if __name__ == '__main__':
    unittest.main()
