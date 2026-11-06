#!/usr/bin/env python3
"""Inspect legacy conversation or bounded staged diagnostics without a model runtime.

This replays retained committed segments only. Missing provider deltas or reducer
events are reported as unavailable, never synthesized from the saved answer.
"""
from __future__ import annotations

import argparse
import codecs
import hashlib
import json
import re
from pathlib import Path
import zipfile

MAX_BYTES = 64 * 1024 * 1024
FILES = {"manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json"}
STAGED_SCHEMA = "gptmobile.staged-diagnostics.v1"


def load_staged_snapshot(archive: zipfile.ZipFile, manifest: dict) -> dict:
    """Validate staged captures without retaining their potentially large record bodies."""
    stages = manifest.get("stages")
    if not isinstance(stages, list) or not 1 <= len(stages) <= 32:
        raise ValueError("Invalid diagnostic stage inventory")
    names = set()
    identities = set()
    useful = 0
    for stage in stages:
        if not isinstance(stage, dict):
            raise ValueError("Invalid diagnostic stage record")
        name = stage.get("fileName", "")
        identity = stage.get("id", "")
        if (not isinstance(name, str) or not isinstance(identity, str)
                or not re.fullmatch(r"[a-z][a-z0-9_.-]{0,95}", name) or name == "manifest.json" or name in names
                or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", identity) or identity in identities):
            raise ValueError("Invalid or duplicate diagnostic stage")
        names.add(name)
        identities.add(identity)
        counts = [stage.get(key) for key in ("bytes", "records", "evidenceRecords")]
        if any(type(value) is not int or value < 0 for value in counts) or counts[2] > counts[1]:
            raise ValueError("Invalid diagnostic record counts")
        status = stage.get("status")
        if status not in {"COMPLETED", "EMPTY", "PARTIAL", "FAILED"}:
            raise ValueError("Invalid diagnostic stage status")
        if status == "COMPLETED" and counts[1] == 0:
            raise ValueError("Empty diagnostics cannot be complete")
        if status == "EMPTY" and (counts[2] != 0 or not stage.get("omissionReason")):
            raise ValueError("Empty stage must explain missing evidence")
        sha = hashlib.sha256()
        decoder = codecs.getincrementaldecoder("utf-8")("strict")
        byte_count = record_count = 0
        last_byte = None
        with archive.open(name) as stream:
            while block := stream.read(8192):
                byte_count += len(block)
                if byte_count > counts[0] or byte_count > MAX_BYTES:
                    raise ValueError("Diagnostic stage exceeds declared byte limit")
                sha.update(block)
                decoder.decode(block)
                record_count += block.count(b"\n")
                last_byte = block[-1]
            decoder.decode(b"", final=True)
        if (byte_count != counts[0] or record_count != counts[1] or sha.hexdigest() != stage.get("sha256")
                or byte_count and last_byte != 10):
            raise ValueError("Diagnostic stage digest, bytes or records mismatch")
        useful += counts[2]
    if set(archive.namelist()) != names | {"manifest.json"}:
        raise ValueError("Unexpected or missing diagnostic stage")
    complete = all(stage["status"] in {"COMPLETED", "EMPTY"} for stage in stages)
    critical = any(stage.get("critical") is True and stage["evidenceRecords"] > 0 for stage in stages)
    empty_critical = any(stage.get("critical") is True and stage["evidenceRecords"] == 0 for stage in stages)
    if (manifest.get("usefulRecords") != useful or manifest.get("capturedFully") is not False
            or manifest.get("captureCompleted") is not complete
            or manifest.get("criticalEvidenceAvailable") is not critical
            or manifest.get("emptyCriticalEvidence") is not empty_critical
            or manifest.get("retryRecommended") is not (not complete or empty_critical or useful == 0)):
        raise ValueError("Diagnostic completeness claims disagree with stage evidence")
    return manifest


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_snapshot(path: Path) -> dict:
    if path.stat().st_size > MAX_BYTES:
        raise ValueError("Export exceeds the inspection memory limit")
    if not zipfile.is_zipfile(path):
        root = json.loads(path.read_text(encoding="utf-8"))
    else:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [entry.filename for entry in infos]
            if len(names) != len(set(names)) or "manifest.json" not in names:
                raise ValueError("Missing manifest or duplicate bundle entry")
            if sum(entry.file_size for entry in infos) > MAX_BYTES:
                raise ValueError("Expanded bundle exceeds the inspection memory limit")
            if archive.getinfo("manifest.json").file_size > 256 * 1024:
                raise ValueError("Diagnostic manifest exceeds the inspection limit")
            with archive.open("manifest.json") as stream:
                manifest_bytes = stream.read(256 * 1024 + 1)
            if len(manifest_bytes) > 256 * 1024:
                raise ValueError("Diagnostic manifest exceeds the inspection limit")
            manifest = json.loads(manifest_bytes)
            if not isinstance(manifest, dict):
                raise ValueError("Invalid diagnostic manifest")
            if manifest.get("schema") == STAGED_SCHEMA:
                return load_staged_snapshot(archive, manifest)
            if set(names) != FILES or len(names) != len(FILES):
                raise ValueError("Unexpected, missing or duplicate bundle entry")
            parts = {}
            remaining = MAX_BYTES
            for entry in infos:
                with archive.open(entry) as stream:
                    data = stream.read(remaining + 1)
                remaining -= len(data)
                if remaining < 0:
                    raise ValueError("Expanded bundle exceeds the inspection memory limit")
                parts[entry.filename] = data.decode("utf-8")
        manifest = json.loads(parts["manifest.json"])
        if manifest.get("schema") != "gptmobile.conversation.debug.bundle.v3":
            raise ValueError("Unsupported bundle schema")
        rows = manifest.get("files", [])
        if len(rows) != len(FILES) - 1 or {row.get("name") for row in rows} != FILES - {"manifest.json"}:
            raise ValueError("Invalid file manifest")
        for row in rows:
            text = parts[row["name"]]
            if row.get("sha256") != digest(text) or row.get("bytes") != len(text.encode("utf-8")):
                raise ValueError("Export file digest or byte count mismatch")
        root = json.loads(parts["conversation.json"])
        events = [json.loads(line) for line in parts["events.jsonl"].splitlines() if line.strip()]
        if events != root.get("runJournal", {}).get("events", []):
            raise ValueError("Journal differs between frozen bundle components")
    if not isinstance(root, dict) or root.get("schema") not in {"gptmobile.conversation.debug.v2", "gptmobile.conversation.debug.v3", "gptmobile.conversation.debug.v4"}:
        raise ValueError("Unsupported conversation schema")
    if not isinstance(root.get("messages"), list) or not isinstance(root.get("profiles"), list):
        raise ValueError("Corrupt conversation records")
    return root


def inspect_snapshot(root: dict) -> dict:
    if root.get("schema") == STAGED_SCHEMA:
        return {
            "schema": "gptmobile.offline-staged-inspection.v1",
            "result": "partial" if root["retryRecommended"] else "retained-stages-verified",
            "captureCompleted": root["captureCompleted"],
            "capturedFully": False,
            "criticalEvidenceAvailable": root["criticalEvidenceAvailable"],
            "usefulRecords": root["usefulRecords"],
            "providerDeltaReplay": "unavailable",
            "reducerReplay": "unavailable",
            "stages": root["stages"],
            "scope": "Byte, record and digest validation of retained independent stages; no inference that application tasks or tests passed.",
        }
    journal = root.get("runJournal", {})
    events = journal.get("events", [])
    streams = {}
    ids = {}
    event_errors = []
    for event in events:
        event_id = event.get("eventId")
        if event_id in ids:
            event_errors.append("duplicate-event" if ids[event_id] == event else "divergent-duplicate-event")
            continue
        ids[event_id] = event
        stream = event.get("streamId")
        sequence = event.get("sequence")
        if not isinstance(sequence, int) or sequence <= 0:
            event_errors.append("invalid-sequence")
            continue
        prior = streams.setdefault(stream, {})
        if sequence in prior:
            event_errors.append("duplicate-sequence")
        prior[sequence] = event
    missing = 0
    for stream, high in journal.get("highWatermarks", {}).items():
        if not isinstance(high, int) or high < 0:
            event_errors.append("invalid-high-watermark")
            continue
        captured = streams.get(stream, {})
        if any(sequence > high for sequence in captured):
            event_errors.append("event-after-high-watermark")
        missing += max(0, high - sum(sequence <= high for sequence in captured))

    runs = {run["runId"]: run for run in root.get("runs", [])}
    messages = {message.get("currentRunId"): message for message in root["messages"] if message.get("currentRunId")}
    source_integrity = {row["runId"]: row.get("verified") for row in root.get("segmentSourceIntegrity", [])}
    by_run = {}
    for segment in root.get("answerSegments", []):
        by_run.setdefault(segment["runId"], []).append(segment)
    replay = []
    for run_id, segments in by_run.items():
        expected = runs.get(run_id, {}).get("ownershipFence")
        previous = None
        parts = []
        errors = []
        ordered = sorted(segments, key=lambda row: row["sequence"])
        for index, segment in enumerate(ordered):
            text = segment.get("text", "")
            if segment.get("sequence") != index:
                errors.append("segment-gap-or-duplicate")
            if expected is None or segment.get("fence") != expected:
                errors.append("stale-or-unknown-fence")
            if segment.get("digestRepresentation") != "redacted-export-segment-utf8":
                errors.append("unknown-digest-representation")
            if segment.get("previousDigest") != previous or segment.get("digest") != digest(text) or segment.get("utf8Bytes") != len(text.encode("utf-8")):
                errors.append("segment-integrity-failure")
            previous = segment.get("digest")
            parts.append(text)
        if source_integrity.get(run_id) is not True:
            errors.append("source-integrity-unverified")
        body = "".join(parts)
        saved = messages.get(run_id, {}).get("content")
        if errors:
            status = "rejected"
        elif saved is None:
            status = "revision-unavailable"
        elif body == saved:
            status = "committed-revision-replayed"
        elif saved.startswith(body):
            status = "partial-committed-prefix"
        else:
            status = "prefix-divergence"
        replay.append({"runId": run_id, "status": status, "segments": len(ordered), "utf8Bytes": len(body.encode("utf-8")), "sha256": digest(body), "errors": sorted(set(errors))})
    traces = []
    trace_errors = []
    for stream_id, stream_events in streams.items():
        reduced = {}
        for sequence, event in sorted(stream_events.items()):
            fingerprint = event.get("outputFingerprint")
            if fingerprint is None:
                continue
            if not isinstance(fingerprint, dict) or type(fingerprint.get("utf8Bytes")) is not int or fingerprint["utf8Bytes"] < 0 or not isinstance(fingerprint.get("sha256"), str) or re.fullmatch(r"[a-f0-9]{64}", fingerprint["sha256"]) is None:
                trace_errors.append("invalid-output-fingerprint")
                continue
            boundary = event.get("outputBoundary")
            representation = event.get("outputRepresentation")
            owner = (event.get("runId"), event.get("fence"), representation)
            comparison = "not-comparable"
            if boundary == "REDUCER" and representation == "VISIBLE_UTF8":
                reduced[owner] = (sequence, fingerprint)
            if boundary == "COMMITTED" and representation == "VISIBLE_UTF8":
                prior = reduced.get(owner)
                comparison = "reducer-checkpoint-unavailable"
                if prior is not None:
                    gap = sequence - prior[0] - 1
                    observed = sum(prior[0] < number < sequence for number in stream_events)
                    if observed != gap:
                        comparison = "intervening-capture-gap"
                    else:
                        comparison = "matches-reducer" if prior[1] == fingerprint else "reducer-to-commit-divergence"
                        if comparison == "reducer-to-commit-divergence":
                            trace_errors.append(comparison)
            traces.append({"runId": event.get("runId"), "streamId": stream_id, "sequence": sequence, "fence": event.get("fence"), "invocationId": event.get("invocationId"), "partId": event.get("outputPartId"), "boundary": boundary, "representation": representation, "fingerprint": fingerprint, "comparison": comparison})
    return {
        "schema": "gptmobile.offline-replay.v1",
        "scope": "retained-committed-segments",
        "digestRepresentation": "redacted-export-body-utf8",
        "providerDeltaReplay": "unavailable",
        "reducerReplay": "unavailable",
        "rendererReplay": "unavailable",
        "completeProviderWireCapture": False,
        "journal": {"events": len(events), "missingSequences": missing, "errors": sorted(set(event_errors)), "captureAvailable": "runJournal" in root},
        "outputTracing": {"scope": "representation-bound-fingerprints-only", "checkpoints": traces, "errors": sorted(set(trace_errors)), "contentReplayAvailable": False},
        "runs": replay,
        "result": "partial" if not replay or missing or event_errors or trace_errors or any(row["status"] != "committed-revision-replayed" for row in replay) else "retained-segments-verified",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_snapshot(load_snapshot(args.export))
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Cannot replay export: {error}\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
