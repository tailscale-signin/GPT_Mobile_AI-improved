import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import github_routing_replay as replay
from replay_conversation_debug import STAGED_SCHEMA

FIXTURE = Path(__file__).parent / "fixtures/github-routing-pr-2568.json"


class RoutingReplayTest(unittest.TestCase):
    def test_workspace_body_references_preserve_envelopes_and_require_prior_verified_bodies(self):
        body = {"checks": [{"state": "PASS", "id": n} for n in range(100)]}
        def ref(row_id, pointer, value):
            return dict(schema="gptmobile.workspace-payload-reference.v1", table="workspace_records", rowId=row_id,
                        pointer=pointer, redactedJsonSha256=hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest())
        first = dict(table="workspace_records", rowId=8, fields=dict(payload=dict(snapshot_id="old", capability_states=body)))
        second = dict(table="workspace_records", rowId=3, fields=dict(payload=dict(snapshot_id="new"),
                      payload_body_refs=dict(capability_states=ref(8, "/fields/payload/capability_states", body))))
        restored = list(replay.workspace_payloads([first, second]))
        self.assertEqual(dict(snapshot_id="new", capability_states=body), restored[1][1])
        whole = dict(table="workspace_records", rowId=9, fields=dict(payload_ref=ref(8, "/fields/payload", first["fields"]["payload"])))
        self.assertEqual(first["fields"]["payload"], list(replay.workspace_payloads([first, whole]))[1][1])
        with self.assertRaisesRegex(ValueError, "earlier row"):
            list(replay.workspace_payloads([second, first]))
        invalid = copy.deepcopy(second)
        invalid["fields"]["payload_body_refs"]["capability_states"]["redactedJsonSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            list(replay.workspace_payloads([first, invalid]))
        invalid = copy.deepcopy(second)
        invalid["fields"]["payload"]["capability_states"] = {"state": "FAILED"}
        with self.assertRaisesRegex(ValueError, "overwrites current evidence"):
            list(replay.workspace_payloads([first, invalid]))

    def test_cli_compares_actual_run_metrics_and_rejects_unpaired_task(self):
        cli = SCRIPTS / "replay_conversation_debug.py"
        result = json.loads(subprocess.check_output([sys.executable, cli, FIXTURE, "--github-routing"]))
        baseline, cache = result["runs"]
        self.assertEqual([2, 2], [r["verifiedRequirements"] for r in (baseline, cache)])
        self.assertEqual([1, 0], [r["repeatedLookups"] for r in (baseline, cache)])
        self.assertEqual([0, 0], [r["tokens"] for r in (baseline, cache)])
        self.assertTrue(all(r["elapsedMs"] >= 0 for r in (baseline, cache)))
        trace = json.loads(FIXTURE.read_bytes())
        trace["task"] = "0" * 64
        with tempfile.TemporaryDirectory() as tmp:
            other = Path(tmp) / "other.json"
            other.write_text(json.dumps(trace))
            with self.assertRaisesRegex(ValueError, "same task"):
                replay.replay([FIXTURE, other])

    def test_unwitnessed_criteria_remain_unverified_and_unknown_usage_remains_unknown(self):
        trace = json.loads(FIXTURE.read_bytes())
        trace["runs"][0]["tokens"] = None
        trace["runs"][0]["requirements"][0]["witnesses"] = []
        self.assertIsNone(replay.compare(trace)["runs"][0]["tokens"])
        self.assertEqual(1, replay.compare(trace)["runs"][0]["verifiedRequirements"])
        broken = copy.deepcopy(trace)
        broken["runs"][0]["calls"][0]["arguments"] = {"token": "private"}
        with self.assertRaisesRegex(ValueError, "Invalid call"):
            replay.validate(broken)
        trace["runs"][0]["requirements"][0]["witnesses"] = [99]
        with self.assertRaisesRegex(ValueError, "Invalid verified evidence"):
            replay.validate(trace)

    def test_projects_only_passed_requirements_with_bound_immutable_receipts_from_staged_export(self):
        revision = "1" * 40
        task = "2" * 64
        secret = "private task and file"
        def row(table, row_id, **fields):
            return dict(table=table, rowId=row_id, fields=fields)
        receipt = dict(schema="github.evidence.v1", binding="workspace", receipt_id="r", call_id="tool",
                       trusted=True, success=True, verification_eligible=True, immutable=True,
                       arguments={"commit_sha": revision}, source_head_sha=revision, source_result_sha256="3"*64,
                       evidence_origin="LIVE", remote_mutation=False)
        ledger = dict(schema="unused", binding="workspace", next_action_decision={"mode": "ACTIVE"},
                      acceptance_ledger=dict(request_digest=task, requirements=[
                          dict(id="req1", criterion=secret, state="PASS", receipt_ids=["r"]),
                          dict(id="req2", criterion=secret, state="PASS", receipt_ids=["missing"]),
                          dict(id="req3", criterion=secret, state="BLOCKED", receipt_ids=["r"])]))
        stages = {"workspace": [row("workspace_records", 1, kind="github_evidence", payload=receipt, runId="private run"),
                                row("workspace_records", 2, kind="synthesis_checkpoint", payload=ledger, runId="private run", updatedAt=2)],
                  "tool-receipts": [row("tool_events", 3, call_id="tool", run_id="private run", sequence=1,
                                        started_at=4, completed_at=7, arguments={"path": secret})],
                  "runs": [row("agent_runs", 4, run_id="private run", started_at=1, completed_at=8, total_tokens=None)]}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "capture.zip"
            def archive():
                inventory = []
                with ZipFile(path, "w") as target:
                    for name, rows in stages.items():
                        encoded = b"".join(json.dumps(r).encode() + b"\n" for r in rows)
                        filename = name + ".jsonl"
                        target.writestr(filename, encoded)
                        inventory.append(dict(id=name.replace("-", "_"), fileName=filename, status="COMPLETED", critical=True,
                                              bytes=len(encoded), records=len(rows), evidenceRecords=len(rows), sha256=hashlib.sha256(encoded).hexdigest()))
                    target.writestr("manifest.json", json.dumps(dict(schema=STAGED_SCHEMA, stages=inventory, usefulRecords=4,
                                                                      capturedFully=False, captureCompleted=True, criticalEvidenceAvailable=True,
                                                                      emptyCriticalEvidence=False, retryRecommended=False)))
            archive()
            trace = replay.replay([path], revision, project=True)
            self.assertNotIn("private", json.dumps(trace))
            run = replay.compare(trace)["runs"][0]
            self.assertEqual((1, 3, None, 7, "active"), (run["verifiedRequirements"], run["requirements"], run["tokens"], run["elapsedMs"], run["policy"]))
            stages["runs"][0]["fields"]["total_tokens"] = 321
            archive()
            measured = replay.replay([path], revision)["runs"][0]
            self.assertEqual(321, measured["tokens"])
            self.assertIsNone(measured["tokensPerCompletedTask"])
            receipt["immutable"] = False
            archive()
            with self.assertRaisesRegex(ValueError, "No bound task"):
                replay.replay([path], revision)
