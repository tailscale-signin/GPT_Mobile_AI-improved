"""Task-selected, immutable source context; no model, shell or network of its own.

The caller supplies an authorized pinned tree and loader. Repository text is
retrieval data, including the optional feature map; it never executes commands
or changes task scope. SESSION_ONLY can use these current bytes without saving
historical memory or invoking an embedding service.
"""
import hashlib
import json
import math
import re
import time

from v14.execution_binding import canonical, fingerprint
from v14.engineering_workspace import SourceInspectionUnavailable

VARIANTS = frozenset({'CURRENT', 'REFERENCE', 'TASK', 'SEMANTIC'})
FEATURE_MAP = 'docs/architecture/feature-map.json'
SCHEMA = 'repository.task_context.v1'
MAX_SOURCE_BYTES = 2 * 1024 * 1024
MAX_FILES = 12


def variant(value=None):
    name = 'TASK' if value is None else str(value).upper()
    if name not in VARIANTS:
        raise ValueError('Choose CURRENT, REFERENCE, TASK or SEMANTIC context')
    return name


def terms(text):
    split = re.sub(r'([a-z])([A-Z])', r'\1 \2', str(text)[:8000])
    return (set(re.findall(r'[\w]{3,}', split.casefold())) |
            set(re.findall(r'[\w]{3,}', split.replace('_', ' ').casefold()))) - {
        'the', 'and', 'with', 'this', 'that', 'from', 'for', 'into', 'how',
        'what', 'would', 'should', 'code', 'file', 'files', 'fix', 'please'}


def regular(tree, path):
    return (isinstance(path, str) and not path.startswith('/') and '\\' not in path and
            not any(p in {'', '.', '..', '.git', 'node_modules', 'build', 'dist', '.gradle', '__pycache__'}
                    or p.lower().startswith('.env') for p in path.split('/')) and
            not re.search(r'(^|/)(credentials|secrets|mcp_config)\.(json|xml|yaml|yml|toml)$', path, re.IGNORECASE) and
            tree.get(path, {}).get('mode') in {'100644', '100755'} and
            re.fullmatch('[a-f0-9]{40}', str(tree[path].get('sha', ''))) is not None)


def token_receipt(text, count_tokens=None):
    """Only a selected tokenizer can claim a measured count; bytes are separate."""
    result = {'token_count': None, 'token_count_status': 'NOT_MEASURED',
              'token_estimate': math.ceil(len(text.encode()) / 4),
              'token_estimate_method': 'UTF8_BYTES_DIVIDED_BY_FOUR',
              'token_estimate_scope': 'CONTEXT_CONTENT',
              'token_scope': 'CONTEXT_CONTENT'}
    if callable(count_tokens):
        value = count_tokens(text)
        count, mode = value[:2] if isinstance(value, tuple) and len(value) in (2, 3) else (None, None)
        if isinstance(value, tuple) and len(value) == 3 and value[2] == 'CONTEXT_WITH_USER_MESSAGE_FRAMING':
            result['token_scope'] = value[2]
        if type(count) is int and count >= 0 and mode == 'SELECTED_TOKENIZER':
            result.update(token_count=count, token_count_status='MEASURED_SELECTED_TOKENIZER')
        elif type(count) is int and count >= 0:
            result.update(token_bound=count, token_bound_method=str(mode)[:80])
    return result


