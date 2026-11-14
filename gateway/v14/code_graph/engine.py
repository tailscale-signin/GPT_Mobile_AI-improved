"""Bounded graph traversal, provenance, architecture analysis, and encrypted snapshots."""
import collections
import base64
import json
import re
import time
import posixpath
import math
import zlib
from urllib.parse import quote

from v14.engineering_crypto import EngineeringCipher
from . import SCHEMA_VERSION, EXTRACTOR_VERSION
from .extract import identity, compact_fragment

MAX_GRAPH_BYTES = 128 * 1024 * 1024


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def scope(principal, actor, repository, branch):
    return identity('graph-v1', principal, actor, repository.lower(), branch)


class GraphStore:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS code_graph_snapshots (scope TEXT NOT NULL, sha TEXT NOT NULL, updated REAL NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,sha))')
        db.execute('CREATE TABLE IF NOT EXISTS code_graph_snapshot_summaries (scope TEXT NOT NULL, sha TEXT NOT NULL, updated REAL NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,sha))')
        db.execute('CREATE TABLE IF NOT EXISTS code_graph_notes (scope TEXT NOT NULL, id TEXT NOT NULL, created REAL NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,id))')
        db.execute('CREATE TABLE IF NOT EXISTS code_graph_metrics (scope TEXT NOT NULL, operation TEXT NOT NULL, count INTEGER NOT NULL, total_ms REAL NOT NULL, output_bytes INTEGER NOT NULL, PRIMARY KEY(scope,operation))')

    def load(self, namespace, sha=None, exclude_sha=None):
        def read(db):
            self.schema(db)
            selectors = ((' AND sha=?', sha) if sha else (' AND sha<>?', exclude_sha) if exclude_sha else ('', None))
            row = db.execute('SELECT sha,payload,updated FROM code_graph_snapshots WHERE scope=?' + selectors[0] + ' ORDER BY updated DESC LIMIT 1',
                             (namespace, selectors[1]) if selectors[0] else (namespace,)).fetchone()
            if not row:
                return None
            decoded = json.loads(EngineeringCipher.decrypt(db, 'graph:' + namespace + ':' + row[0], row[1]))
            if decoded.get('compression') == 'zlib':
                decompressor = zlib.decompressobj()
                raw = decompressor.decompress(base64.b64decode(decoded['data'], validate=True), MAX_GRAPH_BYTES + 1)
                if len(raw) > MAX_GRAPH_BYTES or not decompressor.eof or decompressor.unused_data:
                    raise ValueError('Compressed graph exceeds its storage boundary')
                value = json.loads(raw)
            else:
                value = decoded
            if value.get('schema_version') != SCHEMA_VERSION:
                raise ValueError('Unsupported graph schema; rebuild required')
            # A parser change invalidates old fragments even at the same commit.
            value['fragments'] = {path: fragment for path, fragment in value['fragments'].items()
                                  if fragment.get('version') == EXTRACTOR_VERSION}
            self._save_summary(db, namespace, value, row[2])
            return value
        return self.store.tx(read)

    @staticmethod
    def _save_summary(db, namespace, snapshot, updated):
        snapshot = dict(snapshot, fragments={p: f for p, f in snapshot['fragments'].items() if f.get('version') == EXTRACTOR_VERSION})
        remaining = sorted(set(snapshot['manifest']) - snapshot['fragments'].keys())
        value = {'extractor_version': EXTRACTOR_VERSION, 'snapshot_fingerprint': snapshot_fingerprint(snapshot), 'graph': snapshot_metadata(snapshot),
                 'has_more': bool(remaining), 'index_cursor': identity(namespace, snapshot['sha'], *remaining) if remaining else None,
                 'issues': [{'path': p, 'status': f['status'], 'warnings': f['warnings']} for p, f in snapshot['fragments'].items() if f['status'] != 'indexed'][:50]}
        payload = EngineeringCipher.encrypt(db, 'graph-summary:' + namespace + ':' + snapshot['sha'], encoded(value))
        db.execute('INSERT OR REPLACE INTO code_graph_snapshot_summaries VALUES(?,?,?,?)', (namespace, snapshot['sha'], updated, payload))

    def summary(self, namespace, sha):
        def read(db):
            self.schema(db)
            row = db.execute('SELECT s.payload FROM code_graph_snapshot_summaries s JOIN code_graph_snapshots g '
                             'ON g.scope=s.scope AND g.sha=s.sha AND g.updated=s.updated WHERE s.scope=? AND s.sha=?', (namespace, sha)).fetchone()
            if not row:
                return None
            value = json.loads(EngineeringCipher.decrypt(db, 'graph-summary:' + namespace + ':' + sha, row[0]))
            return value if value.get('extractor_version') == EXTRACTOR_VERSION else None
        value = self.store.tx(read)
        if value is None and self.load(namespace, sha) is not None:
            value = self.store.tx(read)
        return value

    def save(self, namespace, snapshot):
        for fragment in snapshot['fragments'].values():
            compact_fragment(fragment)
        raw = encoded(snapshot)
        if len(raw.encode()) > MAX_GRAPH_BYTES:
            raise ValueError('Graph storage limit reached; narrow the repository scope')
        def write(db):
            self.schema(db)
            compressed = encoded({'compression': 'zlib', 'data': base64.b64encode(zlib.compress(raw.encode())).decode()})
            payload = EngineeringCipher.encrypt(db, 'graph:' + namespace + ':' + snapshot['sha'], compressed)
            updated = time.time()
            db.execute('INSERT OR REPLACE INTO code_graph_snapshots VALUES(?,?,?,?)', (namespace, snapshot['sha'], updated, payload))
            self._save_summary(db, namespace, snapshot, updated)
            db.execute('DELETE FROM code_graph_snapshots WHERE scope=? AND sha NOT IN (SELECT sha FROM code_graph_snapshots WHERE scope=? ORDER BY updated DESC LIMIT 4)', (namespace, namespace))
            if namespace.startswith('public:'):
                db.execute("""DELETE FROM code_graph_snapshots WHERE scope GLOB 'public:*' AND (scope,sha) NOT IN (
                    SELECT scope,sha FROM code_graph_snapshots WHERE scope GLOB 'public:*' ORDER BY updated DESC LIMIT 4)""")
                db.execute("""DELETE FROM code_graph_metrics WHERE scope GLOB 'public:*' AND scope NOT IN (
                    SELECT scope FROM code_graph_snapshots WHERE scope GLOB 'public:*')""")
            db.execute('DELETE FROM code_graph_snapshot_summaries WHERE (scope,sha) NOT IN (SELECT scope,sha FROM code_graph_snapshots)')
        self.store.tx(write)

    def note(self, namespace, value):
        raw = encoded(value)
        if len(raw.encode()) > 24000:
            raise ValueError('Graph note exceeds its bound')
        key = identity(raw)
        def write(db):
            self.schema(db)
            if db.execute('SELECT 1 FROM code_graph_notes WHERE scope=? AND id=?', (namespace, key)).fetchone():
                return
            if db.execute('SELECT COUNT(*) FROM code_graph_notes WHERE scope=?', (namespace,)).fetchone()[0] >= 4096:
                raise ValueError('Graph note capacity reached')
            payload = EngineeringCipher.encrypt(db, 'graph-note:' + namespace + ':' + key, raw)
            db.execute('INSERT INTO code_graph_notes VALUES(?,?,?,?)', (namespace, key, time.time(), payload))
        self.store.tx(write)
        return key

    def notes(self, namespace, snapshot, query=''):
        def read(db):
            self.schema(db)
            rows = db.execute('SELECT id,payload FROM code_graph_notes WHERE scope=? ORDER BY created DESC LIMIT 512', (namespace,)).fetchall()
            return [dict(json.loads(EngineeringCipher.decrypt(db, 'graph-note:' + namespace + ':' + key, payload)), id=key) for key, payload in rows]
        rows = self.store.tx(read)
        superseded = {row.get('supersedes') for row in rows}
        terms = set(re.findall(r'\w{3,}', query.lower()))
        selected = []
        for row in rows:
            row['superseded'] = row['id'] in superseded
            row['historical'] = True
            row['review_required'] = any(snapshot['manifest'].get(path, {}).get('sha') != blob for path, blob in row.get('sources', {}).items())
            row['same_commit'] = row.get('sha') == snapshot['sha']
            row['retrieval_score'] = len(terms & set(re.findall(r'\w{3,}', encoded(row).lower())))
            selected.append(row)
        return sorted(selected, key=lambda row: (not row['superseded'], row['retrieval_score']), reverse=True)[:32]

    def measure(self, namespace, operation, duration_ms, output_bytes):
        def write(db):
            self.schema(db)
            db.execute('INSERT INTO code_graph_metrics VALUES(?,?,1,?,?) ON CONFLICT(scope,operation) DO UPDATE SET count=count+1,total_ms=total_ms+excluded.total_ms,output_bytes=output_bytes+excluded.output_bytes',
                       (namespace, operation, duration_ms, output_bytes))
        self.store.tx(write)

    def metrics(self, namespace):
        def read(db):
            self.schema(db)
            return [{'operation': row[0], 'requests': row[1], 'mean_latency_ms': round(row[2] / row[1], 2), 'output_bytes': row[3]}
                    for row in db.execute('SELECT operation,count,total_ms,output_bytes FROM code_graph_metrics WHERE scope=?', (namespace,))]
        return self.store.tx(read)


