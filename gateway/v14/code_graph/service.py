"""Repository-authorized graph operations using the existing GitHub connection."""
import base64
import concurrent.futures
import json
import os
import re
import subprocess
import threading
import time
import requests
from pathlib import Path
from urllib.parse import quote

from . import EXTRACTOR_VERSION, SCHEMA_VERSION
from .engine import Graph, GraphStore, bounded, encoded, scope, import_matches
from .extract import extract, identity, permitted, MAX_FILE_BYTES
from v14.engineering_backend import blob_sha
from v14.memory import local_model_destination

ACTIONS = frozenset({'index', 'status', 'query', 'node', 'callers', 'callees', 'path', 'impact', 'architecture',
                     'diff', 'diagnose', 'contracts', 'prs', 'note', 'notes', 'requirements', 'wiki', 'metrics',
                     'federated', 'provider'})


def validate(request):
    if not isinstance(request, dict) or request.get('action') not in ACTIONS:
        raise ValueError('Unknown graph operation')
    if len(encoded(request).encode()) > 64000:
        raise ValueError('Graph request exceeds its bound')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', request.get('repository', '')):
        raise ValueError('Select an explicit repository')
    if not isinstance(request.get('branch'), str) or not 1 <= len(request['branch']) <= 200 or any(ord(c) < 32 for c in request['branch']):
        raise ValueError('Select an explicit branch')
    if not re.fullmatch('[a-f0-9]{40}', request.get('commit_sha', '')):
        raise ValueError('An observed full commit SHA is required')
    for name, default, low, high in [('limit', 30, 1, 100), ('depth', 2, 0, 6), ('budget_bytes', 16000, 2048, 64000)]:
        value = request.get(name, default)
        if type(value) is not int or not low <= value <= high:
            raise ValueError('Invalid graph result bound')
    for name in ('query', 'symbol', 'source', 'target', 'producer', 'consumer', 'base_sha'):
        if name in request and (not isinstance(request[name], str) or len(request[name]) > 2000):
            raise ValueError('Invalid graph selector')
    if 'paths' in request and (not isinstance(request['paths'], list) or len(request['paths']) > 100 or any(not isinstance(p, str) or len(p) > 400 for p in request['paths'])):
        raise ValueError('Invalid impact paths')
    return request