def _features(value, tree):
    if not isinstance(value, dict) or value.get('schema_version') != 1 or not isinstance(value.get('features'), list):
        return []
    rows = []
    for item in value['features'][:256]:
        if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not isinstance(item.get('title'), str):
            continue
        row = {'id': item['id'][:100], 'title': item['title'][:200]}
        missing_references = False
        for field in ('terms', 'paths', 'tests', 'documents', 'invariants'):
            values = item.get(field, [])
            valid = [v for v in values[:128] if isinstance(v, str) and len(v) <= 1000] if isinstance(values, list) else []
            if field in {'paths', 'tests', 'documents'}:
                missing_references |= any(not regular(tree, path) for path in valid)
                valid = [path for path in valid if regular(tree, path)]
            row[field] = valid
        declared = value.get('file_blobs', {})
        references = row['paths'] + row['tests'] + row['documents']
        observed = [declared[path].get('blob_sha') == tree[path]['sha'] for path in references
                    if isinstance(declared, dict) and isinstance(declared.get(path), dict)]
        row['freshness'] = ('STALE_REFERENCES' if missing_references or observed and not all(observed) else
                            'VERIFIED_REFERENCES' if observed and len(observed) == len(references) else 'REFERENCES_NOT_DECLARED')
        if row['freshness'] == 'STALE_REFERENCES':
            row['invariants'] = []
        route = item.get('routing')
        if (row['freshness'] == 'VERIFIED_REFERENCES' and isinstance(route, dict)
                and route.get('entry') in row['paths'] and
                all(isinstance(route.get(k), str) and 0 < len(route[k]) <= 200 for k in ('role', 'proof', 'admission', 'decline'))):
            row['routing'] = {k: route[k] for k in ('role', 'entry', 'proof', 'admission', 'decline')}
            row['routing']['execution_authority'] = False
        # Commands intentionally do not enter execution or readiness authority.
        rows.append(row)
    return rows


def _ranges(path, source, query_terms, anchors, reason):
    """Return actual line ranges, including body matches beyond file prefixes."""
    lines = source.splitlines(True)
    if not lines:
        return []
    markdown = path.lower().endswith(('.md', '.mdx'))
    windows = []
    if markdown:
        starts = [i for i, line in enumerate(lines) if re.match(r'^#{1,6}\s+\S', line)]
        starts = sorted(set([0, *starts, len(lines)]))
        for first, last in zip(starts, starts[1:]):
            if last == first:
                continue
            matches = [(len(query_terms & terms(line)), i) for i, line in enumerate(lines[first:last], first)]
            score, match = max(matches, default=(0, first))
            heading_score = len(query_terms & terms(lines[first]))
            if score or heading_score or reason == 'feature_document':
                begin = max(first, match - 8) if last - first > 60 else first
                windows.append((score + 2 * heading_score, begin, min(last, begin + 60), lines[first].strip()[:200]))
    else:
        for first, last in anchors:
            if 1 <= first <= len(lines):
                windows.append((100, max(0, first - 3), min(len(lines), max(first, min(last, first + 70)) + 2), None))
        matches = [(len(query_terms & terms(line)), i) for i, line in enumerate(lines)]
        for score, index in sorted(matches, key=lambda row: (-row[0], row[1]))[:8]:
            if score:
                windows.append((score * 4, max(0, index - 8), min(len(lines), index + 28), None))
    if not windows:
        windows = [(0, 0, min(len(lines), 35), None)]
    selected = []
    for score, first, last, heading in sorted(windows, key=lambda row: (-row[0], row[1])):
        if any(first < row['end_line'] and last > row['start_line'] - 1 for row in selected):
            continue
        content = ''.join(lines[first:last])
        # Never label a truncated line as complete source. Smaller exact windows
        # are preferred; a single oversize line is an explicit omission later.
        while len(content.encode()) > 5000 and last - first > 1:
            last -= 1
            content = ''.join(lines[first:last])
        selected.append({'path': path, 'start_line': first + 1, 'end_line': last,
                         'content': content, 'reason': reason, 'heading': heading,
                         'total_lines': len(lines), 'file_complete': first == 0 and last == len(lines),
                         'content_sha256': hashlib.sha256(content.encode()).hexdigest()})
        if len(selected) == 2:
            break
    return selected


