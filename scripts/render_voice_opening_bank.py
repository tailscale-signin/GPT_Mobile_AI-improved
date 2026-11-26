#!/usr/bin/env python3
"""Render the app's public acknowledgement phrases ahead of time using the pinned CPU Kokoro pack."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

from smoke_mediapipe_speech import MODELS, SAMPLE_RATE, input_chunks, sha256

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
PROFILES = ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/SpeechLanguages.kt"


def profiles():
    result = re.findall(r'SpeechLanguage\("([^"]+)", "[^"]+", "([a-z0-9_ ]+)"\.split', PROFILES.read_text())
    if not result:
        raise ValueError("Missing voice catalog")
    return [(tag, voices.split()) for tag, voices in result]


def write_entry(archive, path, data):
    # Stable timestamps allow comparing archive contents independently of the runner clock.
    archive.writestr(zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0)), data)


def render(args):
    import numpy as np
    from mediapipe.tasks.python.core.base_options import BaseOptions
    from mediapipe.tasks.python.audio.text_to_speech import TextToSpeech, TextToSpeechOptions

    model = MODELS["kokoro"]
    checkpoint = args.model_dir / model["file_name"]
    if not checkpoint.is_file():
        from smoke_mediapipe_speech import provision_model
        checkpoint = provision_model(model, args.model_dir)
    if sha256(checkpoint) != model["sha256"]:
        raise ValueError("Unexpected Kokoro model digest")
    catalog = json.loads((ASSETS / "voice-opening-lines.json").read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    cache = args.model_dir / "opening-generation"
    cache.mkdir(exist_ok=True)
    options = TextToSpeechOptions(base_options=BaseOptions(model_asset_path=str(checkpoint), delegate=BaseOptions.Delegate.CPU), default_voice_id="af_heart", speaking_rate=1.0, target_bucket=0, cache_dir=str(cache))
    manifest = {"schema": 1, "modelSha256": model["sha256"], "sampleRate": SAMPLE_RATE, "voices": {}, "missing": []}
    with TextToSpeech.create_from_options(options) as task, zipfile.ZipFile(args.output, "w") as archive:
        available = {v.voice_id for v in task.list_voices()}
        for tag, voices in profiles():
            lang = tag.split("-")[0]
            for voice in voices:
                if voice not in available:
                    manifest["missing"].append(voice)
                    continue
                clips = {}
                for kind, text in catalog[lang].items():
                    parts = [chunk.samples.copy() for chunk in input_chunks(task, text, incremental=False, evidence={}, voice_id=voice, locale=tag) if chunk.samples.size]
                    samples = np.concatenate(parts)
                    if not 0 < len(samples) <= SAMPLE_RATE * 8 or not np.isfinite(samples).all() or float(np.max(np.abs(samples))) < .005:
                        raise ValueError(f"Invalid voice output for {voice}/{kind}")
                    # Keep a short natural onset/release; encode with the speech codec after validation.
                    active = np.flatnonzero(np.abs(samples) > .001)
                    samples = samples[max(0, active[0] - 480):min(len(samples), active[-1] + 1440)]
                    pcm = (np.clip(samples, -.9999, .9999) * 32767).astype("<i2").tobytes()
                    audio = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0", "-map_metadata", "-1", "-c:a", "libopus", "-application", "voip", "-b:a", "24k", "-f", "ogg", "pipe:1"], input=pcm, capture_output=True, check=True).stdout
                    write_entry(archive, f"{voice}/{kind}.ogg", audio)
                    clips[kind] = {"ms": round(len(samples) * 1000 / SAMPLE_RATE), "sha256": hashlib.sha256(audio).hexdigest()}
                manifest["voices"][voice] = {"language": tag, "clips": clips}
                print(f"Rendered {voice} ({tag})", flush=True)
        if {"af_heart", "ff_siwis"} - manifest["voices"].keys():
            raise ValueError("English and French opening voices are required")
        write_entry(archive, "manifest.json", json.dumps(manifest, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    render(parser.parse_args())
