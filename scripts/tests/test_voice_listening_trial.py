"""Host regressions for listening-sheet identity and complete binary judgments."""
import contextlib
import copy
import csv
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace


SPEC = importlib.util.spec_from_file_location("voice_listening_trial", Path(__file__).resolve().parents[1] / "voice_listening_trial.py")
trial = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(trial)
FIELDS = ("trial_id", "listener_id", "manifest_sha256", "clip", "target", "expected", "correct", "unnatural_pause")


class VoiceListeningTrialTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="voice-listening-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inputs = {}
        for arm in ("previous", "candidate", "streaming"):
            folder = self.root / arm
            folder.mkdir()
            self.inputs[arm] = folder
            for fixture in range(1, 25):
                # These tiny format fixtures are not acoustic qualification recordings.
                with wave.open(str(folder / f"{fixture:02}.wav"), "wb") as output:
                    output.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
                    output.writeframes(b"\0\0" * 8)

    def _prepare(self, name, seed=41):
        folder = self.root / name
        with contextlib.redirect_stdout(io.StringIO()):
            trial.prepare(SimpleNamespace(**self.inputs, output=folder, seed=seed))
        return folder, json.loads((folder / "coordinator.json").read_bytes())

    def _sheet(self, folder, listener):
        return folder / f"listener-{listener}" / "ratings.csv"

    def _read_rows(self, folder, listener):
        with self._sheet(folder, listener).open(newline="", encoding="utf-8") as source:
            return list(csv.DictReader(source))

    def _write_rows(self, folder, listener, rows, fields=FIELDS):
        with self._sheet(folder, listener).open("w", newline="", encoding="utf-8") as output:
            writer = csv.writer(output)
            writer.writerow(fields)
            writer.writerows([[row.get(field, "") for field in fields] for row in rows])

    def _fill_votes(self, folder, correct=None, pauses=None):
        manifest = json.loads((folder / "coordinator.json").read_bytes())
        assignments = {(row["listener"], row["clip"]): row["arm"] for row in manifest["assignments"]}
        correct_left = dict(correct or {"previous": 72, "candidate": 72, "streaming": 72})
        pauses_left = dict(pauses or {"previous": 72, "candidate": 0, "streaming": 0})
        for listener in range(1, 4):
            rows = self._read_rows(folder, listener)
            for row in rows:
                arm = assignments[listener, row["clip"]]
                row["correct"] = "1" if correct_left[arm] else "0"
                row["unnatural_pause"] = "1" if pauses_left[arm] else "0"
                correct_left[arm] -= int(row["correct"])
                pauses_left[arm] -= int(row["unnatural_pause"])
            self._write_rows(folder, listener, rows)
        self.assertEqual(set(correct_left.values()) | set(pauses_left.values()), {0})

    def _snapshot(self, folder):
        paths = [folder / "coordinator.json"] + [self._sheet(folder, listener) for listener in range(1, 4)]
        return {path: path.read_bytes() for path in paths}

    def _restore(self, snapshot):
        for path, data in snapshot.items():
            path.write_bytes(data)

    def _replace_manifest(self, folder, manifest):
        data = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        (folder / "coordinator.json").write_bytes(data)
        # Keep the digest current so malformed manifests must fail structural validation.
        for listener in range(1, 4):
            rows = self._read_rows(folder, listener)
            for row in rows:
                row["manifest_sha256"] = hashlib.sha256(data).hexdigest()
            self._write_rows(folder, listener, rows)

    def test_complete_trial_preserves_seeded_counterbalance_and_thresholds(self):
        folder, manifest = self._prepare("complete")
        _, repeated = self._prepare("same-seed")
        self.assertEqual(manifest["assignments"], repeated["assignments"])
        identities = {manifest["trialId"], *manifest["listenerIds"].values()}
        repeated_identities = {repeated["trialId"], *repeated["listenerIds"].values()}
        self.assertEqual(len(identities), 4)
        self.assertTrue(identities.isdisjoint(repeated_identities))
        for identity in identities | repeated_identities:
            self.assertRegex(identity, r"^[0-9a-f]{32}$")
        for fixture in range(1, 25):
            orders = [[row["arm"] for row in manifest["assignments"]
                       if row["listener"] == listener and row["fixture"] == f"{fixture:02}"]
                      for listener in range(1, 4)]
            self.assertEqual([len(order) for order in orders], [3, 3, 3])
            for position in range(3):
                self.assertEqual({order[position] for order in orders}, {"previous", "candidate", "streaming"})
        for listener in range(1, 4):
            rows = self._read_rows(folder, listener)
            self.assertEqual(tuple(rows[0]), FIELDS)
            self.assertEqual(len(rows), 72)
            self.assertTrue(all(row["correct"] == row["unnatural_pause"] == "" for row in rows))
        self._fill_votes(folder)
        result = trial.score(folder)
        self.assertTrue(result["listeningPassed"])
        self.assertEqual(result["trialId"], manifest["trialId"])
        self.assertEqual(result["manifestSha256"], hashlib.sha256((folder / "coordinator.json").read_bytes()).hexdigest())
        for arm, counts in result["results"].items():
            self.assertEqual(counts, dict(judgments=72, correct=72, unnaturalPauses=72 if arm == "previous" else 0,
                                          correctnessDisagreements=0, pauseDisagreements=0))
        for candidate_correct, streaming_correct, previous_pauses, candidate_pauses, expected in (
                (69, 69, 72, 0, True),
                (68, 68, 72, 0, False),
                (69, 70, 72, 0, False),
                (72, 72, 0, 0, False)):
            with self.subTest(candidate=candidate_correct, streaming=streaming_correct, previous_pauses=previous_pauses):
                self._fill_votes(folder, {"previous": 72, "candidate": candidate_correct, "streaming": streaming_correct},
                                 {"previous": previous_pauses, "candidate": candidate_pauses, "streaming": 0})
                self.assertEqual(trial.score(folder)["listeningPassed"], expected)

    def test_rejects_same_seed_stale_mixed_and_wrong_listener_sheets(self):
        old, old_manifest = self._prepare("old")
        folder, manifest = self._prepare("current")
        self.assertEqual(old_manifest["assignments"], manifest["assignments"])
        self._fill_votes(old)
        self._fill_votes(folder)
        snapshot = self._snapshot(folder)
        for listeners in ((1,), (1, 2, 3)):
            with self.subTest(stale_listeners=listeners):
                self._restore(snapshot)
                for listener in listeners:
                    self._sheet(folder, listener).write_bytes(self._sheet(old, listener).read_bytes())
                with self.assertRaisesRegex(ValueError, "does not belong"):
                    trial.score(folder)
        self._restore(snapshot)
        first, second = self._sheet(folder, 1), self._sheet(folder, 2)
        first.write_bytes(snapshot[second])
        second.write_bytes(snapshot[first])
        with self.assertRaisesRegex(ValueError, "does not belong"):
            trial.score(folder)
        unknown_id = ("0" if manifest["listenerIds"]["1"][0] != "0" else "1") + manifest["listenerIds"]["1"][1:]
        for field, value in (("trial_id", old_manifest["trialId"]),
                             ("listener_id", unknown_id),
                             ("listener_id", manifest["listenerIds"]["2"]),
                             ("manifest_sha256", "0" * 64)):
            with self.subTest(field=field, value=value):
                self._restore(snapshot)
                rows = self._read_rows(folder, 1)
                rows[-1][field] = value
                self._write_rows(folder, 1, rows)
                with self.assertRaisesRegex(ValueError, "does not belong"):
                    trial.score(folder)
        self._restore(snapshot)
        self._write_rows(folder, 1, self._read_rows(folder, 1), fields=("clip", "target", "expected", "correct", "unnatural_pause"))
        with self.assertRaisesRegex(ValueError, "headers"):
            trial.score(folder)

    def test_rejects_manifest_identity_assignment_and_exact_byte_substitutions(self):
        folder, manifest = self._prepare("manifest")
        self._fill_votes(folder)
        snapshot = self._snapshot(folder)
        cases = []
        for field, value in (("schemaVersion", 1), ("schemaVersion", True), ("schemaVersion", 2.0),
                             ("trialId", None), ("trialId", "not-a-trial"), ("listenerIds", []),
                             ("corpusSha256", "0" * 64), ("assignments", {})):
            changed = copy.deepcopy(manifest)
            changed[field] = value
            cases.append((f"{field}={value!r}", changed))
        changed = copy.deepcopy(manifest)
        del changed["schemaVersion"]
        cases.append(("unversioned manifest", changed))
        changed = copy.deepcopy(manifest)
        changed["extra"] = "unrecognized"
        cases.append(("extra manifest field", changed))
        for listener_ids in ({"1": manifest["listenerIds"]["1"], "2": manifest["listenerIds"]["2"]},
                             dict(manifest["listenerIds"], **{"4": "a" * 32}),
                             dict(manifest["listenerIds"], **{"2": manifest["listenerIds"]["1"]}),
                             dict(manifest["listenerIds"], **{"2": manifest["trialId"]}),
                             dict(manifest["listenerIds"], **{"2": []})):
            changed = copy.deepcopy(manifest)
            changed["listenerIds"] = listener_ids
            cases.append((f"invalid listener identities {listener_ids!r}", changed))
        for field, value in (("listener", 4), ("listener", True), ("listener", "1"),
                             ("clip", "073"), ("clip", 1), ("fixture", "25"), ("fixture", []),
                             ("arm", "unknown"), ("arm", {}), ("extra", "unrecognized")):
            changed = copy.deepcopy(manifest)
            changed["assignments"][0][field] = value
            cases.append((f"assignment {field}={value!r}", changed))
        for operation in ("missing", "extra", "duplicate", "duplicate_pair", "nonobject"):
            changed = copy.deepcopy(manifest)
            if operation == "missing":
                changed["assignments"].pop()
            elif operation == "extra":
                changed["assignments"].append(copy.deepcopy(changed["assignments"][0]))
            elif operation == "duplicate":
                changed["assignments"][1] = copy.deepcopy(changed["assignments"][0])
            elif operation == "duplicate_pair":
                changed["assignments"][1]["arm"] = changed["assignments"][0]["arm"]
            else:
                changed["assignments"][0] = None
            cases.append((operation + " assignment", changed))
        cases.append(("nonobject manifest", []))
        for name, changed in cases:
            with self.subTest(name=name):
                self._restore(snapshot)
                self._replace_manifest(folder, changed)
                with self.assertRaises(ValueError):
                    trial.score(folder)
        manifest_path = folder / "coordinator.json"
        for name, data, message in (
                ("duplicate JSON field", b'{"schemaVersion":2,' + snapshot[manifest_path][1:], "Duplicate coordinator field"),
                ("same JSON different bytes", snapshot[manifest_path] + b"\n", "does not belong")):
            with self.subTest(name=name):
                self._restore(snapshot)
                manifest_path.write_bytes(data)
                with self.assertRaisesRegex(ValueError, message):
                    trial.score(folder)

    def test_rejects_header_prompt_vote_cell_and_size_corruption(self):
        folder, _ = self._prepare("sheets")
        self._fill_votes(folder)
        snapshot = self._snapshot(folder)
        sheet = self._sheet(folder, 1)
        original = list(csv.reader(io.StringIO(snapshot[sheet].decode("utf-8"), newline="")))
        cases = []
        for name, header in (("reordered", [FIELDS[1], FIELDS[0], *FIELDS[2:]]),
                             ("duplicate", [FIELDS[0], FIELDS[0], *FIELDS[2:]]),
                             ("missing", list(FIELDS[:-1])), ("extra", [*FIELDS, "comment"]),
                             ("renamed", [*FIELDS[:3], "Clip", *FIELDS[4:]])):
            changed = copy.deepcopy(original)
            changed[0] = header
            cases.append((name + " header", changed))
        for column in (4, 5):
            changed = copy.deepcopy(original)
            changed[1][column] += " substituted"
            cases.append(("changed prompt " + FIELDS[column], changed))
        for column in (6, 7):
            for value in ("", "2", "-1", "true", "1.0", " 1", "1 ", "01"):
                changed = copy.deepcopy(original)
                changed[1][column] = value
                cases.append((f"invalid {FIELDS[column]}={value!r}", changed))
        for name in ("duplicate row", "missing row", "extra row", "unknown clip", "malformed clip", "extra cell", "missing cell"):
            changed = copy.deepcopy(original)
            if name == "duplicate row":
                changed[2] = changed[1].copy()
            elif name == "missing row":
                changed.pop()
            elif name == "extra row":
                changed.append(changed[1].copy())
            elif name == "unknown clip":
                changed[1][3] = "073"
            elif name == "malformed clip":
                changed[1][3] = "1"
            elif name == "extra cell":
                changed[1].append("extra")
            else:
                changed[1].pop()
            cases.append((name, changed))
        for name, changed in cases:
            with self.subTest(name=name):
                self._restore(snapshot)
                output = io.StringIO(newline="")
                csv.writer(output).writerows(changed)
                sheet.write_text(output.getvalue(), encoding="utf-8", newline="")
                with self.assertRaises(ValueError):
                    trial.score(folder)
        for name, data, message in (("unterminated CSV quote", (",".join(FIELDS) + '\n"unterminated\n').encode(), "Malformed CSV"),
                                    ("oversized sheet", b"x" * (trial.MAX_SHEET_BYTES + 1), "exceeds")):
            with self.subTest(name=name):
                self._restore(snapshot)
                sheet.write_bytes(data)
                with self.assertRaisesRegex(ValueError, message):
                    trial.score(folder)
        self._restore(snapshot)
        (folder / "coordinator.json").write_bytes(b"x" * (trial.MAX_COORDINATOR_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "exceeds"):
            trial.score(folder)


if __name__ == "__main__":
    unittest.main()
