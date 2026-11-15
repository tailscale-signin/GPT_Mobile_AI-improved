"""An uncertain sandbox stop retains ownership without replaying candidate work."""
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import sandbox
from benchmarks import fixtures
from benchmarks.contracts import coverage
from benchmarks.runner import BenchmarkRunner
from benchmarks.service import BenchmarkService
from benchmarks.store import BenchmarkConflict
from v14.engineering_backend import blob_sha
from v14.identity import Principal
from v14.maintenance import prepare
from v14.service import ServiceState


SETTINGS = {'sandbox_image': 'python@sha256:' + 'a' * 64, 'sandbox_seconds': 5, 'repair_attempts': 2}
SOURCES = {'service.py': 'def handle(value): return value'}
STARTED = (sandbox.START_MARKER + '\npartial output').encode()


class SandboxCleanupTest(unittest.TestCase):
    def bounded_failure(self, failure, raw=STARTED):
        def run(_argv, _cwd, _timeout, _maximum, destination=None):
            if destination is None:
                return {'exit_code': 1, 'output_tail': 'Docker unavailable'}
            destination.write(raw)
            raise failure('Synthetic bounded evaluator failure')
        return run

    def test_timeout_or_output_bound_with_unknown_removal_interrupts_after_observed_start(self):
        for failure, code in ((TimeoutError, 'CANDIDATE_DEADLINE'), (ValueError, 'CANDIDATE_OUTPUT_LIMIT'), (OSError, 'SANDBOX_OBSERVATION_UNAVAILABLE')):
            with self.subTest(bound=code), \
                    patch.object(sandbox, 'run_bounded', side_effect=self.bounded_failure(failure)), \
                    patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
                with self.assertRaises(sandbox.SandboxInterrupted) as caught:
                    sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker')
                self.assertEqual('unknown', caught.exception.cancellation['outcome'])
                self.assertEqual('sandbox', caught.exception.cancellation['kind'])
                self.assertTrue(caught.exception.observation['executed'])
                self.assertEqual(code, caught.exception.observation['error'])
                self.assertEqual('ERROR' if failure is OSError else 'FAILED', caught.exception.observation['status'])
                self.assertEqual(hashlib.sha256(STARTED).hexdigest(), caught.exception.observation['outputDigest'])
                self.assertGreaterEqual(caught.exception.observation['durationMs'], 0)
                self.assertNotIn('partial output', json.dumps(caught.exception.observation))

    def test_confirmed_removal_keeps_the_observed_candidate_failure(self):
        with patch.object(sandbox, 'run_bounded', side_effect=self.bounded_failure(TimeoutError)), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            observed = sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker')
        self.assertEqual('FAILED', observed['status'])
        self.assertTrue(observed['executed'])
        self.assertEqual('CANDIDATE_DEADLINE', observed['error'])
        self.assertEqual('confirmed', observed['cleanup']['outcome'])

    def test_attached_process_exit_is_terminal_even_if_auto_remove_already_removed_container(self):
        for code in (0, 125):
            def completed(_argv, _cwd, _timeout, _maximum, destination=None):
                if destination is None:
                    return {'exit_code': 0, 'output_tail': ''}
                destination.write((sandbox.START_MARKER + '\n' + json.dumps({'outputs': [{'output': {}}]})).encode())
                return {'exit_code': code, 'output_sha256': 'observed-digest', 'elapsed_ms': 17}
            with self.subTest(exit_code=code), patch.object(sandbox, 'run_bounded', side_effect=completed), \
                    patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
                observed = sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker')
            self.assertEqual('COMPLETED' if code == 0 else 'FAILED', observed['status'])
            self.assertTrue(observed['executed'])
            self.assertEqual(17, observed['durationMs'])
            self.assertEqual('observed-digest', observed['outputDigest'])

    def test_nonzero_client_exit_without_cleanup_proof_is_not_a_terminal_container(self):
        def lost_connection(_argv, _cwd, _timeout, _maximum, destination=None):
            if destination is None:
                return {'exit_code': 1, 'output_tail': 'Docker unavailable'}
            destination.write((sandbox.START_MARKER + '\n').encode())
            return {'exit_code': 125, 'output_sha256': 'observed-digest', 'elapsed_ms': 17}
        with patch.object(sandbox, 'run_bounded', side_effect=lost_connection), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(sandbox.SandboxInterrupted) as caught:
                sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker')
        self.assertEqual('unknown', caught.exception.cancellation['outcome'])
        self.assertEqual(125, caught.exception.observation['exitCode'])
        self.assertEqual(17, caught.exception.observation['durationMs'])

    def test_claim_is_durable_before_launch_and_only_observed_cleanup_releases_it(self):
        events = []
        def completed(_argv, _cwd, _timeout, _maximum, destination=None):
            self.assertEqual('retain', events[-1][0])
            destination.write((sandbox.START_MARKER + '\n{"outputs":[{"output":{}}]}').encode())
            return {'exit_code': 0, 'output_sha256': 'digest', 'elapsed_ms': 8}
        with patch.object(sandbox, 'run_bounded', side_effect=completed), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker',
                            retain=lambda name: events.append(('retain', name)),
                            release=lambda name: events.append(('release', name)))
        self.assertEqual(['retain', 'release'], [row[0] for row in events])
        self.assertEqual(events[0][1], events[1][1])
        with patch.object(sandbox, 'run_bounded', side_effect=self.bounded_failure(TimeoutError)), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(sandbox.SandboxInterrupted):
                sandbox.execute(SOURCES, [{}], SETTINGS, docker='docker',
                                retain=lambda name: events.append(('retain', name)),
                                release=lambda name: events.append(('release', name)))
        self.assertEqual('retain', events[-1][0])

    def test_exact_container_absence_needs_a_successful_bounded_observation(self):
        name = 'gateway-benchmark-' + 'a' * 32
        for exit_code, output, expected in ((1, '', 'unknown'), (0, name, 'unknown'), (0, '', 'confirmed')):
            with self.subTest(exit_code=exit_code, output=output), \
                    patch.object(sandbox.shutil, 'which', return_value='/local/docker'), \
                    patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                    patch.object(sandbox, 'run_bounded', return_value={'exit_code': exit_code, 'output_tail': output}) as observed:
                self.assertEqual(expected, sandbox.reconcile_container(name)['outcome'])
                argv, _, timeout, maximum = observed.call_args.args
                self.assertEqual(['/local/docker', '--config'], argv[:2])
                self.assertEqual(['--context', 'default', 'container', 'ls', '--all', '--filter', 'name=^/' + name + '$', '--format', '{{.Names}}'], argv[3:])
                self.assertEqual(10, timeout)
                self.assertEqual(4096, maximum)
        with patch.object(sandbox.subprocess, 'run') as remove, patch.object(sandbox, 'run_bounded') as observe:
            with self.assertRaises(ValueError):
                sandbox.reconcile_container('another-owners-container')
            remove.assert_not_called()
            observe.assert_not_called()

    def test_preflight_launch_removal_and_restart_observation_use_one_isolated_docker_context(self):
        seen = []
        names = ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')
        def checked(argv, environment):
            self.assertEqual(['/local/docker', '--config'], argv[:2])
            self.assertTrue(Path(argv[2]).is_dir())
            self.assertEqual([], list(Path(argv[2]).iterdir()))
            self.assertEqual(['--context', 'default'], argv[3:5])
            self.assertTrue(all(name not in environment for name in names))
            seen.append(argv[5])
        def process(argv, **kwargs):
            checked(argv, kwargs['env'])
            raw = {'image': json.dumps([SETTINGS['sandbox_image']]).encode(),
                   'run': (sandbox.START_MARKER + '\n{"outputs":[{"output":{}}]}').encode(),
                   'container': b''}[argv[5]]
            return SimpleNamespace(stdout=BytesIO(raw), poll=lambda: 0, wait=lambda **_: 0, kill=Mock())
        def removed(argv, **kwargs):
            checked(argv, kwargs['env'])
            return SimpleNamespace(returncode=1)
        with patch.dict(os.environ, {name: 'untrusted-inherited-setting' for name in names}), \
                patch.object(sandbox.shutil, 'which', return_value='/local/docker'), \
                patch.object(sandbox.subprocess, 'Popen', side_effect=process), \
                patch.object(sandbox.subprocess, 'run', side_effect=removed):
            self.assertEqual('/local/docker', sandbox.preflight(SETTINGS))
            self.assertEqual('COMPLETED', sandbox.execute(SOURCES, [{}], SETTINGS, docker='/local/docker')['status'])
            self.assertEqual('confirmed', sandbox.reconcile_container('gateway-benchmark-' + 'd' * 32)['outcome'])
        self.assertEqual(['image', 'run', 'rm', 'rm', 'container'], seen)


class BenchmarkCleanupOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.principal = Principal('owner', frozenset(), True)

    def service(self, durable=True):
        state = ServiceState()
        state.authorized = lambda _owner: True
        runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=str(self.root / 'data/jobs.sqlite3'),
                                  GATEWAY_DURABLE_JOBS=durable, gateway_draining=False,
                                  LLAMA_BASE='http://model', gateway_service=state)
        engineering = SimpleNamespace(authorized=lambda _owner: True,
                                      store=SimpleNamespace(conversations=SimpleNamespace(gateway_id=lambda: 'installation')))
        service = BenchmarkService(runtime, engineering)
        runtime.gateway_benchmarks = service
        return service

    def request(self, identity):
        return {'request_id': identity, 'model': 'selected', 'suite': 'coding', 'preset': 'quick'}

    def admitted_claim(self, service):
        run = service.create(self.principal, self.request('owned'))
        service.store.update(run['id'], state='RUNNING')
        name = 'gateway-benchmark-' + 'b' * 32
        service.store.claim_sandbox(run['id'], name)
        return run, name

    def maintenance(self, service):
        with patch('v14.maintenance.pending_work', return_value=0):
            return prepare(service.runtime, service.runtime.gateway_service, service.engineering)

    def test_runner_stops_repairs_trials_and_service_admission_until_cleanup_is_confirmed(self):
        for failure in (TimeoutError, ValueError):
            with self.subTest(bound=failure.__name__):
                service = self.service()
                run = service.create(self.principal, self.request(failure.__name__))
                queued = service.create(self.principal, self.request(failure.__name__ + '-queued'))
                task = fixtures.suite('quick')[0]
                proposal = json.dumps({'changes': [
                    {'path': path, 'expected_blob_sha': blob_sha(source),
                     'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                    for path, source in task['sources'].items()], 'evidence': {'changed_files': list(task['sources']), 'tests_executed': False}})
                launches, dispatches = [], []
                def bounded(argv, cwd, _timeout, _maximum, destination=None):
                    if destination is None:
                        return {'exit_code': 1, 'output_tail': 'Docker unavailable'}
                    name = argv[argv.index('--name') + 1]
                    self.assertIn({'run_id': run['id'], 'container': name}, service.store.pending_sandboxes())
                    launches.append(name)
                    if len(launches) == 1:
                        values = json.loads((cwd / 'evaluator/inputs.json').read_text())
                        raw = (sandbox.START_MARKER + '\n' + json.dumps({'outputs': [{'output': fixtures.expected(task, value)} for value in values]})).encode()
                        destination.write(raw)
                        return {'exit_code': 0, 'output_sha256': hashlib.sha256(raw).hexdigest(), 'elapsed_ms': 6}
                    destination.write(STARTED)
                    raise failure('Synthetic bounded evaluator failure')
                def measure(_runner, _run, _name, _turns, _expected, *, validate, output_tokens):
                    dispatches.append(_name)
                    validate(proposal)
                    return {'firstMs': 3, 'totalMs': 9, 'observedModel': 'selected', 'gatewayJobId': 'observed-job'}
                with patch.object(fixtures, 'suite', return_value=[task, dict(task, id='unstarted-trial')]), \
                        patch.object(sandbox, 'preflight', return_value='/local/docker'), \
                        patch.object(sandbox, 'run_bounded', side_effect=bounded), \
                        patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                        patch('benchmarks.service.load_settings', return_value=SETTINGS), \
                        patch.object(BenchmarkRunner, 'execute', BenchmarkRunner.code), \
                        patch.object(BenchmarkRunner, 'measure_case', measure):
                    service.run_one(run['id'])
                    service.run_one(queued['id'])
                self.assertEqual(2, len(launches))
                self.assertEqual([task['id']], dispatches)
                saved = service.store.get(run['id'])
                self.assertEqual('INTERRUPTED', saved['state'])
                self.assertEqual('CANCELLATION_UNCONFIRMED', saved['failure']['code'])
                self.assertEqual('QUEUED', service.store.get(queued['id'])['state'])
                self.assertEqual(1, len(saved['cases']))
                case = saved['cases'][0]
                self.assertEqual('INTERRUPTED', case['status'])
                self.assertFalse(case['graded'])
                self.assertIsNone(case['score'])
                self.assertTrue(case['sandboxObservation']['executed'])
                self.assertEqual(hashlib.sha256(STARTED).hexdigest(), case['sandboxObservation']['outputDigest'])
                self.assertEqual(9, case['timing']['totalMs'])
                self.assertEqual('observed-job', case['gatewayJobId'])
                self.assertEqual(1, len(case['attempts']))
                self.assertEqual(1, service.snapshot()['unconfirmedSandboxCleanups'])
                with self.assertRaises(BenchmarkConflict):
                    service.create(self.principal, self.request('blocked-new-run'))
                with self.assertRaises(BenchmarkConflict):
                    service.store.remove(run['id'], self.principal.id)
                self.assertFalse(self.maintenance(service))
                with patch.object(sandbox.shutil, 'which', return_value='/local/docker'), \
                        patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as remove:
                    self.assertTrue(service.reconcile_sandboxes())
                    self.assertEqual(launches[-1], remove.call_args.args[0][-1])
                self.assertEqual([], service.store.pending_sandboxes())
                with patch.object(BenchmarkRunner, 'execute', return_value={'score': None}) as execute:
                    service.run_one(queued['id'])
                    execute.assert_called_once()
                self.assertEqual('INTERRUPTED', service.store.get(run['id'])['state'])
                self.assertTrue(self.maintenance(service))
                service.runtime.gateway_service.draining = service.runtime.gateway_draining = False

    def test_active_claim_allows_queueing_but_cannot_be_replaced_or_forgotten(self):
        service = self.service()
        run, name = self.admitted_claim(service)
        service.active[run['id']] = threading.Event()
        queued = service.create(self.principal, self.request('normal-queued'))
        self.assertEqual('QUEUED', queued['state'])
        with self.assertRaises(BenchmarkConflict):
            service.store.claim_sandbox(run['id'], 'gateway-benchmark-' + 'c' * 32)
        with self.assertRaises(BenchmarkConflict):
            service.store.release_sandbox(run['id'], 'gateway-benchmark-' + 'c' * 32)
        with self.assertRaises(ValueError):
            service.store.claim_sandbox(run['id'], 'unrelated-container')
        self.assertEqual([{'run_id': run['id'], 'container': name}], service.store.pending_sandboxes())
        self.assertFalse(self.maintenance(service))

    def test_reference_interruption_retains_harness_evidence_without_claiming_a_candidate_trial(self):
        service = self.service()
        run = service.create(self.principal, self.request('reference-timeout'))
        task = fixtures.suite('quick')[0]
        def interrupted(_argv, _cwd, _timeout, _maximum, destination=None):
            if destination is None:
                return {'exit_code': 1, 'output_tail': 'Docker unavailable'}
            destination.write(STARTED)
            raise TimeoutError('Synthetic reference failure')
        with patch.object(fixtures, 'suite', return_value=[task]), \
                patch.object(sandbox, 'preflight', return_value='/local/docker'), \
                patch.object(sandbox, 'run_bounded', side_effect=interrupted), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                patch('benchmarks.service.load_settings', return_value=SETTINGS), \
                patch.object(BenchmarkRunner, 'execute', BenchmarkRunner.code), \
                patch.object(BenchmarkRunner, 'measure_case', side_effect=AssertionError('must not dispatch')) as dispatch:
            service.run_one(run['id'])
            dispatch.assert_not_called()
        saved = service.store.get(run['id'])
        self.assertEqual('INTERRUPTED', saved['state'])
        case = saved['cases'][0]
        self.assertEqual('REFERENCE', case['cancellation']['stage'])
        self.assertTrue(case['sandboxObservation']['executed'])
        self.assertFalse(case['executed'])
        self.assertFalse(case['graded'])
        self.assertEqual(0, coverage(saved['cases'], saved['planned'])['executed'])

    def test_restart_and_disabled_admission_retain_unknown_cleanup_without_replay(self):
        original = self.service()
        run, name = self.admitted_claim(original)
        restarted = self.service(durable=False)
        observed = threading.Event()
        def unavailable(*_args, **_kwargs):
            observed.set()
            return {'exit_code': 1, 'output_tail': 'Docker unavailable'}
        with patch.object(sandbox.shutil, 'which', return_value='/local/docker'), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                patch.object(sandbox, 'run_bounded', side_effect=unavailable), \
                patch.object(BenchmarkRunner, 'execute', side_effect=AssertionError('must not replay')) as execute:
            restarted.start()
            try:
                self.assertTrue(observed.wait(2))
                self.assertEqual(1, restarted.snapshot()['activeBenchmarks'])
                self.assertFalse(self.maintenance(restarted))
                with self.assertRaises(BenchmarkConflict):
                    restarted.create(self.principal, self.request('disabled'))
            finally:
                restarted.begin_drain()
                restarted.thread.join(2)
            self.assertFalse(restarted.thread.is_alive())
            execute.assert_not_called()
        self.assertEqual([{'run_id': run['id'], 'container': name}], restarted.store.pending_sandboxes())
        self.assertEqual('INTERRUPTED', restarted.store.get(run['id'])['state'])

    def test_restart_reconciles_absent_prelaunch_container_without_replaying_the_run(self):
        original = self.service()
        run, name = self.admitted_claim(original)
        restarted = self.service()
        settled = threading.Event()
        release = restarted.store.release_sandbox
        def released(*args):
            release(*args)
            settled.set()
        with patch.object(sandbox.shutil, 'which', return_value='/local/docker'), \
                patch.object(sandbox.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                patch.object(sandbox, 'run_bounded', return_value={'exit_code': 0, 'output_tail': ''}) as observe, \
                patch.object(restarted.store, 'release_sandbox', side_effect=released), \
                patch.object(BenchmarkRunner, 'execute', side_effect=AssertionError('must not replay')) as execute:
            restarted.start()
            try:
                self.assertTrue(settled.wait(2))
            finally:
                restarted.begin_drain()
                restarted.thread.join(2)
            self.assertFalse(restarted.thread.is_alive())
            execute.assert_not_called()
            self.assertIn('name=^/' + name + '$', observe.call_args.args[0])
        self.assertEqual([], restarted.store.pending_sandboxes())
        self.assertEqual('INTERRUPTED', restarted.store.get(run['id'])['state'])
        self.assertTrue(self.maintenance(restarted))


if __name__ == '__main__':
    unittest.main()
