"""One-in-flight GitHub API and durable cooldown contract."""
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.github_api_governor import DurableGitHubCooldowns, GitHubApiGovernor, GitHubApiRateWait


class GovernorTests(unittest.TestCase):
    def test_one_inflight_per_account_and_independent_accounts(self):
        governor = GitHubApiGovernor()
        barrier = threading.Event()
        completed = threading.Event()
        def second():
            with governor.lease('same'):
                completed.set()
        with governor.lease('same'):
            thread = threading.Thread(target=second)
            thread.start()
            self.assertFalse(completed.wait(.2))
            with governor.lease('other'):
                self.assertFalse(completed.is_set())
        self.assertTrue(completed.wait(2))
        thread.join(2)

    def test_429_returns_cooldown_without_a_busy_retry(self):
        now = [100.0]
        governor = GitHubApiGovernor(clock=lambda: now[0], wall=lambda: 1_800_000_000)
        governor.observe('account', 429, {'Retry-After': '600'})
        with self.assertRaises(GitHubApiRateWait) as caught:
            with governor.lease('account'):
                self.fail('cooldown must block physical dispatch')
        self.assertEqual(429, caught.exception.response.status_code)
        self.assertGreaterEqual(int(caught.exception.response.headers['Retry-After']), 600)
        now[0] += 601
        with governor.lease('account'):
            pass

    def test_restart_restores_only_exhausted_resource_and_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'cooldowns.sqlite3'

            def transaction(fn):
                db = sqlite3.connect(path)
                try:
                    with db:
                        return fn(db)
                finally:
                    db.close()

            wall = [1_800_000_000]
            before = GitHubApiGovernor(clock=lambda: 200.0, wall=lambda: wall[0],
                                       cooldowns=DurableGitHubCooldowns(transaction))
            before.observe('one', 403, {'X-RateLimit-Resource': 'search', 'X-RateLimit-Remaining': '0',
                                        'X-RateLimit-Reset': str(wall[0] + 120)})
            before.observe('two', 429, {'Retry-After': '120'})
            wall[0] += 61
            now = [7.0]
            after = GitHubApiGovernor(clock=lambda: now[0], wall=lambda: wall[0],
                                      cooldowns=DurableGitHubCooldowns(transaction))
            with after.lease('one'):
                pass
            with after.lease('three', resource='search'):
                pass
            with self.assertRaises(GitHubApiRateWait) as caught:
                with after.lease('one', resource='search'):
                    self.fail('the primary resource cooldown must survive a restart')
            self.assertEqual('59', caught.exception.response.headers['Retry-After'])
            with self.assertRaises(GitHubApiRateWait):
                with after.lease('two', resource='graphql'):
                    self.fail('the account-wide cooldown must survive a restart')
            now[0] += 60
            with after.lease('one', resource='search'), after.lease('two', resource='graphql'):
                pass

    def test_secondary_403_paces_and_auth_403_does_not(self):
        now = [10.0]
        governor = GitHubApiGovernor(clock=lambda: now[0], wall=lambda: 1_800_000_000)
        governor.observe('readonly', 403, {})
        with governor.lease('readonly'):
            pass
        governor.observe('readonly', 403, {'X-RateLimit-Remaining': '0'})
        with self.assertRaises(GitHubApiRateWait):
            with governor.lease('readonly'):
                pass

    def test_non_mapping_response_headers_do_not_break_observation(self):
        governor = GitHubApiGovernor()
        governor.observe('account', 200, object())
        with governor.lease('account'):
            pass

    def test_observed_core_reserve_defers_only_bulk_reads_and_expires_at_reset(self):
        now = [100.0]
        governor = GitHubApiGovernor(clock=lambda: now[0], wall=lambda: 1_800_000_000 + now[0] - 100)
        governor.observe('account', 200, {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '101',
                                       'X-RateLimit-Reset': '1800000600'})
        with governor.lease('account', reserve=100):
            pass
        # Admission spends the last bulk request even with missing response headers.
        with self.assertRaises(GitHubApiRateWait) as caught:
            with governor.lease('account', reserve=100):
                self.fail('bulk reads must preserve the foreground allowance')
        self.assertFalse(caught.exception.dispatched)
        self.assertEqual('FOREGROUND_RESERVE', caught.exception.reason)
        self.assertEqual('600', caught.exception.response.headers['Retry-After'])
        with governor.lease('account'):
            pass
        with governor.lease('account', resource='search', reserve=100):
            pass
        with governor.lease('another-account', reserve=100):
            pass
        now[0] += 601
        with governor.lease('account', reserve=100):
            pass

    def test_reserve_requires_valid_observed_core_metadata_and_zero_blocks_all_core_reads(self):
        governor = GitHubApiGovernor(clock=lambda: 100.0, wall=lambda: 1_800_000_000)
        cases = [{}, {'X-RateLimit-Resource': 'search', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '1800000600'},
                 {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '0'},
                 {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '-1', 'X-RateLimit-Reset': '1800000600'},
                 {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '9' * 5000, 'X-RateLimit-Reset': '1800000600'},
                 {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': 'NaN'}]
        for headers in cases:
            governor.observe('account', 200, headers)
            with governor.lease('account', reserve=100):
                pass
        governor.observe('account', 200, {'X-RateLimit-Resource': 'core', 'X-RateLimit-Remaining': '0',
                                       'X-RateLimit-Reset': '1800000600'})
        with self.assertRaises(GitHubApiRateWait) as caught:
            with governor.lease('account'):
                pass
        self.assertEqual('OBSERVED_QUOTA_EXHAUSTED', caught.exception.reason)

    def test_retry_delay_distinguishes_access_rejection_and_authoritative_rate_waits(self):
        self.assertIsNone(GitHubApiGovernor.retry_delay(403, {}, 1_800_000_000))
        self.assertEqual(60, GitHubApiGovernor.retry_delay(429, {}, 1_800_000_000))
        self.assertEqual(900, GitHubApiGovernor.retry_delay(403, {'X-RateLimit-Remaining': '0',
                                                             'X-RateLimit-Reset': '1800000900'}, 1_800_000_000))
        self.assertEqual(120, GitHubApiGovernor.retry_delay(429, {'Retry-After': '120'}, 1_800_000_000))
        self.assertEqual(120, GitHubApiGovernor.retry_delay(429, {'Retry-After': '120', 'X-RateLimit-Remaining': '4000',
                                                               'X-RateLimit-Reset': '1800003600'}, 1_800_000_000))
        self.assertEqual(60, GitHubApiGovernor.retry_delay(429, {'X-RateLimit-Remaining': '4000',
                                                              'X-RateLimit-Reset': '1800003600'}, 1_800_000_000))


if __name__ == '__main__':
    unittest.main()
