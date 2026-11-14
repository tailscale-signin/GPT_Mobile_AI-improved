"""Revision-bound result pages that preserve complete relationship endpoints."""
import base64
import json

from .engine import encoded, search_terms, snapshot_fingerprint
from .extract import identity


def compact_node(node):
    keys = ('id', 'name', 'qualified', 'kind', 'path', 'line', 'end_line', 'blob_sha', 'parser', 'url')
    value = {key: node[key] for key in keys if key in node}
    value['name'] = value.get('name', '')[:160]
    value['qualified'] = value.get('qualified', '')[:240]
    if node['kind'] != 'file':
        value['excerpt'] = node.get('excerpt', '')[:320]
    return value


def cursor(binding, offset):
    return base64.urlsafe_b64encode(encoded([binding, offset]).encode()).decode().rstrip('=')


def cursor_offset(value, binding, total):
    if not value:
        return 0
    try:
        if not isinstance(value, str) or len(value) > 512:
            raise ValueError()
        found, offset = json.loads(base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True))
        if found != binding or type(offset) is not int or not 0 <= offset < total:
            raise ValueError()
        return offset
    except (ValueError, TypeError, json.JSONDecodeError):
        raise ValueError('Graph result cursor changed; refresh the selected revision and query') from None


def architecture_records(graph, request):
    files = {node['path']: node for node in graph.nodes.values() if node['kind'] == 'file'}
    dependencies = {}
    for edge in graph.edges:
        source, target = graph.nodes[edge['source']]['path'], graph.nodes[edge['target']]['path']
        if source != target and edge['relation'] != 'contains' and source in files and target in files:
            dependencies.setdefault((source, target), edge['confidence'])
    selected = set(files)
    paths = set(request.get('paths', []))
    terms = search_terms(request.get('query', ''))
    if paths or terms:
        selected = {p for p in files if p in paths or terms and all(term in p.lower() for term in terms)}
        selected |= {b if a in selected else a for a, b in dependencies if a in selected or b in selected}
    pairs = [(a, b) for a, b in sorted(dependencies) if a in selected and b in selected]
    outgoing, degrees = {}, {}
    for source, target in pairs:
        outgoing.setdefault(source, []).append(target)
        degrees[source] = degrees.get(source, 0) + 1
        degrees[target] = degrees.get(target, 0) + 1
    records = []
    for path in sorted(selected, key=lambda p: (-degrees.get(p, 0), p)):
        records.append(([files[path]], []))
        for target in outgoing.get(path, []):
            edge = {'source': files[path]['id'], 'target': files[target]['id'], 'relation': 'depends_on',
                    'confidence': dependencies[(path, target)]}
            records.append(([files[path], files[target]], [edge]))
    return records, {'total_files': len(selected), 'total_relationships': len(pairs), 'repository_files': len(files)}


def query_records(graph, request):
    ranked = graph.ranked(request.get('query', ''), request.get('paths', []))
    records, seen, expanded = [], set(), {}
    depth = min(request.get('depth', 1), 3)
    for node in ranked:
        records.append(([node], []))
        frontier, visited = {node['id']}, {node['id']}
        for step in range(depth):
            following = set()
            for key in sorted(frontier):
                if expanded.get(key, 0) >= depth - step:
                    continue
                expanded[key] = depth - step
                for edge in graph.out[key] + graph.incoming[key]:
                    if edge['relation'] == 'contains':
                        continue
                    signature = (edge['source'], edge['target'], edge['relation'], edge.get('line'))
                    if signature not in seen:
                        records.append(([graph.nodes[edge['source']], graph.nodes[edge['target']]], [edge]))
                        seen.add(signature)
                    following.update((edge['source'], edge['target']))
            frontier = set(sorted(following - visited)[:200])
            visited.update(frontier)
    return records, {'total_matches': len(ranked)}


def graph_page(graph, namespace, request, envelope):
    action = request['action']
    records, totals = architecture_records(graph, request) if action == 'architecture' else query_records(graph, request)
    binding = identity(namespace, snapshot_fingerprint(graph.snapshot), action, request.get('query', ''),
                       sorted(request.get('paths', [])), request.get('depth', 1))
    offset = cursor_offset(request.get('result_cursor'), binding, len(records))
    result = dict(envelope, **totals, status='FOUND' if records else 'EMPTY' if action == 'architecture' else 'NOT_FOUND_IN_INDEX',
                  nodes=[], edges=[], has_more=False, next_cursor=None, result_cursor=None, truncated=False,
                  returned_nodes=0, returned_edges=0, page_offset=offset)
    maximum = request.get('budget_bytes', 16000)
    nodes, edges = {}, {}
    consumed = 0
    for row_nodes, row_edges in records[offset:offset + request.get('limit', 30)]:
        candidate_nodes = dict(nodes)
        candidate_edges = dict(edges)
        for node in row_nodes:
            candidate_nodes[node['id']] = compact_node(node)
        for edge in row_edges:
            candidate_edges[(edge['source'], edge['target'], edge['relation'], edge.get('line'))] = edge
        next_offset = offset + consumed + 1
        next_cursor = cursor(binding, next_offset) if next_offset < len(records) else None
        candidate = dict(result, nodes=list(candidate_nodes.values()), edges=list(candidate_edges.values()),
                         has_more=bool(next_cursor), next_cursor=next_cursor, result_cursor=next_cursor,
                         returned_nodes=len(candidate_nodes), returned_edges=len(candidate_edges))
        required = len(encoded(candidate).encode())
        if required > maximum:
            if not consumed:
                result.update(status='RESULT_ITEM_EXCEEDS_BUDGET', minimum_budget_bytes=required,
                              error={'code': 'GRAPH_RESULT_BUDGET_TOO_SMALL', 'retryable': maximum < 64000})
            break
        nodes, edges, result = candidate_nodes, candidate_edges, candidate
        consumed += 1
    return result
