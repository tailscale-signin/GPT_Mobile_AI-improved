"""Verify at-rest control identity through the real key parser and platform protection."""
from pathlib import Path
import sys
import tempfile
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import serialization

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.control_security import server_secret, tls_identity


class ControlIdentityTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)

    def test_private_key_requires_original_secret_and_reopens_same_certificate(self):
        secret = server_secret(self.root)
        identity = tls_identity(self.root, '127.0.0.1', secret)
        encrypted = Path(identity['key']).read_bytes()
        with self.assertRaises(TypeError):
            serialization.load_pem_private_key(encrypted, password=None)
        with self.assertRaises(ValueError):
            serialization.load_pem_private_key(encrypted, password=b'another-installation')
        key = serialization.load_pem_private_key(encrypted, password=secret.hex().encode())
        certificate = x509.load_pem_x509_certificate(Path(identity['certificate']).read_bytes())
        self.assertEqual(certificate.public_key().public_numbers(), key.public_key().public_numbers())
        reopened_secret = server_secret(self.root)
        self.assertEqual(secret, reopened_secret)
        self.assertEqual(identity['fingerprint'], tls_identity(self.root, '127.0.0.1', reopened_secret)['fingerprint'])
        self.assertEqual(encrypted, Path(identity['key']).read_bytes())

    def test_missing_secret_cannot_replace_an_existing_pinned_identity(self):
        identity = tls_identity(self.root, '127.0.0.1', server_secret(self.root))
        original = Path(identity['certificate']).read_bytes()
        protected = self.root / 'data' / 'control-identity.secret'
        protected.unlink()
        with self.assertRaisesRegex(ValueError, 'Restore the protected control identity'):
            server_secret(self.root)
        self.assertFalse(protected.exists())
        self.assertEqual(original, Path(identity['certificate']).read_bytes())


if __name__ == '__main__':
    unittest.main()
