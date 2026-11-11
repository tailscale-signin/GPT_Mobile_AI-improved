"""One-in-flight GitHub API and durable cooldown contract."""
import sys
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.github_api_governor import GitHubApiGovernor, GitHubApiRateWait


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


if __name__ == '__main__':
    unittest.main()
