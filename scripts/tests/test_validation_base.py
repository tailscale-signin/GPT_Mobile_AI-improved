import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from scripts.resolve_validation_base import resolve_base


ROOT = Path(__file__).resolve().parents[2]


class ValidationBaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Validation fixture')
        self.git('config', 'user.email', 'validation@example.invalid')
        self.write('Legacy.kt', 'fun legacy() = Unit\n')
        self.base = self.commit('baseline')
        self.git('update-ref', 'refs/remotes/origin/main', self.base)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True, stderr=subprocess.PIPE).strip()

    def write(self, path, text):
        (self.repo / path).write_text(text, encoding='utf-8')

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def feature(self):
        self.git('checkout', '-b', 'repair/feature')
        self.write('Changed file.kt', 'fun changed() = Unit\n')
        return self.commit('feature change')

    def resolve(self, event='workflow_dispatch', **kwargs):
        return resolve_base(event, cwd=self.repo, **kwargs)

    def manual(self, **kwargs):
        return self.resolve(default_branch='main', current_ref='refs/heads/repair/feature', **kwargs)

    def test_manual_feature_uses_fetched_default_commit_and_only_changed_paths(self):
        self.feature()
        base = self.manual()
        self.assertEqual(self.base, base)
        paths = self.git('diff', '--name-only', base + '...HEAD').splitlines()
        self.assertEqual(['Changed file.kt'], paths)
        # An unrelated local branch is not a substitute for the fetched default.
        self.git('update-ref', 'refs/heads/main', 'HEAD')
        self.assertEqual(self.base, self.manual())

    def test_manual_default_branch_uses_first_parent_for_latest_change(self):
        self.write('Latest.kt', 'fun latest() = Unit\n')
        head = self.commit('latest default change')
        self.git('update-ref', 'refs/remotes/origin/main', head)
        self.assertEqual(self.base, self.resolve(default_branch='main', current_ref='refs/heads/main'))

    def test_manual_default_branch_merge_compares_to_first_parent(self):
        self.feature()
        self.git('checkout', 'main')
        self.write('Main.kt', 'fun mainChange() = Unit\n')
        parent = self.commit('parallel default change')
        self.git('merge', '--no-ff', 'repair/feature', '-m', 'merge feature')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        base = self.resolve(default_branch='main', current_ref='refs/heads/main')
        self.assertEqual(parent, base)
        self.assertEqual(['Changed file.kt'], self.git('diff', '--name-only', base + '...HEAD').splitlines())

    def test_explicit_manual_commit_is_exact_and_needs_no_default_ref(self):
        head = self.feature()
        self.git('update-ref', '-d', 'refs/remotes/origin/main')
        self.assertEqual(self.base, self.resolve(manual_base=self.base.upper()))
        with self.assertRaises(ValueError):
            self.resolve(manual_base=head)

    def test_explicit_descendant_or_unrelated_history_cannot_make_a_passing_empty_scope(self):
        self.feature()
        self.git('checkout', '-b', 'later')
        self.write('Later.kt', 'fun later() = Unit\n')
        later = self.commit('later commit')
        self.git('checkout', 'repair/feature')
        with self.assertRaises(ValueError):
            self.manual(manual_base=later)
        self.git('checkout', '--orphan', 'unrelated')
        self.git('rm', '-rf', '.')
        self.write('Other.kt', 'fun other() = Unit\n')
        unrelated = self.commit('unrelated root')
        self.git('checkout', 'repair/feature')
        with self.assertRaises(subprocess.CalledProcessError):
            self.manual(manual_base=unrelated)

    def test_pr_push_and_merge_queue_keep_event_base_without_manual_override(self):
        head = self.feature()
        for event in ('pull_request', 'push', 'merge_group'):
            with self.subTest(event=event):
                self.assertEqual(self.base, self.resolve(event, event_base=self.base, manual_base=head))
                with self.assertRaises(ValueError):
                    self.resolve(event, event_base='', manual_base=head)

    def test_invalid_missing_or_noncommit_inputs_fail_without_fallback(self):
        self.feature()
        blob = self.git('rev-parse', 'HEAD:Changed file.kt')
        for value in ('main', '0' * 40, 'f' * 40, '--all', self.base + '\n', blob):
            with self.subTest(value=value):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    self.manual(manual_base=value)
        for event in ('pull_request', 'push', 'merge_group'):
            with self.subTest(event=event):
                with self.assertRaises(ValueError):
                    self.resolve(event, event_base='0' * 40)

    def test_missing_remote_default_or_parent_fails_closed(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.resolve(default_branch='main', current_ref='refs/heads/main')
        self.feature()
        self.git('update-ref', '-d', 'refs/remotes/origin/main')
        with self.assertRaises(subprocess.CalledProcessError):
            self.manual()

    def test_invalid_branch_missing_context_and_unknown_events_fail_closed(self):
        self.feature()
        for values in (
            {'default_branch': '', 'current_ref': 'refs/heads/repair/feature'},
            {'default_branch': 'main', 'current_ref': ''},
            {'default_branch': 'main', 'current_ref': 'refs/tags/v1'},
            {'default_branch': 'bad..branch', 'current_ref': 'refs/heads/repair/feature'},
            {'default_branch': 'main', 'current_ref': 'refs/heads/bad..branch'},
        ):
            with self.subTest(values=values):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    self.resolve(**values)
        with self.assertRaises(ValueError):
            self.resolve('unexpected', event_base=self.base)

    def test_checker_runs_changed_paths_and_propagates_real_linter_failure(self):
        self.feature()
        base = self.manual()
        calls = []
        original_run = subprocess.run

        def run(command, *args, **kwargs):
            if command[0] != 'java':
                return original_run(command, *args, **kwargs)
            calls.append(command)
            # The external linter reports a style failure. The checker must fail
            # rather than hiding it after narrowing the repository comparison.
            raise subprocess.CalledProcessError(1, command)

        previous = Path.cwd()
        try:
            os.chdir(self.repo)
            with patch.object(sys, 'argv', ['check_changed_kotlin.py', '--base', base, '--ktlint', 'pinned-ktlint.jar']), \
                    patch.object(sys, 'path', [str(ROOT / 'scripts'), *sys.path]), \
                    patch('subprocess.run', side_effect=run), \
                    self.assertRaises(subprocess.CalledProcessError):
                runpy.run_path(str(ROOT / 'scripts/check_changed_kotlin.py'), run_name='__main__')
        finally:
            os.chdir(previous)
        self.assertEqual([['java', '-jar', 'pinned-ktlint.jar', 'Changed file.kt']], calls)

    def test_command_exports_only_the_resolved_sha_and_fails_before_export_on_error(self):
        self.feature()
        destination = self.repo / 'github-env'
        environment = {**os.environ, 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'CHANGE_BASE': '',
                       'MANUAL_CHANGE_BASE': '', 'DEFAULT_BRANCH': 'main',
                       'GITHUB_REF': 'refs/heads/repair/feature', 'GITHUB_ENV': str(destination)}
        command = [sys.executable, str(ROOT / 'scripts/resolve_validation_base.py')]
        result = subprocess.run(command, cwd=self.repo, env=environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('CHANGE_BASE=' + self.base + '\n', destination.read_text())
        environment['MANUAL_CHANGE_BASE'] = 'missing-ref'
        result = subprocess.run(command, cwd=self.repo, env=environment, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('CHANGE_BASE=' + self.base + '\n', destination.read_text())


if __name__ == '__main__':
    unittest.main()
