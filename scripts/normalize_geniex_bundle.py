#!/usr/bin/env python3
"""Package a reviewed SDK export as a hashed, data-only .localmodel archive.

metadata.json alone is discovery metadata. This normalizer requires the SDK's
resolved genie_config.json; it never guesses compiled context or shard order.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tomllib
import zipfile

ALLOWED = {'.json', '.bin', '.gguf', '.raw', '.npy', '.txt', '.model', '.tiktoken', '.dat'}

def sha256(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def normalize(root, artifact, family, soc, version):
    root = Path(root).resolve()
    config_path = root / 'genie_config.json'
    if not config_path.is_file():
        raise ValueError('Export resolved genie_config.json with the pinned SDK first; metadata-only layouts cannot be guessed safely')
    config = json.loads(config_path.read_text())
    dialog = config['dialog']
    context = dialog['context']['size']
    tokenizer = dialog['tokenizer']['path']
    model = dialog['engine']['model']
    if model['type'] != 'binary' or not re.fullmatch(r'SM[0-9]{4}', soc) or not isinstance(context, int) or context <= 0:
        raise ValueError('Invalid compiled model context or exact chipset')
    inventory = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symbolic links are not model assets')
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if path.suffix.lower() not in ALLOWED or relative == 'manifest.json' or ':' in relative or '\\' in relative:
            raise ValueError('Unreviewed or executable bundle asset: ' + relative)
        size = path.stat().st_size
        if not 0 < size <= 16_000_000_000:
            raise ValueError('Invalid asset size')
        inventory.append({'path': relative, 'sizeBytes': size, 'sha256': sha256(path)})
    names = {entry['path'] for entry in inventory}
    shards = model['binary']['ctx-bins']
    if not 1 <= len(inventory) <= 256 or sum(i['sizeBytes'] for i in inventory) > 32_000_000_000 or tokenizer not in names or not shards or any(s not in names for s in shards):
        raise ValueError('Incomplete or oversized asset inventory')
    def references(node, key=''):
        if isinstance(node, dict):
            for k, v in node.items(): references(v, k)
        elif isinstance(node, list):
            for v in node: references(v, key)
        elif isinstance(node, str) and (any(token in key.lower().replace('-', '_').split('_') for token in ['path', 'file', 'filename', 'library', 'directory']) or key in {'extensions', 'ctx-bins', 'model-bin', 'forecast-prefix-name'} or any(c in node for c in '/\\:.')):
            if node not in names:
                raise ValueError('Undeclared configuration asset: ' + key)
    references(config)
    metadata = json.loads((root / 'metadata.json').read_text()) if (root / 'metadata.json').exists() else {}
    return {'schemaVersion': 2, 'familyId': family, 'artifactId': artifact, 'runtime': 'GENIEX_QAIRT', 'format': 'qairt_context', 'entryFile': 'genie_config.json', 'files': inventory, 'contextTokens': context, 'supportedBackends': ['npu'], 'runtimeVersion': version, 'soc': soc, 'tokenizerFile': tokenizer, 'weightPrecision': metadata.get('precision'), 'vision': metadata.get('genie', {}).get('supports_vision') is True, 'evidence': 'DISCOVERED', 'validations': []}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--artifact-id', required=True)
    parser.add_argument('--family-id', required=True)
    parser.add_argument('--soc', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.directory.resolve() in args.output.resolve().parents:
        raise ValueError('Output must be outside the input directory')
    version = tomllib.loads(Path('gradle/libs.versions.toml').read_text())['versions']['geniex']
    manifest = normalize(args.directory, args.artifact_id, args.family_id, args.soc, version)
    staging = args.output.with_suffix(args.output.suffix + '.tmp')
    try:
        with zipfile.ZipFile(staging, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            archive.writestr('manifest.json', json.dumps(manifest))
            for item in manifest['files']:
                archive.write(args.directory / item['path'], item['path'])
        staging.replace(args.output)
    finally:
        staging.unlink(missing_ok=True)
    print(json.dumps({'artifact': args.artifact_id, 'archiveSha256': sha256(args.output), 'archiveBytes': args.output.stat().st_size, 'assets': len(manifest['files']), 'deviceQualification': 'not established'}))

if __name__ == '__main__':
    main()
