"""Native references execute here only because every tested source is repo-owned.

Untrusted model proposals remain container-only. No Docker availability is
invented by these host reference tests; actual execution is a separate gate.
"""
import base64
from contextlib import contextmanager
import copy
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import repository_tasks as tasks, repository_sandbox as native
from benchmarks.sandbox import SandboxInterrupted, SandboxUnavailable
from v14.engineering_backend import blob_sha


def trusted_observe(task, sources):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for path, source in sources.items():
            (root / path).write_text(source)
        (root / 'inputs.json').write_text(json.dumps(native.input_bundle(task)))
        # Tests may execute only our fixed shipped references/mutants. Never
        # expose this adapter as a benchmark transport for generated candidates.
        if sources not in (task['sources'], task['referenceSources']):
            raise ValueError('Only repository-owned controls may execute on the host')
        code = native.DRIVER.replace("pathlib.Path('/candidate')", 'pathlib.Path(' + repr(str(root)) + ')')
        code = code.replace("pathlib.Path('/evaluator/inputs.json')", 'pathlib.Path(' + repr(str(root / 'inputs.json')) + ')')
        path = root / 'driver.py'; path.write_text(code)
        process = subprocess.run([sys.executable, str(path)], capture_output=True, timeout=35)
        if process.returncode:
            raise AssertionError(process.stderr.decode()[-2000:])
        marker = (native.START + '\n').encode()
        if not process.stdout.startswith(marker): raise AssertionError('No execution receipt')
        return json.loads(process.stdout[len(marker):])


