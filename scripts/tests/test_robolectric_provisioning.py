import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import urllib.error

from scripts import provision_robolectric_sdks as sdk


class RobolectricProvisioningTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.target = Path(self.directory.name) / 'runtime.jar'
        self.body = b'publisher verified runtime'
        self.digest = hashlib.sha512(self.body).hexdigest()

    def download(self, opener, wait=lambda _: None, clock=lambda: 0, deadline=100,
                 jitter=lambda _low, _high: 0, wall_clock=lambda: 0):
        sdk.download(self.target, 'https://repo.maven.apache.org/runtime.jar', self.digest, deadline,
                     opener, wait, clock, jitter, wall_clock)

    def test_throttling_recovers_before_tests_and_installs_verified_bytes(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append((url, timeout))
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(self.body)

        self.download(opener, delays.append)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertEqual([15], delays)
        self.assertEqual(2, len(calls))
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_verified_cache_does_not_access_the_network(self):
        self.target.write_bytes(self.body)
        self.download(lambda *args, **kwargs: self.fail('unexpected network access'))

    def test_corrupt_cache_is_replaced_only_with_verified_content(self):
        self.target.write_bytes(b'corrupt')
        self.download(lambda *args, **kwargs: io.BytesIO(self.body))
        self.assertEqual(self.body, self.target.read_bytes())

    def test_checksum_failure_is_not_retried_or_installed(self):
        calls = []

        def opener(*args, **kwargs):
            calls.append(1)
            return io.BytesIO(b'wrong runtime')

        with self.assertRaises(ValueError):
            self.download(opener)
        self.assertEqual([1], calls)
        self.assertFalse(self.target.exists())
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_authorization_and_missing_artifacts_are_not_retried(self):
        for status in [401, 403, 404]:
            def opener(url, timeout):
                raise urllib.error.HTTPError(url, status, 'permanent', {}, None)
            with self.assertRaises(urllib.error.HTTPError):
                self.download(opener, lambda _: self.fail('permanent failure retried'))

    def test_persistent_throttling_is_bounded(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(1)
            raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)

        with self.assertRaises(urllib.error.HTTPError):
            self.download(opener, delays.append)
        self.assertEqual(6, len(calls))
        self.assertEqual([15, 30, 60, 60, 60], delays)

    def test_expired_deadline_stops_before_network_access(self):
        with self.assertRaises(TimeoutError):
            self.download(lambda *args, **kwargs: self.fail('expired download dispatched'), clock=lambda: 100)

    def test_local_maven_layout_includes_verified_pom_and_checksum_sidecars(self):
        spec = {'test-version': {'jar': self.digest, 'pom': self.digest}}
        sdk.provision(self.target.parent, spec, lambda *args, **kwargs: io.BytesIO(self.body))
        for extension in spec['test-version']:
            target = self.target.parent / 'org/robolectric/android-all-instrumented/test-version' / f'android-all-instrumented-test-version.{extension}'
            self.assertEqual(self.body, target.read_bytes())
            self.assertEqual(self.digest, target.with_suffix(target.suffix + '.sha512').read_text().strip())

    def test_throttling_recovers_after_the_old_three_attempt_limit(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(timeout)
            if len(calls) <= 3:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(self.body)

        self.download(opener, delays.append)
        self.assertEqual(4, len(calls))
        self.assertEqual([15, 30, 60], delays)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_retry_after_seconds_respected_before_recovery(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(1)
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, 'throttled', {'Retry-After': '90'}, None)
            return io.BytesIO(self.body)

        self.download(opener, delays.append)
        self.assertEqual([90], delays)
        self.assertEqual(self.body, self.target.read_bytes())

    def test_retry_after_http_date_uses_wall_clock_not_monotonic_deadline(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(1)
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, 'throttled',
                                             {'Retry-After': 'Thu, 01 Jan 1970 00:02:00 GMT'}, None)
            return io.BytesIO(self.body)

        self.download(opener, delays.append, wall_clock=lambda: 30)
        self.assertEqual([90], delays)
        self.assertEqual(2, len(calls))

    def test_invalid_retry_after_uses_bounded_backoff(self):
        for value in ['not a date', '-2', 'NaN', 'Infinity']:
            with self.subTest(value=value):
                error = urllib.error.HTTPError('https://repo.maven.apache.org/runtime.jar', 429,
                                               'throttled', {'Retry-After': value}, None)
                self.assertIsNone(sdk.retry_after_seconds(error))

    def test_retry_after_beyond_deadline_does_not_retry_early(self):
        for retry_after in ['120', '9' * 500]:
            with self.subTest(retry_after=retry_after):
                calls = []

                def opener(url, timeout):
                    calls.append(1)
                    raise urllib.error.HTTPError(url, 429, 'throttled', {'Retry-After': retry_after}, None)

                with self.assertRaises(urllib.error.HTTPError):
                    self.download(opener, lambda _: self.fail('wait cannot fit within deadline'))
                self.assertEqual(1, len(calls))
                self.assertFalse(self.target.exists())

    def test_throttling_retries_share_the_original_total_deadline(self):
        calls, now, delays = [], [0], []

        def opener(url, timeout):
            calls.append(timeout)
            raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)

        def wait(seconds):
            delays.append(seconds)
            now[0] += seconds

        with self.assertRaises(urllib.error.HTTPError):
            self.download(opener, wait, clock=lambda: now[0], deadline=40)
        self.assertEqual([15], delays)
        self.assertEqual([20, 20], calls)

    def test_non_throttling_transient_failures_keep_three_attempt_limit(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(1)
            raise urllib.error.HTTPError(url, 503, 'unavailable', {}, None)

        with self.assertRaises(urllib.error.HTTPError):
            self.download(opener, delays.append)
        self.assertEqual(3, len(calls))
        self.assertEqual([5, 10], delays)

    def test_retry_jitter_is_bounded_and_added_after_server_delay(self):
        calls, delays, bounds = [], [], []

        def opener(url, timeout):
            calls.append(1)
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, 'throttled', {'Retry-After': '30'}, None)
            return io.BytesIO(self.body)

        def jitter(low, high):
            bounds.append((low, high))
            return high

        self.download(opener, delays.append, jitter=jitter)
        self.assertEqual([(0, 3)], bounds)
        self.assertEqual([33], delays)
