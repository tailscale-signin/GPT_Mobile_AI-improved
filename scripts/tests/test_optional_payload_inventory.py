import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

path = Path(__file__).resolve().parents[1] / 'inventory_optional_payloads.py'
spec = importlib.util.spec_from_file_location('inventory_optional_payloads', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PayloadInventoryTests(unittest.TestCase):
    def test_inventory_measures_payload_bytes_without_claiming_dex_feature_sizes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'example.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('lib/arm64-v8a/libllama.so', b'a'*400)
                archive.writestr('classes.dex', b'b'*300)
                archive.writestr('assets/universal_sentence_encoder.tflite', b'c'*200)
            result = module.inventory(apk)
            self.assertEqual(400, result['groups']['gguf-runtime']['uncompressedBytes'])
            self.assertEqual(300, result['groups']['shared-dex-not-feature-attributable']['uncompressedBytes'])
            self.assertEqual(200, result['groups']['memory-model-data']['uncompressedBytes'])
            self.assertEqual(apk.stat().st_size, result['archiveBytes'])
