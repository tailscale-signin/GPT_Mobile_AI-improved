"""Read exact-revision debug JVM call evidence from the repository's validation workflow."""
from collections import OrderedDict, defaultdict, deque
import hashlib
import io
import json
import re
import threading
import zipfile

import requests

from .engine import is_test
from .jvm_index_limits import ARCHIVE_LIMIT, JSON_LIMIT

SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'[0-9a-f]{64}')
QUALIFICATION = 'Debug main and unit-test bytecode. Virtual calls target the declared method. Reflection, invokedynamic, inlining and uncompiled variants can omit paths.'


class Unavailable(ValueError):
    pass


def positive(value):
    return type(value) is int and value > 0


def numbered(value, maximum):
    return type(value) is int and 0 <= value < maximum


def unpack(raw, receipt):
    if len(raw) > ARCHIVE_LIMIT or hashlib.sha256(raw).hexdigest() != receipt:
        raise Unavailable('ARCHIVE_DIGEST_MISMATCH')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        if (len(entries) != 1 or entries[0].filename != 'jvm-call-index.json'
                or entries[0].file_size > JSON_LIMIT or entries[0].flag_bits & 1
                or entries[0].compress_type not in {zipfile.ZIP_DEFLATED, zipfile.ZIP_STORED}):
            raise Unavailable('INVALID_INDEX_ARCHIVE')
        with archive.open(entries[0]) as stream:
            return json.load(stream)


class Index:
    def __init__(self, data, snapshot):
        files, methods, calls = (data[key] for key in ('files', 'methods', 'calls'))
        if (type(data['schema']) is not int or data['schema'] != 1 or not isinstance(files, list) or len(files) > 16000
                or not isinstance(methods, list) or len(methods) > 350000 or not isinstance(calls, list) or len(calls) > 1200000):
            raise Unavailable('INVALID_INDEX_BOUNDS')
        self.files = files
        self.methods = methods
        self.by_key = {}
        self.by_path = defaultdict(list)
        self.incoming = defaultdict(list)
        visible = set()
        for i, (path, blob, lines) in enumerate(files):
            if not isinstance(path, str) or not SHA.fullmatch(blob) or not positive(lines):
                raise Unavailable('INVALID_INDEX_SOURCE')
            current = snapshot['manifest'].get(path)
            if current and current['sha'] != blob:
                raise Unavailable('INDEX_SOURCE_BLOB_MISMATCH')
            if current:
                visible.add(i)
        for i, (key, file, line, fingerprint) in enumerate(methods):
            if (not isinstance(key, str) or not 1 < len(key) <= 8192 or '(' not in key or not numbered(file, len(files))
                    or not numbered(line, files[file][2] + 1) or not DIGEST.fullmatch(fingerprint) or key in self.by_key):
                raise Unavailable('INVALID_INDEX_METHOD')
            if file in visible:
                self.by_key[key] = i
                self.by_path[files[file][0]].append(i)
        for caller, target, line in calls:
            if (not numbered(caller, len(methods)) or not numbered(target, len(methods))
                    or not numbered(line, files[methods[caller][1]][2] + 1)):
                raise Unavailable('INVALID_INDEX_CALL')
            if methods[caller][1] in visible and methods[target][1] in visible:
                self.incoming[target].append((caller, line))

    def seeds(self, paths, symbol):
        seeds = {i for path in paths for i in self.by_path.get(path, ())}
        if not symbol:
            return seeds
        rows = self.by_path.get(symbol['path'], ())
        if symbol['kind'] == 'file':
            return seeds.union(rows)
        # Names alone conflate overloads and nested declarations. Bind to both the source
        # declaration range and the method's emitted JVM name, else suggest a descriptor.
        selected = [i for i in rows if symbol['line'] <= self.methods[i][2] <= symbol['end_line']
                    and self.methods[i][0].split('(', 1)[0].rsplit('.', 1)[-1] == symbol['name']]
        if len(selected) != 1:
            raise Unavailable('METHOD_MAPPING_AMBIGUOUS' if selected else 'NO_COMPILER_METHOD_FOR_SYMBOL')
        return seeds.union(selected)

    def describe(self, i):
        key, file, line, _ = self.methods[i]
        path, blob, _ = self.files[file]
        return {'symbol': key, 'path': path, 'line': line, 'blob_sha': blob}

    def impact(self, seeds, depth, limit):
        if not seeds:
            raise Unavailable('NO_MATCHING_COMPILED_METHODS')
        parents = {i: None for i in seeds}
        queue = deque((i, 0) for i in sorted(seeds))
        callers, tests = [], []
        reported_seeds = set()
        visited_at_limit = False
        omitted = False
        while queue:
            target, distance = queue.popleft()
            if distance >= depth:
                continue
            for caller, line in self.incoming.get(target, ()):
                if caller in parents:
                    # A caller or test changed in the same revision can itself be a seed.
                    # Preserve its call to a different seed, without enqueuing a cycle.
                    if caller not in seeds or caller in reported_seeds or caller == target:
                        continue
                else:
                    if len(parents) >= len(seeds) + 2500:
                        visited_at_limit = True
                        queue.clear()
                        break
                    parents[caller] = target
                    queue.append((caller, distance + 1))
                key, file, _, _ = self.methods[caller]
                path, blob, _ = self.files[file]
                target_list = tests if is_test(path) else callers
                if len(target_list) >= limit:
                    omitted = True
                    continue
                chain = [key]
                current = target
                while current is not None:
                    chain.append(self.methods[current][0])
                    current = parents[current]
                if caller in seeds:
                    if key == chain[-1]:
                        continue
                    reported_seeds.add(caller)
                target_list.append({'path': path, 'blob_sha': blob, 'line': line, 'chain': chain})
        return {'status': 'COMPILER_BACKED', 'confidence': 'JVM_INVOKE_INSTRUCTION', 'selected_methods': len(seeds),
                'methods': [self.describe(i) for i in sorted(seeds)[:limit]], 'callers': callers, 'tests': tests, 'truncated': visited_at_limit or omitted, 'max_depth': depth,
                'test_execution': 'NOT_RUN', 'qualification': QUALIFICATION}


