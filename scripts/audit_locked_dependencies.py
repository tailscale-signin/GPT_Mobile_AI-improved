#!/usr/bin/env python3
"""Query OSV for locked Android, build plugin, npm and Python dependencies."""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


def inventory(root, verified_inventory=False):
    packages = set()
    if verified_inventory:
        xml = ET.parse(root / 'gradle/verification-metadata.xml')
        for component in xml.findall('.//{*}components/{*}component'):
            group, artifact, version = (component.attrib[key] for key in ('group', 'name', 'version'))
            packages.add(('Maven', f'{group}:{artifact}', version))
    else:
        release_graphs = {'releaseRuntimeClasspath', 'kspReleaseKotlinProcessorClasspath'}
        locks = {'app/release.lockfile': release_graphs, 'app/geniex-release.lockfile': release_graphs,
                 'buildscript-gradle.lockfile': {'classpath'}}
        for lock, expected in locks.items():
            seen = set()
            for line in (root / lock).read_text().splitlines():
                if not line or line.startswith(('#', 'empty=')):
                    continue
                coordinate, configurations = line.split('=', 1)
                matched = expected.intersection(configurations.split(','))
                if matched:
                    group, artifact, version = coordinate.split(':')
                    packages.add(('Maven', f'{group}:{artifact}', version))
                    seen.update(matched)
            if seen != expected:
                raise ValueError(f'{lock} is missing locked coordinates for {sorted(expected - seen)}')
    for lock in sorted((root / 'mcp').rglob('package-lock.json')):
        if 'node_modules' in lock.parts:
            continue
        for path, metadata in json.loads(lock.read_text())['packages'].items():
            if 'node_modules/' in path and not metadata.get('link'):
                packages.add(('npm', metadata.get('name') or path.rsplit('node_modules/', 1)[1], metadata['version']))
    for lock in ('gateway/requirements.txt', 'gateway/desktop/requirements-build.txt', 'scripts/requirements-ci.txt',
                 'scripts/requirements-voice-mediapipe-smoke.txt'):
        for name, version in re.findall(r'^([\w.-]+)==([^\s;\\]+)', (root / lock).read_text(), re.M):
            packages.add(('PyPI', name, version))
    return sorted(packages)


def request_batch(queries):
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                     data=json.dumps({'queries': queries}).encode(),
                                     headers={'Content-Type': 'application/json'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)['results']
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)


def scan(packages, query=request_batch):
    matches = set()
    for offset in range(0, len(packages), 100):
        pending = [{'package': {'ecosystem': eco, 'name': name}, 'version': version}
                   for eco, name, version in packages[offset:offset + 100]]
        seen = set()
        while pending:
            results = query(pending)
            if not isinstance(results, list) or len(results) != len(pending):
                raise ValueError('Incomplete OSV batch response')
            following = []
            for q, result in zip(pending, results):
                identity = (q['package']['ecosystem'], q['package']['name'], q['version'])
                for item in result.get('vulns', []):
                    matches.add((*identity, item['id']))
                if token := result.get('next_page_token'):
                    checkpoint = (*identity, token)
                    if checkpoint in seen:
                        raise ValueError('OSV repeated a pagination token')
                    seen.add(checkpoint)
                    following.append(dict(q, page_token=token))
            pending = following
    return sorted(matches)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verified-inventory', action='store_true', help='Include Maven build/test tooling and previously verified versions.')
    packages = inventory(Path(__file__).resolve().parents[1], parser.parse_args().verified_inventory)
    issues = scan(packages)
    for ecosystem, name, version, advisory in issues:
        print(f'{ecosystem} {name} {version} — {advisory}')
    print(f'{len(packages)} package versions checked, {len(issues)} package/advisory matches.')
    sys.exit(bool(issues))
