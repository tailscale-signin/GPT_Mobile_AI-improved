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

    def download(self, opener, wait=lambda _: None, clock=lambda: 0):
        sdk.download(self.target, 'https://repo.maven.apache.org/runtime.jar', self.digest, 100, opener, wait, clock)

    def test_throttling_recovers_before_tests_and_installs_verified_bytes(self):
        calls, delays = [], []

        def opener(url, timeout):
            calls.append((url, timeout))
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(self.body)

        self.download(opener, delays.append)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertEqual([5], delays)
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
        self.assertEqual(3, len(calls))
        self.assertEqual([5, 10], delays)

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
