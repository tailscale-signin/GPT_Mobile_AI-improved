"""The shared diagnostic identity follows UTF-8 contract content across checkouts."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('generate_diagnostic_schema', ROOT / 'scripts/generate_diagnostic_schema.py')
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class DiagnosticSchemaGenerationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.source = Path(temporary.name) / 'schema.json'
        self.schema = {
            'description': 'A UTF-8 contract: café',
            'choices': {'stage': ['request']},
            'numeric_attributes': ['status'],
            'digest_attributes': ['schema_digest'],
            'boolean_attributes': ['source_available'],
            'enum_attributes': {'method': ['GET']},
        }

    def contract_bytes(self):
        return (json.dumps(self.schema, ensure_ascii=False, indent=2) + '\n').encode('utf-8')

    def generated(self, raw):
        self.source.write_bytes(raw)
        with patch.object(GENERATOR, 'SOURCE', self.source):
            return GENERATOR.generated()

    def assert_digest(self, outputs, expected):
        self.assertIn(f"SCHEMA_DIGEST = '{expected}'", outputs[ROOT / 'gateway/v14/diagnostic_schema.py'])
        self.assertIn(f'const val DIGEST = "{expected}"', outputs[ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/gateway/GatewayDiagnosticSchema.kt'])

    def test_checkout_line_endings_preserve_the_lf_digest_and_both_generated_contracts(self):
        raw = self.contract_bytes()
        expected_digest = hashlib.sha256(raw).hexdigest()
        expected_outputs = self.generated(raw)
        for newline in (b'\n', b'\r\n', b'\r'):
            with self.subTest(newline=newline):
                outputs = self.generated(raw.replace(b'\n', newline))
                self.assert_digest(outputs, expected_digest)
                self.assertEqual(expected_outputs, outputs)

    def test_real_schema_change_changes_the_digest_and_both_allowlists(self):
        original = self.contract_bytes()
        before = self.generated(original)
        self.schema['choices']['stage'].append('transport')
        changed = self.contract_bytes()
        after = self.generated(changed)
        self.assertNotEqual(hashlib.sha256(original).hexdigest(), hashlib.sha256(changed).hexdigest())
        self.assert_digest(after, hashlib.sha256(changed).hexdigest())
        self.assertNotEqual(before, after)
        self.assertIn("'transport'", after[ROOT / 'gateway/v14/diagnostic_schema.py'])
        self.assertIn('"transport"', after[ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/gateway/GatewayDiagnosticSchema.kt'])
