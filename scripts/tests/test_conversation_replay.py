import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("replay", Path(__file__).parents[1] / "replay_conversation_debug.py")
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class ConversationReplayTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
