"""Schema admission and argument validation never dispatch invalid calls."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.contracts import SchemaValidationError, validate_arguments, validate_schema


class ContractValidationTests(unittest.TestCase):
    def setUp(self):
        self.schema = {
            "type": "object",
            "required": ["query", "limit"],
            "additionalProperties": False,
            "properties": {
                "query": {"type": "string", "minLength": 1, "maxLength": 80},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
                "filters": {
                    "type": "array", "maxItems": 3,
                    "items": {"type": "string", "enum": ["open", "closed"]},
                },
            },
        }

    def test_admitted_schema_accepts_valid_nested_values(self):
        validate_schema(self.schema)
        validate_arguments(self.schema, {"query": "parks", "limit": 10, "filters": ["open"]})

    def test_invalid_types_ranges_enums_and_extra_fields_are_rejected(self):
        invalid = [
            {"query": "parks", "limit": True},
            {"query": "parks", "limit": 21},
            {"query": "parks", "limit": 10, "filters": ["unknown"]},
            {"query": "parks", "limit": 10, "extra": "value"},
            {"query": "parks"},
        ]
        for args in invalid:
            with self.subTest(args=args), self.assertRaises(SchemaValidationError):
                validate_arguments(self.schema, args)

    def test_unsupported_schema_keywords_fail_admission(self):
        with self.assertRaises(SchemaValidationError):
            validate_schema({"type": "object", "$ref": "#/$defs/args"})

    def test_validation_budget_bounds_large_nested_payloads(self):
        schema = {"type": "array", "maxItems": 2000, "items": {"type": "string"}}
        with self.assertRaises(SchemaValidationError):
            validate_arguments(schema, ["x"] * 1200, max_nodes=100)

    def test_permissive_extra_values_are_still_traversal_bounded(self):
        schema = {"type": "object", "additionalProperties": True}
        value = {"extra": {"nested": {"deeper": "value"}}}
        with self.assertRaises(SchemaValidationError):
            validate_arguments(schema, value, max_nodes=3)

    def test_backtracking_pattern_features_are_rejected_at_admission(self):
        with self.assertRaises(SchemaValidationError):
            validate_schema({"type": "string", "pattern": "(a+)+$"})


if __name__ == "__main__":
    unittest.main()
