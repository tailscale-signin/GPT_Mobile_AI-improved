#!/usr/bin/env python3
"""Play public device stimuli, prepare blinded WAV sessions, and score matched listening sheets."""
import argparse
import csv
import hashlib
import io
import json
import random
import re
import secrets
import shutil
import subprocess
import wave
from collections import defaultdict
from pathlib import Path

CORPUS = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/voice/listening-v1.tsv"
ARMS = ("previous", "candidate", "streaming")
ROUTES = ("CPU", "QNN_GPU", "QNN_HTP", "LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "LITERT_KOKORO_NPU", "LITERT_KOKORO_GPU_OPTIMIZED", "LITERT_KOKORO_SM8750")
TEST = "dev.chungjungsoo.gptmobile.presentation.ui.voice.KokoroListeningInstrumentedTest#playPublicFixture"
SCHEMA_VERSION = 2
MAX_COORDINATOR_BYTES = 128 * 1024
MAX_SHEET_BYTES = 128 * 1024
RATING_FIELDS = ("trial_id", "listener_id", "manifest_sha256", "clip", "target", "expected", "correct", "unnatural_pause")


def fixtures():
    with CORPUS.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    if [row["id"] for row in rows] != [f"{n:02}" for n in range(1, 25)]:
        raise ValueError("Expected 24 distinct public fixtures")
    return rows


def play(args):
    if args.fixture not in {row["id"] for row in fixtures()}:
        raise ValueError("Unknown public fixture number")
    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    command += ["shell", "am", "instrument", "-w", "-e", "class", TEST, "-e", "listeningFixture", args.fixture,
                "-e", "listeningBackend", args.backend, "-e", "listeningArrival", args.arrival, args.runner]
    result = subprocess.run(command, capture_output=True, text=True, timeout=230, check=True)
    if not re.search(r"OK \(1 test\)", result.stdout) or "INSTRUMENTATION_STATUS_CODE: -3" in result.stdout:
        raise ValueError("Device did not complete this fixture: " + result.stdout[-1200:])
    print(f"Completed fixture {args.fixture} using {args.backend} ({args.arrival} arrival)")


def prepare(args):
    rows = fixtures()
    paths = {arm: getattr(args, arm) for arm in ARMS}
    # Validate the complete corpus before creating any listener directory.
    inputs = {}
    for row in rows:
        for arm, folder in paths.items():
            source = folder / (row["id"] + ".wav")
            with wave.open(str(source), "rb") as wav:
                fmt = (wav.getnchannels(), wav.getsampwidth(), wav.getframerate())
                if not (fmt[0] in (1, 2) and fmt[1] in (2, 3, 4) and 8000 <= fmt[2] <= 192000 and 0 < wav.getnframes() <= fmt[2] * 120):
                    raise ValueError(f"Invalid stimulus format: {source}")
            inputs[row["id"], arm] = (source, fmt)
    if len({fmt for _, fmt in inputs.values()}) != 1:
        raise ValueError("All three routes must use the same capture format")
    rng = random.Random(args.seed) if args.seed is not None else random.SystemRandom()
    orders = {}
    for row in rows:
        arms = list(ARMS)
        rng.shuffle(arms)
        orders[row["id"]] = arms
    # The seed controls allocation only. A repeated allocation is still a new trial.
    identities = [secrets.token_hex(16) for _ in range(4)]
    if len(set(identities)) != 4:
        raise ValueError("Trial and listener identities must be distinct")
    trial_id = identities[0]
    listener_ids = {str(listener): identities[listener] for listener in range(1, 4)}
    args.output.mkdir(parents=True, exist_ok=False)
    key = []
    sheets = {}
    try:
        for listener in range(3):
            folder = args.output / f"listener-{listener + 1}"
            folder.mkdir()
            order = rows.copy()
            rng.shuffle(order)
            sheets[listener + 1] = []
            sequence = 0
            for row in order:
                rotation = orders[row["id"]]
                for arm in rotation[listener:] + rotation[:listener]:
                    sequence += 1
                    clip = f"{sequence:03}"
                    source, _ = inputs[row["id"], arm]
                    # Rebuild the WAV header so recording metadata cannot disclose the arm.
                    with wave.open(str(source), "rb") as src, wave.open(str(folder / (clip + ".wav")), "wb") as dst:
                        dst.setparams(src.getparams())
                        dst.writeframes(src.readframes(src.getnframes()))
                    sheets[listener + 1].append(dict(trial_id=trial_id, listener_id=listener_ids[str(listener + 1)],
                                                   clip=clip, target=row["target"], expected=row["expected"],
                                                   correct="", unnatural_pause=""))
                    key.append(dict(listener=listener + 1, clip=clip, fixture=row["id"], arm=arm))
        manifest = dict(schemaVersion=SCHEMA_VERSION, trialId=trial_id, listenerIds=listener_ids,
                        corpusSha256=hashlib.sha256(CORPUS.read_bytes()).hexdigest(), assignments=key)
        manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        (args.output / "coordinator.json").write_bytes(manifest_bytes)
        manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
        for listener, ratings in sheets.items():
            folder = args.output / f"listener-{listener}"
            with (folder / "ratings.csv").open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=RATING_FIELDS)
                writer.writeheader()
                for row in ratings:
                    writer.writerow(dict(row, manifest_sha256=manifest_sha256))
    except Exception:
        shutil.rmtree(args.output)
        raise
    print("Three counterbalanced listener folders created. Give each listener only their own folder.")


