#!/usr/bin/env python3
"""Run every native benchmark control with the repository's verified Kotlin compiler.

The small compiler classpath is pinned by the existing Gradle verification
metadata. This gate never executes generated model code on the host.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MIRRORS = ('https://repo.maven.apache.org/maven2', 'https://repo1.maven.org/maven2',
           'https://maven-central.storage-download.googleapis.com/maven2')
MAX_JAR_BYTES = 128 * 1024 * 1024


def artifacts(root=ROOT):
    version = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']['kotlin']
    if re.fullmatch(r'\d+\.\d+\.\d+', version) is None:
        raise ValueError('The native gate needs an exact released Kotlin version')
    coordinates = [('org.jetbrains.kotlin', name, version) for name in
                   ('kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-stdlib',
                    'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    # Runtime dependencies of the publisher's kotlin-compiler-embeddable POM.
    coordinates += [('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                    ('org.jetbrains', 'annotations', '13.0')]
    document = ET.parse(root / 'gradle/verification-metadata.xml').getroot()
    ns = {'v': 'https://schema.gradle.org/dependency-verification'}
    rows = []
    for group, name, revision in coordinates:
        filename = f'{name}-{revision}.jar'
        nodes = document.findall(f"v:components/v:component[@group='{group}'][@name='{name}'][@version='{revision}']/v:artifact[@name='{filename}']/v:sha256", ns)
        hashes = {node.attrib['value'] for node in nodes}
        if not hashes or any(re.fullmatch(r'[a-f0-9]{64}', value) is None for value in hashes):
            raise ValueError('Missing repository-reviewed compiler checksum: ' + filename)
        rows.append(dict(group=group, name=name, version=revision, filename=filename, hashes=hashes))
    return rows


def verified(path, hashes):
    if not path.is_file() or path.stat().st_size > MAX_JAR_BYTES:
        return False
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            checksum.update(chunk)
    return checksum.hexdigest() in hashes


def provision(row, destination, cache, *, offline=False, deadline=None):
    target = destination / row['filename']
    if verified(target, row['hashes']):
        return target
    existing = cache / row['group'] / row['name'] / row['version']
    for candidate in sorted(existing.glob('*/' + row['filename'])):
        if verified(candidate, row['hashes']):
            shutil.copyfile(candidate, target)
            return target
    if offline:
        raise ValueError('Verified compiler artifact unavailable offline: ' + row['filename'])
    relative = '/'.join((row['group'].replace('.', '/'), row['name'], row['version'], row['filename']))
    for mirror in MIRRORS:
        temporary = None
        try:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Compiler preparation deadline expired')
            with urllib.request.urlopen(mirror + '/' + relative, timeout=min(20, remaining)) as response, \
                    tempfile.NamedTemporaryFile(dir=destination, delete=False, suffix='.part') as output:
                temporary = Path(output.name)
                total = 0
                for chunk in iter(lambda: response.read(65536), b''):
                    total += len(chunk)
                    if total > MAX_JAR_BYTES or time.monotonic() >= deadline:
                        raise ValueError('Compiler artifact exceeded its frozen download bound')
                    output.write(chunk)
            if not verified(temporary, row['hashes']):
                raise ValueError('Compiler artifact does not match Gradle verification metadata')
            os.replace(temporary, target)
            return target
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if mirror == MIRRORS[-1]:
                raise
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    raise RuntimeError('Compiler preparation did not produce a verified artifact')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/benchmark-native-controls')
    args = parser.parse_args()
    # Native YAML controls are required in this dedicated gate, never optional skips.
    import yaml  # noqa: F401
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
    if not java or not Path(java).is_file():
        raise ValueError('Configure the trusted Java runtime before native controls')
    compiler = args.output.resolve() / 'compiler'
    compiler.mkdir(parents=True, exist_ok=True)
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    rows = artifacts()
    deadline = time.monotonic() + 300
    paths = [provision(row, compiler, cache, offline=args.offline, deadline=deadline) for row in rows]
    standard = next(path for row, path in zip(rows, paths) if row['name'] == 'kotlin-stdlib')
    environment = dict(os.environ, GATEWAY_KOTLIN_COMPILER_CLASSPATH=os.pathsep.join(map(str, paths)),
                       GATEWAY_KOTLIN_STDLIB=str(standard), GATEWAY_JAVA_EXECUTABLE=java)
    runner = '''import sys, unittest
from pathlib import Path
sys.path.insert(0, str(Path('gateway').resolve()))
suite = unittest.defaultTestLoader.discover('gateway/tests', pattern='test_gateway_repository_tasks.py')
result = unittest.TextTestRunner(verbosity=2).run(suite)
raise SystemExit(0 if result.wasSuccessful() and not result.skipped and result.testsRun >= 10 else 1)
'''
    result = subprocess.run([sys.executable, '-c', runner], cwd=ROOT, env=environment, timeout=180,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (args.output / 'native-controls.txt').write_text(result.stdout, encoding='utf-8')
    print(result.stdout)
    return result.returncode


if __name__ == '__main__':
    sys.exit(main())
