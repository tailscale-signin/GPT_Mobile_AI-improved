#!/usr/bin/env python3
"""Pinned synthetic native fixture; weights remain outside the repository and APK."""
import hashlib
from pathlib import Path
import subprocess
import sys
import urllib.request

upstream, directory = map(Path, sys.argv[1:])
directory.mkdir(parents=True, exist_ok=True)
source = directory / "stories15M-q4_0.gguf"
if not source.exists():
    urllib.request.urlretrieve("https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories15M-q4_0.gguf", source)
assert hashlib.sha256(source.read_bytes()).hexdigest() == "66967fbece6dbe97886593fdbb73589584927e29119ec31f08090732d1861739"
# Add a standard embedded template for JNI protocol tests, without modifying tensors.
template = "{% for message in messages %}{{'<|im_start|>' + message['role'] + '\n' + message['content'] + '<|im_end|>' + '\n'}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\n' }}{% endif %}"
target = directory / "stories15M-chatml.gguf"
subprocess.run([sys.executable, str(upstream / "gguf-py/gguf/scripts/gguf_new_metadata.py"), str(source), str(target), "--chat-template", template, "--force"], check=True)
assert hashlib.sha256(target.read_bytes()).hexdigest() == "d8353ad94a2932a5fb3323c3aa6de33768c4c90db36de53d552c0c4a5444d06a"
print(target)
