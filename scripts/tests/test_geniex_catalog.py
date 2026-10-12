import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('catalog_update', Path(__file__).parents[1] / 'update_geniex_catalog.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class GenieXCatalogTest(unittest.TestCase):
    def capture(self, folder):
        data = {'manifest.json': {'models': [{'id': 'model-a', 'display_name': 'Model A', 'domain': 'MODEL_DOMAIN_GENERATIVE_AI', 'tags': []}]}, 'platform.json': {'devices': [{'chipset': 'SM8750', 'os': {'ostype': 'OPERATING_SYSTEM_TYPE_ANDROID'}}]}, 'model-a.json': {'model_id': 'model-a', 'assets': [{'runtime': 'RUNTIME_GENIEX_QAIRT', 'chipset': 'SM8750', 'precision': 'w4a16', 'download_url': 'https://example.org/package.zip'}, {'runtime': 'RUNTIME_ONNX', 'chipset': None, 'precision': 'float', 'download_url': 'https://example.org/model.onnx'}]}}
        for name, value in data.items():
            (folder / name).write_text(json.dumps(value))
    def test_reconciles_every_variant_without_inventing_downloads(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp); self.capture(path)
            result = module.normalize(path, {'models': []}, {}, 50)
            self.assertEqual(2, result['source']['artifactCount'])
            self.assertEqual({'missing_artifact_metadata', 'unsupported_platform'}, {v['status'] for v in result['coverage']})
            self.assertEqual('', result['models'][0]['downloadUrl'])
    def test_partial_capture_cannot_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp); self.capture(path); (path / 'model-a.json').unlink()
            with self.assertRaises(FileNotFoundError): module.normalize(path, {'models': []}, {}, 50)
    def test_duplicate_variant_is_an_error(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp); self.capture(path)
            doc = module.load(path / 'model-a.json'); doc['assets'].append(doc['assets'][0]); (path / 'model-a.json').write_text(json.dumps(doc))
            with self.assertRaisesRegex(ValueError, 'Duplicate'): module.normalize(path, {'models': []}, {}, 50)
    def test_wrong_inventory_hash_cannot_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp); self.capture(path)
            asset = module.load(path / 'model-a.json')['assets'][0]
            inventory = {module.stable_id('model-a', asset): {'sourceUrl': asset['download_url'], 'sha256': 'unverified'}}
            with self.assertRaises(ValueError): module.normalize(path, {'models': []}, inventory, 50)