def build_bundle(*, repository, source_sha, tree, load_source, query, variant='TASK', paths=None,
                 graph=None, max_bytes=12000, cancel=None, semantic_scores=None, count_tokens=None,
                 source_identity_kind='GIT_COMMIT'):
    """Pure selection API shared by production and public-fixture benchmarks.

    load_source(path) returns {sha,content,cache_hit?}. No unobserved path or blob
    can enter the result. A caller that restricts its tree also restricts recall.
    """
    started = time.monotonic()
    chosen = globals()['variant'](variant)
    size = {'GIT_COMMIT': 40, 'FROZEN_FIXTURE_MANIFEST': 64}.get(source_identity_kind)
    if size is None or not re.fullmatch('[a-f0-9]{' + str(size) + '}', str(source_sha)) or not isinstance(tree, dict):
        raise ValueError('Task context needs an immutable source SHA and tree')
    if type(max_bytes) is not int or not 2048 <= max_bytes <= 65536:
        raise ValueError('Task context uses a 2–64 KiB output allowance')
    if not isinstance(query, str) or len(query) > 8000:
        raise ValueError('Task query must be bounded text')
    loaded, cached, omitted = {}, [], []
    def stopping():
        if cancel is not None and (cancel.is_set() if hasattr(cancel, 'is_set') else cancel()):
            raise InterruptedError('Task context selection cancelled')
    def source(path):
        stopping()
        if not regular(tree, path):
            raise ValueError('Task context path is outside the pinned regular source tree')
        if path in loaded:
            return loaded[path]
        value = load_source(path)
        text = value.get('content') if isinstance(value, dict) else None
        if not isinstance(text, str):
            raise SourceInspectionUnavailable('Task context source is not text')
        raw = text.encode()
        digest = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if value.get('sha') != tree[path]['sha'] or digest != tree[path]['sha']:
            raise ValueError('Task context source differs from its pinned blob')
        if len(raw) > MAX_SOURCE_BYTES or '\0' in text:
            raise SourceInspectionUnavailable('Task context source exceeds the UTF-8 inspection allowance')
        loaded[path] = text
        if value.get('cache_hit') is True:
            cached.append(path)
        return text
    result = {'schema': SCHEMA, 'repository': repository, 'source_sha': source_sha, 'variant': chosen,
              'source_identity_kind': source_identity_kind,
              'selected_ranges': [], 'documents': [], 'test_candidates': [], 'features': [],
              'omissions': omitted, 'freshness': 'PINNED_SOURCE' if source_identity_kind == 'GIT_COMMIT' else 'FROZEN_FIXTURE', 'source_level_only': True,
              'completion_authority': False, 'retrieval': 'LEXICAL_AND_DEPENDENCY',
              'reference_digest': None, 'index_digest': None}
    if chosen == 'CURRENT':
        result['retrieval'] = 'CURRENT_BASELINE'
    else:
        query_terms = terms(query)
        features = []
        if regular(tree, FEATURE_MAP):
            try:
                text = source(FEATURE_MAP)
            except SourceInspectionUnavailable:
                omitted.append({'path': FEATURE_MAP, 'reason': 'SOURCE_UNAVAILABLE'})
            else:
                result['reference_digest'] = tree[FEATURE_MAP]['sha']
                try:
                    features = _features(json.loads(text), tree)
                except (ValueError, TypeError):
                    omitted.append({'path': FEATURE_MAP, 'reason': 'INVALID_FEATURE_MAP'})
        scores, reasons, anchors = {}, {}, {}
        def offer(path, score, reason):
            if regular(tree, path) and score > scores.get(path, 0):
                scores[path], reasons[path] = score, reason
        requested = [path for path in (paths or [])[:60] if regular(tree, path)]
        for path in requested:
            offer(path, 90, 'task_path')
        for path in tree:
            if regular(tree, path):
                matches = query_terms & terms(path)
                if matches:
                    offer(path, 12 * len(matches) + (100 if path in query else 0), 'query_path')
        matches = []
        for feature in features:
            score = len(query_terms & terms(feature['title'] + ' ' + ' '.join(feature['terms'])))
            score += 3 * len(set(requested) & set(feature['paths']))
            if score:
                matches.append((score, feature))
        for score, feature in sorted(matches, key=lambda row: (-row[0], row[1]['id']))[:4]:
            result['features'].append({'id': feature['id'], 'title': feature['title'], 'invariants': feature['invariants'][:4],
                                       'freshness': feature['freshness']})
            if 'routing' in feature:
                result['features'][-1]['routing'] = feature['routing']
                offer(feature['routing']['entry'], 75 + score, 'feature_entry')
            for field, boost, reason in (('paths', 55, 'feature_source'), ('tests', 50, 'feature_test'),
                                         ('documents', 65, 'feature_document')):
                for path in feature[field]:
                    offer(path, boost + score, reason)
        graph = graph if isinstance(graph, dict) else {}
        valid_nodes = []
        for node in graph.get('nodes', [])[:256]:
            if not isinstance(node, dict):
                continue
            path = node.get('path')
            if not regular(tree, path) or node.get('blob_sha') != tree[path]['sha'] or node.get('commit_sha', source_sha) != source_sha:
                continue
            valid_nodes.append(node)
            offer(path, 75 + len(query_terms & terms(node.get('name', ''))) * 10, 'graph_symbol')
            first, last = node.get('line'), node.get('end_line', node.get('line'))
            if type(first) is int and type(last) is int:
                anchors.setdefault(path, []).append((first, last))
        result['index_digest'] = fingerprint(valid_nodes)
        for path in ('docs/architecture/repository-guide.md', 'AGENTS.md', 'README.md'):
            offer(path, 15, 'repository_reference')
        candidates = sorted(scores, key=lambda path: (-scores[path], path))
        if chosen == 'REFERENCE':
            candidates = [path for path in candidates if path.lower().endswith(('.md', '.mdx'))]
        # Reserve slots for tests and reference sections before a large code
        # cluster can fill the working set. These are inspection candidates.
        documents = [p for p in candidates if p.lower().endswith(('.md', '.mdx'))][:3]
        tests = [p for p in candidates if re.search(r'(^|[/_.-])tests?([/_.-]|$)|Test\.', p)][:2]
        ordered = list(dict.fromkeys(candidates[:6] + documents + tests + candidates))[:MAX_FILES]
        sections = []
        for path in ordered:
            try:
                text = source(path)
            except SourceInspectionUnavailable:
                omitted.append({'path': path, 'blob_sha': tree[path]['sha'], 'reason': 'SOURCE_UNAVAILABLE'})
                continue
            for row in _ranges(path, text, query_terms, anchors.get(path, []), reasons[path]):
                row['blob_sha'] = tree[path]['sha']
                sections.append(row)
        if chosen == 'SEMANTIC':
            result['retrieval'] = 'LEXICAL_FALLBACK'
            if callable(semantic_scores) and sections:
                values = [(fingerprint([row['blob_sha'], row['start_line'], row['end_line']]), row) for row in sections]
                ranking = semantic_scores(query, values)
                stopping()
                identities = {key for key, _ in values}
                if (isinstance(ranking, dict) and identities.intersection(ranking) and
                        all(type(v) in (int, float) and math.isfinite(v) for v in ranking.values())):
                    sections.sort(key=lambda row: -ranking.get(fingerprint([row['blob_sha'], row['start_line'], row['end_line']]), -1))
                    result['retrieval'] = 'LOCAL_SEMANTIC_AND_LEXICAL'
        for row in sections:
            field = 'documents' if row['path'].lower().endswith(('.md', '.mdx')) else 'selected_ranges'
            result[field].append(row)
            # Reserve space for coverage, measurement and explicit omissions.
            if len(canonical(result).encode()) > max_bytes - 1600:
                result[field].pop()
                if len(omitted) < 12:
                    omitted.append({'path': row['path'], 'blob_sha': row['blob_sha'],
                                    'start_line': row['start_line'], 'end_line': row['end_line'], 'reason': 'OUTPUT_BUDGET'})
        admitted = {row['path'] for row in result['selected_ranges']}
        result['test_candidates'] = [{'path': p, 'blob_sha': tree[p]['sha'], 'status': 'NOT_RUN'} for p in tests if p in admitted]
    stopping()
    result['coverage'] = {'loaded_files': len(loaded), 'selected_files': len({r['path'] for key in ('selected_ranges', 'documents') for r in result[key]}),
                          'eligible_files': sum(regular(tree, p) for p in tree), 'tree_complete': True,
                          'selection_complete': not omitted, 'exhaustive': False}
    result['cache'] = {'cached_files': len(cached), 'loaded_files': len(loaded), 'cache_hit': bool(loaded) and len(cached) == len(loaded)}
    result['preparation_ms'] = round((time.monotonic() - started) * 1000, 3)
    measured_text = canonical({key: result[key] for key in ('features', 'selected_ranges', 'documents')})
    result.update(token_receipt(measured_text, count_tokens))
    result['bytes'] = 0
    for _ in range(3):
        result['bytes'] = len(canonical(result).encode())
    while result['bytes'] > max_bytes and (result['features'] or result['omissions']):
        (result['features'] or result['omissions']).pop()
        result['coverage']['selection_complete'] = False
        result['bytes'] = len(canonical(result).encode())
    actual_text = canonical({key: result[key] for key in ('features', 'selected_ranges', 'documents')})
    if actual_text != measured_text:
        result.update(token_receipt(actual_text, count_tokens))
        for _ in range(3):
            result['bytes'] = len(canonical(result).encode())
    if result['bytes'] > max_bytes:
        raise ValueError('Task context metadata exceeds its output allowance')
    return result


