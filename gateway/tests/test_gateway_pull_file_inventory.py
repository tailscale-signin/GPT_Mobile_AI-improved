import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from v14.pull_file_inventory import collect, mirror_inventory


class PullFilesTests(unittest.TestCase):
    def setUp(self):
        self.base, self.head = 'a' * 40, 'b' * 40
        self.job = {'id': 'test', 'deadline': time.time() + 90, 'data': {'head_sha': self.head},
                    'request': {'repository': 'owner/repo', 'base': 'main'}}
        self.backend = Mock()
        self.backend.root.return_value = '/repos/owner/repo'
        self.backend.cancellation.return_value = threading.Event()
        self.rows = []
        self.metadata_reads = 0
        self.move = False

        def response(path, job=None):
            if '/files?' in path:
                start = (int(path.split('page=')[-1]) - 1) * 100
                return self.rows[start:start + 100]
            self.metadata_reads += 1
            return {'head': {'sha': self.head if not self.move or self.metadata_reads == 1 else 'e' * 40},
                    'base': {'sha': self.base, 'ref': 'main'}, 'state': 'open', 'changed_files': self.total}

        self.backend.api.side_effect = response

    def inventory(self, count):
        self.total = count
        self.rows = [{'filename': f'file/{index}', 'sha': self.head} for index in range(min(count, 3000))]
        self.rows[-1].update(status='renamed', previous_filename='old-name')

    def inspect(self):
        return collect(self.backend, self.job, 7, self.base, self.head)

    def test_416_distinct_files_include_rename_after_compare_ceiling(self):
        self.inventory(416)
        paths, report = self.inspect()
        self.assertEqual(list(row['filename'] for row in self.rows), paths)
        self.assertEqual((True, 416, 0, {'file/415': 'old-name'}),
                         (report['complete'], report['observed_unique'], report['missing'], report['renamed']))
        self.assertEqual(7, self.backend.api.call_count)

    def test_cap_and_duplicate_report_exact_gap_without_mirror(self):
        for total, duplicate, observed in [(3007, False, 3000), (416, True, 415), (3000, False, 3000)]:
            with self.subTest(total=total), patch('v14.pull_file_inventory.mirror_inventory', side_effect=ValueError('no mirror')):
                self.inventory(total)
                if duplicate:
                    self.rows[100] = self.rows[2]
                _, report = self.inspect()
                self.assertEqual((total == observed, total-observed, observed),
                                 (report['complete'], report['missing'], report['observed_unique']))

    def test_moved_head_discards_all_collected_pages(self):
        self.inventory(416)
        self.move = True
        with self.assertRaisesRegex(ValueError, 'PR changed'):
            self.inspect()

    def test_authorized_local_diff_is_reconciled_with_remote_names_and_count(self):
        self.inventory(3001)
        mirror = {row['filename']: row.get('previous_filename') for row in self.rows}
        mirror['last'] = None
        with patch('v14.pull_file_inventory.mirror_inventory', return_value=mirror):
            paths, report = self.inspect()
            self.assertEqual('APPROVED_GIT_MIRROR', report['source'])
            self.assertTrue(report['complete'])
            self.assertIn('last', paths)
            mirror['file/2999'] = 'another-old-name'
            _, report = self.inspect()
            self.assertFalse(report['complete'])
            self.assertEqual('LOCAL_DIFF_DISAGREES_WITH_GITHUB', report['reason'])

    def test_real_git_mirror_preserves_rename_and_excludes_base_only_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            repo, mirror = Path(directory) / 'working', Path(directory) / 'mirror'
            subprocess.run(['git', 'init', '-q', '-b', 'main', str(repo)], check=True)

            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), '-c', 'user.name=Test',
                    '-c', 'user.email=test@example.invalid', *args], text=True).strip()

            (repo / 'old name').write_text('retained content\n')
            git('add', '.')
            git('commit', '-qm', 'base')
            git('checkout', '-qb', 'feature')
            git('mv', 'old name', 'new name')
            git('commit', '-qm', 'rename')
            head = git('rev-parse', 'HEAD')
            git('checkout', '-q', 'main')
            (repo / 'only-on-base').write_text('base only')
            git('add', '.')
            git('commit', '-qm', 'independent work')
            base = git('rev-parse', 'HEAD')
            subprocess.run(['git', 'clone', '-q', '--mirror', str(repo), str(mirror)], check=True)
            git('--git-dir=' + str(mirror), 'remote', 'set-url', 'origin', 'https://github.com/owner/repo.git')
            self.backend.token.return_value = 'fake'
            self.backend.api.side_effect = lambda path, job=None: {'sha': path.rsplit('/', 1)[1],
                'tree': {'sha': git('rev-parse', path.rsplit('/', 1)[1] + '^{tree}')}}
            policy = ({}, mirror, '', [], [])
            with patch('v14.pull_file_inventory.load_policy', return_value=policy), patch('v14.engineering_source.load_policy', return_value=policy):
                actual = mirror_inventory(self.backend, self.job, base, head)
            self.assertEqual({'new name': 'old name'}, actual)


if __name__ == '__main__':
    unittest.main()
