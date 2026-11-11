import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_validation import (validate, patch_identity, run_bounded,
                                       prepare_behavior_reports, capture_behavior_reports)


class IsolatedValidationTests(unittest.TestCase):
    REPORT = '<testsuite><testcase classname="Fixture" name="behavior"/></testsuite>'

    def validate_fixture(self, commands, coverage=None, changes=(), policy_options=None, execution_mode=None, workers=1, observer=None):
        """Exercise real archive/patch/command/report order; this is not Docker qualification."""
        fixture_env = {key: value for key, value in os.environ.items() if not key.upper().startswith('GIT_')}
        fixture_env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mirror = root / 'repository'
            mirror.mkdir()
            (mirror / 'reports').mkdir()
            (mirror / 'reports/behavior.xml').write_text(self.REPORT)
            (mirror / 'reports/unmapped.xml').write_text('keep this unrelated file')
            (mirror / 'source.txt').write_text('pinned candidate')
            subprocess.run(['git', 'init', '-q', str(mirror)], check=True, capture_output=True, env=fixture_env)
            subprocess.run(['git', '-C', str(mirror), 'add', '.'], check=True, capture_output=True, env=fixture_env)
            subprocess.run(['git', '-C', str(mirror), '-c', 'user.name=Validation Fixture', '-c',
                            'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Fixture'],
                           check=True, capture_output=True, env=fixture_env)
            head = subprocess.check_output(['git', '-C', str(mirror), 'rev-parse', 'HEAD'], text=True, env=fixture_env).strip()
            coverage = coverage or [{'requirement_id': 'behavior', 'case_id': 'Fixture.behavior', 'command_index': 0,
                                    'scope': 'behavior', 'report_path': 'reports/behavior.xml'}]
            image = 'fixture@sha256:' + 'a' * 64
            policy = root / 'policy.json'
            repository_policy = {'mirror': str(mirror / '.git'), 'image': image,
                                 'commands': commands, 'acceptance_map': coverage}
            repository_policy.update(policy_options or {})
            policy.write_text(json.dumps({'owner/repo': repository_policy}))

            def execute(argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None):
                if argv[0] != 'docker':
                    return run_bounded(argv, cwd, timeout, maximum, destination)
                mount = next(value for value in argv if value.startswith('--mount=type=bind,source='))
                checkout = mount.removeprefix('--mount=type=bind,source=').removesuffix(',target=/workspace')
                return run_bounded(argv[argv.index(image) + 1:], checkout, timeout)

            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_VALIDATION_POLICY': str(policy)}), \
                    patch('v14.engineering_validation.run_bounded', side_effect=observer or execute):
                request = {'repository': 'owner/repo'}
                data = {'head_sha': head}
                if execution_mode:
                    request['execution_mode'] = execution_mode
                    data['integration_batch'] = {'controls': {'local_validation_workers': workers}}
                return validate({'request': request, 'data': data}, list(changes))

    @staticmethod
    def emit_report(report):
        return [sys.executable, '-c', 'from pathlib import Path; Path("reports/behavior.xml").write_text(' + repr(report) + ')']

    def test_tracked_passing_report_is_removed_before_a_zero_exit_command_without_new_evidence(self):
        command = [sys.executable, '-c',
            'from pathlib import Path; '
            'assert not Path("reports/behavior.xml").exists(); '
            'assert Path("reports/unmapped.xml").read_text() == "keep this unrelated file"; '
            'assert Path("source.txt").read_text() == "pinned candidate"']
        result = self.validate_fixture([command])
        self.assertEqual(0, result['commands'][0]['exit_code'])
        self.assertEqual('FAILED', result['status'])
        self.assertEqual('UNAVAILABLE', result['requirement_evidence'][0]['status'])
        self.assertIsNone(result['requirement_evidence'][0]['report_sha256'])

    def test_fixture_git_does_not_inherit_another_checkout_or_index(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                'GIT_DIR': str(Path(directory) / 'unrelated.git'),
                'GIT_WORK_TREE': str(Path(directory) / 'unrelated-checkout'),
                'GIT_INDEX_FILE': str(Path(directory) / 'unrelated-index'),
                'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'core.bare', 'GIT_CONFIG_VALUE_0': 'true'}):
            result = self.validate_fixture([self.emit_report(self.REPORT)])
            self.assertEqual('PASSED', result['status'])
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_a_report_added_by_the_candidate_patch_is_not_execution_evidence(self):
        result = self.validate_fixture([[sys.executable, '-c', 'pass']],
            changes=[{'path': 'reports/behavior.xml', 'content': self.REPORT}])
        self.assertEqual('FAILED', result['status'])
        self.assertEqual('UNAVAILABLE', result['requirement_evidence'][0]['status'])

    def test_fresh_report_can_match_prior_bytes_and_is_bound_to_the_executed_candidate(self):
        result = self.validate_fixture([self.emit_report(self.REPORT)])
        self.assertEqual('PASSED', result['status'])
        evidence = result['requirement_evidence'][0]
        self.assertEqual('PASSED', evidence['status'])
        self.assertEqual(hashlib.sha256(self.REPORT.encode()).hexdigest(), evidence['report_sha256'])
        self.assertEqual(result['source_sha'], evidence['source_sha'])
        self.assertEqual(result['commands'][0]['output_sha256'], evidence['output_sha256'])

    def test_full_batch_runs_only_explicitly_grouped_local_checks_in_parallel_sandboxes(self):
        coverage = [{'requirement_id': f'behavior-{index}', 'case_id': 'Fixture.behavior', 'command_index': index,
                     'scope': 'behavior', 'report_path': 'reports/behavior.xml'} for index in range(2)]
        command = [sys.executable, '-c',
                   'import time; from pathlib import Path; time.sleep(0.2); Path("reports/behavior.xml").write_text(' + repr(self.REPORT) + ')']
        original = run_bounded
        active, peak = [0], [0]
        guard = threading.Lock()

        def observe(argv, cwd, timeout, maximum=4 * 1024 * 1024, destination=None):
            if argv[0] != 'docker':
                return original(argv, cwd, timeout, maximum, destination)
            with guard:
                active[0] += 1
                peak[0] = max(peak[0], active[0])
            try:
                time.sleep(0.1)
                mount = next(value for value in argv if value.startswith('--mount=type=bind,source='))
                checkout = mount.removeprefix('--mount=type=bind,source=').removesuffix(',target=/workspace')
                return original(argv[argv.index('fixture@sha256:' + 'a' * 64) + 1:], checkout,
                                timeout, maximum, destination)
            finally:
                with guard:
                    active[0] -= 1

        result = self.validate_fixture([command, command], coverage,
            policy_options={'parallel_command_groups': [[0, 1]]}, execution_mode='full', workers=2, observer=observe)
        self.assertEqual('PASSED', result['status'])
        self.assertEqual(2, result['validation_workers'])
        self.assertEqual([0, 0], [item['exit_code'] for item in result['commands']])
        self.assertEqual(2, peak[0])

    def test_each_command_has_its_own_report_snapshot_and_cannot_reuse_the_prior_output(self):
        coverage = [{'requirement_id': str(index), 'case_id': 'Fixture.behavior', 'command_index': index,
                     'scope': 'behavior', 'report_path': 'reports/behavior.xml'} for index in range(2)]
        skipped = '<testsuite><testcase classname="Fixture" name="behavior"><skipped/></testcase></testsuite>'
        for second, expected in (([sys.executable, '-c', 'pass'], 'UNAVAILABLE'), (self.emit_report(skipped), 'SKIPPED')):
            with self.subTest(expected=expected):
                result = self.validate_fixture([self.emit_report(self.REPORT), second], coverage)
                self.assertEqual('FAILED', result['status'])
                self.assertEqual(['PASSED', expected], [item['status'] for item in result['requirement_evidence']])

    def test_report_directories_are_never_removed_recursively(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'reports'
            path.mkdir()
            (path / 'keep.xml').write_text(self.REPORT)
            with self.assertRaises(ValueError):
                prepare_behavior_reports([{'report_path': 'reports'}], directory)
            self.assertEqual(self.REPORT, (path / 'keep.xml').read_text())

    def test_report_symlink_ancestors_and_leaf_links_are_rejected_without_touching_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout = root / 'checkout'
            checkout.mkdir()
            outside = root / 'outside'
            outside.mkdir()
            (outside / 'behavior.xml').write_text(self.REPORT)
            (checkout / 'inside').mkdir()
            (checkout / 'inside/behavior.xml').write_text(self.REPORT)
            try:
                (checkout / 'outside-link').symlink_to(outside, target_is_directory=True)
                (checkout / 'inside-link').symlink_to(checkout / 'inside', target_is_directory=True)
                (checkout / 'leaf.xml').symlink_to(outside / 'behavior.xml')
            except (OSError, NotImplementedError):
                self.skipTest('The host cannot create symbolic links')
            for relative in ('outside-link/behavior.xml', 'inside-link/behavior.xml', 'leaf.xml'):
                with self.subTest(relative=relative):
                    coverage = [{'report_path': relative, 'case_id': 'Fixture.behavior'}]
                    with self.assertRaises(ValueError):
                        prepare_behavior_reports(coverage, checkout)
                    self.assertEqual({}, capture_behavior_reports(coverage, checkout)[relative]['cases'])
            self.assertEqual(self.REPORT, (outside / 'behavior.xml').read_text())
            self.assertEqual(self.REPORT, (checkout / 'inside/behavior.xml').read_text())

    def test_missing_policy_is_blocked_not_tested(self):
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_VALIDATION_POLICY': ''}):
            receipt = validate({'request': {'repository': 'owner/repo'}, 'data': {'head_sha': 'a' * 40}}, [])
        self.assertEqual('BLOCKED', receipt['status'])
        self.assertEqual(patch_identity('a' * 40, []), receipt['patch_identity'])

    def test_revision_and_patch_each_invalidate_validation(self):
        changes = [{'path': 'file.kt', 'content': 'before'}]
        self.assertNotEqual(patch_identity('a' * 40, changes), patch_identity('b' * 40, changes))
        self.assertNotEqual(patch_identity('a' * 40, changes), patch_identity('a' * 40, [{'path': 'file.kt', 'content': 'after'}]))

    def test_command_output_and_runtime_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = run_bounded([sys.executable, '-c', 'print("tested")'], directory, 5)
            self.assertEqual(0, receipt['exit_code'])
            self.assertIn('tested', receipt['output_tail'])
            with self.assertRaises(ValueError):
                run_bounded([sys.executable, '-c', 'print("x" * 9000)'], directory, 5, maximum=100)
            with self.assertRaises(TimeoutError):
                run_bounded([sys.executable, '-c', 'import time; time.sleep(10)'], directory, .1)

    def test_isolated_child_initializes_async_runtime_without_inheriting_credentials(self):
        script = (
            'import asyncio,json,os,socket; '
            'asyncio.run(asyncio.sleep(0)); '
            'sock=socket.socket(); sock.close(); '
            'print(json.dumps({"home":os.environ.get("HOME"), '
            '"system_root":os.environ.get("SystemRoot"), '
            '"credentials_inherited":"GATEWAY_VALIDATION_TEST_SECRET" in os.environ, '
            '"python_path_inherited":"PYTHONPATH" in os.environ}))'
        )
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                'GATEWAY_VALIDATION_TEST_SECRET': 'must-not-reach-validation',
                'PYTHONPATH': 'must-not-reach-validation'}):
            receipt = run_bounded([sys.executable, '-c', script], directory, 10)
            self.assertEqual(0, receipt['exit_code'], receipt['output_tail'])
            observed = json.loads(receipt['output_tail'])
            self.assertEqual(directory, observed['home'])
            self.assertFalse(observed['credentials_inherited'])
            self.assertFalse(observed['python_path_inherited'])
            self.assertEqual(os.environ.get('SystemRoot') if os.name == 'nt' else None, observed['system_root'])

    def test_large_output_keeps_only_tail_and_hashes_all_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = run_bounded([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"x" * 100000)'], directory, 10)
        self.assertEqual(0, receipt['exit_code'])
        self.assertEqual('x' * 16000, receipt['output_tail'])
        self.assertEqual(hashlib.sha256(b'x' * 100000).hexdigest(), receipt['output_sha256'])

    def test_destination_is_bounded_before_writing_and_captures_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            output = io.BytesIO()
            receipt = run_bounded([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(bytes(range(256)))'], directory, 10, destination=output)
            self.assertEqual(bytes(range(256)), output.getvalue())
            self.assertEqual('', receipt['output_tail'])
            self.assertEqual(hashlib.sha256(output.getvalue()).hexdigest(), receipt['output_sha256'])
            output = io.BytesIO()
            with self.assertRaises(ValueError):
                run_bounded([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"x" * 100000)'], directory, 10, maximum=1000, destination=output)
            self.assertLessEqual(len(output.getvalue()), 1000)

    def test_failure_exit_and_stderr_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = run_bounded([sys.executable, '-c', 'import sys; sys.stderr.write("failed"); sys.exit(7)'], directory, 10)
        self.assertEqual(7, receipt['exit_code'])
        self.assertEqual('failed', receipt['output_tail'])

    def test_deadline_applies_after_output_pipe_is_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(TimeoutError):
                run_bounded([sys.executable, '-c', 'import os, time; os.close(1); os.close(2); time.sleep(10)'], directory, .5)