def snapshot_metadata(snapshot):
    counts = collections.Counter(f['status'] for f in snapshot['fragments'].values())
    pending = max(0, len(snapshot['manifest']) - len(snapshot['fragments']))
    complete = not pending and not any(counts[k] for k in ('partial', 'failed', 'unsupported'))
    return {'schema_version': SCHEMA_VERSION, 'repository': snapshot['repository'], 'branch': snapshot['branch'],
            'commit_sha': snapshot['sha'], 'expected_files': len(snapshot['manifest']),
            'processed_files': len(snapshot['fragments']), 'file_statuses': dict(counts), 'pending_files': pending,
            'complete': complete, 'processing_complete': not pending, 'coverage_complete': complete,
            'retryable_files': sum(f['status'] == 'failed' or f.get('retryable') is True for f in snapshot['fragments'].values()),
            'retryable_unsupported_files': sum(f['status'] == 'unsupported' and f.get('retryable') is True for f in snapshot['fragments'].values()),
            'excluded_files': snapshot.get('excluded_files', 0), 'nodes': None, 'edges': None,
            'unresolved_references': None, 'relationship_status': 'NOT_MEASURED',
            'source_level_only': True, 'runtime_verified': False,
            'limitations': ['Dynamic dispatch and reflection may be unresolved', 'An empty result is not proof of absence',
                            'Processed files are not proof of complete relationship coverage',
                            'Graph links and suggested tests do not establish runtime correctness'],
            'index_metrics': snapshot.get('index_metrics', {})}


