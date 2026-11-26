#!/usr/bin/env python3
"""Inspect conversations, staged captures, multipart bundles or structured app logs without a model runtime.

This replays retained committed segments only. Missing provider deltas or reducer
events are reported as unavailable, never synthesized from the saved answer.
"""
from __future__ import annotations

import argparse
import codecs
from collections import Counter, defaultdict
import hashlib
import json
import re
from pathlib import Path
import tempfile
import zipfile

MAX_BYTES = 64 * 1024 * 1024
FILES = {"manifest.json", "conversation.json", "events.jsonl", "context-manifests.json", "provenance.json"}
STAGED_SCHEMA = "gptmobile.staged-diagnostics.v1"
BUNDLE_SCHEMA = "gptmobile.diagnostic-bundle.v1"
BUNDLE_INSPECTION_SCHEMA = "gptmobile.offline-bundle-inspection.v1"
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
MAX_MANIFEST_BYTES = 256 * 1024
LOG_INSPECTION_SCHEMA = "gptmobile.offline-log-inspection.v1"
MAX_LOG_LINE_BYTES = 256 * 1024
MAX_LOG_ROWS = 100_000
MAX_SIGNATURE_LINES = 64
LOG_SOURCES = {"current.log", "previous.log", "critical.log", "critical-previous.log"}


def _diagnostic_signature(row: dict) -> str:
    """Return a fixed vocabulary, never a message, endpoint, model or private argument."""
    fields = row.get("fields", {})
    code = row.get("errorCode")
    if row.get("tag") == "Tool" and isinstance(code, str) and code in {"INVALID_ARGUMENTS", "POLICY_STALE", "STALE_INVENTORY"}:
        return "tool." + code.lower().replace("_", "-")
    event = row.get("eventName", "")
    if row.get("tag") == "Agent" and event == "RUN_TERMINAL":
        reason = fields.get("reason")
        if isinstance(reason, str) and reason in {"TASK_INCOMPLETE", "TLS_FAILURE", "INCOMPLETE"}:
            return "execution." + reason.lower().replace("_", "-")
        return "execution.reported-failure"
    if event == "FRAME_TIMING":
        return "rendering.frame-deadline-miss"
    if event == "TOOL_CANCELED":
        return "tool.canceled"
    if event == "TOOL_RESOLUTION_FAILED":
        return "mcp.discovery-failure"
    if row.get("category") == "main_thread_disk_io":
        return "android.main-thread-disk-io"
    if row.get("category") == "input_lifecycle":
        return "android.inactive-input-connection"
    if row.get("tag") == "Android" and event.startswith("Skipped ") and "frames!" in event:
        return "rendering.skipped-frames"
    if row.get("category") == "transport_availability":
        return "network.transport-unavailable"
    if row.get("tag") == "Model":
        return "model.reported-failure"
    return "other.observed-failure"


