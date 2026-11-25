"""Offline projection and comparison of revision-pinned GitHub task observations."""
from collections import Counter
from hashlib import sha256
import json
import re
from zipfile import ZipFile

SCHEMA = "github.routing-replay.v1"
SHA = re.compile("[0-9a-f]{40}")
DIGEST = re.compile("[0-9a-f]{64}")
POLICIES = {"recorded", "legacy", "shadow", "active", "read-each-range", "reuse-pinned-range"}
ROUTES = {"cached-range", "lookup", "reviewer", "other"}
LIMIT = 4096


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def natural(value):
    return type(value) is int and 0 <= value <= 2**63 - 1


def check(condition, message):
    if not condition:
        raise ValueError(message)


def logical_rows(archive, name):
    """The app writes the chunks of one logical row contiguously in a single part."""
    pending = []
    digest = None
    offset = 0
    with archive.open(name) as stream:
        for line in stream:
            row = json.loads(line)
            check(isinstance(row, dict), "Malformed staged row")
            if row.get("kind") != "diagnostic_record_chunk":
                check(not pending, "Incomplete staged row")
                yield row
                continue
            content = row["content"].encode("utf-8")
            current = row["recordSha256"]
            check(row["chunkIndex"] == len(pending) and row["offsetUtf16"] == offset
                  and row["recordId"] == "sha256:" + current and (not pending or current == digest)
                  and row["chunkSha256"] == sha256(content).hexdigest(), "Invalid staged row chunk")
            digest = current
            pending.append(content)
            offset += len(row["content"].encode("utf-16-le")) // 2
            if len(pending) == row["chunkCount"]:
                data = b"".join(pending)
                check(row["recordCharacters"] == offset and sha256(data).hexdigest() == digest, "Invalid staged row digest")
                row = json.loads(data)
                check(isinstance(row, dict), "Malformed staged row")
                yield row
                pending.clear()
                offset = 0
    check(not pending, "Incomplete staged row")


