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
assert hashlib.sha256(target.read_bytes()).hexdigest() == "c5398d4d93aa7dac7ee95fbc1f8448620fdb5b7445a8b42286c80d587e53dfd5"
print(target)
# This format is valid Jinja but has no marker recognized by the old hard-coded
# llama_chat_apply_template formatter. Real JNI inference must work with it.
custom_template = "{% macro turn(m) %}{{ m.role|upper }}: {{ m.content|trim }}\n{% endmacro %}{{ bos_token }}{% for m in messages %}{{ turn(m) }}{% endfor %}{% if add_generation_prompt %}ASSISTANT: {% endif %}"
custom_target = directory / "stories15M-jinja.gguf"
subprocess.run([sys.executable, str(upstream / "gguf-py/gguf/scripts/gguf_new_metadata.py"), str(source), str(custom_target), "--chat-template", custom_template, "--force"], check=True)
assert hashlib.sha256(custom_target.read_bytes()).hexdigest() == "3555a89f9dc6c6f625421a4b88b71b173243d7d960872243aa95ef6a47158ca7"
print(custom_target)
