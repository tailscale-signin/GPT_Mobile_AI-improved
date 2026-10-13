#!/usr/bin/env python3
"""Audit public archive inventories using bounded HTTP ranges, without downloading model weights.

The output proves archive metadata inspection only. It never supplies an invented
archive hash, runtime configuration, redistribution grant or device qualification.
"""
import argparse
import concurrent.futures
import io
import json
from pathlib import Path
import struct
from urllib.request import Request, urlopen
from urllib.parse import urlparse
import zipfile

class RangeReader(io.RawIOBase):
    def __init__(self, url):
        if urlparse(url).scheme != 'https' or urlparse(url).hostname != 'qaihub-public-assets.s3.us-west-2.amazonaws.com':
            raise ValueError('Unexpected artifact origin')
        self.url, self.position, self.transferred = url, 0, 0
        with urlopen(Request(url, method='HEAD'), timeout=30) as response:
            self.length = int(response.headers['Content-Length'])
        if not 0 < self.length <= 64_000_000_000:
            raise ValueError('Unsupported archive length')
    def seekable(self): return True
    def tell(self): return self.position
    def seek(self, offset, whence=0):
        self.position = offset if whence == 0 else self.position + offset if whence == 1 else self.length + offset
        if not 0 <= self.position <= self.length: raise ValueError('Invalid archive offset')
        return self.position
    def read(self, size=-1):
        size = self.length - self.position if size < 0 else min(size, self.length - self.position)
        if size == 0: return b''
        if size > 2 * 1024 * 1024 or self.transferred + size > 4 * 1024 * 1024: raise ValueError('Metadata read budget exceeded')
        start = self.position
        with urlopen(Request(self.url, headers={'Range': f'bytes={start}-{start + size - 1}'}), timeout=30) as response:
            if response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{start + size - 1}/{self.length}':
                raise ValueError('Server did not honor the exact bounded range')
            raw = response.read(size + 1)
        if len(raw) != size: raise ValueError('Range length mismatch')
        self.transferred += size
        self.position += size
        return raw

def inspect(item):
    result = dict(item, validation='unavailable', deviceQualification='not_run')
    try:
        stream = RangeReader(item['sourceUrl'])
        with zipfile.ZipFile(stream) as archive:
            files = archive.infolist()
            if len(files) > 1024: raise ValueError('Archive has too many entries')
            inventory = []
            for entry in files:
                path = Path(entry.filename)
                if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or ':' in entry.filename or ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                    raise ValueError('Unsafe archive entry')
                inventory.append({'path': entry.filename, 'sizeBytes': entry.file_size, 'compressedBytes': entry.compress_size, 'crc32': f'{entry.CRC:08x}'})
            if len({x['path'].lower() for x in inventory}) != len(inventory): raise ValueError('Duplicate archive entries')
            metadata = {}
            for entry in files:
                if Path(entry.filename).name in {'metadata.json', 'genie_config.json'} and entry.file_size <= 256 * 1024:
                    metadata[entry.filename] = json.loads(archive.read(entry))
            result.update(validation='archive_metadata_inspected', archiveBytes=stream.length, files=inventory, metadata=metadata,
                          missing=['archive_sha256', 'all_asset_sha256', 'redistribution_review', 'device_qualification'])
    except Exception as error:
        result['reason'] = type(error).__name__ + ': ' + str(error)[:160]
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.capture / 'manifest.json').read_text())
    rows = []
    for model in manifest['models']:
        path = args.capture / (model['id'] + '.json')
        if not path.exists(): continue
        for asset in json.loads(path.read_text())['assets']:
            if asset.get('runtime') in {'RUNTIME_GENIE', 'RUNTIME_GENIEX_QAIRT'}:
                rows.append({'familyId': model['id'], 'runtime': asset['runtime'], 'chipset': asset.get('chipset'), 'precision': asset.get('precision'), 'sourceUrl': asset['download_url']})
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(inspect, rows))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'schema': 1, 'registryVersion': manifest['version'], 'artifacts': results}, indent=2) + '\n')
    print(json.dumps({'artifacts': len(results), 'inspected': sum(r['validation'] == 'archive_metadata_inspected' for r in results), 'fullyQualified': 0}))

if __name__ == '__main__': main()