def staged_runs(path, revision):
    """Read only the original checked capture stages, replacing all task, argument and receipt identities."""
    from replay_conversation_debug import load_snapshot, STAGED_SCHEMA

    manifest = load_snapshot(path)
    check(manifest.get("schema") == STAGED_SCHEMA, "Select one original staged diagnostic archive")
    check(isinstance(revision, str) and SHA.fullmatch(revision), "A 40-character repository revision is required")
    evidence, checkpoints, calls, runs = {}, {}, {}, {}
    with ZipFile(path) as archive:
        check({"workspace.jsonl", "tool-receipts.jsonl", "runs.jsonl"} <= set(archive.namelist()), "Required diagnostic stages are missing")
        workspace = [r for r in logical_rows(archive, "workspace.jsonl") if r.get("table") == "workspace_records"]
        originals = {r["rowId"]: r["fields"]["payload"] for r in workspace if "payload" in r.get("fields", {})}
        for record in workspace:
            f = record.get("fields", {})
            if f.get("kind") not in {"github_evidence", "synthesis_checkpoint"}:
                continue
            p = f.get("payload")
            ref = f.get("payload_ref")
            if ref:
                p = originals.get(ref.get("rowId"))
                check(isinstance(p, dict) and ref.get("schema") == "gptmobile.workspace-payload-reference.v1"
                      and ref.get("pointer") == "/fields/payload" and ref.get("table") == "workspace_records"
                      and sha256(json.dumps(p, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest() == ref.get("redactedJsonSha256"),
                      "Workspace payload reference could not be verified in the capture")
            if not isinstance(p, dict):
                continue
            run = f.get("runId")
            if p.get("schema") == "github.evidence.v1" and isinstance(p.get("call_id"), str) and p.get("source_run_id", run) == run:
                key = (run, p["call_id"])
                check(key not in evidence or evidence[key] == p, "Conflicting evidence for the same invocation")
                evidence[key] = p
            if p.get("acceptance_ledger", {}).get("requirements") and natural(f.get("updatedAt")):
                if run not in checkpoints or f["updatedAt"] > checkpoints[run][0]:
                    checkpoints[run] = (f["updatedAt"], p)
        for record in logical_rows(archive, "tool-receipts.jsonl"):
            f = record.get("fields", {})
            if record.get("table") == "tool_events" and isinstance(f.get("call_id"), str):
                calls.setdefault(f.get("run_id"), []).append(f)
        for record in logical_rows(archive, "runs.jsonl"):
            f = record.get("fields", {})
            if record.get("table") == "agent_runs":
                runs[f.get("run_id")] = f
    output = []
    for run, (_, checkpoint) in checkpoints.items():
        if run not in calls or run not in runs:
            continue
        identities, witnesses, projected = {}, {}, []
        for event in sorted(calls[run], key=lambda e: e["sequence"]):
            receipt = evidence.get((run, event["call_id"]), {})
            bound = receipt.get("binding") == checkpoint.get("binding") and bool(checkpoint.get("binding"))
            args = receipt.get("arguments", {})
            scoped = bound and receipt.get("trusted") is True and receipt.get("verification_eligible") is True and receipt.get("success") is True
            pinned = scoped and receipt.get("immutable") is True and args.get("commit_sha") == revision and receipt.get("source_head_sha") in (None, revision)
            fingerprint = canonical([event.get("model_tool_name"), event.get("arguments"), receipt.get("repository"), receipt.get("epoch")])
            key = identities.setdefault(fingerprint, len(identities) + 1)
            start, end = event.get("started_at"), event.get("completed_at")
            elapsed = end - start if natural(start) and natural(end) and end >= start else None
            digest = receipt.get("source_result_sha256")
            verified_pin = bool(pinned and isinstance(digest, str) and DIGEST.fullmatch(digest))
            if verified_pin:
                witnesses[receipt.get("receipt_id")] = len(projected)
            route = "cached-range" if receipt.get("evidence_origin") == "CACHED" and verified_pin else "lookup" if scoped and receipt.get("remote_mutation") is False else "other"
            projected.append(dict(key=key, route=route, elapsedMs=elapsed, result=digest if verified_pin else None))
        if not witnesses:
            continue
        ledger = checkpoint["acceptance_ledger"]
        task = ledger.get("request_digest") or checkpoint.get("request_digest")
        if not isinstance(task, str) or not DIGEST.fullmatch(task):
            continue
        requirements = []
        for row in ledger["requirements"]:
            if row.get("required") is False:
                continue
            ids = row.get("receipt_ids", [])
            proof = [witnesses[i] for i in ids] if ids and all(i in witnesses for i in ids) else []
            requirements.append(dict(key=sha256(canonical([task, row["id"]])).hexdigest(),
                                     witnesses=proof if row.get("state") == "PASS" else []))
        run_info = runs[run]
        elapsed = [run_info.get(k) for k in ("started_at", "completed_at")]
        total = run_info.get("total_tokens")
        # A missing total may include estimates, partial provider usage, or unmetered helper calls.
        mode = checkpoint.get("next_action_decision", {}).get("mode")
        policy = mode.lower() if mode in ("ACTIVE", "LEGACY", "SHADOW") else "recorded"
        record = dict(policy=policy, calls=projected, requirements=requirements,
                      completionVerified=checkpoint.get("completion_verified") is True and ledger.get("completion_verified") is True,
                      elapsedMs=elapsed[1] - elapsed[0] if all(map(natural, elapsed)) and elapsed[1] >= elapsed[0] else None,
                      tokens=total if natural(total) else None)
        output.append(dict(schema=SCHEMA, revision=revision, task=task, runs=[record]))
    check(output, "No bound task with pinned receipts, a verifier ledger and run accounting was found")
    return output


def validate(trace):
    check(isinstance(trace, dict) and set(trace) == {"schema", "revision", "task", "runs"}
          and trace["schema"] == SCHEMA and SHA.fullmatch(trace["revision"]) and DIGEST.fullmatch(trace["task"]), "Invalid replay identity")
    check(isinstance(trace["runs"], list) and 1 <= len(trace["runs"]) <= 64, "Invalid replay run count")
    criteria = None
    for run in trace["runs"]:
        check(isinstance(run, dict) and set(run) == {"policy", "calls", "requirements", "elapsedMs", "tokens", "completionVerified"}
              and type(run["completionVerified"]) is bool and run["policy"] in POLICIES and all(v is None or natural(v) for v in (run["elapsedMs"], run["tokens"])), "Invalid measured run")
        calls = run["calls"]
        check(isinstance(calls, list) and len(calls) <= LIMIT, "Invalid call count")
        for call in calls:
            check(isinstance(call, dict) and set(call) == {"key", "route", "elapsedMs", "result"} and natural(call["key"])
                  and call["route"] in ROUTES and (call["elapsedMs"] is None or natural(call["elapsedMs"]))
                  and (call["result"] is None or DIGEST.fullmatch(call["result"])), "Invalid call observation")
        rows = run["requirements"]
        check(isinstance(rows, list) and 1 <= len(rows) <= LIMIT, "Invalid obligation count")
        for row in rows:
            check(isinstance(row, dict) and set(row) == {"key", "witnesses"} and DIGEST.fullmatch(row["key"])
                  and isinstance(row["witnesses"], list) and len(row["witnesses"]) <= LIMIT
                  and all(natural(i) and i < len(calls) and calls[i]["result"] is not None for i in row["witnesses"]), "Invalid verified evidence")
        keys = {row["key"] for row in rows}
        check(len(keys) == len(rows) and criteria in (None, keys), "Different or duplicated task obligations")
        criteria = keys


def compare(trace):
    validate(trace)
    results = []
    for run in trace["runs"]:
        counts = Counter(c["key"] for c in run["calls"] if c["route"] == "lookup")
        verified = sum(bool(r["witnesses"]) for r in run["requirements"])
        completed = run["completionVerified"] and verified == len(run["requirements"])
        results.append(dict(policy=run["policy"], requirements=len(run["requirements"]),
                            verifiedRequirements=verified, completedTask=completed,
                            tokensPerCompletedTask=run["tokens"] if completed else None,
                            calls=len(run["calls"]), repeatedLookups=sum(n - 1 for n in counts.values()),
                            cachedReads=sum(c["route"] == "cached-range" for c in run["calls"]),
                            tokens=run["tokens"], elapsedMs=run["elapsedMs"]))
    return dict(schema="github.routing-comparison.v1", revision=trace["revision"], task=trace["task"], runs=results)


def load_traces(path, revision):
    if path.suffix.lower() == ".zip":
        return staged_runs(path, revision)
    check(path.stat().st_size <= 8 * 1024 * 1024, "Replay file is too large")
    trace = json.loads(path.read_bytes())
    validate(trace)
    if revision:
        check(revision == trace["revision"], "Requested revision differs from replay")
    return [trace]


def replay(paths, revision=None, project=False):
    grouped = {}
    for path in paths:
        for trace in load_traces(path, revision):
            key = (trace["revision"], trace["task"])
            if key in grouped:
                grouped[key]["runs"].extend(trace["runs"])
            else:
                grouped[key] = trace
    if len(paths) > 1:
        check(len(grouped) == 1, "Compare only the same task and repository revision")
    for trace in grouped.values():
        validate(trace)
    values = [t if project else compare(t) for t in grouped.values()]
    return values[0] if len(values) == 1 else values