class CompilerEvidence:
    def __init__(self, backend):
        self.backend = backend
        self.cache = OrderedDict()
        self.lock = threading.Lock()

    def load(self, namespace, snapshot):
        repository, sha = snapshot['repository'], snapshot['sha']
        if not hasattr(self.backend, 'download_graph_artifact'):
            raise Unavailable('CONNECTED_ACTIONS_READ_REQUIRED')
        root = '/repos/' + repository
        name = 'jvm-call-index-' + sha
        listing = self.backend.api(root + '/actions/artifacts?name=' + name + '&per_page=10')
        for artifact in listing['artifacts']:
            if artifact.get('name') != name or artifact.get('expired') is not False:
                continue
            receipt = artifact.get('digest') or ''
            artifact_id = artifact.get('id')
            run_id = (artifact.get('workflow_run') or {}).get('id')
            if (not positive(artifact_id) or not positive(run_id) or not re.fullmatch(r'sha256:[0-9a-f]{64}', receipt)
                    or not positive(artifact.get('size_in_bytes')) or artifact['size_in_bytes'] > ARCHIVE_LIMIT):
                continue
            run = self.backend.api(root + '/actions/runs/' + str(run_id))
            if (run.get('id') != run_id or run.get('path') != '.github/workflows/pr-validation.yml'
                    or run.get('status') != 'completed' or run.get('conclusion') != 'success'
                    or not positive(run.get('run_attempt')) or run['repository']['full_name'].lower() != repository.lower()
                    or run['head_repository']['full_name'].lower() != repository.lower()):
                continue
            key = (namespace, sha, artifact_id, receipt, run_id, run['run_attempt'])
            provenance = {'source_sha': sha, 'artifact_id': artifact_id, 'run_id': run_id, 'run_attempt': run['run_attempt']}
            with self.lock:
                if key in self.cache:
                    self.cache.move_to_end(key)
                    return self.cache[key], provenance
            raw = self.backend.download_graph_artifact(repository, artifact_id, ARCHIVE_LIMIT)
            data = unpack(raw, receipt[7:])
            analyzed = data['analyzed_sha']
            if (data['commit_sha'] != sha or str(run_id) != data['run_id'] or str(run['run_attempt']) != data['run_attempt']
                    or not SHA.fullmatch(analyzed) or not SHA.fullmatch(data['tree_sha'])):
                continue
            commit = self.backend.api(root + '/git/commits/' + analyzed)
            if commit['sha'] != analyzed or commit['tree']['sha'] != data['tree_sha']:
                continue
            # A PR workflow can compile a synthetic merge. Bind it to a PR head only
            # if both Git trees are identical and GitHub ties that commit to this run.
            parents = {row['sha'] for row in commit.get('parents', [])}
            if sha == analyzed:
                if run['head_sha'] != sha:
                    continue
            else:
                head = run['head_sha']
                pr_heads = {pr.get('head', {}).get('sha') for pr in run.get('pull_requests', [])}
                if sha not in parents or not (head == sha or (head == analyzed and sha in pr_heads)):
                    continue
                selected = self.backend.api(root + '/git/commits/' + sha)
                if selected.get('sha') != sha or selected['tree']['sha'] != data['tree_sha']:
                    continue
            index = Index(data, snapshot)
            with self.lock:
                self.cache[key] = index
                while len(self.cache) > 2:
                    self.cache.popitem(last=False)
            return index, provenance
        raise Unavailable('NO_QUALIFIED_WORKFLOW_ARTIFACT')

    def impact(self, namespace, snapshot, paths, symbol, depth, limit, previous=None, descriptor=None):
        try:
            index, receipt = self.load(namespace, snapshot)
            change_info = {}
            if previous:
                before, base_receipt = self.load(namespace, previous)
                seeds = {i for key, i in index.by_key.items()
                         if key not in before.by_key or index.methods[i][3] != before.methods[before.by_key[key]][3]}
                removed = sorted(before.by_key.keys() - index.by_key.keys())
                added = sum(1 for i in seeds if index.methods[i][0] not in before.by_key)
                change_info = {'base_sha': previous['sha'], 'base_artifact_id': base_receipt['artifact_id'],
                               'added_method_count': added, 'modified_method_count': len(seeds) - added,
                               'removed_method_count': len(removed)}
                if removed:
                    prior = before.impact({before.by_key[key] for key in removed}, depth, min(limit, 4))
                    change_info.update(removed_methods=prior['methods'], base_callers=prior['callers'],
                                       base_tests=prior['tests'], base_truncated=prior['truncated'])
                if not seeds:
                    return dict(receipt, **change_info, status='NO_CHANGED_HEAD_METHODS',
                                confidence='METHOD_BYTECODE_FINGERPRINT', test_execution='NOT_RUN')
            elif descriptor:
                if descriptor not in index.by_key:
                    raise Unavailable('JVM_DESCRIPTOR_NOT_FOUND')
                seeds = {index.by_key[descriptor]}
            else:
                seeds = index.seeds(paths, symbol)
            return dict(index.impact(seeds, depth, min(limit, 6)), **receipt, **change_info)
        except InterruptedError:
            raise
        except Unavailable as error:
            reason = str(error)
        except (requests.RequestException, TimeoutError, ValueError, TypeError, KeyError, IndexError,
                AttributeError, zipfile.BadZipFile, NotImplementedError, RecursionError):
            reason = 'COMPILER_EVIDENCE_UNAVAILABLE'
        return {'status': 'LEXICAL_FALLBACK', 'confidence': 'SOURCE_CANDIDATE', 'reason': reason,
                'source_sha': snapshot['sha'], 'test_execution': 'NOT_RUN'}
