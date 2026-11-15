import unittest

from authentication.issue_report_diagnostics import (
    COMPOSER_EVENTS, COMPOSER_TIMELINE_FIELDS, CORE_FIELDS, validate_diagnostics,
)


class ReportExtensionsTest(unittest.TestCase):
    def snapshot(self, area, values):
        return {"schemaVersion": 1, "area": area, "capturedAtMillis": 1700000000000,
                "captureMode": "SELECTED_CAPTURE", "values": values,
                "unavailableFields": sorted(CORE_FIELDS[area] - values.keys())}

    def timeline(self, events=("ATTACHED", "IME_VISIBLE", "IME_HIDDEN")):
        values = {"composer_generation": 1, "composer_events_dropped": 0, "composer_enabled": True,
                  "composer_window_focused": False, "composer_lifecycle": "RESUMED", "composer_input_mode": "TOUCH"}
        for index, event in enumerate(events, 1):
            values[f"composer_event_{index}"] = event
            values[f"composer_event_{index}_age_ms"] = 0 if index == len(events) else 600000 - index
        return values

    def test_all_closed_events_and_exact_optional_field_set_preserve_order_and_legacy_values(self):
        self.assertEqual(22, len(COMPOSER_TIMELINE_FIELDS))
        self.assertEqual(24, len(COMPOSER_EVENTS))
        for event in COMPOSER_EVENTS:
            sample = self.snapshot("APP", self.timeline((event,) * 8))
            self.assertEqual(sample, validate_diagnostics(sample))
        for lifecycle in ("INITIALIZED", "CREATED", "STARTED", "RESUMED", "DESTROYED"):
            for mode in ("TOUCH", "KEYBOARD"):
                sample = self.snapshot("APP", dict(self.timeline(), composer_lifecycle=lifecycle, composer_input_mode=mode))
                self.assertEqual(sample, validate_diagnostics(sample))
        legacy = self.snapshot("APP", {"composer_focus_transitions": 12, "composer_ime_transitions": 12,
                                      "composer_focused": False, "composer_ime_visible": False})
        self.assertEqual(legacy, validate_diagnostics(legacy))
        self.assertFalse(COMPOSER_TIMELINE_FIELDS & set(legacy["unavailableFields"]))
        for field in COMPOSER_TIMELINE_FIELDS:
            omitted = self.snapshot("APP", {})
            omitted["omittedFields"] = [field]
            self.assertEqual(omitted, validate_diagnostics(omitted))
            unavailable = self.snapshot("APP", {})
            unavailable["unavailableFields"].append(field)
            with self.assertRaises(ValueError):
                validate_diagnostics(unavailable)

    def test_strict_types_bounds_unknown_fields_and_malformed_pairs_never_validate(self):
        valid = self.timeline()
        malformed = []
        for field in ("composer_generation", "composer_events_dropped", "composer_event_1_age_ms"):
            for invalid in (-1, "1", True, 1.0, None, [], {}, 600001 if field.endswith("age_ms") else 16000001):
                malformed.append(dict(valid, **{field: invalid}))
        malformed.append(dict(valid, composer_generation=0))
        for field in ("composer_enabled", "composer_window_focused"):
            for invalid in ("false", 0, None):
                malformed.append(dict(valid, **{field: invalid}))
        for field in ("composer_lifecycle", "composer_input_mode", "composer_event_1"):
            for invalid in ("private text", 1, None):
                malformed.append(dict(valid, **{field: invalid}))
        for missing in (("composer_generation",), ("composer_events_dropped",), ("composer_event_2",),
                        ("composer_event_2_age_ms",), ("composer_event_2", "composer_event_2_age_ms")):
            malformed.append({key: value for key, value in valid.items() if key not in missing})
        malformed += [dict(valid, composer_event_1_age_ms=1), dict(valid, composer_event_3_age_ms=1),
                      dict(valid, composer_event_9="ATTACHED"), dict(valid, typed_text="private text")]
        for values in malformed:
            with self.subTest(values=values), self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot("APP", values))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.snapshot("VOICE", valid))
        equal_ages = {key: 0 if key.endswith("_age_ms") else value for key, value in valid.items()}
        self.assertTrue(validate_diagnostics(self.snapshot("APP", equal_ages)))
        self.assertTrue(validate_diagnostics(self.snapshot("APP", dict(valid, composer_generation=16000000, composer_events_dropped=16000000))))
        for omitted in (["composer_event_4"], ["composer_event_4", "composer_event_4_age_ms"]):
            mixed = self.snapshot("APP", valid)
            mixed["omittedFields"] = omitted
            with self.assertRaises(ValueError):
                validate_diagnostics(mixed)
        all_omitted = self.snapshot("APP", {})
        all_omitted["omittedFields"] = [field for field in COMPOSER_TIMELINE_FIELDS if field.startswith("composer_event_")]
        self.assertEqual(all_omitted, validate_diagnostics(all_omitted))

    def test_tool_budget_and_local_graph_hash_are_optional_exact_types_without_cumulative_claims(self):
        for value in (0, 1, 1023, 16000000):
            sample = self.snapshot("TOOL", {"tool_budget_bytes_available": value})
            self.assertEqual(sample, validate_diagnostics(sample))
            self.assertNotIn("tool_result_bytes_used", sample["values"])
            self.assertNotIn("tool_result_bytes_limit", sample["values"])
        for field, valid, invalid in (
            ("tool_budget_bytes_available", 0, (-1, 16000001, "0", True, 0.0, None)),
            ("graph_index_attempt_hash", "a" * 64, ("A" * 64, "a" * 63, "g" * 64, "private UUID", 1, None)),
        ):
            sample = self.snapshot("TOOL", {field: valid})
            self.assertEqual(sample, validate_diagnostics(sample))
            for value in invalid:
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot("TOOL", {field: value}))
            with self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot("APP", {field: valid}))
            omitted = self.snapshot("TOOL", {})
            omitted["omittedFields"] = [field]
            self.assertEqual(omitted, validate_diagnostics(omitted))
            unavailable = self.snapshot("TOOL", {})
            unavailable["unavailableFields"].append(field)
            with self.assertRaises(ValueError):
                validate_diagnostics(unavailable)