def load_diagnostic_log(path: Path) -> dict:
    """Stream schema-7 JSONL into a bounded, content-free evidence inventory.

    Counts describe observations, not independently proven bugs. Continuations and
    duplicate references are structural evidence only: their source payload hashes
    cannot be recomputed from redacted exported messages.
    """
    def integer(value, description):
        if type(value) is not int or value < 0:
            raise ValueError("Invalid diagnostic " + description)
        return value

    def identity(value):
        if not isinstance(value, str) or not 1 <= len(value) <= 256:
            raise ValueError("Invalid diagnostic identity")
        return value

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate diagnostic JSON key")
            result[key] = value
        return result

    def invalid_constant(_):
        raise ValueError("Non-finite diagnostic JSON number")

    counts = Counter()
    signatures = {}
    primary = {}
    identity_sources = defaultdict(set)
    source_events = defaultdict(list)
    continuations = defaultdict(set)
    references = []
    reference_positions = set()
    issue_ids = set()
    frame_totals = Counter()
    frame_screens = defaultdict(Counter)
    frame_identities = {}
    capture = None
    source_sha = None
    fingerprint = hashlib.sha256()
    byte_count = 0
    with path.open("rb") as stream:
        while line := stream.readline(MAX_LOG_LINE_BYTES + 1):
            counts["rows"] += 1
            byte_count += len(line)
            if len(line) > MAX_LOG_LINE_BYTES or byte_count > MAX_BYTES or counts["rows"] > MAX_LOG_ROWS:
                raise ValueError("Diagnostic inspection bound exceeded")
            if not line.endswith(b"\n"):
                raise ValueError("Incomplete diagnostic final line")
            fingerprint.update(line)
            try:
                row = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=invalid_constant)
            except (UnicodeError, ValueError, RecursionError):
                raise ValueError(f"Invalid diagnostic JSON at line {counts['rows']}") from None
            if not isinstance(row, dict) or type(row.get("schema")) is not int or row["schema"] != 7:
                raise ValueError("Unsupported structured diagnostic schema")
            kind = row.get("kind")
            if not isinstance(kind, str) or kind not in {"event", "continuation", "duplicate_reference_batch"}:
                raise ValueError("Unsupported structured diagnostic record")
            if type(row.get("countAsIssue")) is not bool or type(row.get("duplicate")) is not bool:
                raise ValueError("Invalid diagnostic issue flags")
            counts[kind] += 1
            if kind == "duplicate_reference_batch":
                batch = row.get("references")
                if (not isinstance(batch, list) or not 1 <= len(batch) <= 32
                        or integer(row.get("referenceCount"), "reference count") != len(batch)
                        or row["countAsIssue"] or not row["duplicate"]):
                    raise ValueError("Invalid diagnostic duplicate-reference batch")
                for reference in batch:
                    if not isinstance(reference, dict):
                        raise ValueError("Invalid diagnostic duplicate reference")
                    target = identity(reference.get("eventId"))
                    source = identity(reference.get("captureSource"))
                    canonical_source = identity(reference.get("canonicalSource"))
                    source_line = integer(reference.get("sourceLine"), "source line")
                    canonical_line = integer(reference.get("canonicalLine"), "canonical line")
                    sha = reference.get("payloadSha256")
                    position = (source, source_line)
                    if (source_line == 0 or canonical_line == 0 or position in reference_positions
                            or source not in LOG_SOURCES or canonical_source not in LOG_SOURCES
                            or source == canonical_source and canonical_line >= source_line
                            or not isinstance(sha, str) or re.fullmatch(r"[a-f0-9]{64}", sha) is None):
                        raise ValueError("Invalid or repeated diagnostic duplicate reference")
                    reference_positions.add(position)
                    references.append((target, source, source_line, canonical_source, canonical_line, counts["rows"]))
                    if len(references) > MAX_LOG_ROWS:
                        raise ValueError("Diagnostic duplicate-reference bound exceeded")
                    if source in LOG_SOURCES:
                        counts["retainedSourceRecords"] += 1
                continue
            event_id = identity(row.get("eventId"))
            sequence = integer(row.get("sequence"), "sequence")
            source = identity(row.get("captureSource"))
            if event_id in identity_sources:
                counts["repeatedEventIdentities"] += 1
            identity_sources[event_id].add(source)
            if source in LOG_SOURCES:
                counts["retainedSourceRecords"] += 1
                source_events[source].append((event_id, counts["rows"]))
            for flag in ("truncatedAtSource", "truncatedAtExport"):
                if type(row.get(flag)) is not bool:
                    raise ValueError("Invalid diagnostic truncation flag")
                counts[flag] += row[flag]
            if kind == "continuation":
                parent = identity(row.get("parentEventId"))
                if (sequence < 1 or sequence > MAX_LOG_ROWS or row["countAsIssue"]
                        or event_id != f"{parent}:{sequence}"):
                    raise ValueError("Invalid or repeated diagnostic continuation")
                continuations[parent].add(sequence)
                continue
            if sequence != 0:
                raise ValueError("Invalid primary diagnostic sequence")
            primary.setdefault(event_id, source)
            fields = row.get("fields")
            if not isinstance(fields, dict) or not isinstance(row.get("eventName"), str):
                raise ValueError("Invalid diagnostic event fields")
            if row.get("tag") == "Export" and "appCommit" in fields:
                candidate = fields["appCommit"]
                if isinstance(candidate, str) and re.fullmatch(r"[a-f0-9]{40}", candidate):
                    if source_sha is not None and source_sha != candidate:
                        raise ValueError("Conflicting diagnostic application commits")
                    source_sha = candidate
            if row.get("tag") == "DiagnosticLogCapture" and "retainedRecords" in fields:
                if capture is not None:
                    raise ValueError("Duplicate diagnostic capture summary")
                capture = {key: integer(fields.get(key), key) for key in (
                    "retainedRecords", "sourceFailures", "droppedRecords", "droppedCriticalRecords"
                )}
                capture["omissionsReported"] = fields.get("omissionReason") != "none"
                capture["trackingEnabled"] = fields.get("trackingEnabled") is True
            if row["countAsIssue"]:
                if row["duplicate"] or row.get("classification") != "OBSERVED_FAILURE":
                    raise ValueError("Diagnostic issue flags contradict record classification")
                issue_id = identity(row.get("incidentId"))
                issue_ids.add(issue_id)
                counts["issueRecords"] += 1
                signature = _diagnostic_signature(row)
                group = signatures.setdefault(signature, {"observations": 0, "sourceLines": [], "sourceLinesOmitted": 0})
                group["observations"] += 1
                if len(group["sourceLines"]) < MAX_SIGNATURE_LINES:
                    group["sourceLines"].append(counts["rows"])
                else:
                    group["sourceLinesOmitted"] += 1
            if row.get("eventName") == "FRAME_TIMING" and not row["duplicate"]:
                values = {key: integer(fields.get(key), "frame counter") for key in (
                    "frames", "jankFrames", "stallsOver100Ms", "stallsOver500Ms", "droppedSamples", "fallbackDeadlines"
                )}
                if (values["jankFrames"] > values["frames"]
                        or not 0 <= values["stallsOver500Ms"] <= values["stallsOver100Ms"] <= values["frames"]):
                    raise ValueError("Inconsistent diagnostic frame denominator")
                # Screen names outside this static vocabulary may contain user text.
                screen = fields.get("screen")
                if not isinstance(screen, str) or screen not in {"Chat", "Debug/Logs", "MainActivity"}:
                    screen = "other"
                previous = frame_identities.get(event_id)
                if previous is not None:
                    if previous != (screen, values):
                        counts["divergentFrameIdentities"] += 1
                    continue
                frame_identities[event_id] = (screen, values.copy())
                values["windows"] = 1
                frame_totals.update(values)
                frame_screens[screen].update(values)
    if counts["event"] == 0:
        raise ValueError("Empty diagnostic log")
    for parent, sequences in continuations.items():
        if parent not in primary or max(sequences) != len(sequences):
            raise ValueError("Orphan or incomplete diagnostic continuation sequence")
    # Spool rows retain their source order. A duplicate reference occupies its
    # declared source line, so the remaining positions identify full exported
    # rows without retaining or reprinting their private message bodies.
    canonical_positions = {}
    for source in LOG_SOURCES:
        omitted = {line for name, line in reference_positions if name == source}
        events = source_events[source]
        total = len(events) + len(omitted)
        if omitted and max(omitted) > total:
            raise ValueError("Diagnostic duplicate source line exceeds retained source")
        iterator = iter(events)
        for position in range(1, total + 1):
            if position not in omitted:
                canonical_positions[(source, position)] = next(iterator)
    for target, source, source_line, canonical_source, canonical_line, batch_line in references:
        canonical = canonical_positions.get((canonical_source, canonical_line))
        if canonical is None or canonical[0] != target or canonical[1] >= batch_line:
            raise ValueError("Diagnostic duplicate reference lacks its canonical event")
    warnings = []
    if capture is None:
        warnings.append("capture-summary-unavailable")
    else:
        capture["reconciledSourceRecords"] = counts["retainedSourceRecords"]
        capture["recordCountMatches"] = capture["retainedRecords"] == counts["retainedSourceRecords"]
        if not capture["recordCountMatches"]:
            warnings.append("retained-source-count-mismatch")
        if any(capture[key] for key in ("sourceFailures", "droppedRecords", "droppedCriticalRecords", "omissionsReported")):
            warnings.append("source-capture-has-gaps")
        if not capture["trackingEnabled"]:
            warnings.append("diagnostic-tracking-disabled")
    if counts["truncatedAtSource"] or counts["truncatedAtExport"]:
        warnings.append("truncated-records")
    if counts["repeatedEventIdentities"]:
        warnings.append("repeated-event-identities")
    if counts["divergentFrameIdentities"]:
        warnings.append("divergent-frame-identities")
    return {
        "schema": LOG_INSPECTION_SCHEMA,
        "result": "partial" if warnings else "retained-log-records-inspected",
        "inputSha256": fingerprint.hexdigest(),
        "inputBytes": byte_count,
        "appCommit": source_sha,
        "counts": dict(counts, duplicateReferences=len(references), distinctIssueIdentities=len(issue_ids)),
        "capture": capture,
        "warnings": warnings,
        "failureSignatures": [{"signature": key, **value} for key, value in sorted(signatures.items())],
        "frameObservations": {"totals": dict(frame_totals), "screens": {key: dict(value) for key, value in sorted(frame_screens.items())}},
        "taskCompletion": "not-verified",
        "causalAttribution": "not-established",
        "physicalDeviceQualification": "not-established",
        "providerDeltaReplay": "unavailable",
        "reducerReplay": "unavailable",
        "scope": "Retained schema-7 observations and structural references only; signature groups are not a count of distinct defects, and redacted source payload digests are not reverified.",
    }


