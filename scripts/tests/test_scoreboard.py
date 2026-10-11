import copy
import json
from pathlib import Path
import sys
import unittest
import urllib.error
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scoreboard import DOMAINS, best, manifest, parse_issue, score, validate
from publish_scoreboard import initialize, publish, recover_pending


def submission(kind="MODEL", plan="QUICK", run="run-one", started=100):
    trials = []
    for key, (category, _) in manifest(kind, plan).items():
        trial = dict(id=key, outcome="PASSED", durationMs=500, firstTextMs=100, speed=50)
        if category == "intelligence":
            trial["intelligence"] = dict.fromkeys(DOMAINS, True)
        if kind == "DELEGATION":
            trial.update(reviewerScore=100, reviewerCalls=1, toolCalls=1, successfulToolCalls=1)
        trials.append(trial)
    data = dict(id=run, kind=kind, plan=plan, suiteVersion=1 if kind == "DELEGATION" else 6, startedAt=started, provider="OPENAI", model="model-one", trials=trials)
    if kind == "DELEGATION":
        data.update(primaryProvider="GOOGLE", primaryModel="primary", reviewerProvider="ANTHROPIC", reviewerModel="judge")
    return data


class ScoreboardTest(unittest.TestCase):
    def test_initialization_creates_an_empty_data_only_feed(self):
        class Fake:
            def __init__(self):
                self.calls = []
            def api(self, method, path, data=None, **kwargs):
                self.calls.append((method, path, data))
                if method == "GET":
                    return None
                return {"sha": "new"}
        client = Fake()
        initialize(client)
        tree = next(d for _, p, d in client.calls if p == "/git/trees")
        self.assertEqual(["scoreboards/index.json"], [entry["path"] for entry in tree["tree"]])
        index = json.loads(tree["tree"][0]["content"])
        self.assertEqual([], index["entries"])
        self.assertEqual(1, index["schemaVersion"])
        self.assertGreater(index["updatedAt"], 0)
        commit = next(d for _, p, d in client.calls if p == "/git/commits")
        self.assertEqual([], commit["parents"])

    def test_initialization_preserves_existing_scores_and_refuses_missing_index(self):
        class Fake:
            index = {"schemaVersion": 1, "entries": [submission()]}
            def api(self, method, path, **kwargs):
                if method != "GET":
                    raise AssertionError("must not mutate existing board")
                return {"object": {"sha": "parent"}} if path.startswith("/git/ref/") else self.index
        client = Fake()
        initialize(client)
        client.index = None
        with self.assertRaisesRegex(ValueError, "restore it from history"):
            initialize(client)

    def test_initialization_race_rereads_winner_without_overwriting(self):
        class Fake:
            created = False
            def api(self, method, path, data=None, **kwargs):
                if path.startswith("/git/ref/heads/"):
                    return {"object": {"sha": "winner"}} if self.created else None
                if path.startswith("/contents/"):
                    return {"schemaVersion": 1, "entries": [submission()]}
                if path == "/git/refs":
                    self.created = True
                    raise urllib.error.HTTPError("fixed", 422, "race", {}, None)
                if path in ("/git/trees", "/git/commits"):
                    return {"sha": "new"}
                raise AssertionError(path)
        initialize(Fake())

    def test_recovery_snapshots_pages_before_closing_and_excludes_pull_requests(self):
        order = []
        class Fake:
            def api(self, method, path):
                order.append(path)
                if path.endswith("&page=1"):
                    return [{"number": i, "title": "[Scoreboard v1] fixture"} for i in range(1, 101)]
                return [{"number": 101, "title": "[Scoreboard v1] fixture"}, {"number": 102, "title": "[Scoreboard v1] PR", "pull_request": {}}, {"number": 103, "title": "unrelated"}]
        with patch("publish_scoreboard.process_issue", side_effect=lambda g, issue: order.append(issue["number"])):
            recover_pending(Fake())
        self.assertTrue(all(isinstance(item, str) for item in order[:2]))
        self.assertEqual(list(range(1, 102)), order[2:])

    def test_publisher_rejects_invalid_data_before_writing(self):
        class Fake:
            def api(self, *args, **kwargs):
                raise AssertionError("invalid input must not reach GitHub")
        with self.assertRaises(ValueError):
            publish(Fake(), submission(), "../private")
        with self.assertRaises(ValueError):
            publish(Fake(), dict(submission(), apiKey="private"), 1)

    def test_exact_manifests_and_perfect_score(self):
        for kind in ("MODEL", "DELEGATION"):
            for plan, count in (("QUICK", 5), ("FULL", 50)):
                data = submission(kind, plan)
                self.assertEqual(count, len(data["trials"]))
                self.assertEqual(100, score(data))

    def test_portable_policy_is_absolute_and_best_is_single_run(self):
        first = submission()
        newer = submission(run="second", started=200)
        newer["trials"][1]["outcome"] = "FAILED"
        self.assertEqual(65, score(newer))
        self.assertEqual("run-one", best([newer, first])[0]["id"])
        self.assertEqual("second", best([first, submission(run="second", started=200)])[0]["id"])

    def test_provider_and_case_sensitive_model_and_plan_are_distinct(self):
        rows = [submission(), submission(plan="FULL"), dict(submission(), provider="GOOGLE"), dict(submission(), model="Model-One")]
        self.assertEqual(4, len(best(rows)))

    def test_failures_stay_in_denominator(self):
        data = submission()
        data["trials"][0]["outcome"] = "TIMED_OUT"
        self.assertEqual(80, score(data))
        delegate = submission("DELEGATION")
        delegate["trials"][0]["outcome"] = "ERROR"
        delegate["trials"][0].pop("reviewerScore")
        self.assertLessEqual(score(delegate), 80)

    def test_rejects_incomplete_unsupported_and_fabricated_review(self):
        for mutate in (
            lambda d: d["trials"].pop(),
            lambda d: d["trials"].__setitem__(1, d["trials"][0]),
            lambda d: d["trials"][0].update(outcome="UNSUPPORTED"),
            lambda d: d["trials"][0].update(durationMs=-1),
            lambda d: d["trials"][0].update(firstTextMs=501),
            lambda d: d["trials"][0].update(speed=float("nan")),
            lambda d: d["trials"][0].update(toolCalls=True),
            lambda d: d["trials"][0].update(successfulToolCalls=1),
            lambda d: d["trials"][-1].update(intelligence={}),
            lambda d: d.update(provider="LITERT_LM"),
            lambda d: d.update(suiteVersion=5),
            lambda d: d.update(model="https://private/api"),
            lambda d: d.update(startedAt=True),
            lambda d: d.update(schemaVersion=True),
            lambda d: d.update(kind=[]),
            lambda d: d.update(provider={}),
            lambda d: d["trials"][0].update(outcome=[])
        ):
            data = submission()
            mutate(data)
            with self.assertRaises(ValueError):
                validate(data)
        data = submission("DELEGATION")
        data["trials"][0].pop("reviewerScore")
        with self.assertRaises(ValueError):
            validate(data)
        data = submission("DELEGATION")
        data["reviewerModel"] = data["model"]
        with self.assertRaises(ValueError):
            validate(data)

    def test_private_fields_and_untrusted_markup_are_never_accepted(self):
        for field in ("apiKey", "profileName", "endpointUrl", "chat", "overall"):
            with self.assertRaises(ValueError):
                validate(dict(submission(), **{field: "secret"}))
        for field in ("preview", "error", "diagnosticEvents", "nativeMetrics"):
            data = submission()
            data["trials"][0][field] = "private content"
            with self.assertRaises(ValueError):
                validate(data)
        for body in ('```json\n{"id":"a","id":"b"}\n```', '```json\n[]\n```', '$(touch /tmp/injected)', '`' * 60_001):
            with self.assertRaises(ValueError):
                parse_issue(body)
        data = submission()
        parsed = parse_issue("Public result\n\n```json\n" + json.dumps(data) + "\n```")
        self.assertEqual(100, score(parsed))
        with self.assertRaises(ValueError):
            parse_issue("```json\n" + json.dumps(dict(data, issueNumber=1)) + "\n```")

    def test_android_server_shared_contract(self):
        fixtures = json.loads((Path(__file__).resolve().parents[2] / "app/src/test/resources/scoreboard-contract.json").read_text())
        for case in fixtures:
            self.assertAlmostEqual(case["score"], score(case["submission"]))

    def test_publisher_writes_fixed_paths_and_retries_non_fast_forward(self):
        class Fake:
            def __init__(self):
                self.calls = []
                self.updates = 0
            def api(self, method, path, data=None, **kwargs):
                self.calls.append((method, path, data))
                if path == "/git/ref/heads/scoreboard-data":
                    return {"object": {"sha": "parent"}}
                if "/submissions/" in path:
                    return None
                if "/contents/scoreboards/index.json" in path:
                    return {"entries": []}
                if path == "/git/commits/parent":
                    return {"tree": {"sha": "base"}}
                if path in ("/git/trees", "/git/commits"):
                    return {"sha": "new"}
                if method == "PATCH":
                    self.updates += 1
                    if self.updates == 1:
                        raise urllib.error.HTTPError("fixed", 422, "race", {}, None)
                    return {}
                raise AssertionError(path)
        client = Fake()
        publish(client, submission(), 17)
        self.assertEqual(2, client.updates)
        trees = [d for m, p, d in client.calls if p == "/git/trees"]
        self.assertEqual({"scoreboards/index.json", "scoreboards/submissions/17.json"}, {t["path"] for t in trees[0]["tree"]})
        self.assertTrue(all(d["force"] is False for m, p, d in client.calls if m == "PATCH"))

    def test_accepted_issue_cannot_be_rewritten(self):
        class Fake:
            def api(self, method, path, **kwargs):
                self_method = method
                if self_method != "GET":
                    raise AssertionError("must not mutate")
                return {"object": {"sha": "parent"}} if path == "/git/ref/heads/scoreboard-data" else submission()
        publish(Fake(), submission(run="edited"), 17)


if __name__ == "__main__":
    unittest.main()