def prepare(backend, job, role, evidence, graph, *, chosen=None, max_bytes=12000, query=None):
    from v14.engineering_workspace import load_file
    from v14.graph_context import stage_selection
    name = variant(chosen if chosen is not None else job.get('request', {}).get('context_variant', job['data'].get('context_variant')))
    objective, paths = stage_selection(job, role, evidence)
    query = objective if query is None else query
    def load(path):
        hit = path in job['data'].get('files', {})
        return dict(load_file(backend, job, path), cache_hit=hit)
    # No new model/destination is chosen by this retrieval layer. A selected-only
    # or session-only job does not invoke the optional historical embedding path.
    scores = None
    memory = getattr(backend, 'task_context_semantic_scores', None)
    if (name == 'SEMANTIC' and job.get('request', {}).get('memory_mode') != 'SESSION_ONLY' and
            job.get('execution_binding', {}).get('routing_policy') != 'SELECTED_ONLY' and callable(memory)):
        scores = lambda q, rows: memory(job, q, rows)
    bundle = build_bundle(repository=job['request']['repository'], source_sha=job['data']['head_sha'],
                          tree=job['data']['tree'], load_source=load, query=query, variant=name,
                          paths=paths, graph=graph, max_bytes=max_bytes, cancel=backend.cancellation(job), semantic_scores=scores)
    # Public diagnostics retain counts/digests only. Full immutable sections are
    # supplied directly to the already-bound model input, not a second memory.
    job['data']['task_context_receipt'] = {key: bundle[key] for key in
        ('schema', 'source_sha', 'variant', 'retrieval', 'reference_digest', 'index_digest', 'coverage', 'cache', 'bytes',
         'preparation_ms', 'token_count', 'token_count_status', 'token_estimate', 'token_estimate_method')}
    job['data']['task_context_receipt']['delivery_status'] = 'PREPARED'
    for field in ('selected_ranges', 'documents', 'test_candidates'):
        job['data']['task_context_receipt'][field] = [{key: row[key] for key in
            ('path', 'blob_sha', 'start_line', 'end_line', 'reason', 'heading', 'status') if key in row}
            for row in bundle[field]][:24]
    return bundle


