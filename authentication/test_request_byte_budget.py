import unittest

from authentication.issue_report_diagnostics import CORE_FIELDS, validate_diagnostics


class RequestByteBudgetTest(unittest.TestCase):
    def snapshot(self, values, area="PROVIDER"):
        return {"schemaVersion": 1, "area": area, "capturedAtMillis": 1700000000000,
                "captureMode": "INCIDENT", "values": values,
                "unavailableFields": sorted(CORE_FIELDS[area] - values.keys())}

    def test_exact_byte_counts_survive_the_closed_report_contract(self):
        values = {"provider_failure_code": "REQUEST_BYTES_EXCEEDED",
                  "request_serialized_bytes": 20_000_000, "request_tool_schema_bytes": 1000,
                  "request_evidence_bytes": 19_000_000, "provider_request_byte_limit": 16_777_216,
                  "provider_request_limit_source": "RESPONSE_HEADER", "payload_replan_allowed": False}
        report = self.snapshot(values)
        self.assertEqual(report, validate_diagnostics(report))
        legacy = self.snapshot({"provider_failure_code": "HTTP_ERROR"})
        self.assertEqual(legacy, validate_diagnostics(legacy))

    def test_unknown_limits_types_content_and_unrelated_areas_are_rejected(self):
        for field in ("request_serialized_bytes", "request_tool_schema_bytes", "request_evidence_bytes", "provider_request_byte_limit"):
            for invalid in (-1, 2_147_483_648, True, "1000", 1.0, None, "private request"):
                with self.subTest(field=field, invalid=invalid), self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot({field: invalid}))
        for invalid in ("Content-Length", "secret header", 1, None):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot({"provider_request_limit_source": invalid}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.snapshot({"request_serialized_bytes": 1000}, area="VOICE"))