def _read_bounded(path, limit):
    with path.open("rb") as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"Trial file exceeds {limit} bytes: {path.name}")
    return data


def _unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate coordinator field: " + name)
        result[name] = value
    return result


def score(folder):
    manifest_bytes = _read_bounded(folder / "coordinator.json", MAX_COORDINATOR_BYTES)
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    key = json.loads(manifest_bytes, object_pairs_hook=_unique_object)
    if not isinstance(key, dict) or set(key) != {"schemaVersion", "trialId", "listenerIds", "corpusSha256", "assignments"}:
        raise ValueError("Expected a complete versioned trial manifest")
    if type(key["schemaVersion"]) is not int or key["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("Unsupported listening trial schema; prepare a new trial")
    if not isinstance(key["trialId"], str) or re.fullmatch(r"[0-9a-f]{32}", key["trialId"]) is None:
        raise ValueError("Invalid trial identity")
    listener_ids = key["listenerIds"]
    if not isinstance(listener_ids, dict) or set(listener_ids) != {"1", "2", "3"}:
        raise ValueError("Expected exactly three listener identities")
    if any(not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{32}", value) is None for value in listener_ids.values()):
        raise ValueError("Invalid listener identity")
    if len(set(listener_ids.values())) != 3 or key["trialId"] in listener_ids.values():
        raise ValueError("Trial and listener identities must be distinct")
    if key["corpusSha256"] != hashlib.sha256(CORPUS.read_bytes()).hexdigest():
        raise ValueError("Fixture set differs from the trial")
    prompts = {row["id"]: row for row in fixtures()}
    known = set(prompts)
    clips = {f"{n:03}" for n in range(1, 73)}
    if not isinstance(key["assignments"], list) or len(key["assignments"]) != 216:
        raise ValueError("Expected exactly 216 blinded assignments")
    for assignment in key["assignments"]:
        if not isinstance(assignment, dict) or set(assignment) != {"listener", "clip", "fixture", "arm"}:
            raise ValueError("Invalid blinded assignment fields")
        if (type(assignment["listener"]) is not int or assignment["listener"] not in (1, 2, 3) or
                not isinstance(assignment["clip"], str) or assignment["clip"] not in clips or
                not isinstance(assignment["fixture"], str) or assignment["fixture"] not in known or
                not isinstance(assignment["arm"], str) or assignment["arm"] not in ARMS):
            raise ValueError("Unknown listener, clip, fixture or arm in blinded assignments")
    votes = defaultdict(dict)
    for listener in range(1, 4):
        assignments = [x for x in key["assignments"] if x["listener"] == listener]
        mapping = {x["clip"]: (x["arm"], x["fixture"]) for x in assignments}
        if len(assignments) != 72 or len(mapping) != 72 or set(mapping.values()) != {(arm, f) for arm in ARMS for f in known}:
            raise ValueError("Incomplete or duplicate blinded assignments")
        seen = set()
        sheet_bytes = _read_bounded(folder / f"listener-{listener}" / "ratings.csv", MAX_SHEET_BYTES)
        try:
            reader = csv.DictReader(io.StringIO(sheet_bytes.decode("utf-8-sig"), newline=""), strict=True)
            if reader.fieldnames != list(RATING_FIELDS):
                raise ValueError(f"Unexpected sheet headers for listener {listener}")
            for row_number, row in enumerate(reader, 1):
                if row_number > 72 or set(row) != set(RATING_FIELDS) or any(value is None for value in row.values()):
                    raise ValueError(f"Unexpected sheet cells or row count for listener {listener}")
                if (row["trial_id"] != key["trialId"] or row["listener_id"] != listener_ids[str(listener)] or
                        row["manifest_sha256"] != manifest_sha256):
                    raise ValueError(f"Sheet does not belong to this trial, manifest and listener {listener}")
                clip = row["clip"]
                if clip not in mapping or clip in seen or any(row[name] not in ("0", "1") for name in ("correct", "unnatural_pause")):
                    raise ValueError(f"Missing, invalid or duplicate rating in listener {listener}")
                prompt = prompts[mapping[clip][1]]
                if row["target"] != prompt["target"] or row["expected"] != prompt["expected"]:
                    raise ValueError(f"Prompt differs from the assigned fixture for listener {listener}")
                seen.add(clip)
                votes[mapping[clip]][listener] = (int(row["correct"]), int(row["unnatural_pause"]))
        except csv.Error as error:
            raise ValueError(f"Malformed CSV for listener {listener}") from error
        if seen != set(mapping):
            raise ValueError(f"Incomplete sheet for listener {listener}")
    results = {}
    for arm in ARMS:
        ratings = [votes[arm, f] for f in sorted(known)]
        results[arm] = dict(judgments=72, correct=sum(v[0] for r in ratings for v in r.values()),
                            unnaturalPauses=sum(v[1] for r in ratings for v in r.values()),
                            correctnessDisagreements=sum(len({v[0] for v in r.values()}) > 1 for r in ratings),
                            pauseDisagreements=sum(len({v[1] for v in r.values()}) > 1 for r in ratings))
    candidate = results["candidate"]
    return dict(trialId=key["trialId"], manifestSha256=manifest_sha256, results=results,
                listeningPassed=(candidate["correct"] / 72 >= .95 and
                candidate["correct"] >= results["streaming"]["correct"] and
                candidate["unnaturalPauses"] < results["previous"]["unnaturalPauses"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    device = commands.add_parser("play")
    device.add_argument("--runner", required=True, help="Installed Android test component from adb shell pm list instrumentation")
    device.add_argument("--backend", choices=ROUTES, required=True)
    device.add_argument("--fixture", required=True)
    device.add_argument("--arrival", choices=("word", "character"), default="word")
    device.add_argument("--serial")
    device.add_argument("--adb", default="adb")
    blind = commands.add_parser("prepare")
    for name in ARMS:
        blind.add_argument("--" + name, type=Path, required=True)
    blind.add_argument("--seed", type=int)
    blind.add_argument("--output", type=Path, required=True)
    results = commands.add_parser("score")
    results.add_argument("folder", type=Path)
    args = parser.parse_args()
    if args.command == "play":
        play(args)
    elif args.command == "prepare":
        prepare(args)
    else:
        result = score(args.folder)
        print(json.dumps(result, indent=2))
        return 0 if result["listeningPassed"] else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