def snapshot_fingerprint(snapshot):
    return identity(snapshot['sha'], EXTRACTOR_VERSION,
                    [(p, snapshot['manifest'][p]['sha'], f.get('version'), f['status'], len(f['nodes']), len(f['references']))
                     for p, f in sorted(snapshot['fragments'].items())])


def search_terms(text):
    split = re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', text).replace('_', ' ')
    stop = {'the', 'and', 'for', 'all', 'are', 'this', 'that', 'with', 'from', 'then', 'them', 'its',
            'please', 'fix', 'find', 'examine', 'issues', 'issue', 'problem', 'problems', 'working', 'make', 'can', 'our'}
    return {word for word in re.findall(r'[A-Za-z][A-Za-z0-9]{2,}', split.lower()) if word not in stop}


class Graph:
    def __init__(self, snapshot):
        self.snapshot = snapshot
        self.nodes, self.edges, self.unresolved = {}, [], []
        by_name = collections.defaultdict(list)
        file_nodes = {}
        modules = collections.defaultdict(set)
        declarations = collections.defaultdict(list)
        packages = collections.defaultdict(set)
        for path, fragment in sorted(snapshot['fragments'].items()):
            for original in fragment['nodes']:
                node = dict(original, path=path, repository=snapshot['repository'], commit_sha=snapshot['sha'],
                            blob_sha=snapshot['manifest'][path]['sha'], parser=fragment.get('parser', 'unknown'))
                node['url'] = 'https://github.com/' + snapshot['repository'] + '/blob/' + snapshot['sha'] + '/' + quote(path, safe='/') + '#L' + str(node['line'])
                self.nodes[node['id']] = node
                by_name[node['name']].append(node)
                package = fragment.get('package', '')
                if package and node['kind'] != 'file':
                    declarations[package + '.' + node['qualified']].append(node)
                    packages[package].add(path)
                if node['kind'] == 'file':
                    file_nodes[path] = node
                    parts = path.rsplit('.', 1)[0].split('/')
                    for offset in range(len(parts)):
                        modules['.'.join(parts[offset:])].add(path)
            self.edges.extend(edge if 'path' in edge else dict(edge, path=path) for edge in fragment['edges'])
        import_targets = {}
        for path, fragment in snapshot['fragments'].items():
            for imported in fragment['imports']:
                name = imported['name']
                if name.startswith('.') and not name.startswith(('./', '../')):
                    target = python_import_target(path, name)
                    targets = {p for p in file_nodes if target and p.rsplit('.', 1)[0] in {target, target + '/__init__'}}
                    if not targets and target:
                        parent = target.rpartition('/')[0]
                        targets = {p for p in file_nodes if p.rsplit('.', 1)[0] in {parent, parent + '/__init__'}}
                elif name.startswith('./') or name.startswith('../'):
                    target = posixpath.normpath(posixpath.join(posixpath.dirname(path), name))
                    if target.rsplit('.', 1)[-1] in {'js', 'mjs', 'jsx', 'ts', 'tsx', 'py'}:
                        target = target.rsplit('.', 1)[0]
                    targets = modules.get(target.replace('/', '.'), set()) | modules.get((target + '/index').replace('/', '.'), set())
                else:
                    targets = ({n['path'] for n in declarations.get(name, [])} or
                               (packages.get(name[:-2], set()) if name.endswith('.*') else set()) or
                               modules.get(name, set()) or modules.get(name.rpartition('.')[0], set()))
                    if not targets and ('/' in name or name.endswith(('.h', '.hpp'))):
                        candidates = {posixpath.normpath(posixpath.join(posixpath.dirname(path), name)), name}
                        targets = {p for p in file_nodes if p in candidates}
                import_targets[(path, name)] = targets
        imported_paths = {path: set().union(*(import_targets[(path, i['name'])] for i in f['imports'])) if f['imports'] else set()
                          for path, f in snapshot['fragments'].items()}
        # Always resolve against the full current snapshot, including unchanged callers.
        # A changed declaration therefore invalidates old inferred edges automatically.
        for path, fragment in sorted(snapshot['fragments'].items()):
            for original_ref in fragment['references']:
                ref = dict(original_ref, path=path)
                source = self.nodes.get(ref['source'])
                if not source:
                    continue
                target_name = ref['name'].rsplit('.', 1)[-1]
                aliases = [i for i in fragment['imports'] if i.get('alias') == target_name and i.get('symbol') not in {None, '*'}]
                names = {i['symbol'] for i in aliases} or {target_name}
                candidates = [n for name in names for n in by_name.get(name, []) if n['kind'] in {'function', 'class', 'interface'}]
                if 'default' in names:
                    candidates.extend(n for n in self.nodes.values() if n.get('default_export'))
                receiver = ref.get('receiver')
                if receiver in {'self', 'this', 'cls'}:
                    parent = source['qualified'].rsplit('.', 1)[0]
                    candidates = [n for n in candidates if n['path'] == path and n['qualified'].rsplit('.', 1)[0] == parent]
                elif receiver:
                    namespace_imports = [i for i in fragment['imports'] if i.get('namespace') and receiver in {i.get('alias'), i.get('module'), i['name']}]
                    if namespace_imports:
                        allowed = set().union(*(import_targets[(path, i['name'])] for i in namespace_imports))
                        candidates = [n for n in candidates if n['path'] in allowed and '.' not in n['qualified']]
                    else:
                        receiver_names = {i.get('symbol', receiver) for i in fragment['imports'] if i.get('alias') == receiver} or {receiver}
                        classes = [n for name in receiver_names for n in by_name.get(name, []) if n['kind'] in {'class', 'interface'}]
                        classes = [n for n in classes if n['path'] == path or n['path'] in imported_paths[path] or
                                   fragment.get('package') and snapshot['fragments'][n['path']].get('package') == fragment['package']]
                        candidates = [n for n in candidates if any(n['path'] == c['path'] and n['qualified'].startswith(c['qualified'] + '.') for c in classes)]
                else:
                    owners = {'.'.join(source['qualified'].split('.')[:i]) for i in range(1, len(source['qualified'].split('.')) + 1)} | {''}
                    local = [n for n in candidates if n['path'] == path and n['qualified'].rpartition('.')[0] in owners]
                    if local:
                        candidates = local
                    else:
                        candidates = [n for n in candidates if (
                            fragment.get('language') in {'kotlin', 'java'} and fragment.get('package') and
                            snapshot['fragments'][n['path']].get('package') == fragment['package'] and '.' not in n['qualified']) or
                            any(n['path'] in import_targets[(path, i['name'])] and
                                (i.get('alias') == target_name and i.get('symbol') in {n['name'], 'default'} or
                                 i.get('wildcard') or import_matches(path, i['name'], n['path'], n['name'])) for i in fragment['imports'])]
                if len(candidates) == 1:
                    self.edges.append({'source': ref['source'], 'target': candidates[0]['id'], 'relation': ref['relation'],
                                       'confidence': 'INFERRED', 'path': path, 'line': ref['line'], 'method': 'unique_static_candidate'})
                else:
                    self.unresolved.append(dict(ref, confidence='AMBIGUOUS' if candidates else 'UNRESOLVED',
                                                candidates=[n['id'] for n in candidates[:12]]))
            for imported in fragment['imports']:
                candidates = [file_nodes[p] for p in sorted(import_targets[(path, imported['name'])])]
                if len(candidates) == 1 and candidates[0]['path'] != path:
                    self.edges.append({'source': imported['source'], 'target': candidates[0]['id'], 'relation': 'imports',
                                       'confidence': 'INFERRED', 'path': path, 'line': imported['line'], 'method': 'unique_module_path'})
        self.edges = list({identity(e['source'], e['target'], e['relation'], e.get('line')): e for e in self.edges}.values())
        self.out, self.incoming = collections.defaultdict(list), collections.defaultdict(list)
        for edge in self.edges:
            if edge['source'] in self.nodes and edge['target'] in self.nodes:
                self.out[edge['source']].append(edge)
                self.incoming[edge['target']].append(edge)

    def metadata(self):
        return dict(snapshot_metadata(self.snapshot), nodes=len(self.nodes), edges=len(self.edges),
                    unresolved_references=len(self.unresolved), relationship_status='MEASURED_SOURCE_RELATIONSHIPS')

    def resolve(self, label):
        if label in self.nodes:
            return [self.nodes[label]]
        return [n for n in self.nodes.values() if label in {n['name'], n['qualified'], n['path']} and
                (n['kind'] == 'file' if label == n['path'] else True)]

    def select(self, label):
        matches = self.resolve(label)
        if len(matches) != 1:
            return None, {'status': 'AMBIGUOUS' if matches else 'NOT_FOUND_IN_INDEX', 'candidates': matches[:20]}
        return matches[0], None

    def ranked(self, question, paths=None):
        terms = search_terms(question)
        aliases = {'pronunciation': {'speech', 'tts', 'lexicon'}, 'speaking': {'speech', 'tts'},
                   'microphone': {'capture', 'recorder'}, 'scrolling': {'scroll', 'anchor'},
                   'repository': {'repo'}, 'map': {'graph'}}
        expanded = set().union(*(aliases.get(term, set()) for term in terms)) if terms else set()
        expanded -= terms
        frequencies = collections.defaultdict(set)
        matches = []
        for node in self.nodes.values():
            haystack = (node['qualified'] + ' ' + node['path'] + ' ' + node['excerpt']).lower()
            matched = {term for term in terms | expanded if term in haystack}
            for term in matched:
                frequencies[term].add(node['path'])
            matches.append((node, matched))
        total_files = max(1, len(self.snapshot['fragments']))
        weights = {term: (1 + math.log((total_files + 1) / (len(paths) + 1))) * (0.75 if term in expanded else 1)
                   for term, paths in frequencies.items()}
        exact = question.strip().lower()
        requested_paths = set(paths or [])
        scored = []
        prefer_docs = bool(terms & {'documentation', 'document', 'design', 'requirements', 'decision', 'architecture'})
        for node, matched in matches:
            name_terms = search_terms(node['name'] if node['kind'] != 'file' else node['path'].rsplit('/', 1)[-1])
            score = sum(weights[term] * (8 if term in name_terms else 3 if term in node['path'].lower() else 1) for term in matched)
            if (exact in {node['id'].lower(), node['name'].lower(), node['qualified'].lower(), node['path'].lower()} and
                    (node['kind'] != 'documentation' or prefer_docs)):
                score += 1000
            if node['path'] in requested_paths:
                score += 80
            if score:
                if node['path'].endswith(('.md', '.mdx', '.json')) and not prefer_docs:
                    score *= 0.25
                if is_test(node['path']) and 'test' not in terms and 'tests' not in terms:
                    score *= 0.55
                scored.append((score, node))
        ranked = sorted(scored, key=lambda row: (-row[0], row[1]['path'], row[1]['line']))
        # A file with many same-scoring methods must not consume the whole retrieval window.
        counts, first, remainder = collections.Counter(), [], []
        for _, node in ranked:
            counts[node['path']] += 1
            (first if counts[node['path']] <= 3 else remainder).append(node)
        return first + remainder

    def query(self, question, limit=30, depth=1, paths=None):
        ranked = self.ranked(question, paths)
        seeds = [n['id'] for n in ranked[:limit]]
        selected = set(seeds)
        frontier = set(seeds)
        for _ in range(min(depth, 3)):
            following = set()
            for key in sorted(frontier):
                for edge in self.out[key] + self.incoming[key]:
                    if edge['relation'] != 'contains':
                        following.update([edge['source'], edge['target']])
            frontier = following - selected
            selected.update(sorted(frontier)[:max(0, limit * 2 - len(selected))])
        ids = seeds + sorted(selected - set(seeds))
        return {'status': 'FOUND' if ids else 'NOT_FOUND_IN_INDEX', 'nodes': [self.nodes[key] for key in ids], 'total_matches': len(ranked),
                'edges': [e for e in self.edges if e['source'] in selected and e['target'] in selected]}

    def neighbors(self, label, direction='both', relation=None):
        node, error = self.select(label)
        if error:
            return error
        edges = (self.out[node['id']] if direction != 'incoming' else []) + (self.incoming[node['id']] if direction != 'outgoing' else [])
        edges = [e for e in edges if not relation or e['relation'] == relation]
        ids = {node['id']} | {e['source'] for e in edges} | {e['target'] for e in edges}
        return {'status': 'FOUND', 'nodes': [self.nodes[key] for key in sorted(ids)], 'edges': edges,
                'unresolved': [r for r in self.unresolved if r['source'] == node['id']][:20]}

    def path(self, source, target, max_hops=6):
        start, error = self.select(source)
        if error:
            return dict(error, endpoint='source')
        finish, error = self.select(target)
        if error:
            return dict(error, endpoint='target')
        queue = collections.deque([(start['id'], [])])
        seen = {start['id']}
        while queue and len(seen) <= 10000:
            current, path = queue.popleft()
            if current == finish['id']:
                return {'status': 'FOUND', 'nodes': [self.nodes[key] for key in [start['id']] + [e['target'] for e in path]],
                        'edges': path, 'confidence': 'INFERRED' if any(e['confidence'] != 'EXTRACTED' for e in path) else 'EXTRACTED'}
            if len(path) < max_hops:
                for edge in self.out[current]:
                    if edge['target'] not in seen:
                        seen.add(edge['target'])
                        queue.append((edge['target'], path + [edge]))
        return {'status': 'NO_PATH_IN_INDEX', 'visited_nodes': len(seen), 'hop_limit': max_hops}

    def impact(self, paths, depth=2):
        seeds = {key for key, n in self.nodes.items() if n['path'] in paths}
        distances = {key: 0 for key in seeds}
        frontier = set(seeds)
        for step in range(1, min(depth, 6) + 1):
            following = set()
            for key in frontier:
                for edge in self.incoming[key] + self.out[key]:
                    if edge['relation'] != 'contains':
                        following.update([edge['source'], edge['target']])
            frontier = following - distances.keys()
            for key in sorted(frontier)[:max(0, 10000 - len(distances))]:
                distances[key] = step
        files = {}
        for key, distance in distances.items():
            path = self.nodes[key]['path']
            files[path] = min(distance, files.get(path, distance))
        tests = [{'path': path, 'distance': distance, 'reason': 'Indexed dependency path', 'execution_status': 'NOT_RUN'}
                 for path, distance in sorted(files.items(), key=lambda pair: (pair[1], pair[0])) if is_test(path)]
        return {'paths': [{'path': p, 'distance': d} for p, d in sorted(files.items(), key=lambda pair: (pair[1], pair[0]))],
                'tests': tests, 'unindexed_paths': sorted(set(paths) - {n['path'] for n in self.nodes.values()}),
                'max_depth': depth, 'required_ci_unchanged': True, 'runtime_verified': False}

    def architecture(self):
        dependencies = collections.defaultdict(set)
        for edge in self.edges:
            a, b = self.nodes[edge['source']]['path'], self.nodes[edge['target']]['path']
            if a != b and edge['relation'] != 'contains':
                dependencies[a].add(b)
        undirected = collections.defaultdict(set)
        for a, targets in dependencies.items():
            for b in targets:
                undirected[a].add(b)
                undirected[b].add(a)
        unseen = {n['path'] for n in self.nodes.values()}
        communities = []
        while unseen:
            seed = min(unseen)
            group, pending = set(), [seed]
            while pending:
                item = pending.pop()
                if item not in group:
                    group.add(item)
                    unseen.discard(item)
                    pending.extend(undirected[item] - group)
            communities.append({'id': identity(*sorted(group)), 'name': seed.split('/')[0], 'files': sorted(group), 'method': 'weakly_connected_component'})
        # Iterative DFS avoids recursion on large repositories; cycles are bounded review signals.
        cycles, colors = [], {}
        for start in sorted(dependencies):
            if colors.get(start):
                continue
            stack, trail = [(start, iter(sorted(dependencies[start])))], [start]
            positions = {start: 0}
            colors[start] = 1
            while stack:
                current, neighbors = stack[-1]
                target = next(neighbors, None)
                if target is None:
                    colors[current] = 2
                    stack.pop()
                    positions.pop(trail.pop(), None)
                elif colors.get(target) == 1 and len(cycles) < 100:
                    cycles.append(trail[positions[target]:] + [target])
                elif not colors.get(target):
                    colors[target] = 1
                    positions[target] = len(trail)
                    trail.append(target)
                    stack.append((target, iter(sorted(dependencies[target]))))
        hubs = sorted(({'path': p, 'connections': len(neighbors)} for p, neighbors in undirected.items()), key=lambda n: (-n['connections'], n['path']))[:30]
        return {'communities': sorted(communities, key=lambda n: (-len(n['files']), n['name'])), 'dependency_cycles': cycles,
                'hubs': hubs, 'signals_only': True, 'dependencies': {p: sorted(v) for p, v in sorted(dependencies.items())}}

    def compare(self, previous):
        def nodes(graph):
            return {(n['path'], n['qualified'], n['kind'], n['signature']): n for n in graph.nodes.values()}
        before, after = nodes(previous), nodes(self)
        def edges(graph):
            return {(graph.nodes[e['source']]['path'], graph.nodes[e['source']]['qualified'], e['relation'],
                     graph.nodes[e['target']]['path'], graph.nodes[e['target']]['qualified']) for e in graph.edges}
        old, new = edges(previous), edges(self)
        return {'base_sha': previous.snapshot['sha'], 'head_sha': self.snapshot['sha'],
                'added_symbols': [after[k] for k in sorted(after.keys() - before.keys())][:100],
                'removed_symbols': [before[k] for k in sorted(before.keys() - after.keys())][:100],
                'added_relationships': sorted(new - old)[:100], 'removed_relationships': sorted(old - new)[:100],
                'total_changes': len(after.keys() - before.keys()) + len(before.keys() - after.keys()) + len(new - old) + len(old - new)}

    def diagnose(self, log):
        locations = [(match.group(1).replace('\\', '/'), int(match.group(2))) for pattern in
                     (r'([\w./\\-]+\.(?:py|kt|kts|java|jsx?|tsx?)):(\d+)', r'File "([^"]+)", line (\d+)')
                     for match in list(re.finditer(pattern, log))[:100]]
        rows = []
        for node in self.nodes.values():
            filename = node['path'].split('/')[-1]
            source_locations = [(path, line) for path, line in locations if path == node['path'] or
                                path.endswith('/' + node['path']) or path.split('/')[-1] == filename]
            containing = [(path, line) for path, line in source_locations if node['line'] <= line <= node['end_line']]
            if source_locations:
                if containing:
                    rows.append(dict(node, diagnostic_locations=[{'path': p, 'line': n} for p, n in containing], match_reason='source_line'))
            elif node['path'] in log or filename in log or (len(node['name']) >= 5 and re.search(r'\b' + re.escape(node['name']) + r'\b', log)):
                rows.append(dict(node, diagnostic_locations=[], match_reason='name_candidate'))
        rows.sort(key=lambda n: (n['match_reason'] != 'source_line', n['path'] not in log, n['kind'] == 'file', n['end_line'] - n['line'], n['path'], n['line']))
        return {'status': 'CANDIDATES' if rows else 'NO_MATCH_IN_INDEX', 'nodes': rows[:30],
                'impact': self.impact({n['path'] for n in rows[:10]}, 1), 'root_cause_proven': False}

    def contracts(self, producer, consumer):
        if producer not in self.snapshot['fragments'] or consumer not in self.snapshot['fragments']:
            raise ValueError('Index both explicitly selected contract files first')
        writes = [dict(f, path=producer) for f in self.snapshot['fragments'][producer]['fields'] if f['direction'] in {'writes', 'declares'}]
        reads = [dict(f, path=consumer) for f in self.snapshot['fragments'][consumer]['fields'] if f['direction'] == 'reads']
        available = {f['name'] for f in writes}
        return {'producer': producer, 'consumer': consumer, 'matched_fields': sorted(available & {f['name'] for f in reads}),
                'consumer_fields_without_producer': [f for f in reads if f['name'] not in available],
                'producer_fields': writes, 'consumer_fields': reads, 'confidence': 'CANDIDATE',
                'limitations': ['Literal field comparison; dynamic builders, types and protocol direction require source review']}

    def pr_overlap(self, prs):
        scopes = {row['number']: {item['path'] for item in self.impact(row['files'], 1)['paths']} for row in prs}
        collisions = []
        for i, left in enumerate(prs):
            for right in prs[i + 1:]:
                overlap = scopes[left['number']] & scopes[right['number']]
                if overlap:
                    collisions.append({'left': left['number'], 'right': right['number'], 'related_paths': sorted(overlap)[:30],
                                       'textual_conflict_proven': False})
        ranked = sorted(prs, key=lambda row: (len(scopes[row['number']]), row['number']))
        return {'pull_requests': [dict(row, impacted_files=len(scopes[row['number']])) for row in prs], 'overlaps': collisions,
                'suggested_review_order': [row['number'] for row in ranked], 'order_reason': 'Smaller indexed impact first; inspect overlapping changes together',
                'merge_authorized': False, 'revalidate_after_each_merge': True}

    def wiki(self):
        architecture = self.architecture()
        lines = ['# Repository architecture', '', self.snapshot['repository'] + ' at `' + self.snapshot['sha'] + '`', '',
                 'Generated from indexed source relationships. This is not runtime or test evidence.', '']
        for group in architecture['communities'][:20]:
            lines.extend(['## ' + group['name'].replace('\n', ' '), ''])
            for path in group['files'][:25]:
                nodes = [n for n in self.nodes.values() if n['path'] == path and n['kind'] != 'file']
                first = next((n for n in self.nodes.values() if n['path'] == path), None)
                if first:
                    lines.append('- [' + path.replace('[', '').replace(']', '') + '](' + first['url'] + '): ' + ', '.join(n['name'] for n in nodes[:6]))
            lines.append('')
        links = [(a, b) for a, targets in architecture['dependencies'].items() for b in targets][:30]
        labels = sorted({p for link in links for p in link})
        lines += ['## Dependency paths', '', '```mermaid', 'flowchart TD']
        for i, path in enumerate(labels):
            safe = path.replace('"', '').replace('\n', '').replace('`', '')
            lines.append(f'  n{i}["{safe}"]')
        for a, b in links:
            lines.append(f'  n{labels.index(a)} --> n{labels.index(b)}')
        lines += ['```', '']
        return {'markdown': '\n'.join(lines), 'partial_document': len(architecture['communities']) > 20 or len(labels) >= 30}


