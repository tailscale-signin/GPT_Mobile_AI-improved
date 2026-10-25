import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scoreboard import DOMAINS, best, manifest, score, validate


class VersionTenPolicyTest(unittest.TestCase):
    def submission(self, version=10):
        domains = DOMAINS | {"Multilingual", "Grounded reasoning"} if version == 10 else DOMAINS
        trials = []
        for key, (category, _) in manifest("MODEL", "FULL", version).items():
            row = dict(id=key, outcome="PASSED", durationMs=500)
            if category == "intelligence":
                row.update(intelligence=dict.fromkeys(domains, True), intelligenceParseable=True, intelligenceFormatCompliant=True)
            trials.append(row)
        result = dict(id=f"run-{version}", kind="MODEL", plan="FULL", suiteVersion=version, startedAt=1000, provider="CUSTOM", model="model", trials=trials, schemaVersion=3 if version == 10 else 2, effectiveConfigHash="a"*64, comparisonContextHash="b"*64, measurementVersion=4, scoringVersion=4)
        if version == 10:
            result.update(executionPolicy="DETERMINISTIC_CAPABILITY_V1", fixtureRotationSeed=0, fixtureSetVersion=4, fixtureManifestHash="c"*64)
        return result

    def test_expansion_keeps_all_fifty_cases_and_all_eight_domains(self):
        data = self.submission()
        self.assertEqual(50, len(data["trials"]))
        self.assertEqual(48, sum(len(row.get("intelligence", {})) for row in data["trials"]))
        self.assertEqual(100.0, score(data))
        data["trials"][-1]["intelligence"]["Multilingual"] = False
        self.assertAlmostEqual(100.0 - 15.0 / 48, score(data))

    def test_unknown_or_saved_profile_policy_never_enters_capability_domain(self):
        for policy in (None, "PROFILE_SETTINGS_V1"):
            data = self.submission()
            data["executionPolicy"] = policy
            with self.assertRaises(ValueError):
                validate(data)
        self.assertEqual(100.0, score(self.submission(9)))

    def test_unparseable_semantics_and_missing_rotation_are_rejected(self):
        for change in (lambda row: row["trials"][-1].update(intelligenceParseable=False), lambda row: row.pop("fixtureRotationSeed"), lambda row: row.update(fixtureManifestHash="unknown")):
            data = self.submission()
            change(data)
            with self.assertRaises(ValueError):
                validate(data)

    def test_rotations_and_historical_grades_stay_separate(self):
        first = self.submission()
        rotated = copy.deepcopy(first)
        rotated.update(id="rotated", fixtureRotationSeed=1)
        self.assertEqual(3, len(best([first, rotated, self.submission(9)])))


if __name__ == "__main__":
    unittest.main()
