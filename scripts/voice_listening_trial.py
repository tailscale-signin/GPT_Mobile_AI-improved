#!/usr/bin/env python3
"""Play public device stimuli, prepare blinded WAV sessions, and score matched listening sheets."""
import argparse
import csv
import hashlib
import json
import random
import re
import shutil
import subprocess
import wave
from collections import defaultdict
from pathlib import Path

CORPUS = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/voice/listening-v1.tsv"
ARMS = ("previous", "candidate", "streaming")
ROUTES = ("CPU", "QNN_GPU", "QNN_HTP", "LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU")
TEST = "dev.chungjungsoo.gptmobile.presentation.ui.voice.KokoroListeningInstrumentedTest#playPublicFixture"


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
    args.output.mkdir(parents=True, exist_ok=False)
    key = []
    fields = ("clip", "target", "expected", "correct", "unnatural_pause")
    try:
        for listener in range(3):
            folder = args.output / f"listener-{listener + 1}"
            folder.mkdir()
            order = rows.copy()
            rng.shuffle(order)
            with (folder / "ratings.csv").open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=fields)
                writer.writeheader()
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
                        writer.writerow(dict(clip=clip, target=row["target"], expected=row["expected"], correct="", unnatural_pause=""))
                        key.append(dict(listener=listener + 1, clip=clip, fixture=row["id"], arm=arm))
        keyfile = args.output / "coordinator.json"
        keyfile.write_text(json.dumps(dict(corpusSha256=hashlib.sha256(CORPUS.read_bytes()).hexdigest(), assignments=key), indent=2))
    except Exception:
        shutil.rmtree(args.output)
        raise
    print("Three counterbalanced listener folders created. Give each listener only their own folder.")


def score(folder):
    key = json.loads((folder / "coordinator.json").read_text())
    if key["corpusSha256"] != hashlib.sha256(CORPUS.read_bytes()).hexdigest():
        raise ValueError("Fixture set differs from the trial")
    known = {row["id"] for row in fixtures()}
    votes = defaultdict(dict)
    for listener in range(1, 4):
        assignments = [x for x in key["assignments"] if x["listener"] == listener]
        mapping = {x["clip"]: (x["arm"], x["fixture"]) for x in assignments}
        if len(assignments) != 72 or len(mapping) != 72 or set(mapping.values()) != {(arm, f) for arm in ARMS for f in known}:
            raise ValueError("Incomplete or duplicate blinded assignments")
        seen = set()
        with (folder / f"listener-{listener}" / "ratings.csv").open(newline="", encoding="utf-8-sig") as sheet:
            for row in csv.DictReader(sheet):
                clip = row["clip"]
                if clip not in mapping or clip in seen or any(row[name] not in ("0", "1") for name in ("correct", "unnatural_pause")):
                    raise ValueError(f"Missing, invalid or duplicate rating in listener {listener}")
                seen.add(clip)
                votes[mapping[clip]][listener] = (int(row["correct"]), int(row["unnatural_pause"]))
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
    return dict(results=results, listeningPassed=(candidate["correct"] / 72 >= .95 and
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
