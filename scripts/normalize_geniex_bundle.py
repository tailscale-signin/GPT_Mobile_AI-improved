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
import struct
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


def gguf_metadata(path):
    """Read bounded GGUF v2/v3 metadata, never tensor weights or filename guesses."""
    with Path(path).open('rb') as stream:
        def read(size):
            if size < 0 or size > 1_048_576 or stream.tell() + size > 8_388_608:
                raise ValueError('GGUF metadata budget exceeded')
            raw = stream.read(size)
            if len(raw) != size: raise ValueError('Truncated GGUF header')
            return raw
        def number(fmt): return struct.unpack('<' + fmt, read(struct.calcsize(fmt)))[0]
        def text(): return read(number('Q')).decode('utf-8')
        scalar = {0:'B', 1:'b', 2:'H', 3:'h', 4:'I', 5:'i', 6:'f', 7:'?', 10:'Q', 11:'q', 12:'d'}
        def value(kind, depth=0):
            if depth > 1: raise ValueError('Nested GGUF arrays are unsupported')
            if kind in scalar: return number(scalar[kind])
            if kind == 8: return text()
            if kind == 9:
                item_kind, count = number('I'), number('Q')
                if count > 300_000: raise ValueError('Oversized GGUF metadata array')
                for _ in range(count): value(item_kind, depth + 1)
                return None
            raise ValueError('Unknown GGUF metadata type')
        if read(4) != b'GGUF' or number('I') not in (2, 3): raise ValueError('Invalid GGUF format/version')
        tensors, count = number('Q'), number('Q')
        if not 0 < tensors <= 100_000 or count > 4096: raise ValueError('Invalid GGUF header counts')
        result = {}
        for _ in range(count):
            key = text()
            if key in result: raise ValueError('Duplicate GGUF metadata key')
            result[key] = value(number('I'))
        return result


def normalize_gguf(root, artifact, family, entry, version, projector=None):
    """Stage explicit CPU/GPU packages. Hashes establish identity, not execution qualification."""
    root = Path(root).resolve()
    def checked(name):
        path = root / name
        if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink() or not path.is_file() or path.resolve().parent != root or path.suffix.lower() != '.gguf':
            raise ValueError('GGUF assets must be regular files at the package root')
        return path
    first = checked(entry)
    metadata = gguf_metadata(first)
    architecture = metadata.get('general.architecture')
    context = metadata.get(str(architecture) + '.context_length')
    if not isinstance(architecture, str) or not architecture or not isinstance(context, int) or not 0 < context <= 4_194_304:
        raise ValueError('GGUF architecture/context metadata is unresolved')
    names = [entry]
    shard = re.fullmatch(r'(.*)-00001-of-([0-9]{5})\.gguf', entry, re.IGNORECASE)
    if shard:
        count = int(shard[2])
        if not 1 <= count <= 255 or metadata.get('split.count') != count: raise ValueError('Inconsistent GGUF shard count')
        names = [f'{shard[1]}-{i:05d}-of-{count:05d}.gguf' for i in range(1, count + 1)]
        for index, name in enumerate(names):
            values = gguf_metadata(checked(name))
            if values.get('split.no') != index or values.get('split.count') != count or values.get('general.architecture') != architecture:
                raise ValueError('Inconsistent or missing GGUF shard')
    elif metadata.get('split.count', 1) != 1:
        raise ValueError('Select the first GGUF shard')
    if projector:
        if projector in names: raise ValueError('Projector must be a distinct asset')
        gguf_metadata(checked(projector))
        names.append(projector)
    inventory = [{'path': name, 'sizeBytes': checked(name).stat().st_size, 'sha256': sha256(checked(name))} for name in names]
    if any(not 0 < x['sizeBytes'] <= 16_000_000_000 for x in inventory) or sum(x['sizeBytes'] for x in inventory) > 32_000_000_000:
        raise ValueError('GGUF bundle exceeds installation limits')
    return {'schemaVersion': 2, 'familyId': family, 'artifactId': artifact, 'runtime': 'GENIEX_LLAMA_CPP', 'format': 'gguf', 'entryFile': entry, 'files': inventory, 'contextTokens': context, 'architecture': architecture, 'supportedBackends': ['cpu', 'gpu'], 'runtimeVersion': version, 'projectorFile': projector, 'vision': projector is not None, 'evidence': 'DISCOVERED', 'validations': []}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--artifact-id', required=True)
    parser.add_argument('--family-id', required=True)
    parser.add_argument('--soc')
    parser.add_argument('--gguf-entry', help='Explicit first GGUF file; CPU/GPU only, metadata and shards are checked')
    parser.add_argument('--projector', help='Explicit user-reviewed projector pairing; never chosen by filename proximity')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.directory.resolve() in args.output.resolve().parents:
        raise ValueError('Output must be outside the input directory')
    version = tomllib.loads(Path('gradle/libs.versions.toml').read_text())['versions']['geniex']
    manifest = normalize_gguf(args.directory, args.artifact_id, args.family_id, args.gguf_entry, version, args.projector) if args.gguf_entry else normalize(args.directory, args.artifact_id, args.family_id, args.soc or '', version)
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
