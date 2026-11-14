"""Build only on Windows x64 with pinned build/runtime dependencies already installed."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GATEWAY = ROOT / 'gateway'
sys.path.insert(0, str(GATEWAY))
from v14 import VERSION
from v14.package import validate_package


def main():
    if sys.platform != 'win32' or platform.machine().lower() not in {'amd64', 'x86_64'}:
        raise SystemExit('Build the Windows installer on a Windows x64 runner')
    manifest = validate_package(GATEWAY)
    source_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    output = ROOT / 'dist' / 'windows'
    output.mkdir(parents=True, exist_ok=True)
    staging = ROOT / 'build' / 'gateway-source'
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    # Include exactly the validated source manifest, never local state or credentials.
    for name in [*manifest['files'], 'manifest-v14.json']:
        target = staging / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(GATEWAY / name, target)
    (staging / 'build-info.json').write_text(json.dumps({'sourceCommit': source_commit, 'status': 'bundled', 'platform': 'windows-x64'}), encoding='utf-8')
    subprocess.run([sys.executable, '-m', 'PyInstaller', '--noconfirm', '--clean', '--onedir', '--console', '--noupx',
        '--icon', str(GATEWAY / 'desktop/assets/gateway.ico'),
        '--name', 'Gateway', '--distpath', str(output), '--workpath', str(ROOT / 'build' / 'pyinstaller'),
        '--specpath', str(ROOT / 'build'), '--paths', str(GATEWAY), '--add-data', str(staging) + os.pathsep + '.',
        '--collect-submodules', 'desktop', '--hidden-import', 'gateway_v14', '--collect-submodules', 'v14', '--collect-submodules', 'benchmarks', '--collect-submodules', 'uvicorn',
        '--hidden-import', 'gateway_v13_runtime', '--hidden-import', 'gateway_v13_transport',
        '--collect-all', 'tree_sitter', '--collect-all', 'tree_sitter_kotlin', '--collect-all', 'tree_sitter_java',
        '--collect-all', 'tree_sitter_javascript', '--collect-all', 'tree_sitter_typescript',
        '--copy-metadata', 'fastapi', '--copy-metadata', 'uvicorn', '--copy-metadata', 'requests', '--copy-metadata', 'httpx',
        str(GATEWAY / 'desktop' / 'entry.py')], check=True)
    bundle = output / 'Gateway'
    release = dict(version=VERSION, stateSchema=1, bootstrapProtocol=1, sourceCommit=source_commit,
        files={p.relative_to(bundle).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(bundle.rglob('*')) if p.is_file()})
    (bundle / 'release.json').write_text(json.dumps(release, indent=2), encoding='utf-8')
    from desktop.releases import validate_release
    validate_release(bundle, VERSION)
    archive = output / f'Gateway-{VERSION}-windows-x64.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(bundle.rglob('*')):
            if path.is_file():
                zipped.write(path, path.relative_to(bundle).as_posix())
    compiler = Path(os.environ.get('ISCC', r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'))
    if not compiler.is_file():
        raise SystemExit('Inno Setup 6 compiler is required (set ISCC to its full path)')
    subprocess.run([str(compiler), '/DGatewayVersion=' + VERSION, str(GATEWAY / 'desktop' / 'installer.iss')], check=True)
    artifacts = [archive, output / f'Gateway-{VERSION}-windows-x64-setup.exe']
    (output / 'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in artifacts), encoding='utf-8')


if __name__ == '__main__':
    main()