def is_test(path):
    return bool(re.search(r'(^|/)(tests?|androidTest)/|(^|/)(test_|Test)|(?:Test|Tests|Spec)\.|\.(?:test|spec)\.', path))


def python_import_target(source_path, imported):
    levels = len(imported) - len(imported.lstrip('.'))
    package = source_path.split('/')[:-1]
    if levels > len(package):
        return None
    return '/'.join(package[:len(package) - levels + 1] + imported.lstrip('.').split('.'))


def import_matches(source_path, imported, target_path, symbol=None):
    module = target_path.rsplit('.', 1)[0]
    if imported.startswith('.') and not imported.startswith(('./', '../')):
        target = python_import_target(source_path, imported)
        if not target:
            return False
        parent, _, name = target.rpartition('/')
        return module in {target, target + '/__init__'} or module in {parent, parent + '/__init__'} and (symbol is None or name == symbol)
    if imported.startswith('./') or imported.startswith('../'):
        target = posixpath.normpath(posixpath.join(posixpath.dirname(source_path), imported))
        if target.rsplit('.', 1)[-1] in {'js', 'mjs', 'jsx', 'ts', 'tsx', 'py'}:
            target = target.rsplit('.', 1)[0]
        return module == target or module == target + '/index'
    dotted = module.replace('/', '.')
    if dotted == imported or dotted.endswith('.' + imported):
        return True
    # Python imports can name a symbol inside a module; Kotlin imports name the type/file.
    parent, separator, name = imported.rpartition('.')
    return bool(separator and (dotted == parent or dotted.endswith('.' + parent)) and (symbol is None or name == symbol))


