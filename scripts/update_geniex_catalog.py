#!/usr/bin/env python3
"""Normalize a captured official AI Hub inventory without running native tooling.

Capture the registry used by GenieX v0.8.0, never scrape the website or assume CLI JSON.
No model weights are fetched. Publication is an explicit, reviewed operation.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urlparse
from urllib.request import urlopen

REGISTRY = 'https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/releases/latest/manifest.json'
UPSTREAM_SHA = 'a5c8e7fb4a364ddcccefbf187afd1bac52d88de5'
LIMIT = 8 * 1024 * 1024
STATES = {'represented', 'unsupported_platform', 'unsupported_app_workflow', 'missing_artifact_metadata', 'withdrawn'}

def load(path):
    raw = Path(path).read_bytes()
    if len(raw) > LIMIT:
        raise ValueError('Metadata exceeds 8 MiB')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)

def fetch(url, target):
    parsed = urlparse(url)
    if parsed.scheme != 'https' or parsed.hostname != urlparse(REGISTRY).hostname or parsed.username or parsed.query:
        raise ValueError('Unexpected registry metadata origin')
    with urlopen(url, timeout=30) as response:
        if urlparse(response.url).hostname != parsed.hostname:
            raise ValueError('Unexpected redirect')
        raw = response.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ValueError('Metadata exceeds 8 MiB')
    target.write_bytes(raw)
    load(target)

def stable_id(model, asset):
    key = '|'.join([model, asset.get('chipset') or 'unknown', asset.get('runtime', ''), asset.get('precision', ''), asset.get('download_url', '')])
    return 'geniex_' + hashlib.sha256(key.encode()).hexdigest()[:24]

def validate_inventory_entry(approved, asset):
    if approved.get('sourceUrl') != asset.get('download_url'):
        raise ValueError('Inventory source mismatch')
    url = urlparse(approved.get('normalizedBundleUrl', ''))
    if url.scheme != 'https' or not url.hostname or url.username or url.password or not url.path.endswith('.localmodel'):
        raise ValueError('Inventory requires a published normalized bundle')
    if not re.fullmatch('[a-f0-9]{64}', approved.get('sha256', '')):
        raise ValueError('Inventory requires the archive SHA-256')
    if approved.get('runtimeVersion') != '0.8.0' or approved.get('manifestVersion') != 2:
        raise ValueError('Inventory runtime or manifest mismatch')
    files = approved.get('files', [])
    if not files or len(files) > 256 or len({f['path'] for f in files}) != len(files):
        raise ValueError('Inventory requires all unique file hashes')
    for f in files:
        path = Path(f['path'])
        if path.is_absolute() or '..' in path.parts or '\\' in str(path) or ':' in str(path):
            raise ValueError('Unsafe inventory path')
        if not re.fullmatch('[a-f0-9]{64}', f.get('sha256', '')) or not 0 < f.get('sizeBytes', 0) <= 16_000_000_000:
            raise ValueError('Unverified inventory asset')
    if sum(f['sizeBytes'] for f in files) > 32_000_000_000:
        raise ValueError('Inventory exceeds installation limit')
    if approved.get('redistributionApproved') is not True:
        raise ValueError('Bundle redistribution has not been reviewed')


def normalize(capture, baseline, inventory, revision):
    capture = Path(capture)
    manifest = load(capture / 'manifest.json')
    platform = load(capture / 'platform.json')
    android = {d['chipset'] for d in platform['devices'] if d.get('os', {}).get('ostype') == 'OPERATING_SYSTEM_TYPE_ANDROID'}
    models = manifest['models']
    if not models or len({m['id'] for m in models}) != len(models):
        raise ValueError('Empty or duplicate source model IDs')
    coverage, entries, digests = [], list(baseline['models']), {}
    for p in sorted(capture.glob('*.json')):
        digests[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
    missing_release_urls = []
    for model in models:
        name = model['id']
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', name):
            raise ValueError('Unsafe source model ID')
        asset_path = capture / (name + '.json')
        if not asset_path.exists() and not model.get('manifest_urls', {}).get('release_assets'):
            assets = []
            missing_release_urls.append(name)
        else:
            assets_doc = load(asset_path)
            if assets_doc['model_id'] != name:
                raise ValueError('Source identity mismatch')
            assets = assets_doc['assets']
        info_path = capture / (name + '.info.json')
        info = load(info_path) if info_path.exists() else {}
        if not assets and any(r in {'RUNTIME_GENIEX_QAIRT', 'RUNTIME_GENIE'} for r in model.get('supported_runtimes', [])):
            entries.append({'id': 'geniex_' + name + '_unresolved', 'familyId': name, 'displayName': model['display_name'],
                            'runtimeRequirement': 'geniex-0.8.0', 'coverageStatus': 'missing_artifact_metadata',
                            'manifestVersion': 2, 'minAppVersion': '0.9.50.0', 'sourceUrl': info.get('source_repo', ''),
                            'licenseUrl': info.get('license_url', ''), 'redistributionRestricted': info.get('restrict_model_sharing', False)})
        if not assets:
            coverage.append({'familyId': name, 'artifactId': name + ':unresolved', 'status': 'missing_artifact_metadata', 'runtime': '', 'chipset': ''})
        seen = set()
        for asset in assets:
            aid = stable_id(name, asset)
            if aid in seen:
                raise ValueError('Duplicate upstream variant')
            seen.add(aid)
            runtime, chipset = asset['runtime'], asset.get('chipset') or ''
            approved = inventory.get(aid)
            chat = model.get('domain') in {'MODEL_DOMAIN_GENERATIVE_AI', 'MODEL_DOMAIN_MULTIMODAL'}
            qairt = runtime in {'RUNTIME_GENIEX_QAIRT', 'RUNTIME_GENIE'}
            state = 'missing_artifact_metadata'
            if not chat:
                state = 'unsupported_app_workflow'
            elif not qairt or chipset not in android:
                state = 'unsupported_platform'
            elif approved:
                validate_inventory_entry(approved, asset)
                if approved.get('sourceUrl') != asset['download_url'] or not re.fullmatch('[a-f0-9]{64}', approved.get('sha256', '')):
                    raise ValueError('Approved inventory does not match source artifact')
                if approved.get('contextTokens', 0) <= 0 or approved.get('sizeBytes', 0) <= 0:
                    raise ValueError('Approved inventory lacks execution metadata')
                state = 'represented'
            coverage.append({'familyId': name, 'artifactId': aid, 'status': state, 'runtime': runtime, 'chipset': chipset, 'precision': asset.get('precision', '')})
            if qairt and chat:
                entries.append({
                    'id': aid, 'familyId': name, 'displayName': model['display_name'],
                    'variantLabel': chipset + ' · ' + asset.get('precision', ''),
                    'precision': asset.get('precision', ''), 'runtimeRequirement': 'geniex-0.8.0',
                    'minAppVersion': '0.9.50.0', 'manifestVersion': 2,
                    'downloadUrl': approved.get('normalizedBundleUrl', '') if state == 'represented' else '',
                    'sha256': approved['sha256'] if state == 'represented' else '',
                    'sizeInBytes': approved['sizeBytes'] if state == 'represented' else 0,
                    'maxContextTokens': approved['contextTokens'] if state == 'represented' else 0,
                    'supportedAccelerators': ['npu'], 'capabilities': {'vision': 'MODEL_TAG_VLM' in model.get('tags', [])},
                    'upstreamRevision': UPSTREAM_SHA,
                    'sourceUrl': asset.get('download_url', ''), 'licenseUrl': info.get('license_url', ''),
                    'redistributionRestricted': info.get('restrict_model_sharing', False),
                    'coverageStatus': state, 'sourceChipset': chipset
                })
    if len({e['id'] for e in entries}) != len(entries):
        raise ValueError('Catalog identity collision')
    complete = all(c['status'] in STATES for c in coverage)
    result = {'schemaVersion': 2, 'revision': revision, 'models': entries,
              'source': {'upstreamCommit': UPSTREAM_SHA, 'retrievedAt': datetime.now(timezone.utc).isoformat(),
                         'inputDigest': hashlib.sha256(json.dumps(digests, sort_keys=True).encode()).hexdigest(),
                         'familyCount': len(models), 'artifactCount': len(coverage), 'complete': complete,
                         'registryVersion': manifest.get('version', ''), 'missingReleaseUrls': missing_release_urls},
              'coverage': coverage}
    if len(json.dumps(result).encode()) > 2_000_000:
        raise ValueError('App metadata limit exceeded; reviewed sharding is required')
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--baseline', type=Path, default=Path('model_catalog.json'))
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--revision', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.fetch:
        args.capture.mkdir(parents=True, exist_ok=True)
        fetch(REGISTRY, args.capture / 'manifest.json')
        source = load(args.capture / 'manifest.json')
        fetch(source['platform_url'], args.capture / 'platform.json')
        for model in source['models']:
            if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', model['id']):
                raise ValueError('Unsafe model ID')
            urls = model.get('manifest_urls', {})
            if urls.get('release_assets'):
                fetch(urls['release_assets'], args.capture / (model['id'] + '.json'))
            if urls.get('info'):
                fetch(urls['info'], args.capture / (model['id'] + '.info.json'))
    result = normalize(args.capture, load(args.baseline), load(args.inventory) if args.inventory else {}, args.revision)
    args.output.mkdir(parents=True, exist_ok=True)
    # Stage both projections; leave the published endpoints alone until review.
    (args.output / 'model_catalog_v2.json').write_text(json.dumps(result, indent=2) + '\n')
    projection = {'schemaVersion': 1, 'revision': result['revision'], 'models': [e for e in result['models'] if e.get('manifestVersion', 1) == 1]}
    (args.output / 'model_catalog.json').write_text(json.dumps(projection, indent=2) + '\n')
    counts = {state: sum(c['status'] == state for c in result['coverage']) for state in sorted(STATES)}
    print(json.dumps({'families': result['source']['familyCount'], 'artifacts': result['source']['artifactCount'], 'coverage': counts, 'inputDigest': result['source']['inputDigest']}, indent=2))

if __name__ == '__main__':
    main()
