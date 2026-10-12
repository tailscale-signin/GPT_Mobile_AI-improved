import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('normalize_bundle', Path(__file__).parents[1] / 'normalize_geniex_bundle.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class GenieXBundleTest(unittest.TestCase):
    def fixture(self, path):
        (path/'tokenizer.json').write_text('{}')
        (path/'shard.bin').write_bytes(b'fixture-not-native-model')
        (path/'metadata.json').write_text('{"precision":"w4a16"}')
        (path/'genie_config.json').write_text(json.dumps({'dialog': {'context': {'size': 2048}, 'tokenizer': {'path': 'tokenizer.json'}, 'engine': {'model': {'type': 'binary', 'binary': {'ctx-bins': ['shard.bin']}}}}}))
    def test_normalizes_exact_compiled_context_and_hashed_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp); self.fixture(path)
            value=module.normalize(path, 'artifact', 'family', 'SM8750', '0.8.0')
            self.assertEqual(2048, value['contextTokens'])
            self.assertEqual('DISCOVERED', value['evidence'])
            self.assertEqual(4,len(value['files']))
            self.assertTrue(all(len(f['sha256'])==64 for f in value['files']))
    def test_executable_and_undeclared_assets_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp); self.fixture(path); (path/'injected.so').write_bytes(b'code')
            with self.assertRaises(ValueError): module.normalize(path, 'artifact', 'family', 'SM8750', '0.8.0')
            (path/'injected.so').unlink(); (path/'shard.bin').unlink()
            with self.assertRaises(ValueError): module.normalize(path, 'artifact', 'family', 'SM8750', '0.8.0')
    def test_metadata_alone_is_not_invented_runtime_configuration(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp); (path/'metadata.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'metadata-only'): module.normalize(path, 'artifact', 'family', 'SM8750', '0.8.0')