def bounded(value, maximum=16000):
    """Retain valid JSON and explicit omission metadata; no truncated JSON strings."""
    original = len(encoded(value).encode())
    if original <= maximum:
        return value
    result = json.loads(encoded(value))
    result['truncated'] = True
    result['unbounded_bytes'] = original
    # Drop from the largest list/text recursively, retaining identities and confidence.
    while len(encoded(result).encode()) > maximum:
        candidates = []
        def visit(item):
            if isinstance(item, dict):
                for key, child in item.items():
                    if isinstance(child, list) and child:
                        candidates.append((len(encoded(child)), item, key, 'list'))
                    elif isinstance(child, dict) and len(child) > 1 and key not in {'graph', 'retrieval'}:
                        candidates.append((len(encoded(child)), item, key, 'dict'))
                    elif isinstance(child, str) and len(child) > 400 and key not in {'id', 'url'}:
                        candidates.append((len(child), item, key, 'text'))
                    else:
                        visit(child)
            elif isinstance(item, list):
                for child in item:
                    visit(child)
        visit(result)
        if not candidates:
            metadata = value.get('graph', {})
            return {'status': 'RESULT_EXCEEDS_BUDGET', 'truncated': True, 'unbounded_bytes': original,
                    'graph': {k: metadata[k] for k in ('schema_version', 'repository', 'branch', 'commit_sha') if k in metadata}}
        _, owner, key, kind = max(candidates, key=lambda row: row[0])
        if kind == 'list':
            owner[key] = owner[key][:len(owner[key]) // 2]
        elif kind == 'dict':
            owner[key] = dict(list(owner[key].items())[:len(owner[key]) // 2])
        else:
            owner[key] = owner[key][:max(400, len(owner[key]) // 2)]
    # Edges without displayed endpoints cannot be rendered as an intact subgraph.
    if 'nodes' in result and 'edges' in result:
        ids = {n['id'] for n in result['nodes']}
        result['edges'] = [e for e in result['edges'] if e['source'] in ids and e['target'] in ids]
    return result