def workspace_receipt(data):
    """Content-free, bounded projection for the authorized workspace board."""
    saved = data.get('task_context_receipt')
    if not isinstance(saved, dict):
        return None
    view = {key: saved[key] for key in ('schema', 'source_sha', 'variant', 'retrieval', 'reference_digest', 'index_digest',
            'coverage', 'cache', 'bytes', 'preparation_ms', 'token_count', 'token_count_status', 'token_estimate', 'token_estimate_method',
            'delivery_status') if key in saved}
    for field in ('selected_ranges', 'documents', 'test_candidates'):
        view[field] = [{key: row[key] for key in ('path', 'blob_sha', 'start_line', 'end_line', 'reason', 'heading', 'status') if key in row}
                       for row in saved.get(field, [])[:24] if isinstance(row, dict)]
    admission = saved.get('admission')
    if isinstance(admission, dict):
        view['admission'] = {key: admission[key] for key in ('state', 'source_sha', 'view_sha256', 'bytes', 'complete',
                             'token_count', 'token_count_status') if key in admission}
        for field in ('selected_ranges', 'documents'):
            view['admission'][field] = [{key: row[key] for key in ('path', 'blob_sha', 'start_line', 'end_line') if key in row}
                                       for row in admission.get(field, [])[:24] if isinstance(row, dict)]
    view['source_current'] = saved.get('source_sha') == data.get('head_sha')
    if len(canonical(view).encode()) > 20000:
        view = {'schema': SCHEMA, 'source_sha': saved.get('source_sha'), 'source_current': view['source_current'], 'state': 'DETAIL_EXCEEDS_BUDGET'}
    return view


