import hashlib
import io
from pathlib import Path
import re
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

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


    def test_android_unit_test_sdks_have_pinned_jars_and_poms(self):
        coordinates = {
            31: '12-robolectric-7732740-i7',
            32: '12.1-robolectric-8229987-i7',
            33: '13-robolectric-9030017-i7',
            34: '14-robolectric-10818077-i7',
            35: '15-robolectric-13954326-i7',
            36: '16-robolectric-13921718-i7',
        }
        test_sources = Path(__file__).resolve().parents[2] / 'app/src/test'
        used = set()
        for source in test_sources.rglob('*.kt'):
            for versions in re.findall(r'\bsdk\s*=\s*\[([\d,\s]+)\]', source.read_text(encoding='utf-8')):
                used.update(int(api) for api in re.findall(r'\d+', versions))
        self.assertTrue(used)
        for api in used:
            with self.subTest(android_api=api):
                self.assertIn(api, coordinates, 'Record the Robolectric coordinate for this Android API')
                coordinate = coordinates[api]
                self.assertIn(coordinate, sdk.SDKS)
                pins = sdk.SDKS[coordinate]
                self.assertEqual({'jar', 'pom'}, set(pins))
                for digest in pins.values():
                    self.assertRegex(digest, r'^[0-9a-f]{128}$')

    def test_preview_and_release_validate_runtimes_before_running_full_unit_suites(self):
        workflows = Path(__file__).resolve().parents[2] / '.github/workflows'
        for name in ('geniex-preview.yml', 'release-build.yml', 'audit-workspace.yml', 'marketplace-contracts.yml'):
            with self.subTest(workflow=name):
                workflow = (workflows / name).read_text(encoding='utf-8')
                restore = workflow.index('- name: Restore verified Robolectric runtime cache')
                verify = workflow.index('run: python3 scripts/provision_robolectric_sdks.py')
                unit = workflow.index(':app:testDebugUnitTest')
                self.assertLess(restore, verify)
                self.assertLess(verify, unit)

    def test_ci_cache_restores_before_verified_provisioning_and_saves_after_success(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/pr-validation.yml').read_text(encoding='utf-8')
        restored = workflow.index('- name: Restore verified Robolectric runtime cache')
        provisioned = workflow.index('- name: Provision verified Robolectric runtimes')
        saved = workflow.index('- name: Save verified Robolectric runtime cache')
        self.assertLess(restored, provisioned)
        self.assertLess(provisioned, saved)
        pin = '55cc8345863c7cc4c66a329aec7e433d2d1c52a9'
        self.assertIn(f'uses: actions/cache/restore@{pin}', workflow)
        self.assertIn(f'uses: actions/cache/save@{pin}', workflow)
        self.assertIn("if: success() && steps.robolectric-cache.outputs.cache-hit != 'true'", workflow)
        self.assertEqual(2, workflow.count("key: robolectric-sha512-linux-${{ hashFiles('scripts/provision_robolectric_sdks.py') }}"))
        self.assertEqual(2, workflow.count('path: ~/.m2/repository/org/robolectric/android-all-instrumented'))
        self.assertIn('run: python3 scripts/provision_robolectric_sdks.py', workflow)

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

    def test_canonical_mirror_recovers_from_primary_429_with_verified_artifact(self):
        calls, delays = [], []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def opener(url, timeout):
            calls.append(url)
            if url == canonical:
                raise urllib.error.HTTPError(url, 429, 'shared-runner throttle', {}, None)
            return io.BytesIO(self.body)

        sdk.download(self.target, canonical, self.digest, 100, opener, delays.append,
                     lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([canonical, sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar'], calls)
        self.assertEqual([], delays)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_long_primary_retry_after_does_not_block_independent_verified_mirror(self):
        calls, delays = [], []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def opener(url, timeout):
            calls.append(url)
            if url == canonical:
                raise urllib.error.HTTPError(url, 429, 'host throttled', {'Retry-After': '3600'}, None)
            return io.BytesIO(self.body)

        sdk.download(self.target, canonical, self.digest, 100, opener, delays.append,
                     lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([canonical, sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar'], calls)
        self.assertEqual([], delays)
        self.assertEqual(self.body, self.target.read_bytes())

    def test_all_hosts_blocked_beyond_deadline_each_try_once_without_hot_loop(self):
        calls, delays = [], []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def opener(url, timeout):
            calls.append(url)
            raise urllib.error.HTTPError(url, 429, 'host throttled', {'Retry-After': '3600'}, None)

        with self.assertRaises(urllib.error.HTTPError):
            sdk.download(self.target, canonical, self.digest, 100, opener, delays.append,
                         lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([
            canonical,
            sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar',
            sdk.GOOGLE_MAVEN_CENTRAL_BASE + '/sample/runtime.jar',
        ], calls)
        self.assertEqual([], delays)
        self.assertFalse(self.target.exists())

    def test_host_retry_after_is_honored_before_that_host_is_reused(self):
        calls, delays, now = [], [], [0]
        canonical = f"{sdk.BASE}/sample/runtime.jar"
        alternate = sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar'

        def opener(url, timeout):
            calls.append((url, now[0]))
            if url == alternate and now[0] >= 30:
                return io.BytesIO(self.body)
            delay = 120 if url == canonical else 30 if url == alternate else 60
            raise urllib.error.HTTPError(url, 429, 'host throttled', {'Retry-After': str(delay)}, None)

        def wait(seconds):
            delays.append(seconds)
            now[0] += seconds

        sdk.download(self.target, canonical, self.digest, 100, opener, wait,
                     lambda: now[0], lambda _low, _high: 0, lambda: 0)
        self.assertEqual([(canonical, 0), (alternate, 0),
                          (sdk.GOOGLE_MAVEN_CENTRAL_BASE + '/sample/runtime.jar', 0), (alternate, 30)], calls)
        self.assertEqual([30], delays)
        self.assertEqual(self.body, self.target.read_bytes())

    def test_provision_preserves_host_cooldown_across_artifacts(self):
        calls = []
        spec = {'sample': {'jar': self.digest, 'pom': self.digest}}
        suffix = '/sample/android-all-instrumented-sample.'

        def opener(url, timeout):
            calls.append(url)
            if url.startswith(sdk.BASE + '/'):
                raise urllib.error.HTTPError(url, 429, 'host throttled', {'Retry-After': '3600'}, None)
            return io.BytesIO(self.body)

        sdk.provision(self.target.parent, spec, opener,
                      lambda _: self.fail('ready mirror must not inherit a different host cooldown'), lambda: 0)
        self.assertEqual([sdk.BASE + suffix + 'jar', sdk.MAVEN_CENTRAL_ALTERNATE_BASE + suffix + 'jar',
                          sdk.MAVEN_CENTRAL_ALTERNATE_BASE + suffix + 'pom'], calls)
        for extension in ('jar', 'pom'):
            target = self.target.parent / 'org/robolectric/android-all-instrumented/sample' / f'android-all-instrumented-sample.{extension}'
            self.assertEqual(self.body, target.read_bytes())
            self.assertEqual(self.digest, target.with_suffix(target.suffix + '.sha512').read_text().strip())


    def test_google_maven_mirror_recovers_after_both_central_hosts_429(self):
        calls, delays = [], []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def opener(url, timeout):
            calls.append(url)
            if len(calls) <= 2:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(self.body)

        sdk.download(self.target, canonical, self.digest, 100, opener, delays.append,
                     lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([
            canonical,
            sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar',
            sdk.GOOGLE_MAVEN_CENTRAL_BASE + '/sample/runtime.jar'
        ], calls)
        self.assertEqual([], delays)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertFalse(list(self.target.parent.glob('*.part')))


    def test_mirror_404_falls_back_to_canonical_without_weakening_checksum(self):
        canonical = f"{sdk.BASE}/sample/runtime.jar"
        calls, delays = [], []

        def opener(url, timeout):
            calls.append(url)
            if len(calls) <= 2:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            if len(calls) == 3:
                raise urllib.error.HTTPError(url, 404, 'mirror not synchronized', {}, None)
            return io.BytesIO(self.body)

        sdk.download(self.target, canonical, self.digest, 100, opener, delays.append,
                     lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([
            canonical,
            sdk.MAVEN_CENTRAL_ALTERNATE_BASE + '/sample/runtime.jar',
            sdk.GOOGLE_MAVEN_CENTRAL_BASE + '/sample/runtime.jar',
            canonical,
        ], calls)
        self.assertEqual([15], delays)
        self.assertEqual(self.body, self.target.read_bytes())
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_primary_404_on_pinned_pom_rotates_and_requires_the_same_bytes(self):
        version = '14-robolectric-10818077-i7'
        suffix = f'/{version}/android-all-instrumented-{version}.pom'
        primary = sdk.BASE + suffix
        self.assertEqual(sdk.SDKS[version]['pom'], sdk.PINNED_CENTRAL_ARTIFACTS[primary])
        for mirror_bytes in (self.body, b'different content'):
            with self.subTest(verified=mirror_bytes == self.body):
                self.target.unlink(missing_ok=True)
                calls = []

                def opener(url, timeout):
                    calls.append(url)
                    if url == primary:
                        raise urllib.error.HTTPError(url, 404, 'edge cache miss', {}, None)
                    return io.BytesIO(mirror_bytes)

                with patch.dict(sdk.PINNED_CENTRAL_ARTIFACTS, {primary: self.digest}):
                    if mirror_bytes == self.body:
                        sdk.download(self.target, primary, self.digest, 100, opener, lambda _: None, lambda: 0)
                        self.assertEqual(self.body, self.target.read_bytes())
                    else:
                        with self.assertRaisesRegex(ValueError, 'SHA-512'):
                            sdk.download(self.target, primary, self.digest, 100, opener, lambda _: None, lambda: 0)
                        self.assertFalse(self.target.exists())
                self.assertEqual([primary, sdk.MAVEN_CENTRAL_ALTERNATE_BASE + suffix], calls)

    def test_primary_404_does_not_rotate_when_the_pin_differs(self):
        url = next(iter(sdk.PINNED_CENTRAL_ARTIFACTS))
        def missing(request, timeout):
            self.assertEqual(request, url)
            raise urllib.error.HTTPError(request, 404, 'missing', {}, None)

        with self.assertRaises(urllib.error.HTTPError):
            sdk.download(self.target, url, self.digest, 100, missing, lambda _: self.fail('unrecognized pin retried'), lambda: 0)
        self.assertFalse(self.target.exists())

    def test_canonical_404_still_fails_without_using_any_mirror(self):
        canonical = f"{sdk.BASE}/sample/runtime.jar"
        calls = []

        def opener(url, timeout):
            calls.append(url)
            raise urllib.error.HTTPError(url, 404, 'unknown published coordinate', {}, None)

        with self.assertRaises(urllib.error.HTTPError):
            sdk.download(self.target, canonical, self.digest, 100, opener,
                         lambda _: self.fail('canonical 404 retried'), lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([canonical], calls)
        self.assertFalse(self.target.exists())

    def test_google_maven_mirror_still_rejects_unpinned_bytes(self):
        calls = []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def opener(url, timeout):
            calls.append(url)
            if len(calls) <= 2:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(b'invalid mirror content')

        with self.assertRaisesRegex(ValueError, 'SHA-512'):
            sdk.download(self.target, canonical, self.digest, 100, opener, lambda _: None,
                         lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual(3, len(calls))
        self.assertFalse(self.target.exists())
        self.assertFalse(list(self.target.parent.glob('*.part')))

    def test_mirror_cannot_bypass_publisher_digest_or_unsupported_source(self):
        calls = []
        canonical = f"{sdk.BASE}/sample/runtime.jar"

        def bad_mirror(url, timeout):
            calls.append(url)
            if url == canonical:
                raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)
            return io.BytesIO(b'wrong contents')

        with self.assertRaisesRegex(ValueError, 'SHA-512'):
            sdk.download(self.target, canonical, self.digest, 100, bad_mirror,
                         lambda _: None, lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual(2, len(calls))
        self.assertFalse(self.target.exists())
        self.assertFalse(list(self.target.parent.glob('*.part')))

        unrelated = 'https://example.com/private/resource.jar'
        redirects = []

        def throttled(url, timeout):
            redirects.append(url)
            raise urllib.error.HTTPError(url, 429, 'throttled', {}, None)

        with self.assertRaises(urllib.error.HTTPError):
            sdk.download(self.target, unrelated, self.digest, 100, throttled,
                         lambda _: None, lambda: 0, lambda _low, _high: 0, lambda: 0)
        self.assertEqual([unrelated] * 6, redirects)

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
