"""Pin an operator-selected timed Kokoro export and its voices for CUDA speech."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from v14.voice import FRONTEND, FRONTEND_SYMBOLS_SHA256, FRAMES_PER_DURATION, PROTOCOL, SAMPLE_RATE, frontend_symbols, load_pack


def prepare(directory, model='model.onnx', voices='voices.bin'):
    directory = Path(directory).resolve()
    def artifact(name, maximum):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', name):
            raise ValueError('Model and voice names must be filenames inside the model directory')
        path = (directory / name).resolve()
        if path.parent != directory or not path.is_file() or not 0 < path.stat().st_size <= maximum:
            raise ValueError('A required model pack file is missing or exceeds its size limit')
        return path
    def checksum(path):
        digest = hashlib.sha256()
        with path.open('rb') as source:
            for part in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(part)
        return digest.hexdigest()
    symbols = frontend_symbols(artifact('tokens.txt', 65536).read_bytes())
    phones = None
    with artifact('lexicon-us-en.txt', 32 * 1024 * 1024).open(encoding='utf-8') as lexicon:
        for row in lexicon:
            fields = row.split()
            if fields and fields[0] == 'hello':
                phones = fields[1:]
                break
    if not phones or any(phone not in symbols for phone in phones):
        raise ValueError('Use the app-compatible English tokens and lexicon; the hello entry could not be encoded')
    tokens = [0] + [symbols[phone] for phone in phones] + [0]
    manifest = dict(protocol=PROTOCOL, frontend_id=FRONTEND, sample_rate=SAMPLE_RATE, frames_per_duration=FRAMES_PER_DURATION,
                    frontend_symbols_sha256=FRONTEND_SYMBOLS_SHA256,
                    model=model, model_sha256=checksum(artifact(model, 768 * 1024 * 1024)),
                    voices=voices, voices_sha256=checksum(artifact(voices, 256 * 1024 * 1024)),
                    warmup_token_ids=tokens, warmup_word_tokens=[1, len(tokens) - 1])
    target = directory / 'voice-manifest.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    temporary.replace(target)
    # Verify the same parser the service uses before accepting this manifest.
    load_pack(directory)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--model', default='model.onnx')
    parser.add_argument('--voices', default='voices.bin')
    args = parser.parse_args()
    result = prepare(args.directory, args.model, args.voices)
    print(json.dumps({'manifest': str(args.directory / 'voice-manifest.json'), 'model_sha256': result['model_sha256']}))