def load_staged_snapshot(archive: zipfile.ZipFile, manifest: dict, maximum_bytes: int = MAX_BYTES) -> dict:
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
                if byte_count > counts[0] or byte_count > maximum_bytes:
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
    availability = manifest.get("evidenceAvailability")
    if availability is not None:
        critical_stages = [stage for stage in stages if stage.get("critical") is True]
        expected = {
            "criticalSectionCount": len(critical_stages),
            "criticalSectionsWithEvidence": sum(stage["evidenceRecords"] > 0 for stage in critical_stages),
            "criticalSectionsWithoutEvidence": sum(stage["evidenceRecords"] == 0 for stage in critical_stages),
            "incompleteCriticalSections": sum(stage["status"] not in {"COMPLETED", "EMPTY"} for stage in critical_stages),
            "applicationLogRecords": sum(stage["evidenceRecords"] for stage in stages if stage["id"] in {"critical_logs", "recent_logs"}),
        }
        if (not isinstance(availability, dict)
                or any(type(availability.get(key)) is not int or availability[key] != value for key, value in expected.items())
                or availability.get("availableCriticalSections") != [stage["id"] for stage in critical_stages if stage["evidenceRecords"] > 0]
                or availability.get("emptyCriticalSections") != [stage["id"] for stage in critical_stages if stage["evidenceRecords"] == 0]):
            raise ValueError("Diagnostic evidence availability disagrees with stage evidence")
    return manifest


