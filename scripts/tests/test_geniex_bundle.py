import importlib.util
import json
from pathlib import Path
import tempfile
import struct
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

    def test_gguf_metadata_pins_context_and_requires_all_shards(self):
        def write(path, number):
            def text(value):
                data=value.encode(); return struct.pack('<Q',len(data))+data
            values={'general.architecture':'llama','llama.context_length':4096,'split.count':2,'split.no':number}
            raw=b'GGUF'+struct.pack('<IQQ',3,1,len(values))
            for key,value in values.items():
                raw+=text(key)+struct.pack('<I',8 if isinstance(value,str) else 4)+(text(value) if isinstance(value,str) else struct.pack('<I',value))
            path.write_bytes(raw)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp); write(path/'model-00001-of-00002.gguf',0)
            with self.assertRaises(ValueError): module.normalize_gguf(path,'id','family','model-00001-of-00002.gguf','0.8.0')
            write(path/'model-00002-of-00002.gguf',1)
            result=module.normalize_gguf(path,'id','family','model-00001-of-00002.gguf','0.8.0')
            self.assertEqual(4096,result['contextTokens']); self.assertEqual(['cpu','gpu'],result['supportedBackends'])
            self.assertEqual(2,len(result['files'])); self.assertFalse(result['vision'])
            with self.assertRaises(ValueError): module.normalize_gguf(path,'id','family','model-00002-of-00002.gguf','0.8.0')
