import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("log_replay", Path(__file__).parents[1] / "replay_conversation_debug.py")
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class StructuredDiagnosticInspectionTest(unittest.TestCase):
    def event(self, identity, **values):
        row = dict(schema=7, kind="event", eventId=identity, incidentId=identity, sequence=0,
                   captureSource="critical.log", duplicate=False, countAsIssue=False,
                   classification="SYMPTOM", truncatedAtSource=False, truncatedAtExport=False,
                   tag="Tool", eventName="Finished", fields={})
        row.update(values)
        return row

    def fixture(self):
        export = self.event("export", captureSource="unknown", tag="Export", fields={"appCommit": "1" * 40})
        capture = self.event("capture", captureSource="unknown", tag="DiagnosticLogCapture",
                             fields=dict(retainedRecords=4, sourceFailures=0, droppedRecords=0,
                                         droppedCriticalRecords=0, trackingEnabled=True, omissionReason="none"))
        failure = self.event("failure", countAsIssue=True, classification="OBSERVED_FAILURE",
                             errorCode="INVALID_ARGUMENTS", message="Bearer secret-value-do-not-publish",
                             fields={"endpoint": "https://private.invalid/secret-value-do-not-publish"})
        continuation = self.event("failure:1", kind="continuation", parentEventId="failure", sequence=1,
                                  message="secret-value-do-not-publish continuation")
        frame = self.event("frame", captureSource="current.log", tag="Rendering", eventName="FRAME_TIMING",
                           fields=dict(frames=100, jankFrames=2, stallsOver100Ms=1, stallsOver500Ms=0,
                                       droppedSamples=0, fallbackDeadlines=0, screen="Chat"))
        batch = dict(schema=7, kind="duplicate_reference_batch", duplicate=True, countAsIssue=False,
                     referenceCount=1, references=[dict(eventId="failure", captureSource="previous.log",
                                                        sourceLine=1, canonicalSource="critical.log",
                                                        canonicalLine=1, payloadSha256="a" * 64)])
        return [export, capture, failure, continuation, frame, batch]

    def inspect(self, rows):
        body = "".join(json.dumps(row) + "\n" for row in rows).encode()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-diagnostics.jsonl"
            path.write_bytes(body)
            return replay.inspect_snapshot(replay.load_snapshot(path)), body

    def test_inspects_actual_export_shape_and_reconciles_source_records(self):
        result, body = self.inspect(self.fixture())
        self.assertEqual("retained-log-records-inspected", result["result"])
        self.assertEqual(hashlib.sha256(body).hexdigest(), result["inputSha256"])
        self.assertEqual(len(body), result["inputBytes"])
        self.assertEqual("1" * 40, result["appCommit"])
        self.assertEqual(4, result["counts"]["event"])
        self.assertEqual(1, result["counts"]["continuation"])
        self.assertEqual(1, result["counts"]["duplicateReferences"])
        self.assertEqual(4, result["capture"]["reconciledSourceRecords"])
        self.assertTrue(result["capture"]["recordCountMatches"])
        self.assertEqual([3], result["failureSignatures"][0]["sourceLines"])
        self.assertEqual("tool.invalid-arguments", result["failureSignatures"][0]["signature"])
        self.assertEqual("not-verified", result["taskCompletion"])
        self.assertEqual("not-established", result["causalAttribution"])
        self.assertEqual("not-established", result["physicalDeviceQualification"])
        self.assertEqual("unavailable", result["reducerReplay"])
        self.assertNotIn("secret-value-do-not-publish", json.dumps(result))

    def test_all_frame_windows_contribute_even_when_not_classified_as_issues(self):
        rows = self.fixture()
        another = copy.deepcopy(rows[4])
        another.update(eventId="frame-two", incidentId="frame-two", countAsIssue=True, classification="OBSERVED_FAILURE")
        another["fields"]["screen"] = "private screen name"
        rows.append(another)
        rows[1]["fields"]["retainedRecords"] += 1
        result, _ = self.inspect(rows)
        self.assertEqual(200, result["frameObservations"]["totals"]["frames"])
        self.assertEqual(4, result["frameObservations"]["totals"]["jankFrames"])
        self.assertEqual({"Chat", "other"}, set(result["frameObservations"]["screens"]))
        self.assertNotIn("private screen name", json.dumps(result))

    def test_repeated_observations_are_not_reported_as_distinct_defects(self):
        rows = self.fixture()
        rows.append(copy.deepcopy(rows[2]))
        rows[1]["fields"]["retainedRecords"] += 1
        result, _ = self.inspect(rows)
        self.assertEqual(2, result["counts"]["issueRecords"])
        self.assertEqual(1, result["counts"]["distinctIssueIdentities"])
        self.assertEqual(2, result["failureSignatures"][0]["observations"])
        self.assertIn("repeated-event-identities", result["warnings"])
        self.assertEqual("partial", result["result"])

    def test_canonical_reference_can_point_to_continuation(self):
        rows = self.fixture()
        rows[-1]["references"][0]["eventId"] = "failure:1"
        rows[-1]["references"][0]["canonicalLine"] = 2
        result, _ = self.inspect(rows)
        self.assertEqual("retained-log-records-inspected", result["result"])

    def test_rejects_self_forward_wrong_position_and_out_of_range_references(self):
        for change in ("self", "forward", "wrong-position", "outside-canonical", "outside-source", "historical"):
            rows = self.fixture()
            reference = rows[-1]["references"][0]
            if change == "self":
                reference["captureSource"] = "critical.log"
            elif change == "forward":
                reference.update(captureSource="critical.log", canonicalLine=2)
            elif change == "wrong-position":
                reference["canonicalLine"] = 2
            elif change == "outside-canonical":
                reference["canonicalLine"] = 99999
            elif change == "outside-source":
                reference["sourceLine"] = 99999
            else:
                reference.update(canonicalSource="unknown", eventId="export")
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.inspect(rows)

    def test_valid_same_source_recap_and_later_source_positions_reconcile(self):
        rows = self.fixture()
        rows[-1]["references"][0].update(captureSource="critical.log", sourceLine=2)
        result, _ = self.inspect(rows)
        self.assertEqual("retained-log-records-inspected", result["result"])
        self.assertTrue(result["capture"]["recordCountMatches"])
        # After a recapture at line2, the full continuation occupies source line3.
        rows[-1]["references"].append(dict(eventId="failure:1", captureSource="previous.log", sourceLine=1,
                                           canonicalSource="critical.log", canonicalLine=3, payloadSha256="b" * 64))
        rows[-1]["referenceCount"] = 2
        rows[1]["fields"]["retainedRecords"] += 1
        result, _ = self.inspect(rows)
        self.assertEqual(2, result["counts"]["duplicateReferences"])

    def test_reference_cannot_claim_a_canonical_row_exported_later(self):
        rows = self.fixture()
        batch = rows.pop()
        rows.insert(2, batch)
        with self.assertRaises(ValueError):
            self.inspect(rows)

    def test_rejects_orphan_gap_and_wrong_continuation_identity(self):
        for parent, sequence, identity in (("missing", 1, "missing:1"), ("failure", 2, "failure:2"), ("failure", 1, "wrong")):
            with self.subTest(parent=parent, sequence=sequence), self.assertRaises(ValueError):
                rows = self.fixture()
                rows[3].update(parentEventId=parent, sequence=sequence, eventId=identity)
                self.inspect(rows)

    def test_rejects_missing_wrong_source_or_repeated_duplicate_references(self):
        for change in ("missing", "wrong-source", "repeated", "wrong-count"):
            rows = self.fixture()
            batch = rows[-1]
            if change == "missing":
                batch["references"][0]["eventId"] = "not-retained"
            elif change == "wrong-source":
                batch["references"][0]["canonicalSource"] = "absent.log"
            elif change == "repeated":
                batch["references"].append(copy.deepcopy(batch["references"][0]))
                batch["referenceCount"] = 2
            else:
                batch["referenceCount"] = 2
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.inspect(rows)

    def test_capture_gaps_truncation_and_missing_summary_never_become_complete(self):
        for change in ("count", "dropped", "source", "truncated", "tracking", "summary"):
            rows = self.fixture()
            if change == "count":
                rows[1]["fields"]["retainedRecords"] += 1
            elif change == "dropped":
                rows[1]["fields"]["droppedRecords"] = 1
            elif change == "source":
                rows[1]["fields"]["sourceFailures"] = 1
            elif change == "truncated":
                rows[2]["truncatedAtSource"] = True
            elif change == "tracking":
                rows[1]["fields"]["trackingEnabled"] = False
            else:
                del rows[1]
            with self.subTest(change=change):
                result, _ = self.inspect(rows)
                self.assertEqual("partial", result["result"])
                self.assertTrue(result["warnings"])
                self.assertEqual("not-verified", result["taskCompletion"])

    def test_duplicate_frame_identity_never_inflates_denominator_and_divergence_is_visible(self):
        for divergent in (False, True):
            rows = self.fixture()
            repeated = copy.deepcopy(rows[4])
            if divergent:
                repeated["fields"]["frames"] = 300
            rows.append(repeated)
            rows[1]["fields"]["retainedRecords"] += 1
            with self.subTest(divergent=divergent):
                result, _ = self.inspect(rows)
                self.assertEqual(100, result["frameObservations"]["totals"]["frames"])
                self.assertEqual(divergent, "divergent-frame-identities" in result["warnings"])

    def test_unknown_failure_metadata_and_screen_names_are_not_published(self):
        rows = self.fixture()
        rows[2].update(errorCode="PRIVATE_VALUE", eventName="PRIVATE_VALUE", tag="PRIVATE_VALUE", category="PRIVATE_VALUE")
        result, _ = self.inspect(rows)
        self.assertEqual("other.observed-failure", result["failureSignatures"][0]["signature"])
        self.assertNotIn("PRIVATE_VALUE", json.dumps(result))

    def test_signature_line_samples_remain_bounded_without_losing_observation_counts(self):
        rows = self.fixture()[:2]
        rows[1]["fields"]["retainedRecords"] = 80
        rows += [self.event(f"failure-{i}", countAsIssue=True, classification="OBSERVED_FAILURE", errorCode="POLICY_STALE")
                 for i in range(80)]
        result, _ = self.inspect(rows)
        group = result["failureSignatures"][0]
        self.assertEqual(80, group["observations"])
        self.assertEqual(64, len(group["sourceLines"]))
        self.assertEqual(16, group["sourceLinesOmitted"])

    def test_malformed_input_rejects_without_echoing_private_payload(self):
        invalid = (b'{"secret-value-do-not-publish":\n', b'{}', b'\xff\n',
                   b'{"schema":7,"schema":7,"secret-value-do-not-publish":0}\n',
                   b'{"secret-value-do-not-publish":NaN}\n')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-diagnostics.jsonl"
            for body in invalid:
                path.write_bytes(body)
                with self.subTest(body=body):
                    with self.assertRaises(ValueError) as raised:
                        replay.load_snapshot(path)
                    self.assertNotIn("secret-value-do-not-publish", str(raised.exception))

    def test_rejects_boolean_counters_future_schema_contradictory_flags_and_invalid_frames(self):
        for change in ("boolean", "schema", "flags", "frames", "fields"):
            rows = self.fixture()
            if change == "boolean":
                rows[1]["fields"]["retainedRecords"] = True
            elif change == "schema":
                rows[2]["schema"] = 8
            elif change == "flags":
                rows[2]["duplicate"] = True
            elif change == "frames":
                rows[4]["fields"]["jankFrames"] = 101
            else:
                rows[2]["fields"] = []
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.inspect(rows)

    def test_parser_bounds_rows_bytes_and_line_allocation(self):
        for name, limit in (("MAX_LOG_ROWS", 2), ("MAX_BYTES", 200), ("MAX_LOG_LINE_BYTES", 80)):
            with self.subTest(bound=name), patch.object(replay, name, limit), self.assertRaises(ValueError):
                self.inspect(self.fixture())


if __name__ == "__main__":
    unittest.main()