def _read_manifest(archive: zipfile.ZipFile) -> dict:
    if archive.getinfo("manifest.json").file_size > MAX_MANIFEST_BYTES:
        raise ValueError("Diagnostic manifest exceeds the inspection limit")
    with archive.open("manifest.json") as stream:
        data = stream.read(MAX_MANIFEST_BYTES + 1)
    if len(data) > MAX_MANIFEST_BYTES:
        raise ValueError("Diagnostic manifest exceeds the inspection limit")
    manifest = json.loads(data)
    if not isinstance(manifest, dict):
        raise ValueError("Invalid diagnostic manifest")
    return manifest


def load_diagnostic_bundle(archive: zipfile.ZipFile, manifest: dict) -> dict:
    """Stream immutable parts to one temporary file at a time; never unpack archive paths.

    The outer inventory and every inner stage are verified independently. Dependency
    closure is recomputed, and missing parts never turn into recovered source records.
    """
    session = manifest.get("sessionId")
    if not isinstance(session, str) or re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", session) is None:
        raise ValueError("Invalid diagnostic bundle session")
    pattern = re.compile(r"(?:partial-)?diagnostics-" + re.escape(session) + r"-part-([1-9][0-9]{0,17})-[0-9a-f-]{36}\.zip")
    descriptors = manifest.get("parts")
    declared_missing = manifest.get("missingParts")
    if (not isinstance(descriptors, list) or not 1 <= len(descriptors) <= 64
            or not isinstance(declared_missing, list) or len(declared_missing) > 128
            or any(not isinstance(row, dict) for row in declared_missing)
            or type(manifest.get("dependencyListTruncated")) is not bool):
        raise ValueError("Invalid diagnostic bundle dependency inventory")
    for field in ("captureCompleted", "dependencyClosureComplete", "shareComplete"):
        if type(manifest.get(field)) is not bool:
            raise ValueError("Invalid diagnostic bundle completeness flag")
    if manifest.get("capturedFully") is not False:
        raise ValueError("Diagnostic bundle cannot claim complete source history")
    closure_declared = not declared_missing and not manifest["dependencyListTruncated"]
    if (manifest["dependencyClosureComplete"] is not closure_declared
            or manifest["shareComplete"] is not (closure_declared and manifest["captureCompleted"])):
        raise ValueError("Diagnostic bundle completeness claims disagree with dependencies")
    inventory = {}
    for row in descriptors:
        if not isinstance(row, dict):
            raise ValueError("Invalid diagnostic bundle part")
        name, number, size, sha = (row.get(key) for key in ("fileName", "archivePart", "bytes", "sha256"))
        match = pattern.fullmatch(name) if isinstance(name, str) else None
        if (match is None or name in inventory or type(number) is not int or number != int(match[1])
                or type(size) is not int or size <= 0 or not isinstance(sha, str)
                or re.fullmatch(r"[a-f0-9]{64}", sha) is None):
            raise ValueError("Invalid or duplicate diagnostic bundle part")
        inventory[name] = dict(fileName=name, archivePart=number, bytes=size, sha256=sha)
    if (set(archive.namelist()) != set(inventory) | {"manifest.json"}
            or sum(row["bytes"] for row in inventory.values()) > MAX_BUNDLE_BYTES):
        raise ValueError("Diagnostic bundle inventory or total byte limit mismatch")
    latest_name = manifest.get("latestArchive")
    if not isinstance(latest_name, str) or latest_name not in inventory:
        raise ValueError("Latest diagnostic archive is missing; inspect the bundle manifest and obtain the named part")

    remaining = MAX_BUNDLE_BYTES
    verified = {}
    links = []
    unresolved = []

    def dependency(reference, parent, stage=None):
        if not isinstance(reference, dict):
            unresolved.append(dict(reason="INVALID_ARCHIVE_REFERENCE"))
            return
        name, number, sha = (reference.get(key) for key in ("fileName", "archivePart", "sha256"))
        if (not isinstance(name, str) or pattern.fullmatch(name) is None
                or type(number) is not int or not 0 < number < parent
                or stage is None and number != parent - 1
                or not isinstance(sha, str) or re.fullmatch(r"[a-f0-9]{64}", sha) is None):
            unresolved.append(dict(reason="INVALID_ARCHIVE_REFERENCE"))
            return
        target = dict(fileName=name, archivePart=number, sha256=sha)
        if stage is not None:
            target["stage"] = {key: stage.get(key) for key in ("stage", "fileName", "sha256", "bytes", "records")}
        links.append(target)

    for name, descriptor in inventory.items():
        if archive.getinfo(name).file_size != descriptor["bytes"]:
            raise ValueError("Diagnostic bundle part byte count mismatch")
        # A disk-backed scratch file lets ZipFile seek without retaining a complete
        # compressed part in memory, and never trusts an archive-supplied path.
        with tempfile.TemporaryFile() as temporary:
            sha = hashlib.sha256()
            count = 0
            with archive.open(name) as source:
                while block := source.read(8192):
                    count += len(block)
                    if count > descriptor["bytes"]:
                        raise ValueError("Diagnostic bundle part exceeds its declared byte count")
                    sha.update(block)
                    temporary.write(block)
            if count != descriptor["bytes"] or sha.hexdigest() != descriptor["sha256"]:
                raise ValueError("Diagnostic bundle part digest or byte count mismatch")
            temporary.seek(0)
            with zipfile.ZipFile(temporary) as part:
                entries = part.infolist()
                names = [entry.filename for entry in entries]
                expanded = sum(entry.file_size for entry in entries)
                if (not 2 <= len(entries) <= 33 or len(names) != len(set(names))
                        or "manifest.json" not in names or expanded > remaining):
                    raise ValueError("Diagnostic part inventory or cumulative expanded byte limit exceeded")
                captured = _read_manifest(part)
                if (captured.get("schema") != STAGED_SCHEMA or captured.get("sessionId") != session
                        or type(captured.get("archivePart")) is not int or captured["archivePart"] != descriptor["archivePart"]
                        or not isinstance(captured.get("sourceIdentityHash"), str)
                        or re.fullmatch(r"[a-f0-9]{64}", captured["sourceIdentityHash"]) is None):
                    raise ValueError("Diagnostic part schema or source ownership mismatch")
                load_staged_snapshot(part, captured, remaining)
                remaining -= expanded
                previous = captured.get("previousArchive")
                if previous is not None or captured["archivePart"] > 1:
                    dependency(previous, captured["archivePart"])
                for stage in captured["stages"]:
                    for field in ("continuedFrom", "sourceReference"):
                        reference = stage.get(field)
                        if reference is not None:
                            if not isinstance(reference, dict):
                                raise ValueError("Invalid diagnostic stage dependency")
                            dependency(reference.get("archive"), captured["archivePart"], reference)
                # Retain bounded metadata only, not record bodies or arbitrary manifest fields.
                verified[name] = dict(
                    **descriptor, sourceIdentityHash=captured["sourceIdentityHash"],
                    captureCompleted=captured["captureCompleted"], retryRecommended=captured["retryRecommended"],
                    usefulRecords=captured["usefulRecords"], criticalEvidenceAvailable=captured["criticalEvidenceAvailable"],
                    stages={stage["id"]: {key: stage.get(key) for key in ("fileName", "sha256", "bytes", "records", "status", "evidenceRecords")}
                            for stage in captured["stages"]},
                )
    latest = verified[latest_name]
    if (any(part["sourceIdentityHash"] != latest["sourceIdentityHash"] for part in verified.values())
            or latest["captureCompleted"] is not manifest["captureCompleted"]):
        raise ValueError("Diagnostic bundle source identity or latest completion mismatch")
    for link in links:
        part = verified.get(link["fileName"])
        if part is None or any(part[key] != link[key] for key in ("archivePart", "sha256")):
            unresolved.append(dict(fileName=link["fileName"], reason="ARCHIVE_UNAVAILABLE_OR_MISMATCHED"))
        elif "stage" in link:
            reference = link["stage"]
            identity = reference.get("stage")
            stage = part["stages"].get(identity) if isinstance(identity, str) else None
            if stage is None or any(stage[key] != reference[key] for key in ("fileName", "sha256", "bytes", "records")):
                unresolved.append(dict(fileName=link["fileName"], reason="STAGE_REFERENCE_MISMATCH"))
    if closure_declared and unresolved:
        raise ValueError("Diagnostic bundle claims complete dependencies, but referenced parts or stages are unresolved")
    return {
        "schema": BUNDLE_INSPECTION_SCHEMA,
        "result": "retained-bundle-parts-verified" if closure_declared and not latest["retryRecommended"] else "partial",
        "sessionId": session, "latestArchive": latest_name,
        "captureCompleted": latest["captureCompleted"], "capturedFully": False,
        "dependencyClosureComplete": closure_declared,
        "verifiedPartCount": len(verified), "expandedBytes": MAX_BUNDLE_BYTES - remaining,
        "parts": list(verified.values()), "missingParts": declared_missing,
        "unresolvedDependencies": unresolved, "dependencyListTruncated": manifest["dependencyListTruncated"],
        "providerDeltaReplay": "unavailable", "reducerReplay": "unavailable", "taskCompletion": "not-verified",
        "recoveryAction": "NONE" if closure_declared else "Obtain the missing or unverified archive parts named in manifest.json; available records do not recover their referenced history.",
        "scope": "Verified immutable ZIP parts and retained stage bytes, record counts, hashes and dependency links. Parts are not combined into replayed history; task success and complete source coverage are not established.",
    }


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_snapshot(path: Path) -> dict:
    file_bytes = path.stat().st_size
    if file_bytes > MAX_BUNDLE_BYTES + MAX_MANIFEST_BYTES + 64 * 1024:
        raise ValueError("Export exceeds the diagnostic bundle inspection limit; inspect its original archive parts separately")
    if path.suffix.lower() == ".jsonl":
        if file_bytes > MAX_BYTES:
            raise ValueError("Export exceeds the inspection memory limit")
        return load_diagnostic_log(path)
    if not zipfile.is_zipfile(path):
        if file_bytes > MAX_BYTES:
            raise ValueError("Export exceeds the inspection memory limit")
        root = json.loads(path.read_text(encoding="utf-8"))
    else:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [entry.filename for entry in infos]
            if len(names) > 65 or len(names) != len(set(names)) or "manifest.json" not in names:
                raise ValueError("Missing manifest or duplicate bundle entry")
            manifest = _read_manifest(archive)
            if manifest.get("schema") == BUNDLE_SCHEMA:
                return load_diagnostic_bundle(archive, manifest)
            if file_bytes > MAX_BYTES or sum(entry.file_size for entry in infos) > MAX_BYTES:
                raise ValueError("Expanded bundle exceeds the inspection memory limit")
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
    if root.get("schema") in {LOG_INSPECTION_SCHEMA, BUNDLE_INSPECTION_SCHEMA}:
        return root
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
    github = parser.add_mutually_exclusive_group()
    github.add_argument("--github-routing", action="store_true", help="Compare measured read-only routing runs for one revision")
    github.add_argument("--github-project", action="store_true", help="Print a redacted routing trace for offline replay")
    parser.add_argument("--github-compare", type=Path, help="Second measured run for the same task and revision")
    parser.add_argument("--github-revision", help="Exact source commit required for Android staged diagnostic ZIPs")
    args = parser.parse_args()
    try:
        if args.github_routing or args.github_project:
            from github_routing_replay import replay
            paths = [args.export] + ([args.github_compare] if args.github_compare else [])
            result = replay(paths, args.github_revision, project=args.github_project)
        elif args.github_compare or args.github_revision:
            raise ValueError("Select --github-routing or --github-project")
        else:
            result = inspect_snapshot(load_snapshot(args.export))
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Cannot replay export: {error}\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
