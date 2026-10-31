"""Real Git graph tests for immutable, forward-only release recovery proposals."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_release as release


class ReleaseRollbackTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous_directory = Path.cwd()
        os.chdir(self.directory.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.email', 'fixture@example.test')
        self.git('config', 'user.name', 'Release fixture')
        self.root = self.commit('root', 'base')
        self.target = self.commit('change', 'feature')

    def tearDown(self):
        os.chdir(self.previous_directory)
        self.directory.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self, name, content):
        Path(name).write_text(content)
        self.git('add', name)
        self.git('commit', '-m', name)
        return self.git('rev-parse', 'HEAD')

    def test_proposal_pins_ancestry_and_does_not_apply_or_publish(self):
        base = self.commit('later', 'preserve me')
        plan = release.rollback_plan(base, self.target)
        self.assertEqual(base, plan['expected_head_sha'])
        self.assertEqual([self.root], plan['parent_shas'])
        self.assertEqual(['git', 'revert', '--no-edit', self.target], plan['review_command'])
        self.assertEqual('NOT_RUN', plan['qualification'])
        self.assertFalse(plan['published'])
        self.assertFalse(plan['automatic_remote_reset'])
        self.assertEqual(base, self.git('rev-parse', 'HEAD'))
        self.assertEqual('', self.git('status', '--porcelain'))
        # The proposed command really creates a new commit and preserves later work.
        self.git(*plan['review_command'][1:])
        self.assertFalse(Path('change').exists())
        self.assertEqual('preserve me', Path('later').read_text())
        self.assertEqual(base, self.git('rev-parse', 'HEAD^'))

    def test_unrelated_target_stale_head_and_root_are_rejected(self):
        self.git('switch', '-c', 'other', self.root)
        unrelated = self.commit('other', 'unrelated')
        self.git('switch', 'main')
        for base, target, mainline in [(self.target, unrelated, None), (self.root, self.target, None),
                                        (self.target, self.root, None), (self.target, self.target, 1)]:
            with self.assertRaises(ValueError):
                release.rollback_plan(base, target, mainline)

    def test_merge_requires_an_explicit_valid_parent_and_command_reverts_that_side(self):
        self.git('switch', '-c', 'feature', self.root)
        self.commit('branch-change', 'feature branch')
        self.git('switch', 'main')
        self.git('merge', '--no-ff', 'feature', '-m', 'merge feature')
        merge = self.git('rev-parse', 'HEAD')
        for mainline in (None, 0, 3):
            with self.assertRaises(ValueError):
                release.rollback_plan(merge, merge, mainline)
        plan = release.rollback_plan(merge, merge, 1)
        self.git(*plan['review_command'][1:])
        self.assertFalse(Path('branch-change').exists())
        self.assertTrue(Path('change').exists())
        self.assertEqual(merge, self.git('rev-parse', 'HEAD^'))


if __name__ == '__main__':
    unittest.main()
