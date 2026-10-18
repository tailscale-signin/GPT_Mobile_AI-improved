#!/usr/bin/env python3
"""Check the final compilation commands for packaged GGUF compute targets."""
import json
from pathlib import Path
import re
import shlex
import sys

files = [Path(x) for x in sys.argv[1:]]
if not files:
    files = list(Path('local-gguf-runtime').rglob('compile_commands.json'))
if not files:
    raise SystemExit('No GGUF compile database found; build the native APK variant first.')
for file in files:
    selected = [row for row in json.loads(file.read_text()) if any(x in row['file'] for x in ('gguf_jni.cpp', 'ggml-cpu/', 'ggml-quants.c'))]
    if not any('gguf_jni.cpp' in row['file'] for row in selected):
        raise SystemExit(f'{file}: GGUF JNI target absent')
    if not any('ggml-cpu/' in row['file'] for row in selected):
        raise SystemExit(f'{file}: CPU compute target absent')
    for row in selected:
        args = row.get('arguments') or shlex.split(row['command'])
        flags = [x for x in args if re.fullmatch(r'-O(?:[0-3sgz]|fast)', x)]
        if not flags or flags[-1] in ('-O0', '-Og'):
            raise SystemExit(f"Unoptimized inference target {row['file']}: {flags}")
        if '-march=native' in args or '-mcpu=native' in args:
            raise SystemExit(f"Nonportable instruction baseline in {row['file']}")
    print(f'{file}: verified optimized portable compilation for {len(selected)} inference sources')