class NativeResourceAccountingTests(unittest.TestCase):
    def setUp(self):
        self.task = tasks.task_spec('repository-room-add-default')
        path = 'migration.sql'
        self.proposal = json.dumps({'changes': [{'path': path,
            'expected_blob_sha': blob_sha(self.task['sources'][path]),
            'content': self.task['referenceSources'][path]}]})
        self.settings = {'repository_image': 'native/runtime@sha256:' + 'a' * 64, 'sandbox_seconds': 60}
        self.started = (native.START + '\n').encode()
        self.reference = {'runtime': {'kind': 'sqlite'}, 'outputs': self.task['expected']}
        self.broken = {'runtime': {'kind': 'sqlite'}, 'outputs': []}

    @contextmanager
    def process_boundary(self, responses, *, cleanup='confirmed', cancel=None):
        """Only container/process I/O is replaced; execute and grade stay real."""
        remaining = list(responses)
        calls = []
        def bounded(argv, cwd, timeout, maximum, destination=None):
            calls.append(argv)
            response = remaining.pop(0)
            if isinstance(response, tuple):
                raw, failure = response
                destination.write(raw)
                destination.flush()
                if cancel is not None:
                    cancel.set()
                if failure is not None:
                    raise failure
            else:
                raw = self.started + json.dumps(response).encode()
                destination.write(raw)
            return {'exit_code': 0, 'elapsed_ms': 17, 'output_sha256': hashlib.sha256(raw).hexdigest()}
        def removal(name, **kwargs):
            return {'kind': 'sandbox', 'container': name,
                    'outcome': cleanup if not remaining else 'confirmed'}
        with patch.object(native.sandbox, 'preflight', return_value='docker'), \
                patch.object(native, 'run_bounded', side_effect=bounded), \
                patch.object(native.sandbox, 'remove_container', side_effect=removal), \
                patch.object(native.sandbox, 'confirm_container_absent', side_effect=removal):
            yield calls

    def test_started_native_resource_bounds_remain_known_model_failures_after_calibration(self):
        for failure, code in ((TimeoutError, 'NATIVE_EXECUTION_DEADLINE'), (ValueError, 'NATIVE_OUTPUT_LIMIT')):
            with self.subTest(bound=code), self.process_boundary([
                    self.reference, self.broken, (self.started, failure('PRIVATE_PROCESS_DETAIL'))]) as calls:
                receipt = native.grade(self.task, self.proposal, self.settings, None)
                self.assertEqual(3, len(calls))
                self.assertEqual('PASSED', receipt['calibration']['status'])
                self.assertEqual(('FAILED', True, True, True, True, 'MODEL', code),
                                 tuple(receipt[key] for key in ('status', 'executed', 'graded', 'qualified', 'outcomeKnown', 'failureDomain', 'error')))
                self.assertFalse(receipt['resolved'])
                self.assertIsNone(receipt['score'])
                self.assertNotIn('PRIVATE_PROCESS_DETAIL', json.dumps(receipt))

    def test_unstarted_transport_and_malformed_native_observations_stay_unqualified(self):
        observations = [(b'', TimeoutError('startup')), (b'', ValueError('startup output')),
                        (self.started, OSError('transport')),
                        (self.started + b'not-json', None), (self.started + b'[]', None)]
        for raw, failure in observations:
            with self.subTest(raw=raw, failure=type(failure).__name__), \
                    self.process_boundary([self.reference, self.broken, (raw, failure)]):
                receipt = native.grade(self.task, self.proposal, self.settings, None)
            self.assertEqual('BLOCKED', receipt['status'])
            self.assertEqual('HARNESS', receipt['failureDomain'])
            self.assertFalse(receipt['outcomeKnown'])
            self.assertFalse(receipt['qualified'])
            self.assertFalse(receipt['graded'])

    def test_unknown_cleanup_and_user_cancellation_never_publish_a_resource_failure(self):
        for cleanup, cancel in (('unknown', None), ('confirmed', threading.Event())):
            released = []
            with self.subTest(cleanup=cleanup), self.process_boundary([
                    self.reference, self.broken, (self.started, TimeoutError('bounded'))], cleanup=cleanup, cancel=cancel):
                with self.assertRaises(SandboxInterrupted) as caught:
                    native.grade(self.task, self.proposal, self.settings, cancel, release=released.append)
            self.assertEqual(cleanup, caught.exception.cancellation['outcome'])
            self.assertEqual(2 if cleanup == 'unknown' else 3, len(released))

    def test_reference_resource_failure_blocks_grading_before_candidate_dispatch(self):
        with self.process_boundary([(self.started, TimeoutError('reference deadline')), self.broken]) as calls:
            receipt = native.grade(self.task, self.proposal, self.settings, None)
        self.assertEqual(2, len(calls))
        self.assertEqual('NATIVE_CALIBRATION_FAILED', receipt['error'])
        self.assertFalse(receipt['qualified'])
        self.assertFalse(receipt['outcomeKnown'])


