"""Install and qualify the optional GPU venv for an installed Windows Gateway."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from prepare_voice_pack import prepare
from v14.voice import MAX_BODY_BYTES
from v14.voice_process import SidecarCudaBackend


def install(root, model_directory, interpreter, model='model.onnx', voices='voices.bin'):
    root, model_directory, interpreter = Path(root).resolve(), Path(model_directory).resolve(), Path(interpreter).resolve()
    if not (root / 'current.json').is_file() or not (root / 'data').is_dir():
        raise ValueError('Choose the installed Gateway configuration root containing current.json and data')
    if not interpreter.is_file():
        raise ValueError('Choose a full path to a supported CPython interpreter')
    prefix = root / 'addons' / 'voice-cuda' / 'venv'
    python = prefix / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    # Check the interpreter pip will use, including an existing installation.
    runtime_check = """
import sys, sysconfig
windows_wheel = sysconfig.get_platform() == "win-amd64" and not sysconfig.get_config_var("Py_GIL_DISABLED")
if (sys.implementation.name != "cpython" or sys.maxsize <= 2**32 or
        not (3, 11) <= sys.version_info[:2] <= (3, 14) or (sys.platform == "win32" and not windows_wheel)):
    sys.exit("Gateway CUDA speech requires 64-bit CPython 3.11 through 3.14 (standard x64 builds on Windows).")
"""
    subprocess.run([str(python if python.is_file() else interpreter), '-I', '-c', runtime_check], check=True)
    if not python.is_file():
        subprocess.run([str(interpreter), '-m', 'venv', str(prefix)], check=True)
    requirements = Path(__file__).resolve().with_name('requirements-voice.txt')
    subprocess.run([str(python), '-m', 'pip', 'install', '--require-hashes', '--only-binary=:all:', '-r', str(requirements)], check=True)
    manifest_path = model_directory / 'voice-manifest.json'
    previous = None
    if manifest_path.exists():
        if manifest_path.stat().st_size > MAX_BODY_BYTES:
            raise ValueError('Existing voice manifest exceeds its size limit')
        previous = manifest_path.read_bytes()
    prepare(model_directory, model, voices)
    candidate = manifest_path.read_bytes()
    # Qualification occurs through the exact private-pipe path used by the
    # frozen host. A CPU-only or broken setup is never enabled.
    try:
        backend = SidecarCudaBackend(model_directory, python)
    except Exception:
        # Keep a prior working model selection when qualification fails, without
        # overwriting a concurrent operator's subsequent manifest change.
        if manifest_path.read_bytes() == candidate:
            if previous is None:
                manifest_path.unlink()
            else:
                restoration = manifest_path.with_suffix('.restore')
                restoration.write_bytes(previous)
                restoration.replace(manifest_path)
        raise
    try:
        evidence = {'model_sha256': backend.model_sha256, 'execution_provider': 'CUDAExecutionProvider',
                    'executed_cuda_nodes': backend.executed_cuda_nodes}
    finally:
        backend.close()
    settings = dict(schema_version=1, enabled=True, python=str(python.absolute()), model_directory=str(model_directory))
    target = root / 'data' / 'voice-cuda.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(settings, indent=2) + '\n', encoding='utf-8')
    temporary.replace(target)
    return evidence


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--model-directory', required=True, type=Path)
    parser.add_argument('--python', default=sys.executable, type=Path)
    parser.add_argument('--model', default='model.onnx')
    parser.add_argument('--voices', default='voices.bin')
    args = parser.parse_args()
    evidence = install(args.root, args.model_directory, args.python, args.model, args.voices)
    print(json.dumps(dict(evidence, enabled=True, next_step='Restart Gateway, then select Gateway CUDA in Android voice settings.')))
