"""Native references execute here only because every tested source is repo-owned.

Untrusted model proposals remain container-only. No Docker availability is
invented by these host reference tests; actual execution is a separate gate.
"""
import base64
from contextlib import contextmanager, redirect_stdout
import copy
import hashlib
import io
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
        self.reference, self.broken = self.controls_for(self.task)

    def controls_for(self, task):
        runtimes = {'kotlin': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': True},
                    'sqlite': {'kind': 'sqlite', 'sqliteVersion': '3.45.0'},
                    'actions': {'kind': 'actions', 'yamlVersion': '6.0', 'execution': 'YAML_EXPRESSION_AND_SHELL_SIMULATION'}}
        reference = {'runtime': runtimes[task['language']], 'outputs': copy.deepcopy(task['expected'])}
        broken = copy.deepcopy(reference)
        if task['language'] == 'kotlin':
            broken['outputs'][0] += '|incorrect'
        elif task['language'] == 'sqlite':
            broken['outputs'][0]['version'] += 1
        else:
            job = next(iter(broken['outputs'][0].values()))
            job['result'] = 'failure' if job['result'] == 'success' else 'success'
        self.assertNotEqual(reference['outputs'], broken['outputs'])
        return reference, broken

    @contextmanager
    def process_boundary(self, responses, *, cleanup='confirmed', cancel=None):
        """Only container/process I/O is replaced; execute and grade stay real."""
        remaining = list(responses)
        calls = []
        def bounded(argv, cwd, timeout, maximum, destination=None):
            calls.append(argv)
            response = remaining.pop(0)
            exit_code = 0
            if isinstance(response, tuple):
                raw, failure, *exit_codes = response
                exit_code = exit_codes[0] if exit_codes else 0
                destination.write(raw)
                destination.flush()
                if cancel is not None:
                    cancel.set()
                if failure is not None:
                    raise failure
            else:
                raw = self.started + json.dumps(response).encode()
                destination.write(raw)
            return {'exit_code': exit_code, 'elapsed_ms': 17, 'output_sha256': hashlib.sha256(raw).hexdigest()}
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

    def assert_unqualified_observation(self, candidate):
        with self.process_boundary([self.reference, self.broken, candidate]) as calls:
            receipt = native.grade(self.task, self.proposal, self.settings, None)
        self.assertEqual(3, len(calls))
        self.assertEqual('BLOCKED', receipt['status'])
        self.assertEqual('HARNESS', receipt['failureDomain'])
        self.assertEqual('NATIVE_OBSERVATION_UNAVAILABLE', receipt['error'])
        for field in ('graded', 'qualified', 'outcomeKnown', 'resolved'):
            self.assertIs(False, receipt[field], field)
        self.assertIsNone(receipt['score'])
        self.assertNotIn('PRIVATE_DETAIL', json.dumps(receipt))

    def test_malformed_native_runtime_and_outputs_are_never_model_evidence(self):
        candidates = [{}, {'outputs': []}, {'runtime': [], 'outputs': []},
                      {'runtime': {}, 'outputs': []}, {'runtime': {'kind': 'kotlin'}, 'outputs': []},
                      {'runtime': {'kind': 'sqlite', 'PRIVATE_DETAIL': 'unapproved'}, 'outputs': []},
                      {'runtime': {'kind': 'sqlite', 'sqliteVersion': True}, 'outputs': []},
                      {'runtime': {'kind': 'sqlite', 'sqliteVersion': ''}, 'outputs': []},
                      {'runtime': {'kind': 'sqlite', 'sqliteVersion': 'x' * 1025}, 'outputs': []},
                      {'runtime': self.reference['runtime'], 'outputs': None},
                      {'runtime': self.reference['runtime'], 'outputs': 'PRIVATE_DETAIL'},
                      {'runtime': self.reference['runtime'], 'outputs': ['PRIVATE_DETAIL']},
                      {'runtime': self.reference['runtime']}]
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                self.assert_unqualified_observation(candidate)

    def test_unknown_untyped_or_contradictory_native_errors_remain_private_and_unqualified(self):
        candidates = [{'candidateError': value, 'outputs': []} for value in
                      ('PRIVATE_DETAIL', True, None, [], {}, 'COMPILATION_FAILED', 'NATIVE_EXECUTION_DEADLINE')]
        candidates += [{'runtimeError': value} for value in ('PRIVATE_DETAIL', True, None, [], {})]
        candidates += [{'candidateError': 'NATIVE_STAGE_DEADLINE', 'outputs': ['PRIVATE_DETAIL']},
                       {'candidateError': 'NATIVE_OUTPUT_LIMIT'},
                       {'candidateError': 'NATIVE_STAGE_DEADLINE', 'runtime': self.reference['runtime'], 'outputs': []},
                       {'runtimeError': 'NATIVE_RUNTIME_OBSERVATION_FAILED', 'outputs': []},
                       {'runtimeError': 'NATIVE_RUNTIME_OBSERVATION_FAILED', 'candidateError': 'NATIVE_OUTPUT_LIMIT', 'outputs': []},
                       {'runtimeError': 'NATIVE_RUNTIME_OBSERVATION_FAILED', 'outputs': ['PRIVATE_DETAIL']}]
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                self.assert_unqualified_observation(candidate)

    def test_duplicate_non_finite_and_overdeep_native_json_remains_unqualified(self):
        bodies = [b'{"runtime":{"kind":"sqlite"},"outputs":[],"outputs":[]}',
                  b'{"runtime":{"kind":"sqlite","kind":"sqlite"},"outputs":[]}',
                  b'{"runtime":{"kind":"sqlite"},"outputs":[NaN]}',
                  b'{"runtime":{"kind":"sqlite"},"outputs":[Infinity]}',
                  b'{"runtime":{"kind":"sqlite"},"outputs":[1e9999]}',
                  b'{"runtime":{"kind":"sqlite"},"outputs":' + b'[' * 2000 + b']' * 2000 + b'}']
        for body in bodies:
            with self.subTest(body_prefix=body[:80]):
                self.assert_unqualified_observation((self.started + body, None))

    def test_all_driver_candidate_failures_and_host_nonzero_exit_remain_calibrated(self):
        cases = [('repository-kotlin-stale-generation', 'COMPILATION_FAILED', {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': False}),
                 ('repository-kotlin-stale-generation', 'EXECUTION_FAILED', {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': True}),
                 ('repository-kotlin-stale-generation', 'INVALID_OUTPUT_FRAMING', {'kind': 'kotlin', 'compiler': 'kotlinc 2', 'compiled': True}),
                 ('repository-ci-required-needs', 'WORKFLOW_CONTRACT_INVALID',
                  {'kind': 'actions', 'yamlVersion': '6.0'}),
                 ('repository-room-add-default', 'NATIVE_STAGE_DEADLINE', None),
                 ('repository-room-add-default', 'NATIVE_OUTPUT_LIMIT', None),
                 ('repository-room-add-default', 'NATIVE_PROCESS_FAILED', None)]
        for task_id, code, runtime in cases:
            task = tasks.task_spec(task_id)
            proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(task['sources'][path]),
                                               'content': content} for path, content in task['referenceSources'].items()]})
            reference, broken = self.controls_for(task)
            candidate = {'candidateError': code, 'outputs': []}
            if runtime is not None:
                candidate['runtime'] = runtime
            if code == 'NATIVE_PROCESS_FAILED':
                candidate = (self.started, None, 1)
            with self.subTest(code=code), self.process_boundary([reference, broken, candidate]) as calls:
                receipt = native.grade(task, proposal, self.settings, None)
            self.assertEqual(3, len(calls))
            self.assertEqual('PASSED', receipt['calibration']['status'])
            self.assertEqual(('FAILED', True, True, True, 'MODEL', code),
                             tuple(receipt[key] for key in ('status', 'graded', 'qualified', 'outcomeKnown', 'failureDomain', 'error')))
            self.assertFalse(receipt['resolved'])
            self.assertIsNone(receipt['score'])

    def test_known_runtime_failures_stay_unqualified(self):
        for code in ('KOTLIN_RUNTIME_UNAVAILABLE', 'KOTLIN_COMPILER_UNAVAILABLE', 'YAML_RUNTIME_UNAVAILABLE',
                     'LANGUAGE_UNAVAILABLE', 'NATIVE_RUNTIME_OBSERVATION_FAILED'):
            with self.subTest(code=code), self.process_boundary([self.reference, self.broken, {'runtimeError': code}]):
                receipt = native.grade(self.task, self.proposal, self.settings, None)
            self.assertEqual('BLOCKED', receipt['status'])
            self.assertEqual(code, receipt['error'])
            self.assertFalse(receipt['graded'])
            self.assertFalse(receipt['qualified'])
            self.assertFalse(receipt['outcomeKnown'])

    def test_actual_sqlite_driver_receipts_keep_calibrated_success(self):
        reference = trusted_observe(self.task, self.task['referenceSources'])
        broken = trusted_observe(self.task, self.task['sources'])
        with self.process_boundary([reference, broken, reference]) as calls:
            receipt = native.grade(self.task, self.proposal, self.settings, None)
        self.assertEqual(3, len(calls))
        self.assertEqual('PASSED', receipt['calibration']['status'])
        self.assertEqual('PASSED', receipt['status'])
        for field in ('graded', 'qualified', 'outcomeKnown', 'resolved'):
            self.assertIs(True, receipt[field], field)
        self.assertEqual(reference['runtime'], receipt['runtime'])

    def test_native_runtime_types_and_compilation_contradictions_stay_unqualified(self):
        cases = [('repository-kotlin-stale-generation', {'runtime': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': 'true'}, 'outputs': []}),
                 ('repository-kotlin-stale-generation', {'runtime': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': False}, 'outputs': []}),
                 ('repository-kotlin-stale-generation', {'runtime': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': True}, 'candidateError': 'COMPILATION_FAILED', 'outputs': []}),
                 ('repository-kotlin-stale-generation', {'runtime': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': False}, 'candidateError': 'EXECUTION_FAILED', 'outputs': []}),
                 ('repository-kotlin-stale-generation', {'runtime': {'kind': 'kotlin', 'compiler': 'kotlinc fixture', 'compiled': True}, 'outputs': [{}]}),
                 ('repository-ci-required-needs', {'runtime': {'kind': 'actions', 'yamlVersion': '6.0', 'execution': 'PRIVATE_DETAIL'}, 'outputs': []}),
                 ('repository-ci-required-needs', {'runtime': {'kind': 'actions', 'yamlVersion': 6, 'execution': 'YAML_EXPRESSION_AND_SHELL_SIMULATION'}, 'outputs': []})]
        for task_id, candidate in cases:
            self.task = tasks.task_spec(task_id)
            self.proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(self.task['sources'][path]),
                                                    'content': content} for path, content in self.task['referenceSources'].items()]})
            self.reference, self.broken = self.controls_for(self.task)
            with self.subTest(task=task_id, candidate=candidate):
                self.assert_unqualified_observation(candidate)

    def test_normal_observations_require_complete_runtime_metadata(self):
        for task_id in ('repository-kotlin-stale-generation', 'repository-room-add-default', 'repository-ci-required-needs'):
            self.task = tasks.task_spec(task_id)
            self.proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(self.task['sources'][path]),
                                                    'content': content} for path, content in self.task['referenceSources'].items()]})
            self.reference, self.broken = self.controls_for(self.task)
            for field in set(self.reference['runtime']) - {'kind'}:
                candidate = copy.deepcopy(self.reference)
                del candidate['runtime'][field]
                with self.subTest(task=task_id, missing=field):
                    self.assert_unqualified_observation(candidate)

    def test_normal_observations_require_exact_declared_output_count(self):
        for outputs in ([], self.task['expected'][:-1], self.task['expected'] + self.task['expected'][:1]):
            with self.subTest(output_count=len(outputs)):
                self.assert_unqualified_observation({'runtime': self.reference['runtime'], 'outputs': outputs})

    def test_sqlite_observation_fields_and_probe_coverage_are_required(self):
        variants = [('empty observation', lambda value: value.clear())]
        variants += [(f'missing {field}', lambda value, field=field: value.pop(field))
                     for field in ('error', 'schema', 'rows', 'checks', 'version')]
        variants += [('untyped error', lambda value: value.update(error=0)),
                     ('untyped version', lambda value: value.update(version=True)),
                     ('extra observation field', lambda value: value.update(PRIVATE_DETAIL=True)),
                     ('untyped schema', lambda value: value.update(schema=[])),
                     ('untyped table rows', lambda value: value.update(rows=[])),
                     ('missing probe observations', lambda value: value.update(checks=[])),
                     ('extra probe observation', lambda value: value['checks'].append({'ok': False})),
                     ('untyped probe', lambda value: value['checks'].__setitem__(0, {'ok': 1})),
                     ('missing probe rows', lambda value: value['checks'].__setitem__(0, {'ok': True})),
                     ('rows on failed probe', lambda value: value['checks'].__setitem__(0, {'ok': False, 'rows': []})),
                     ('untyped probe rows', lambda value: value['checks'].__setitem__(0, {'ok': True, 'rows': [[{}]]})),
                     ('mismatched tables', lambda value: value['rows'].update(unobserved=[]))]
        self.assertTrue(self.reference['outputs'][0]['checks'])
        for label, modify in variants:
            candidate = copy.deepcopy(self.reference)
            modify(candidate['outputs'][0])
            with self.subTest(variant=label):
                self.assert_unqualified_observation(candidate)

    def test_sqlite_column_foreign_key_and_row_types_are_required(self):
        table = next(iter(self.reference['outputs'][0]['schema']))
        valid_column = ['id', 'TEXT', 0, None, 1]
        columns = [[], ['id', 'TEXT', 0, None], [1, 'TEXT', 0, None, 1], ['id', None, 0, None, 1],
                   ['id', 'TEXT', True, None, 1], ['id', 'TEXT', 2, None, 1], ['id', 'TEXT', 0, {}, 1],
                   ['id', 'TEXT', 0, None, True], ['id', 'TEXT', 0, None, -1]]
        foreign_keys = [[], ['parent', 'id', None, 'NO ACTION', 'NO ACTION'],
                        [None, 'id', None, 'NO ACTION', 'NO ACTION', 'NONE'],
                        ['parent', None, None, 'NO ACTION', 'NO ACTION', 'NONE'],
                        ['parent', 'id', {}, 'NO ACTION', 'NO ACTION', 'NONE'],
                        ['parent', 'id', None, [], 'NO ACTION', 'NONE'],
                        ['parent', 'id', None, 'PRIVATE_DETAIL', 'NO ACTION', 'NONE'],
                        ['parent', 'id', None, 'NO ACTION', 'PRIVATE_DETAIL', 'NONE'],
                        ['parent', 'id', None, 'NO ACTION', 'NO ACTION', None]]
        variants = [('column', {'columns': [value], 'foreignKeys': []}) for value in columns]
        variants += [('foreign key', {'columns': [valid_column], 'foreignKeys': [value]}) for value in foreign_keys]
        variants += [('table shape', value) for value in ({}, {'columns': [], 'foreignKeys': {}},
                                                       {'columns': {}, 'foreignKeys': []})]
        for label, schema in variants:
            candidate = copy.deepcopy(self.reference)
            candidate['outputs'][0]['schema'][table] = schema
            with self.subTest(variant=label, schema=schema):
                self.assert_unqualified_observation(candidate)
        for rows in ({}, [None], [[{}]], [[[]]], [[True]]):
            candidate = copy.deepcopy(self.reference)
            candidate['outputs'][0]['rows'][table] = rows
            with self.subTest(rows=rows):
                self.assert_unqualified_observation(candidate)

    def test_actions_require_each_declared_job_and_typed_result_permissions(self):
        for task_id in ('repository-ci-required-needs', 'repository-ci-fork-permissions'):
            self.task = tasks.task_spec(task_id)
            self.proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(self.task['sources'][path]),
                                                    'content': content} for path, content in self.task['referenceSources'].items()]})
            self.reference, self.broken = self.controls_for(self.task)
            job = native.input_bundle(self.task)['jobs'][0]
            for malformed in ({}, {'result': 'success'}, {'permissions': {}},
                              {'result': True, 'permissions': {}}, {'result': 'cancelled', 'permissions': {}},
                              {'result': 'success', 'permissions': []}, {'result': 'success', 'permissions': {'contents': 'none'}},
                              {'result': 'success', 'permissions': {'contents': True}},
                              {'result': 'success', 'permissions': {'contents': 'PRIVATE_DETAIL'}}):
                candidate = copy.deepcopy(self.reference)
                candidate['outputs'][0][job] = malformed
                with self.subTest(task=task_id, job=malformed):
                    self.assert_unqualified_observation(candidate)
            for names in ({}, {**self.reference['outputs'][0], 'extra': {'result': 'success', 'permissions': {}}}):
                candidate = copy.deepcopy(self.reference)
                candidate['outputs'][0] = names
                with self.subTest(task=task_id, jobs=list(names)):
                    self.assert_unqualified_observation(candidate)

    def test_actual_sqlite_schema_data_and_error_variants_remain_model_failures(self):
        variants = ['DROP TABLE profiles;', 'ALTER TABLE profiles RENAME TO renamed_profiles;',
                    "CREATE TABLE extra_table(id INTEGER, amount REAL, note TEXT); INSERT INTO extra_table VALUES (1,1.25,NULL);",
                    "UPDATE profiles SET name='changed';", 'THIS IS NOT VALID SQL;',
                    'CREATE TABLE child(parent_id TEXT REFERENCES profiles);',
                    'ALTER TABLE profiles ADD COLUMN folded TEXT GENERATED ALWAYS AS (lower(name)) VIRTUAL;']
        reference = trusted_observe(self.task, self.task['referenceSources'])
        broken = trusted_observe(self.task, self.task['sources'])
        for sql in variants:
            # These fixed test-owned SQL mutations never execute model output.
            task = copy.deepcopy(self.task)
            task['referenceSources'] = {'migration.sql': sql}
            observed = trusted_observe(task, task['referenceSources'])
            proposal = json.dumps({'changes': [{'path': 'migration.sql', 'expected_blob_sha': blob_sha(self.task['sources']['migration.sql']),
                                               'content': sql}]})
            with self.subTest(sql=sql), self.process_boundary([reference, broken, observed]):
                receipt = native.grade(self.task, proposal, self.settings, None)
            self.assertEqual('FAILED', receipt['status'])
            self.assertEqual('MODEL', receipt['failureDomain'])
            for field in ('graded', 'qualified', 'outcomeKnown'):
                self.assertIs(True, receipt[field], field)
            self.assertFalse(receipt['resolved'])

    def test_complete_wrong_action_results_and_permissions_remain_model_failures(self):
        self.task = tasks.task_spec('repository-ci-fork-permissions')
        self.proposal = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(self.task['sources'][path]),
                                                'content': content} for path, content in self.task['referenceSources'].items()]})
        self.reference, self.broken = self.controls_for(self.task)
        permission_change = copy.deepcopy(self.reference)
        permissions = permission_change['outputs'][0]['gate']['permissions']
        permissions['contents'] = 'read' if permissions.get('contents') == 'write' else 'write'
        for observed in (self.broken, permission_change):
            with self.subTest(observed=observed['outputs'][0]), self.process_boundary([self.reference, self.broken, observed]):
                receipt = native.grade(self.task, self.proposal, self.settings, None)
            self.assertEqual('FAILED', receipt['status'])
            self.assertEqual('MODEL', receipt['failureDomain'])
            self.assertTrue(receipt['qualified'])
            self.assertTrue(receipt['outcomeKnown'])

    def test_malformed_calibration_receipts_prevent_candidate_dispatch(self):
        controls = [({'runtime': {}, 'outputs': self.task['expected']}, self.broken),
                    (self.reference, {'runtime': self.reference['runtime']}),
                    (self.reference, {'runtime': self.reference['runtime'], 'outputs': []}),
                    (self.reference, {'runtime': self.reference['runtime'], 'outputs': self.task['expected'][:-1]}),
                    (self.reference, {'runtime': self.reference['runtime'], 'outputs': [{} for _ in self.task['vectors']]})]
        for reference, broken in controls:
            with self.subTest(reference=reference, broken=broken), self.process_boundary([reference, broken]) as calls:
                receipt = native.grade(self.task, self.proposal, self.settings, None)
            self.assertEqual(2, len(calls))
            self.assertEqual('NATIVE_CALIBRATION_FAILED', receipt['error'])
            self.assertFalse(receipt['qualified'])
            self.assertFalse(receipt['outcomeKnown'])


class GeneratedNativeReceiptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.task = tasks.task_spec('repository-room-add-default')
        cls.reference = trusted_observe(cls.task, cls.task['referenceSources'])
        cls.broken = trusted_observe(cls.task, cls.task['sources'])
        cls.source = native._remote_verifier('native/runtime@sha256:' + 'a' * 64, cls.task)
        assert cls.reference['outputs'] == cls.task['expected']
        assert cls.broken['outputs'] != cls.task['expected']
        assert len(cls.reference['outputs']) == len(cls.broken['outputs']) == len(cls.task['vectors'])

    def generated_run(self, responses):
        remaining = copy.deepcopy(responses)
        dispatched = []
        def run(argv, **kwargs):
            if 'run' in argv:
                dispatched.append(argv)
                payload = remaining.pop(0)
                output = (native.START + '\n' + json.dumps(payload)).encode()
                return subprocess.CompletedProcess(argv, 0, stdout=output, stderr=b'')
            self.assertIn('rm', argv)
            return subprocess.CompletedProcess(argv, 0, stdout=b'', stderr=b'')
        with tempfile.TemporaryDirectory(prefix='native-remote-proof-') as directory:
            root = Path(directory)
            (root / 'expected.json').write_text(json.dumps(self.task['expected']))
            # New protocol fields must be derived from this actual fixed task.
            (root / 'evaluator').mkdir()
            (root / 'evaluator/inputs.json').write_text(json.dumps(native.input_bundle(self.task)))
            namespace = {'__file__': str(root / 'verify.py'), '__name__': '__main__'}
            stream = io.StringIO()
            code, error = 0, None
            with patch('subprocess.run', side_effect=run), redirect_stdout(stream):
                try:
                    exec(compile(self.source, str(root / 'verify.py'), 'exec'), namespace)
                except SystemExit as stopped:
                    code = stopped.code
                except Exception as failed:
                    code, error = 1, type(failed).__name__
            lines = [line for line in stream.getvalue().splitlines() if line.startswith('{')]
            document = json.loads(lines[-1]) if lines else {}
            return code, document, len(dispatched), error

    def test_complete_actual_driver_controls_and_candidate_stay_green(self):
        code, receipt, dispatched, error = self.generated_run([self.reference, self.broken, self.reference])
        self.assertEqual((0, None, 3), (code, error, dispatched))
        self.assertIs(True, receipt['qualified'])
        self.assertIs(True, receipt['resolved'])

    def test_incomplete_candidate_runtime_cannot_publish_success(self):
        for runtime in ({}, {'kind': 'sqlite'}):
            malformed = dict(self.reference, runtime=runtime)
            with self.subTest(runtime=runtime):
                code, receipt, _, _ = self.generated_run([self.reference, self.broken, malformed])
                self.assertFalse(code == 0 and receipt.get('qualified') is True, receipt)

    def test_incomplete_reference_runtime_cannot_qualify_calibration(self):
        for runtime in ({}, {'kind': 'sqlite'}):
            malformed = dict(self.reference, runtime=runtime)
            with self.subTest(runtime=runtime):
                code, receipt, dispatched, _ = self.generated_run([malformed, self.broken, self.reference])
                self.assertFalse(code == 0 and receipt.get('qualified') is True, receipt)
                self.assertLess(dispatched, 3)

    def test_incomplete_broken_control_cannot_qualify_calibration(self):
        for outputs in ([], self.broken['outputs'][:1]):
            malformed = dict(self.broken, outputs=outputs)
            with self.subTest(output_count=len(outputs)):
                code, receipt, dispatched, _ = self.generated_run([self.reference, malformed, self.reference])
                self.assertFalse(code == 0 and receipt.get('qualified') is True, receipt)
                self.assertLess(dispatched, 3)


    def test_runtime_failures_and_unknown_errors_never_publish_qualified_candidate_evidence(self):
        candidates = [{'runtimeError': 'NATIVE_RUNTIME_OBSERVATION_FAILED'},
                      {'candidateError': 'PRIVATE_DETAIL', 'outputs': []},
                      {'candidateError': None, 'outputs': []}]
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                code, receipt, dispatched, _ = self.generated_run([self.reference, self.broken, candidate])
                self.assertNotEqual(0, code)
                self.assertEqual(3, dispatched)
                self.assertNotIn('qualified', receipt)
                self.assertNotIn('PRIVATE_DETAIL', json.dumps(receipt))

    def test_closed_driver_resource_failures_remain_qualified_failed_candidates(self):
        for code in ('NATIVE_STAGE_DEADLINE', 'NATIVE_OUTPUT_LIMIT'):
            with self.subTest(code=code):
                stopped, receipt, dispatched, error = self.generated_run([
                    self.reference, self.broken, {'candidateError': code, 'outputs': []}])
                self.assertEqual((1, None, 3), (stopped, error, dispatched))
                self.assertIs(True, receipt['qualified'])
                self.assertIs(False, receipt['resolved'])

    def test_generated_nested_sqlite_observations_are_required_for_candidate_and_controls(self):
        malformed = copy.deepcopy(self.reference)
        malformed['outputs'] = [{} for _ in self.task['vectors']]
        for position in range(3):
            responses = [self.reference, self.broken, self.reference]
            responses[position] = malformed
            with self.subTest(position=position):
                code, receipt, dispatched, _ = self.generated_run(responses)
                self.assertNotEqual(0, code)
                self.assertNotIn('qualified', receipt)
                if position < 2:
                    self.assertLess(dispatched, 3)

    def test_generated_complete_wrong_sqlite_observations_remain_model_failures(self):
        code, receipt, dispatched, error = self.generated_run([self.reference, self.broken, self.broken])
        self.assertEqual((1, None, 3), (code, error, dispatched))
        self.assertIs(True, receipt['qualified'])
        self.assertIs(False, receipt['resolved'])

    def test_generated_actions_require_declared_jobs_and_preserve_typed_wrong_values(self):
        self.task = tasks.task_spec('repository-ci-fork-permissions')
        self.reference = {'runtime': {'kind': 'actions', 'yamlVersion': '6.0', 'execution': 'YAML_EXPRESSION_AND_SHELL_SIMULATION'},
                          'outputs': copy.deepcopy(self.task['expected'])}
        self.broken = copy.deepcopy(self.reference)
        gate = self.broken['outputs'][0]['gate']
        gate['result'] = 'failure' if gate['result'] == 'success' else 'success'
        self.source = native._remote_verifier('native/runtime@sha256:' + 'a' * 64, self.task)
        for candidate, expected in ((self.reference, True), (self.broken, False)):
            with self.subTest(resolved=expected):
                code, receipt, dispatched, error = self.generated_run([self.reference, self.broken, candidate])
                self.assertEqual((0 if expected else 1, None, 3), (code, error, dispatched))
                self.assertIs(True, receipt['qualified'])
                self.assertIs(expected, receipt['resolved'])
        for job in ({}, {'result': 'success'}, {'result': 'cancelled', 'permissions': {}},
                    {'result': 'success', 'permissions': {'contents': 'none'}}):
            candidate = copy.deepcopy(self.reference)
            candidate['outputs'][0]['gate'] = job
            with self.subTest(job=job):
                code, receipt, dispatched, _ = self.generated_run([self.reference, self.broken, candidate])
                self.assertNotEqual(0, code)
                self.assertEqual(3, dispatched)
                self.assertNotIn('qualified', receipt)


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
