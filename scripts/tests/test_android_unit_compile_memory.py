from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_android_compile_memory as production_memory
import check_android_unit_compile_memory as unit_memory


class AndroidUnitCompileMemoryTest(unittest.TestCase):
    def files(self, available_mib=12_000, swap_mib=0):
        mib = 1024 * 1024
        return {
            '/proc/meminfo': f'MemTotal: {16_384 * 1024} kB\nMemFree: {16_000 * 1024} kB\nMemAvailable: {available_mib * 1024} kB\nSwapTotal: {swap_mib * 1024} kB\nSwapFree: {swap_mib * 1024} kB',
            '/proc/self/cgroup': '0::/actions/job',
            '/proc/self/mountinfo': '31 24 0:25 / /sys/fs/cgroup rw - cgroup2 cgroup rw',
            '/sys/fs/cgroup/cgroup.controllers': 'memory cpu',
            '/sys/fs/cgroup/memory.current': str(500 * mib),
            '/sys/fs/cgroup/actions/memory.max': 'max',
            '/sys/fs/cgroup/actions/memory.current': str(400 * mib),
            '/sys/fs/cgroup/actions/job/memory.max': 'max',
            '/sys/fs/cgroup/actions/job/memory.current': str(300 * mib),
        }

    def command(self, reader):
        stdout, stderr = io.StringIO(), io.StringIO()
        observe = unit_memory.observe
        # Substitute only host resource reads; the CLI, adapter and production
        # observation/parser all execute against the supplied proc/cgroup data.
        with patch.object(unit_memory, 'observe', side_effect=lambda: observe(reader)) as observed:
            with redirect_stdout(stdout), redirect_stderr(stderr):
                code = unit_memory.main()
        self.assertEqual(1, observed.call_count)
        self.assertEqual(1, len(stdout.getvalue().splitlines()))
        return code, json.loads(stdout.getvalue()), stderr.getvalue()

    def test_exact_unit_budget_passes_without_changing_production_policy_or_observations(self):
        files = self.files(available_mib=7168)
        before = production_memory.observe(files.get)
        result = unit_memory.observe(files.get)
        self.assertEqual('INSUFFICIENT', before['status'])
        self.assertEqual('SUFFICIENT', result['status'])
        self.assertEqual((4096, 1024, 2048), (result['heapMiB'], result['metaspaceMiB'], result['reserveMiB']))
        self.assertEqual(7_516_192_768, result['requiredBytes'])
        self.assertEqual(result['requiredBytes'], result['availableBytes'])
        for key in ('ram', 'cgroups', 'availableBytes', 'swapCountsAsHeadroom'):
            self.assertEqual(before[key], result[key])
        self.assertEqual(before, production_memory.observe(files.get))
        self.assertEqual((6144, 768, 2048), (before['heapMiB'], before['metaspaceMiB'], before['reserveMiB']))
        self.assertEqual(8960 * 1024 * 1024, production_memory.REQUIRED_BYTES)

    def test_one_byte_short_of_finite_headroom_fails_even_with_swap(self):
        files = self.files(swap_mib=64_000)
        files['/sys/fs/cgroup/actions/job/memory.max'] = str(7_516_192_768 - 1)
        files['/sys/fs/cgroup/actions/job/memory.current'] = '0'
        result = unit_memory.observe(files.get)
        self.assertEqual('INSUFFICIENT', result['status'])
        self.assertEqual(7_516_192_768 - 1, result['availableBytes'])
        self.assertFalse(result['swapCountsAsHeadroom'])

    def test_finite_ancestor_limits_unlimited_and_inheriting_children(self):
        files = self.files()
        files['/sys/fs/cgroup/actions/memory.max'] = str(8192 * 1024 * 1024)
        files['/sys/fs/cgroup/actions/memory.current'] = str(1024 * 1024 * 1024 + 1)
        for inherited in (False, True):
            with self.subTest(inherited=inherited):
                if inherited:
                    files.pop('/sys/fs/cgroup/actions/job/memory.max')
                    files['/sys/fs/cgroup/actions/cgroup.subtree_control'] = 'cpu'
                result = unit_memory.observe(files.get)
                self.assertEqual('INSUFFICIENT', result['status'])
                self.assertEqual(7_516_192_768 - 1, result['availableBytes'])

    def test_available_ram_cannot_be_replaced_by_free_ram_or_swap(self):
        result = unit_memory.observe(self.files(available_mib=7167, swap_mib=64_000).get)
        self.assertEqual('INSUFFICIENT', result['status'])
        self.assertEqual(7167 * 1024 * 1024, result['availableBytes'])
        self.assertEqual(64_000 * 1024 * 1024, result['ram']['SwapFree'])
        self.assertFalse(result['swapCountsAsHeadroom'])

    def test_missing_observations_cannot_be_promoted_to_sufficient(self):
        for missing in ('/proc/meminfo', '/proc/self/cgroup', '/proc/self/mountinfo',
                        '/sys/fs/cgroup/actions/job/memory.current'):
            with self.subTest(missing=missing):
                files = self.files()
                files.pop(missing)
                result = unit_memory.observe(files.get)
                self.assertEqual('UNKNOWN', result['status'])
                self.assertIsNone(result['availableBytes'])
                self.assertEqual(7_516_192_768, result['requiredBytes'])

    def test_hidden_or_unreadable_ancestry_fails_without_exposing_host_details(self):
        files = self.files()
        files['/proc/self/mountinfo'] = '31 24 0:25 /actions /sys/fs/cgroup rw - cgroup2 cgroup rw'
        self.assertEqual('UNKNOWN', unit_memory.observe(files.get)['status'])
        files = self.files()

        def unreadable(path):
            if path.endswith('/memory.max'):
                raise PermissionError('private-runner-canary')
            return files.get(path)

        code, receipt, error = self.command(unreadable)
        self.assertEqual(1, code)
        self.assertEqual('UNKNOWN', receipt['status'])
        self.assertNotIn('private-runner-canary', json.dumps(receipt) + error)
        self.assertNotIn('/actions', json.dumps(receipt) + error)

    def test_command_emits_one_fixed_receipt_and_only_admits_sufficient_headroom(self):
        for available, expected_status, expected_code in ((7168, 'SUFFICIENT', 0), (7167, 'INSUFFICIENT', 1)):
            with self.subTest(available_mib=available):
                code, receipt, error = self.command(self.files(available_mib=available).get)
                self.assertEqual(expected_code, code)
                self.assertEqual(expected_status, receipt['status'])
                self.assertEqual((4096, 1024, 2048), (receipt['heapMiB'], receipt['metaspaceMiB'], receipt['reserveMiB']))
                if expected_code:
                    self.assertIn('7168 MiB', error)
                    self.assertIn('No compilation or retry was started.', error)
                else:
                    self.assertEqual('', error)


if __name__ == '__main__':
    unittest.main()
