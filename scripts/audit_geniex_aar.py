#!/usr/bin/env python3
"""Record the resolved Maven artifact identity and verify its reviewed native inventory.
Release-asset hashes are deliberately not substituted for Maven artifact hashes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile


def audit(aar, reviewed, version):
    expected = {name.replace('lib/', 'jni/', 1): entry for name, entry in reviewed['libraries'].items() if entry.get('source') == 'com.qualcomm.qti:geniex-android:' + version}
    with zipfile.ZipFile(aar) as archive:
        names = [i.filename for i in archive.infolist()]
        if len(set(names)) != len(names):
            raise ValueError('Duplicate AAR members')
        native = {n: {'sha256': hashlib.sha256(archive.read(n)).hexdigest(), 'bytes': archive.getinfo(n).file_size} for n in names if n.startswith('jni/') and n.endswith('.so')}
    if native.keys() != expected.keys():
        raise ValueError('Native inventory changed: ' + str(sorted(native.keys() ^ expected.keys())))
    for name, identity in native.items():
        if identity['sha256'] != expected[name]['sha256'] or identity['bytes'] != expected[name]['bytes']:
            raise ValueError('Native identity changed: ' + name)
    return {'coordinate': 'com.qualcomm.qti:geniex-android:' + version, 'aarSha256': hashlib.sha256(Path(aar).read_bytes()).hexdigest(), 'aarBytes': Path(aar).stat().st_size, 'nativeLibraries': native, 'reviewedNativeInventoryMatches': True, 'deviceQualification': 'not established by artifact audit'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    version = tomllib.loads(Path('gradle/libs.versions.toml').read_text())['versions']['geniex']
    candidates = list((args.gradle_cache / 'com.qualcomm.qti/geniex-android' / version).rglob('*.aar'))
    if len(candidates) != 1:
        raise ValueError('Resolve exactly one pinned Maven AAR before auditing')
    result = audit(candidates[0], json.loads(Path('docs/audits/geniex-preview-native-libraries.json').read_text()), version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(result['coordinate'] + ' SHA-256 ' + result['aarSha256'])

if __name__ == '__main__':
    main()