def admitted_receipt(prepared, request, *, state='ADMITTED'):
    """Describe the actual envelope, never infer use from a prepared selection.

    DISPATCH_INTENT means the fenced transport is about to send; only a verified
    successful bound invocation upgrades it to VERIFIED_RESPONSE. Neither is a
    claim that the model semantically used or followed the source.
    """
    if not isinstance(prepared, dict):
        return None
    result = dict(prepared)
    try:
        envelope = json.loads(request['messages'][-1]['content'])
        bundle = envelope.get('task_context') if isinstance(envelope, dict) else None
    except (KeyError, IndexError, TypeError, ValueError):
        bundle = None
    if (not isinstance(bundle, dict) or bundle.get('schema') != SCHEMA or
            bundle.get('source_sha') != prepared.get('source_sha')):
        result.update(delivery_status='NOT_IN_REQUEST', admission={'state': 'NOT_IN_REQUEST'})
        return result
    admission = {'state': state, 'source_sha': bundle['source_sha'], 'view_sha256': fingerprint(bundle),
                 'bytes': len(canonical(bundle).encode()), 'complete': bundle.get('view_complete', True),
                 'token_count': None, 'token_count_status': 'NOT_MEASURED_CONTEXT_COMPONENT'}
    for field in ('selected_ranges', 'documents'):
        rows = bundle.get(field, [])
        admission[field] = [{key: row[key] for key in ('path', 'blob_sha', 'start_line', 'end_line') if key in row}
                            for row in rows[:24] if isinstance(row, dict) and isinstance(row.get('content'), str)] if isinstance(rows, list) else []
    result.update(delivery_status=state, admission=admission)
    return result


def project_bundle(bundle, maximum):
    """Keep exact anchor identities when admission shrinks the source view."""
    if not isinstance(bundle, dict):
        return bundle
    view = {key: bundle[key] for key in ('schema', 'repository', 'source_sha', 'variant', 'source_identity_kind',
            'freshness', 'retrieval') if key in bundle}
    view.update(selected_ranges=[], documents=[], test_candidates=[], view_complete=True,
                retained_digest=fingerprint(bundle), omitted_ranges=0, source_level_only=True,
                completion_authority=False, token_count=None, token_count_status='NOT_MEASURED_PROJECTED_VIEW')
    for field in ('selected_ranges', 'documents'):
        rows = bundle.get(field, [])
        for original in rows:
            row = {key: original[key] for key in ('path', 'blob_sha', 'start_line', 'end_line', 'content', 'content_sha256', 'reason') if key in original}
            view[field].append(row)
            if len(canonical(view).encode()) > maximum:
                view[field].pop()
                # Preserve a useful first exact anchor even when its full range
                # needs an explicit paged read; do not silently crop source.
                if not view[field]:
                    row.pop('content', None)
                    row['content_omitted'] = True
                    view[field].append(row)
                    if len(canonical(view).encode()) > maximum:
                        view[field].pop()
                view['view_complete'] = False
                view['omitted_ranges'] += 1
    while len(canonical(view).encode()) > maximum and (view['documents'] or view['selected_ranges']):
        (view['documents'] or view['selected_ranges']).pop()
        view['view_complete'] = False
        view['omitted_ranges'] += 1
    return view