class GraphService:
    def __init__(self, store, backend, evidence=None):
        self.store, self.backend, self.evidence = GraphStore(store), backend, evidence
        self.lock = threading.RLock()
        self.admission = threading.BoundedSemaphore(2)

    def authorize(self, principal, request):
        # Recheck both identity and repository access before reading any private cache.
        actor = self.backend.api('/user')['id']
        root = '/repos/' + request['repository']
        metadata = self.backend.api(root)
        if metadata.get('full_name', '').lower() != request['repository'].lower():
            raise PermissionError('Repository identity changed')
        if metadata.get('permissions', {}).get('pull') is False:
            raise PermissionError('Repository read access is required')
        commit = self.backend.api(root + '/commits/' + quote(request['commit_sha'], safe=''))
        if commit.get('sha') != request['commit_sha']:
            raise ValueError('Graph source revision changed')
        head = self.backend.api(root + '/git/ref/heads/' + quote(request['branch'], safe=''))['object']['sha']
        return scope(principal, actor, request['repository'], request['branch']), root, head, actor

    def snapshot(self, namespace, request, root):
        previous = self.store.load(namespace, request['commit_sha'])
        if previous:
            return previous
        tree = self.backend.api(root + '/git/trees/' + request['commit_sha'] + '?recursive=1')
        if tree.get('truncated') is not False:
            raise ValueError('Repository tree is incomplete; graph indexing did not start')
        manifest = {item['path']: {'sha': item['sha'], 'size': item.get('size', 0)} for item in tree['tree']
                    if item.get('type') == 'blob' and item.get('mode') in {'100644', '100755'} and permitted(item['path'])}
        if len(manifest) > 8000:
            raise ValueError('Repository exceeds the 8000-file graph limit')
        prior = self.store.load(namespace)
        fragments = {}
        if prior:
            fragments = {p: f for p, f in prior['fragments'].items() if p in manifest and
                         prior['manifest'][p]['sha'] == manifest[p]['sha'] and f.get('version') == EXTRACTOR_VERSION and
                         f.get('status') not in {'failed', 'unsupported'}}
        return {'schema_version': SCHEMA_VERSION, 'repository': request['repository'], 'branch': request['branch'],
                'sha': request['commit_sha'], 'manifest': manifest, 'fragments': fragments,
                'excluded_files': sum(i.get('type') == 'blob' for i in tree['tree']) - len(manifest),
                'index_metrics': {'cache_hits': len(fragments), 'parsed_files': 0, 'read_bytes': 0, 'index_ms': 0.0}}

    def index(self, namespace, request, root):
        snapshot = self.snapshot(namespace, request, root)
        snapshot.setdefault('index_metrics', {'cache_hits': len(snapshot['fragments']), 'parsed_files': 0, 'read_bytes': 0, 'index_ms': 0.0})
        if request.get('retry_failed') is True and not request.get('index_cursor'):
            snapshot['fragments'] = {p: f for p, f in snapshot['fragments'].items() if f['status'] not in {'unsupported', 'failed'}}
        pending = sorted(set(snapshot['manifest']) - snapshot['fragments'].keys())
        expected_cursor = identity(namespace, snapshot['sha'], *pending)
        if request.get('index_cursor') and request['index_cursor'] != expected_cursor:
            raise ValueError('Index cursor changed; inspect status before continuing')
        started = time.perf_counter()

        def read(path):
            entry = snapshot['manifest'][path]
            if entry.get('size', 0) > MAX_FILE_BYTES:
                return path, {'version': EXTRACTOR_VERSION, 'nodes': [], 'edges': [], 'references': [], 'imports': [], 'fields': [],
                              'status': 'unsupported', 'warnings': ['File exceeds the 256 KiB graph limit'], 'source_bytes': 0}
            blob = self.backend.api(root + '/git/blobs/' + entry['sha'])
            if blob.get('encoding') != 'base64' or blob.get('size', MAX_FILE_BYTES + 1) > MAX_FILE_BYTES:
                raise ValueError('Invalid graph source blob')
            raw = base64.b64decode(blob['content'].replace('\n', ''), validate=True)
            if len(raw) > MAX_FILE_BYTES:
                raise ValueError('Graph source blob exceeds its bound')
            try:
                content = raw.decode('utf-8')
            except UnicodeDecodeError:
                return path, {'version': EXTRACTOR_VERSION, 'nodes': [], 'edges': [], 'references': [], 'imports': [], 'fields': [],
                              'status': 'unsupported', 'warnings': ['Source is not UTF-8'], 'source_bytes': len(raw)}
            if blob_sha(content) != entry['sha']:
                raise ValueError('Graph source blob digest mismatch')
            return path, extract(path, content)

        def read_isolated(path):
            try:
                return read(path)
            except (PermissionError, InterruptedError):
                # Revocation and cancellation stop the operation, not just one file.
                raise
            except requests.HTTPError as error:
                if error.response is not None and error.response.status_code in {401, 403}:
                    raise
            except (requests.RequestException, ValueError, OSError):
                pass
            # Keep successful siblings and a bounded failure marker, never exception
            # bodies (which can contain source, credentials or signed URLs).
            return path, {'version': EXTRACTOR_VERSION, 'nodes': [], 'edges': [], 'references': [], 'imports': [], 'fields': [],
                          'status': 'failed', 'warnings': ['Source read or integrity validation failed; retry_failed is required'], 'source_bytes': 0}

        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for path, fragment in pool.map(read_isolated, pending[:24]):
                snapshot['fragments'][path] = fragment
                snapshot['index_metrics']['parsed_files'] += 1
                snapshot['index_metrics']['read_bytes'] += fragment['source_bytes']
        snapshot['index_metrics']['index_ms'] += round((time.perf_counter() - started) * 1000, 2)
        self.store.save(namespace, snapshot)
        remaining = sorted(set(snapshot['manifest']) - snapshot['fragments'].keys())
        return snapshot, {'has_more': bool(remaining), 'index_cursor': identity(namespace, snapshot['sha'], *remaining) if remaining else None,
                          'processed_files': len(snapshot['fragments']), 'expected_files': len(snapshot['manifest'])}

    def execute(self, principal, request):
        request = validate(request)
        if not isinstance(principal, str) or not principal or principal == 'unowned':
            raise PermissionError('A scoped gateway principal is required')
        if not self.admission.acquire(blocking=False):
            raise TimeoutError('Graph analysis capacity is occupied')
        try:
            return self._execute(principal, request)
        finally:
            self.admission.release()

    def _execute(self, principal, request):
        started = time.perf_counter()
        namespace, root, head, actor = self.authorize(principal, request)
        action = request['action']
        if action == 'index':
            with self.lock:
                snapshot, result = self.index(namespace, request, root)
        else:
            snapshot = self.store.load(namespace, request['commit_sha'])
            if not snapshot:
                return {'status': 'NOT_INDEXED', 'repository': request['repository'], 'commit_sha': request['commit_sha'],
                        'next_action': 'index', 'stale': head != request['commit_sha']}
            graph = Graph(snapshot)
            notes = lambda: self.store.notes(namespace, snapshot, request.get('query', ''))
            symbol = request.get('symbol', '')
            if action == 'status':
                remaining = sorted(set(snapshot['manifest']) - snapshot['fragments'].keys())
                result = {'has_more': bool(remaining), 'index_cursor': identity(namespace, snapshot['sha'], *remaining) if remaining else None,
                          'issues': [{'path': p, 'status': f['status'], 'warnings': f['warnings']} for p, f in snapshot['fragments'].items() if f['status'] != 'indexed'][:50]}
            elif action == 'query':
                result = graph.query(request.get('query', ''), request.get('limit', 30), request.get('depth', 1))
                result['related_memory'] = notes()[:4]
            elif action in {'node', 'callers', 'callees'}:
                result = graph.neighbors(symbol, {'node': 'both', 'callers': 'incoming', 'callees': 'outgoing'}[action], None if action == 'node' else 'calls')
            elif action == 'path':
                result = graph.path(request.get('source', ''), request.get('target', ''), request.get('depth', 6))
            elif action == 'impact':
                paths = request.get('paths', [])
                if symbol:
                    selected, error = graph.select(symbol)
                    if error:
                        return dict(error, graph=graph.metadata())
                    paths = list(set(paths) | {selected['path']})
                result = graph.impact(paths, request.get('depth', 2))
            elif action == 'architecture':
                result = graph.architecture()
            elif action == 'diff':
                base_sha = request.get('base_sha', '')
                if not re.fullmatch('[a-f0-9]{40}', base_sha):
                    raise ValueError('Comparison requires an exact base commit SHA')
                previous = self.store.load(namespace, base_sha)
                if not previous:
                    raise ValueError('Index the requested comparison commit first')
                result = graph.compare(Graph(previous))
            elif action == 'diagnose':
                log = request.get('log', '')
                if not isinstance(log, str) or len(log) > 24000:
                    raise ValueError('Diagnostic excerpt exceeds its bound')
                result = graph.diagnose(log)
                result['previous_repairs'] = [n for n in self.store.notes(namespace, snapshot, log) if n['kind'] == 'repair'][:8]
            elif action == 'contracts':
                result = graph.contracts(request.get('producer', ''), request.get('consumer', ''))
            elif action == 'prs':
                rows = self.backend.paged(root + '/pulls?state=open&base=' + quote(request['branch'], safe=''), maximum=100)
                prs = []
                for row in rows[:20]:
                    files = self.backend.paged(root + '/pulls/' + str(row['number']) + '/files', maximum=1000)
                    prs.append({'number': row['number'], 'head_sha': row['head']['sha'], 'base_sha': row['base']['sha'],
                                'title': row['title'], 'draft': row['draft'], 'files': [f['filename'] for f in files]})
                result = graph.pr_overlap(prs)
                result['queue_truncated'] = len(rows) > 20
            elif action == 'note':
                result = self.remember(principal, namespace, snapshot, request)
            elif action == 'notes':
                result = {'notes': notes(), 'limit': 32}
            elif action == 'requirements':
                all_notes = self.store.notes(namespace, snapshot)
                requirements = [n for n in all_notes if n['kind'] == 'requirement' and not n['superseded']]
                result = {'requirements': [dict(n, implementation_paths=list(n.get('sources', {})),
                                                test_candidates=graph.impact(list(n.get('sources', {})), 2)['tests'],
                                                validation=self.requirement_evidence(n, snapshot)) for n in requirements],
                          'complete': False, 'instruction': 'Linked implementation and tests are inspection. Only exact-commit execution receipts establish passed checks.'}
            elif action == 'wiki':
                result = graph.wiki()
            elif action == 'metrics':
                result = {'measurements': self.store.metrics(namespace), 'accuracy': 'Not measured', 'token_savings': 'Not measured',
                          'actual_token_counts': 'Not available', 'measurement_scope': 'This principal, account, repository and branch'}
            elif action == 'federated':
                result = self.federated(principal, request, actor)
            elif action == 'provider':
                result = self.provider(request)
            else:
                raise ValueError('Unsupported graph action')
        if action == 'index':
            # Avoid rebuilding a large graph after every batch. Queries construct it on demand.
            metadata = {'repository': snapshot['repository'], 'branch': snapshot['branch'], 'commit_sha': snapshot['sha'],
                        'processed_files': len(snapshot['fragments']), 'expected_files': len(snapshot['manifest']),
                        'index_metrics': snapshot['index_metrics'], 'source_level_only': True, 'runtime_verified': False}
        else:
            metadata = graph.metadata()
        result = bounded(dict(result, graph=metadata, stale=head != snapshot['sha'], observed_branch_head=head), request.get('budget_bytes', 16000))
        # Do not publish cached source if the host switches accounts during the read.
        if self.backend.api('/user')['id'] != actor:
            raise PermissionError('GitHub account changed during graph retrieval')
        self.store.measure(namespace, action, round((time.perf_counter() - started) * 1000, 2), len(encoded(result).encode()))
        return result

    def remember(self, principal, namespace, snapshot, request):
        kind, content = request.get('kind'), request.get('content')
        if kind not in {'decision', 'repair', 'requirement'} or not isinstance(content, str) or not 1 <= len(content) <= 8000:
            raise ValueError('A bounded decision, repair or requirement is required')
        paths = request.get('paths', [])
        if any(path not in snapshot['manifest'] for path in paths):
            raise ValueError('Link notes to paths in the selected source revision')
        supersedes = request.get('supersedes')
        if supersedes and supersedes not in {n['id'] for n in self.store.notes(namespace, snapshot)}:
            raise ValueError('Superseded note is outside the selected memory scope')
        value = {'kind': kind, 'content': content, 'sha': snapshot['sha'], 'supersedes': supersedes,
                 'sources': {path: snapshot['manifest'][path]['sha'] for path in paths}, 'confidence': 'RECORDED_NOTE_NOT_CURRENT_PROOF'}
        if request.get('receipt_id'):
            job_id = request.get('job_id', '')
            job = self.store.store.get(job_id, principal)
            if not job or job['request']['repository'].lower() != snapshot['repository'].lower() or job['request']['branch'] != snapshot['branch']:
                raise PermissionError('Receipt belongs to another repository scope')
            receipt = self.evidence.read(job_id, request['receipt_id']) if self.evidence else None
            if not receipt:
                raise ValueError('Execution receipt is unavailable')
            value['receipt'] = {'job_id': job_id, 'receipt_id': request['receipt_id'], 'payload': receipt['payload']}
        return {'note_id': self.store.note(namespace, value), 'persisted': True, 'execution_authority': False}

    @staticmethod
    def requirement_evidence(note, snapshot):
        payload = note.get('receipt', {}).get('payload', {})
        # A successful API read is never a passed test. Keep the original payload inspectable.
        status = 'RECEIPT_LINKED_REVIEW_REQUIRED' if payload else 'UNTESTED'
        run = payload.get('result', {})
        if (isinstance(run, dict) and payload.get('source') == 'github_api' and payload.get('trusted') is True and
                payload.get('success') is True and payload.get('method') == 'GET' and
                re.fullmatch('/repos/' + re.escape(snapshot['repository']) + r'/actions/runs/\d+', payload.get('path', '')) and
                run.get('head_sha') == snapshot['sha'] and run.get('status') == 'completed'):
            status = 'LINKED_WORKFLOW_PASSED' if run.get('conclusion') == 'success' else 'LINKED_WORKFLOW_FAILED'
        return {'status': status, 'feature_completeness_proven': False,
                'same_commit': isinstance(run, dict) and run.get('head_sha') == snapshot['sha'], 'receipt_id': note.get('receipt', {}).get('receipt_id')}

    def federated(self, principal, request, actor):
        targets = request.get('targets', [])
        if not isinstance(targets, list) or not 1 <= len(targets) <= 5:
            raise ValueError('Select one to five explicit repository revisions')
        results, signatures, graphs = [], {}, []
        for target in targets:
            item = validate(dict(target, action='query', query=request.get('query', '')))
            namespace, _, head, current_actor = self.authorize(principal, item)
            if actor != current_actor:
                raise PermissionError('GitHub account changed')
            snapshot = self.store.load(namespace, item['commit_sha'])
            if not snapshot:
                results.append({'repository': item['repository'], 'status': 'NOT_INDEXED'})
                continue
            graph = Graph(snapshot)
            graphs.append(graph)
            found = graph.query(item['query'], 10, 1)
            results.append(dict(found, repository=item['repository'], sha=item['commit_sha'], stale=head != item['commit_sha']))
            for node in found['nodes']:
                if node['kind'] in {'class', 'interface', 'function'}:
                    signatures.setdefault(node['name'], []).append({'repository': item['repository'], 'node': node['id'], 'url': node['url']})
        links = [{'symbol': name, 'locations': values, 'confidence': 'AMBIGUOUS_SHARED_NAME', 'dependency_proven': False}
                 for name, values in signatures.items() if len({v['repository'] for v in values}) > 1]
        dependencies, declarations = [], {}
        for graph in graphs:
            for node in graph.nodes.values():
                if node['kind'] in {'class', 'interface', 'function'}:
                    declarations.setdefault(node['name'], []).append((graph, node))
        comparisons, truncated = 0, False
        for source in graphs:
            for path, fragment in source.snapshot['fragments'].items():
                for imported in fragment['imports']:
                    if imported['name'].startswith('.'):
                        continue
                    # Qualified declaration imports only. Package/module imports do not prove a cross-repository symbol dependency.
                    choices = declarations.get(imported['name'].rsplit('.', 1)[-1], [])
                    comparisons += len(choices)
                    if comparisons > 100000:
                        truncated = True
                        break
                    candidates = [(other, node) for other, node in choices if other.snapshot['repository'] != source.snapshot['repository'] and
                                  import_matches(path, imported['name'], node['path'], node['name'])]
                    if len(candidates) == 1:
                        other, node = candidates[0]
                        dependencies.append({'source_repository': source.snapshot['repository'], 'source_commit': source.snapshot['sha'],
                            'source_path': path, 'source_line': imported['line'], 'target_repository': other.snapshot['repository'],
                            'target_commit': other.snapshot['sha'], 'target_node': node['id'], 'target_url': node['url'],
                            'relation': 'imports_candidate', 'confidence': 'INFERRED', 'runtime_verified': False})
                    if len(dependencies) >= 100:
                        truncated = True
                        break
                if truncated:
                    break
            if truncated:
                break
        return {'repositories': results, 'cross_repository_candidates': links, 'cross_repository_dependencies': dependencies[:100],
                'cross_repository_scan_truncated': truncated, 'dependency_coverage': 'Explicit qualified declaration imports only',
                'scope': 'Explicitly selected and independently authorized repositories only'}

    def provider(self, request):
        runtime = self.store.store.runtime
        server = os.getenv('GATEWAY_GRAPHIFY_SERVER', '')
        projects = json.loads(os.getenv('GATEWAY_GRAPHIFY_PROJECTS', '{}'))
        project = projects.get(request['repository'])
        if not server or not project or server not in runtime.load_mcp_config():
            raise ValueError('Configure an existing local Graphify MCP server and repository path mapping on the host')
        directory = Path(project).resolve(strict=True)
        def git(*args):
            return subprocess.check_output(['git', '-C', str(directory), *args], timeout=10, text=True, stderr=subprocess.DEVNULL).strip()
        remote = git('remote', 'get-url', 'origin')
        normalized = remote.removesuffix('.git').removeprefix('https://github.com/').removeprefix('git@github.com:')
        if normalized.lower() != request['repository'].lower() or git('rev-parse', 'HEAD') != request['commit_sha'] or git('status', '--porcelain'):
            raise ValueError('Graphify checkout must be clean and match the selected repository and commit')
        answer = runtime.mcp_call(server, 'tools/call', {'name': 'query_graph', 'arguments': {
            'question': request.get('query', ''), 'project_path': str(directory), 'depth': max(1, request.get('depth', 2)), 'token_budget': 2000}})
        if git('rev-parse', 'HEAD') != request['commit_sha'] or git('status', '--porcelain'):
            raise ValueError('Graphify checkout changed during retrieval')
        return {'provider': 'Graphify', 'result': answer, 'confidence': 'EXTERNAL_INDEX_REQUIRES_SOURCE_VERIFICATION',
                'index_freshness': 'Not independently verified', 'runtime_verified': False}

    def capture(self, job, phase, output):
        if job['request'].get('memory_mode') == 'SESSION_ONLY' or not job['data'].get('actor_id'):
            return
        namespace = scope(job['principal'], job['data']['actor_id'], job['request']['repository'], job['request']['branch'])
        if phase == 'READ' and output.get('files'):
            sha = job['data']['head_sha']
            with self.lock:
                snapshot = self.store.load(namespace, sha) or {'schema_version': SCHEMA_VERSION, 'repository': job['request']['repository'],
                    'branch': job['request']['branch'], 'sha': sha, 'manifest': {p: v for p, v in output['tree'].items() if permitted(p) and v.get('mode', '100644') in {'100644', '100755'}}, 'fragments': {}}
                for path, value in output['files'].items():
                    if path in snapshot['manifest'] and blob_sha(value['content']) == snapshot['manifest'][path]['sha']:
                        snapshot['fragments'][path] = extract(path, value['content'])
                self.store.save(namespace, snapshot)
        elif phase in {'PLAN', 'CI', 'REVIEW'}:
            kind = {'PLAN': 'requirement', 'CI': 'repair', 'REVIEW': 'decision'}[phase]
            paths = [p for p in job['data'].get('files', {}) if permitted(p)]
            self.store.note(namespace, {'kind': kind, 'sha': job['data'].get('head_sha'),
                'content': (job['request']['objective'] if phase == 'PLAN' else encoded(output))[:8000],
                'sources': {p: job['data']['files'][p]['sha'] for p in paths}, 'source_job': job['id'],
                'source_receipt': job['data'].get('latest_evidence'), 'confidence': 'HISTORICAL_OBSERVATION'})

    def recall(self, job, query):
        if (job['request'].get('memory_mode') == 'SESSION_ONLY' or not job['data'].get('actor_id') or
                not local_model_destination(getattr(self.store.store.runtime, 'LLAMA_BASE', ''))):
            return {'status': 'DISABLED'}
        namespace = scope(job['principal'], job['data']['actor_id'], job['request']['repository'], job['request']['branch'])
        snapshot = self.store.load(namespace, job['data'].get('head_sha'))
        if not snapshot or snapshot['sha'] != job['data'].get('head_sha'):
            return {'status': 'NOT_INDEXED'}
        graph = Graph(snapshot)
        return bounded(dict(graph.query(query, 12, 1), graph=graph.metadata()), 10000)
