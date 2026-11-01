import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('sign_preview_apks', Path(__file__).resolve().parents[1] / 'sign_preview_apks.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class PreviewSigningTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.signer = self.root / 'apksigner'
        self.key = self.root / 'debug.keystore'
        self.signer.touch()
        self.key.touch()

    def invoke(self, verify='Verified using v2 scheme (APK Signature Scheme v2): true\nVerified using v3 scheme (APK Signature Scheme v3): true', fail=None, manifest_min_sdk=None):
        self.calls = []
        def run(command, **kwargs):
            self.calls.append(command)
            self.assertTrue(kwargs['check'])
            self.assertEqual(300, kwargs['timeout'])
            if command[1] == fail:
                raise subprocess.CalledProcessError(1, command)
            if command[1] == 'sign':
                Path(command[command.index('--out') + 1]).write_bytes(b'verified fixture')
            report = verify
            if command[1] == 'verify' and manifest_min_sdk is not None:
                minimum = int(command[command.index('--min-sdk-version') + 1]) if '--min-sdk-version' in command else manifest_min_sdk
                if minimum >= 28:
                    report = report.replace('Verified using v2 scheme (APK Signature Scheme v2): true', 'Verified using v2 scheme (APK Signature Scheme v2): false')
            return subprocess.CompletedProcess(command, 0, stdout=report)
        return signing.sign_previews(self.root, self.signer, self.key, run)

    def test_every_split_is_signed_verified_and_promoted(self):
        for name in ('arm64', 'universal'):
            (self.root / f'{name}-unsigned.apk').write_bytes(b'unsigned')
        self.assertEqual(2, self.invoke())
        self.assertEqual(['sign', 'verify', 'sign', 'verify'], [call[1] for call in self.calls])
        self.assertEqual({'arm64.apk', 'universal.apk'}, {path.name for path in self.root.glob('*.apk')})
        self.assertTrue(all(path.read_bytes() == b'verified fixture' for path in self.root.glob('*.apk')))

    def test_failed_signing_or_verification_preserves_original(self):
        apk = self.root / 'arm64-unsigned.apk'
        apk.write_bytes(b'original')
        for failure in ('sign', 'verify'):
            with self.assertRaises(subprocess.CalledProcessError):
                self.invoke(fail=failure)
            self.assertEqual(b'original', apk.read_bytes())
            self.assertFalse((self.root / 'arm64.apk').exists())

    def test_api31_manifest_still_verifies_both_requested_schemes(self):
        (self.root / 'arm64-unsigned.apk').write_bytes(b'original')
        self.assertEqual(1, self.invoke(manifest_min_sdk=31))
        self.assertEqual(b'verified fixture', (self.root / 'arm64.apk').read_bytes())

    def test_missing_required_signature_cannot_be_promoted(self):
        apk = self.root / 'arm64-unsigned.apk'
        apk.write_bytes(b'original')
        with self.assertRaises(ValueError):
            self.invoke(verify='Verified using v2 scheme (APK Signature Scheme v2): true')
        self.assertEqual(b'original', apk.read_bytes())
        self.assertFalse((self.root / 'arm64.apk').exists())

    def test_missing_apks_fail_instead_of_reporting_success(self):
        with self.assertRaises(ValueError):
            self.invoke()


if __name__ == '__main__':
    unittest.main()
