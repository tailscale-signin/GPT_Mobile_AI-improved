import unittest

from v14.events import replay_snapshot


class EventReplayTests(unittest.TestCase):
    def test_gap_returns_generation_bound_snapshot_at_latest_cursor(self):
        snapshot = replay_snapshot(
            "job-1",
            {"generation": "gen-1", "status": "running", "last_sequence": 12, "result_available": False},
            [{"sequence": 9}, {"sequence": 10}],
            3,
            "gpt-mobile-gateway-progress/2",
        )
        self.assertEqual(snapshot["generation"], "gen-1")
        self.assertEqual(snapshot["sequence"], 12)
        self.assertTrue(snapshot["replayGap"])
        self.assertFalse(snapshot["resultAvailable"])

    def test_contiguous_replay_needs_no_snapshot(self):
        self.assertIsNone(replay_snapshot("job-1", {}, [{"sequence": 4}, {"sequence": 5}], 3,
                                          "gpt-mobile-gateway-progress/2"))


if __name__ == "__main__":
    unittest.main()
