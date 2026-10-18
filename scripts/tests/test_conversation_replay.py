import copy
import json
import tempfile
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("replay", Path(__file__).parents[1] / "replay_conversation_debug.py")
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class ConversationReplayTest(unittest.TestCase):
    def test_current_v4_and_legacy_versions_load_but_future_version_rejects(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "conversation.json"
            for version in (2, 3, 4):
                root = dict(self.fixture(), schema=f"gptmobile.conversation.debug.v{version}")
                path.write_text(json.dumps(root), encoding="utf-8")
                self.assertEqual(root, replay.load_snapshot(path))
            path.write_text(json.dumps(dict(root, schema="gptmobile.conversation.debug.v99")), encoding="utf-8")
            with self.assertRaises(ValueError):
                replay.load_snapshot(path)

    def fixture(self):
        texts = ["First paragraph.\n", "Second paragraph 👩🏽‍💻."]
        rows = []
        previous = None
        for index, text in enumerate(texts):
            rows.append(dict(runId="run", sequence=index, fence=4, text=text, digest=replay.digest(text), previousDigest=previous, utf8Bytes=len(text.encode("utf-8")), digestRepresentation="redacted-export-segment-utf8"))
            previous = rows[-1]["digest"]
        return dict(messages=[dict(currentRunId="run", content="".join(texts))], profiles=[], runs=[dict(runId="run", ownershipFence=4)], answerSegments=rows, segmentSourceIntegrity=[dict(runId="run", verified=True)])

    def test_replays_exact_committed_revision_without_fabricating_provider_capture(self):
        result = replay.inspect_snapshot(self.fixture())
        self.assertEqual("retained-segments-verified", result["result"])
        self.assertEqual("committed-revision-replayed", result["runs"][0]["status"])
        self.assertFalse(result["completeProviderWireCapture"])
        self.assertEqual("unavailable", result["providerDeltaReplay"])

    def test_locates_missing_prefix_and_rejects_stale_owner(self):
        root = self.fixture()
        root["messages"][0]["content"] = "Second paragraph 👩🏽‍💻."
        self.assertEqual("prefix-divergence", replay.inspect_snapshot(root)["runs"][0]["status"])
        root["runs"][0]["ownershipFence"] = 5
        row = replay.inspect_snapshot(root)["runs"][0]
        self.assertEqual("rejected", row["status"])
        self.assertIn("stale-or-unknown-fence", row["errors"])

    def test_sequence_gaps_and_divergent_duplicates_remain_explicit(self):
        root = self.fixture()
        event = dict(eventId="event", streamId="stream", sequence=2)
        duplicate = copy.deepcopy(event)
        duplicate["visibleBytes"] = 900
        root["runJournal"] = dict(events=[event, duplicate], highWatermarks={"stream": 4})
        result = replay.inspect_snapshot(root)
        self.assertEqual("partial", result["result"])
        self.assertEqual(3, result["journal"]["missingSequences"])
        self.assertIn("divergent-duplicate-event", result["journal"]["errors"])

    def test_fingerprints_locate_reducer_to_commit_loss_without_claiming_content_replay(self):
        root = self.fixture()
        def event(sequence, boundary, body, representation="VISIBLE_UTF8"):
            return dict(eventId=f"event-{sequence}", runId="run", streamId="stream", sequence=sequence, fence=4, outputBoundary=boundary, outputRepresentation=representation, outputFingerprint=dict(utf8Bytes=len(body.encode("utf-8")), sha256=replay.digest(body)))
        root["runJournal"] = dict(events=[event(1, "REDUCER", "Full answer"), event(2, "COMMITTED", "answer"), event(3, "RENDER_INPUT", "display text", "DISPLAY_FILTERED_MARKDOWN_UTF8")], highWatermarks={"stream": 3})
        result = replay.inspect_snapshot(root)
        self.assertEqual("partial", result["result"])
        self.assertEqual(["reducer-to-commit-divergence"], result["outputTracing"]["errors"])
        self.assertEqual("not-comparable", result["outputTracing"]["checkpoints"][-1]["comparison"])
        self.assertFalse(result["outputTracing"]["contentReplayAvailable"])
        root["runJournal"]["events"][1] = event(2, "COMMITTED", "Full answer")
        self.assertEqual("matches-reducer", replay.inspect_snapshot(root)["outputTracing"]["checkpoints"][1]["comparison"])
        root["runJournal"]["events"][1] = event(4, "COMMITTED", "Different")
        root["runJournal"]["highWatermarks"]["stream"] = 4
        traced = replay.inspect_snapshot(root)["outputTracing"]
        self.assertEqual("intervening-capture-gap", traced["checkpoints"][-1]["comparison"])
        self.assertEqual([], traced["errors"])


if __name__ == "__main__":
    unittest.main()