class RepositoryNativeTests(unittest.TestCase):
    def test_workflow_expression_semantics_and_unsupported_bypass(self):
        namespace = {'re': re, 'json': json}
        source = native.DRIVER[native.DRIVER.index('TOKEN = '):native.DRIVER.index('def run():')]
        exec(compile(source, '<trusted-expression-parser>', 'exec'), namespace)
        evaluate = namespace['expression']
        self.assertTrue(evaluate("'SUCCESS' == 'success'", {})[0])
        self.assertTrue(evaluate("'1' == 1", {})[0])
        self.assertEqual('fallback', evaluate("false && fromJSON('invalid') || 'fallback'", {})[0])
        self.assertTrue(evaluate("contains(fromJSON('[\"PASS\"]'), 'pass')", {})[0])
        self.assertTrue(evaluate('always()', {'needs': {'lint': {'result': 'skipped'}}})[0])
        self.assertFalse(evaluate('success()', {'needs': {'lint': {'result': 'skipped'}}})[0])
        namespace['spec'] = {'jobs': ['gate']}
        with self.assertRaisesRegex(ValueError, 'Unsupported job'):
            namespace['action_observe']({'jobs': {'gate': {'continue-on-error': True}}}, {'needs': {}, 'github': {}})

    def test_kotlin_controls_compile_and_execute_when_compiler_is_configured(self):
        compiler = os.environ.get('GATEWAY_KOTLIN_COMPILER_CLASSPATH')
        standard = os.environ.get('GATEWAY_KOTLIN_STDLIB')
        java = os.environ.get('GATEWAY_JAVA_EXECUTABLE')
        if not (compiler and standard and java):
            self.skipTest('Set explicit local JVM/Kotlin compiler paths to execute native reference controls')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            commands = []
            for index, ident in enumerate(tasks.TASK_IDS[:4]):
                task = tasks.task_spec(ident)
                for label, sources in [('reference', task['referenceSources']), ('broken', task['sources'])]:
                    namespace = f'candidate{index}{label}'
                    source = sources['Task.kt'].replace('package candidate', 'package ' + namespace, 1)
                    (root / (namespace + '.kt')).write_text(source)
                    commands.append(f'"{index}{label}" -> {namespace}.solve(input)')
            (root / 'ControlMain.kt').write_text('''import java.util.Base64
fun main(args: Array<String>) {
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        val input = String(Base64.getDecoder().decode(line), Charsets.UTF_8)
        val output = when(args[0]) {
''' + '\n'.join(commands) + '''
            else -> error("Unknown trusted control")
        }
        println(Base64.getEncoder().encodeToString(output.toByteArray(Charsets.UTF_8)))
    }
}
''')
            artifact = root / 'controls.jar'
            compile_result = subprocess.run([java, '-XX:ActiveProcessorCount=2', '-Xmx512m', '-cp', compiler,
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', standard,
                '-d', str(artifact), *map(str, root.glob('*.kt'))], capture_output=True, timeout=90)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr.decode()[-4000:])
            for index, ident in enumerate(tasks.TASK_IDS[:4]):
                task = tasks.task_spec(ident)
                data = '\n'.join(base64.b64encode(row['events'].encode()).decode() for row in task['vectors']) + '\n'
                for label in ('reference', 'broken'):
                    with self.subTest(task=ident, control=label):
                        result = subprocess.run([java, '-Xmx96m', '-cp', str(artifact) + os.pathsep + standard,
                            'ControlMainKt', str(index) + label], input=data.encode(), capture_output=True, timeout=15)
                        self.assertEqual(0, result.returncode, result.stderr.decode()[-2000:])
                        actual = [base64.b64decode(line).decode() for line in result.stdout.splitlines()]
                        if label == 'reference': self.assertEqual(task['expected'], actual)
                        else: self.assertNotEqual(task['expected'], actual)

    def test_extent_has_distinct_defects_and_no_manufactured_variants(self):
        self.assertEqual([4, 8, 12], [len(tasks.task_manifest(preset)) for preset in ('quick', 'standard', 'deep')])
        values = tasks.task_manifest('deep')
        self.assertEqual(12, len({row['id'] for row in values}))
        self.assertEqual(12, len({row['sources'][next(iter(row['sources']))] for row in values}))
        self.assertTrue(all(row['contract'] and row['taskDigest'] == row['manifestDigest'] for row in values))
        self.assertNotEqual(values[0]['hiddenTestsDigest'], tasks.task_spec(values[0]['id'], 18)['hiddenTestsDigest'])

    def test_sqlite_controls_run_real_migrations_and_kill_each_defect(self):
        for ident in tasks.TASK_IDS[4:8]:
            with self.subTest(task=ident):
                task = tasks.task_spec(ident)
                reference = trusted_observe(task, task['referenceSources'])
                broken = trusted_observe(task, task['sources'])
                self.assertIn('sqliteVersion', reference['runtime'])
                self.assertEqual(task['expected'], reference['outputs'])
                self.assertNotEqual(task['expected'], broken.get('outputs'))

    def test_yaml_gate_controls_execute_semantics_and_shell_not_substrings(self):
        try:
            import yaml
        except ImportError:
            self.skipTest('PyYAML exists in the configured native runtime image, not a Gateway dependency')
        for ident in tasks.TASK_IDS[8:]:
            with self.subTest(task=ident):
                task = tasks.task_spec(ident)
                reference = trusted_observe(task, task['referenceSources'])
                broken = trusted_observe(task, task['sources'])
                self.assertIn('yamlVersion', reference['runtime'])
                self.assertEqual(task['expected'], reference.get('outputs'))
                self.assertNotEqual(task['expected'], broken.get('outputs'))

    def test_proposal_is_bound_to_exact_source_blob(self):
        task = tasks.task_spec(tasks.TASK_IDS[0])
        path, source = next(iter(task['sources'].items()))
        proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                           'content': task['referenceSources'][path]}]})
        self.assertEqual(task['referenceSources'], native.parse_proposal(task, proposal))
        with self.assertRaises(ValueError):
            native.parse_proposal(task, proposal.replace(blob_sha(source), '0' * 40))

    def test_no_runtime_is_blocked_not_a_model_failure(self):
        task = tasks.task_spec(tasks.TASK_IDS[0])
        path, source = next(iter(task['sources'].items()))
        proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source), 'content': task['referenceSources'][path]}]})
        with patch.object(native, 'execute', side_effect=SandboxUnavailable('Configure runtime', 'IMAGE_MISSING')):
            receipt = native.grade(task, proposal, {}, None)
        self.assertEqual('BLOCKED', receipt['status'])
        self.assertEqual('SETUP', receipt['failureDomain'])
        self.assertFalse(receipt['outcomeKnown'])
        self.assertFalse(receipt['qualified'])

    def test_forged_success_does_not_replace_output_comparison(self):
        task = tasks.task_spec(tasks.TASK_IDS[0])
        path, source = next(iter(task['sources'].items()))
        proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source), 'content': task['referenceSources'][path]}]})
        good = dict(executed=True, runtime={'compiled': True}, outputs=task['expected'])
        bad = dict(executed=True, runtime={'compiled': True}, outputs=['wrong'])
        with patch.object(native, 'execute', side_effect=[good, bad, dict(bad, passed=True, qualified=True)]):
            receipt = native.grade(task, proposal, {}, None)
        self.assertTrue(receipt['qualified'])
        self.assertFalse(receipt['resolved'])
        self.assertEqual('FAILED', receipt['status'])
        self.assertIsNone(receipt['score'])

    def test_bad_reference_or_unkilled_control_blocks_model_scoring(self):
        task = tasks.task_spec(tasks.TASK_IDS[0])
        path, source = next(iter(task['sources'].items()))
        proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source), 'content': task['referenceSources'][path]}]})
        good = dict(executed=True, runtime={'compiled': True}, outputs=task['expected'])
        with patch.object(native, 'execute', side_effect=[good, good]) as execute:
            receipt = native.grade(task, proposal, {}, None)
        self.assertEqual(2, execute.call_count)
        self.assertEqual('NATIVE_CALIBRATION_FAILED', receipt['error'])
        self.assertFalse(receipt['qualified'])

    def test_expected_answers_never_enter_candidate_mounts(self):
        task = tasks.task_spec(tasks.TASK_IDS[0])
        image = 'native/runtime@sha256:' + 'a' * 64
        files, workflow = native.remote_bundle(task, task['referenceSources'], image, 'fixture01')
        self.assertIn('self-hosted', files[workflow])
        verifier = files['benchmark-native/fixture01/verify.py']
        self.assertIn('--pull=never', verifier)
        self.assertIn("root / 'evaluator'", verifier)
        self.assertNotIn('expected', json.dumps(native.input_bundle(task)))
        self.assertNotIn(task['oracleDigest'], files['benchmark-native/fixture01/evaluator/driver.py'])
        compile(verifier, '<trusted-remote-verifier>', 'exec')
        compile(native.DRIVER, '<trusted-native-driver>', 'exec')
        with self.assertRaises(ValueError):
            native.remote_bundle(task, task['referenceSources'], 'native:latest', 'fixture01')


if __name__ == '__main__':
    unittest.main()
