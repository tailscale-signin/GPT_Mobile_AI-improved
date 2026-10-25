import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scoreboard import DOMAINS, best, manifest, score, validate


class VersionNineContractTest(unittest.TestCase):
    def submission(self, version):
        trials = []
        for key, (category, _) in manifest("MODEL", "FULL", version).items():
            trial = dict(id=key, outcome="PASSED", durationMs=500)
            if category == "intelligence":
                trial["intelligence"] = dict.fromkeys(DOMAINS, True)
            trials.append(trial)
        return dict(id="run-" + str(version), kind="MODEL", plan="FULL", suiteVersion=version, startedAt=1000, provider="CUSTOM", model="model", trials=trials, schemaVersion=2, effectiveConfigHash="a" * 64, comparisonContextHash="b" * 64, measurementVersion=3 if version == 8 else 4, scoringVersion=3 if version == 8 else 4)

    def test_eight_and_nine_share_case_identity_but_never_merge_policy_domains(self):
        old, new = self.submission(8), self.submission(9)
        self.assertEqual(manifest("MODEL", "FULL", 8), manifest("MODEL", "FULL", 9))
        self.assertEqual(50, len(new["trials"]))
        self.assertEqual(100.0, score(old))
        self.assertEqual(100.0, score(new))
        self.assertEqual(2, len(best([old, new])))

    def test_execution_failure_still_cannot_become_public_accuracy(self):
        for version in (8, 9):
            row = self.submission(version)
            row["trials"][0]["outcome"] = "ERROR"
            with self.assertRaisesRegex(ValueError, "answer accuracy"):
                validate(row)

    def test_old_version_seven_manifest_is_unchanged(self):
        self.assertIn("context", manifest("MODEL", "FULL", 7))
        self.assertNotIn("context", manifest("MODEL", "FULL", 8))
        self.assertNotIn("context", manifest("MODEL", "FULL", 9))
        self.assertIn("tool-11", manifest("MODEL", "FULL", 7))
        self.assertNotIn("tool-10", manifest("MODEL", "FULL", 8))
        self.assertNotIn("tool-10", manifest("MODEL", "FULL", 9))


if __name__ == "__main__":
    unittest.main()
