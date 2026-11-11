"""TLS-authenticated official release metadata, digest verification and bounded extraction."""
import hashlib
import json
import re
import shutil
import stat
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit
import requests

REPOSITORY = 'tailscale-signin/GPT_Mobile_AI-improved'
RELEASES_URL = f'https://api.github.com/repos/{REPOSITORY}/releases?per_page=30'
MAX_DOWNLOAD = 512 * 1024 * 1024
MAX_EXPANDED = 1536 * 1024 * 1024


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value):
        raise ValueError('Expected a stable numeric version')
    return tuple(map(int, value.split('.')))


def choose(releases, current):
    candidates = []
    for release in releases:
        tag = release.get('tag_name', '')
        if release.get('draft') or release.get('prerelease') or not tag.startswith('gateway-v'):
            continue
        try:
            number = tag.removeprefix('gateway-v')
            if version(number) <= version(current) or version(number)[0] not in {15, 16}:
                continue
        except ValueError:
            continue
        expected = f'Gateway-{number}-windows-x64.zip'
        for asset in release.get('assets', []):
            digest = asset.get('digest') or ''
            url = asset.get('browser_download_url', '')
            if asset.get('name') != expected or not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
                continue
            if url != f'https://github.com/{REPOSITORY}/releases/download/{tag}/{expected}':
                continue
            if type(asset.get('size')) is not int or not 0 < asset['size'] <= MAX_DOWNLOAD:
                continue
            candidates.append(dict(version=number, url=url, sha256=digest[7:], size=asset['size']))
    return max(candidates, key=lambda x: version(x['version']), default=None)


def check(current, session=None):
    session = session or requests.Session()
    session.trust_env = False
    with session.get(RELEASES_URL, timeout=(10, 20), headers={'Accept': 'application/vnd.github+json'}, stream=True, allow_redirects=False) as response:
        response.raise_for_status()
        data = bytearray()
        for chunk in response.iter_content(65536):
            data.extend(chunk)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('Release metadata exceeds limit')
    parsed = json.loads(data)
    if not isinstance(parsed, list):
        raise ValueError('Invalid release metadata')
    return choose(parsed, current)


def download(candidate, target, session=None):
    session = session or requests.Session()
    session.trust_env = False
    # Revalidate the exact repository URL before any network access.
    expected = f'https://github.com/{REPOSITORY}/releases/download/gateway-v{candidate["version"]}/Gateway-{candidate["version"]}-windows-x64.zip'
    if candidate['url'] != expected or not re.fullmatch('[a-f0-9]{64}', candidate['sha256']):
        raise ValueError('Invalid update destination or digest')
    hasher, size = hashlib.sha256(), 0
    try:
        url = expected
        for _ in range(6):
            response = session.get(url, timeout=(10, 30), stream=True, allow_redirects=False)
            if response.status_code not in {301, 302, 303, 307, 308}:
                break
            from urllib.parse import urljoin
            redirected = urljoin(url, response.headers.get('Location', ''))
            response.close()
            parsed = urlsplit(redirected)
            if parsed.scheme != 'https' or parsed.hostname not in {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'} or parsed.username or parsed.password or parsed.port not in {None, 443}:
                raise ValueError('Untrusted download redirect')
            url = redirected
        else:
            raise ValueError('Too many download redirects')
        with response:
            response.raise_for_status()
            final = urlsplit(response.url)
            if final.scheme != 'https' or final.hostname not in {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}:
                raise ValueError('Untrusted download redirect')
            with Path(target).open('xb') as stream:
                for chunk in response.iter_content(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_DOWNLOAD or size > candidate['size']:
                        raise ValueError('Update exceeds declared size')
                    hasher.update(chunk)
                    stream.write(chunk)
        if size != candidate['size'] or hasher.hexdigest() != candidate['sha256']:
            raise ValueError('Update size or SHA-256 mismatch')
    except BaseException:
        Path(target).unlink(missing_ok=True)
        raise


def extract(archive, destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError('Update stage must be new')
    destination.mkdir(parents=True)
    seen, expanded = set(), 0
    try:
        with zipfile.ZipFile(archive) as zipped:
            if len(zipped.infolist()) > 15000:
                raise ValueError('Too many archive members')
            for item in zipped.infolist():
                name = item.orig_filename
                path = PurePosixPath(name)
                # Windows names are case insensitive; reject ADS, links and device names.
                if path.is_absolute() or '\\' in name or ':' in name or any(p in {'', '.', '..'} or p.endswith((' ', '.')) or re.fullmatch(r'(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', p) for p in name.rstrip('/').split('/')):
                    raise ValueError('Unsafe archive path')
                if stat.S_ISLNK(item.external_attr >> 16) or name.casefold() in seen or item.flag_bits & 1:
                    raise ValueError('Duplicate, linked or encrypted archive entry')
                seen.add(name.casefold())
                expanded += item.file_size
                if expanded > MAX_EXPANDED:
                    raise ValueError('Expanded update exceeds limit')
                target = destination.joinpath(*path.parts)
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with zipped.open(item) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output)
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def validate_release(path, expected_version=None):
    path = Path(path).resolve()
    manifest = json.loads((path / 'release.json').read_text(encoding='utf-8'))
    number = manifest.get('version')
    version(number)
    if expected_version and number != expected_version or manifest.get('stateSchema') != 1 or manifest.get('bootstrapProtocol') != 1:
        raise ValueError('Incompatible release/state/bootstrap schema')
    files = manifest.get('files')
    if not isinstance(files, dict) or 'Gateway.exe' not in files or len(files) > 15000:
        raise ValueError('Incomplete runtime manifest')
    actual = {str(p.relative_to(path)).replace('\\', '/') for p in path.rglob('*') if p.is_file() and p != path / 'release.json'}
    if actual != set(files):
        raise ValueError('Unexpected or missing runtime files')
    if any(p.is_symlink() for p in path.rglob('*')):
        raise ValueError('Linked release content')
    for name, digest in files.items():
        if not isinstance(name, str) or '\\' in name or ':' in name or any(p in {'', '.', '..'} for p in name.split('/')):
            raise ValueError('Unsafe runtime path')
        target = path / name
        if not target.resolve().is_relative_to(path) or target.is_symlink() or not re.fullmatch('[a-f0-9]{64}', str(digest)) or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise ValueError('Runtime integrity mismatch: ' + name)
    return manifest
