import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from scripts.provision_memory_model import provision, verified


class MemoryModelProvisioningTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.cache = Path(self.directory.name)
        self.data = b"pinned-model-fixture"
        self.spec = {"filename": "model.tflite", "url": "https://example.invalid/model", "sha256": hashlib.sha256(self.data).hexdigest(), "bytes": len(self.data)}

    def test_offline_cache_and_local_file_never_use_network(self):
        source = self.cache / "supplied.tflite"
        source.write_bytes(self.data)
        network = Mock(side_effect=AssertionError("Unexpected network"))
        target = provision(self.cache, source, True, self.spec, network)
        self.assertTrue(verified(target, self.spec))
        self.assertEqual(target, provision(self.cache, offline=True, spec=self.spec, opener=network))
        network.assert_not_called()

    def test_missing_offline_model_fails_with_recovery_instruction(self):
        with self.assertRaisesRegex(ValueError, "Provision online first"):
            provision(self.cache, offline=True, spec=self.spec)

    def test_retry_recovers_transient_failure_and_removes_partial_files(self):
        network = Mock(side_effect=[OSError("interrupted"), io.BytesIO(self.data)])
        target = provision(self.cache, spec=self.spec, opener=network, wait=lambda _: None)
        self.assertTrue(verified(target, self.spec))
        self.assertEqual(2, network.call_count)
        self.assertEqual([], list(self.cache.rglob("*.part")))

    def test_wrong_hash_or_size_never_replaces_the_artifact(self):
        for data in (b"x" * len(self.data), self.data + b"oversize", self.data[:-1]):
            with self.assertRaises(ValueError):
                provision(self.cache, spec=self.spec, opener=lambda *a, **k: io.BytesIO(data), wait=lambda _: None)
        self.assertFalse((self.cache / self.spec["sha256"] / self.spec["filename"]).exists())
        self.assertEqual([], list(self.cache.rglob("*.part")))
