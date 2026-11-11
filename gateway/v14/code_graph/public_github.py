"""Anonymous GitHub reads for public repository maps."""
import json
import tempfile
import time
import zipfile
from contextlib import ExitStack
from functools import lru_cache
from urllib.parse import quote

import requests

from v14.engineering_backend import GitHubEngineeringBackend, blob_sha
from .extract import MAX_FILE_BYTES

API = 'https://api.github.com'
MAX_ARCHIVE_BYTES = 128 * 1024 * 1024


class PublicRepositoryUnavailable(Exception):
    """The selected repository could not be verified as public."""


class PublicGitHubRateLimited(Exception):
    """GitHub throttled an anonymous request."""


def anonymous_get(url, *, headers=None):
    # An explicit no-op auth prevents requests from loading ~/.netrc.
    options = dict(auth=lambda request: request, headers=headers, allow_redirects=False, stream=True, timeout=(5, 12))
    try:
        return requests.get(url, **options)
    except (requests.ConnectionError, requests.Timeout):
        return requests.get(url, **options)


def require_ok(response):
    if response.status_code == 429 or (response.status_code == 403 and (
            response.headers.get('X-RateLimit-Remaining') == '0' or 'Retry-After' in response.headers)):
        raise PublicGitHubRateLimited()
    response.raise_for_status()
    if response.status_code != 200:
        raise ValueError('Unexpected public GitHub response')


def bounded_chunks(response, maximum, seconds):
    deadline = time.monotonic() + seconds
    total = 0
    for chunk in response.iter_content(64 * 1024):
        total += len(chunk)
        if total > maximum or time.monotonic() > deadline:
            raise ValueError('Public GitHub download exceeded its size or time limit')
        yield chunk


class PublicGitHubReader:
    def __init__(self):
        self._archive = ExitStack()
        self._source = None
        self._revision = None

    def close(self):
        self._archive.close()
        self._revision = None
        self._source = None

    def api(self, path, job=None):
        if job is not None or not path.startswith('/repos/'):
            raise PermissionError('Public maps only read GitHub repositories')
        with anonymous_get(API + path, headers={'Accept': 'application/vnd.github+json',
                           'X-GitHub-Api-Version': '2022-11-28', 'Cache-Control': 'no-cache'}) as response:
            require_ok(response)
            return json.loads(b''.join(bounded_chunks(response, 8 * 1024 * 1024, 20)))

    def authorize(self, root, repository, branch, sha):
        try:
            metadata = self.api(root)
        except requests.HTTPError as error:
            if error.response is not None and error.response.status_code in {401, 404}:
                raise PublicRepositoryUnavailable() from None
            raise
        if (metadata.get('private') is not False or metadata.get('visibility', 'public') != 'public' or
                metadata.get('full_name', '').lower() != repository.lower()):
            raise PublicRepositoryUnavailable()
        head = self.api(root + '/git/ref/heads/' + quote(branch, safe=''))['object']['sha']
        if head != sha:
            self.verify_commit(root, sha)
        return head

    @lru_cache(maxsize=32)
    def verify_commit(self, root, sha):
        if self.api(root + '/commits/' + sha).get('sha') != sha:
            raise ValueError('Graph source revision changed')

    def paged(self, path, key=None, maximum=1000):
        return GitHubEngineeringBackend.paged(self, path, key=key, maximum=maximum)

    def prepare_sources(self, repository, sha):
        # Called under the graph index lock. ZIP members are read in place,
        # never extracted, and are checked against the pinned Git tree.
        revision = (repository.lower(), sha)
        if self._revision == revision:
            return self._source is not None
        self.close()
        with ExitStack() as stack:
            try:
                archive_file = stack.enter_context(tempfile.TemporaryFile())
                with anonymous_get(f'https://codeload.github.com/{repository}/zip/{sha}') as response:
                    require_ok(response)
                    for chunk in bounded_chunks(response, MAX_ARCHIVE_BYTES, 45):
                        archive_file.write(chunk)
                archive = stack.enter_context(zipfile.ZipFile(archive_file))
                root = archive.infolist()[0].filename.split('/', 1)[0]
                self._source = (archive, root)
                self._archive = stack.pop_all()
            except (IndexError, OSError, requests.RequestException, ValueError, zipfile.BadZipFile):
                # Oversize or unavailable archives can still use pinned raw files.
                pass
        self._revision = revision
        return self._source is not None

    def source(self, repository, sha, path, expected_blob):
        if self._source:
            archive, root = self._source
            try:
                member = archive.getinfo(root + '/' + path)
                if not member.is_dir() and member.file_size <= MAX_FILE_BYTES:
                    with archive.open(member) as stream:
                        raw = stream.read(MAX_FILE_BYTES + 1)
                    if len(raw) <= MAX_FILE_BYTES and blob_sha(raw) == expected_blob:
                        return raw
            except (KeyError, OSError, RuntimeError, zipfile.BadZipFile):
                pass
        # Archives may omit export-ignored files or substitute LFS objects.
        with anonymous_get(f'https://raw.githubusercontent.com/{repository}/{sha}/' + quote(path, safe='/')) as response:
            require_ok(response)
            return b''.join(bounded_chunks(response, MAX_FILE_BYTES, 15))
