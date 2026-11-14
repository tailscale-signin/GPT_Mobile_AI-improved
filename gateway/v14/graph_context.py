"""Stage-specific graph selection and content-free retrieval receipts.

Paths and diagnostic text are used only for authorized local retrieval. Public
job/model receipts contain bounded counts and fixed states, never source names.
"""
from __future__ import annotations

import json
import math
import re

from v14.execution_binding import canonical, fingerprint


GRAPH_STATUSES = frozenset({'FOUND', 'NOT_FOUND_IN_INDEX', 'NOT_INDEXED', 'DISABLED', 'UNAVAILABLE',
                            'PARTIAL', 'INDEXING', 'RESULT_EXCEEDS_BUDGET'})
GRAPH_REASONS = frozenset({'SESSION_ONLY', 'LOCAL_MODEL_REQUIRED', 'IDENTITY_UNAVAILABLE',
                           'REVISION_UNAVAILABLE', 'INDEX_NOT_READY', 'INDEX_PARTIAL', 'CONTEXT_BUDGET',
                           'AUTHORIZATION_CHANGED', 'RETRIEVAL_FAILED', 'CANCELLED', 'NOT_REQUESTED',
                           'SESSION_OR_DESTINATION_POLICY', 'MATCHES_FOUND', 'NO_MATCH_IN_INDEX'})
GRAPH_PROVIDERS = frozenset({'authenticated', 'public', 'public_cache', 'private', 'local',
                             'AUTHENTICATED', 'PUBLIC', 'PUBLIC_CACHE', 'LOCAL', 'NOT_AVAILABLE'})
GRAPH_ROLES = frozenset({'planner', 'repair planner', 'coding agent', 'selected-model reviewer',
                         'repository answerer', 'repository planner', 'design options',
                         'architecture options', 'decision options', 'final summary', 'final report'})


def stage_selection(job, role, evidence):
    """Prefer inspected/changed paths; never mistake the whole tree for a focus."""
    evidence = evidence if isinstance(evidence, dict) else {}
    data = job.get('data', {})
    candidates = []
    if role == 'selected-model reviewer':
        candidates.extend(change.get('path') for change in data.get('changes', [])[:60] if isinstance(change, dict))
    files = evidence.get('files')
    if isinstance(files, dict):
        candidates.extend(list(files)[:60])
    for plan in (evidence.get('previous_plan'), evidence.get('plan'), data.get('plan')):
        if isinstance(plan, dict) and isinstance(plan.get('paths'), list):
            candidates.extend(plan['paths'][:30])
    # Initial planning_paths is already ranked against the objective. Its first
    # page is only a preparation hint; the graph query remains objective-led.
    if not candidates and isinstance(evidence.get('paths'), list):
        candidates.extend(evidence['paths'][:12])
    paths = list(dict.fromkeys(path for path in candidates if isinstance(path, str) and
                               0 < len(path) <= 400 and not any(ord(c) < 32 for c in path)))[:24]
    objective = job.get('request', {}).get('objective', '')
    query = objective[:1400] if isinstance(objective, str) else ''
    # CI context changes the repair search without replaying an unbounded log.
    # This text never enters the content-free receipt below.
    ci = evidence.get('ci')
    if ci and role in {'planner', 'repair planner', 'coding agent', 'selected-model reviewer'}:
        query += '\n' + canonical(ci)[:600]
    return query[:2000], paths


def graph_receipt(graph, job, role):
    """Allowlist values before a summary reaches job/status/diagnostic surfaces."""
    graph = graph if isinstance(graph, dict) else {}
    retrieval = graph.get('retrieval') if isinstance(graph.get('retrieval'), dict) else {}
    metadata = graph.get('graph') if isinstance(graph.get('graph'), dict) else graph
    nodes = graph.get('nodes') if isinstance(graph.get('nodes'), list) else []
    expected = job.get('data', {}).get('head_sha')
    observed = retrieval.get('indexed_sha') or metadata.get('commit_sha')
    status = graph.get('status', retrieval.get('status'))
    status = status if isinstance(status, str) and status in GRAPH_STATUSES else 'UNAVAILABLE'
    reason = retrieval.get('reason', graph.get('reason'))
    reason = reason if isinstance(reason, str) and reason in GRAPH_REASONS else ('RETRIEVAL_FAILED' if status == 'UNAVAILABLE' else None)
    provider = retrieval.get('provider')
    provider = provider if isinstance(provider, str) and provider in GRAPH_PROVIDERS else 'NOT_AVAILABLE'
    path_count = retrieval.get('selected_paths')
    if type(path_count) is not int:
        path_count = len({node.get('path') for node in nodes if isinstance(node, dict) and isinstance(node.get('path'), str)})
    elapsed = retrieval.get('elapsed_ms')
    return public_graph_receipt({'schema_version': 1, 'stage': job.get('state') if job.get('state') in
            {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE', 'VERIFY'} else 'UNKNOWN',
            'role': role if role in GRAPH_ROLES else 'other', 'status': status, 'reason': reason, 'provider': provider,
            'revision_match': bool(expected and observed == expected),
            'coverage_complete': retrieval.get('coverage_complete', metadata.get('coverage_complete', metadata.get('complete'))) is True,
            'processing_complete': retrieval.get('processing_complete', metadata.get('processing_complete')) is True,
            'requested_nodes': len(nodes), 'selected_path_count': max(0, min(path_count, 10000)),
            'cache_hit': retrieval.get('cache_hit') is True, 'prepared_files': retrieval.get('prepared_files'),
            'byte_budget': retrieval.get('byte_budget'), 'returned_bytes': retrieval.get('returned_bytes'),
            'input_bytes': len(canonical(graph).encode()), 'source_digest': fingerprint(graph),
            'elapsed_ms': int(max(0, min(elapsed, 3600000))) if type(elapsed) in {int, float} and math.isfinite(elapsed) else None,
            'included_nodes': 0, 'omitted_nodes': len(nodes), 'view_bytes': 0,
            'view_status': 'NOT_ADMITTED', 'dispatched': False})


def public_graph_receipt(value):
    """Reapply the allowlist to restored journal data before public projection."""
    if not isinstance(value, dict):
        return None
    choices = {'stage': {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE', 'VERIFY', 'UNKNOWN'},
               'role': GRAPH_ROLES | {'other'}, 'status': GRAPH_STATUSES, 'reason': GRAPH_REASONS,
               'provider': GRAPH_PROVIDERS, 'view_status': {'NOT_ADMITTED', 'INCLUDED', 'PARTIAL', 'OMITTED', 'NOT_AVAILABLE'}}
    result = {'schema_version': 1}
    for key, allowed in choices.items():
        candidate = value.get(key)
        result[key] = candidate if isinstance(candidate, str) and candidate in allowed else None
    for key in ('revision_match', 'coverage_complete', 'processing_complete', 'cache_hit', 'dispatched'):
        result[key] = value.get(key) is True
    for key in ('requested_nodes', 'selected_path_count', 'input_bytes', 'elapsed_ms', 'included_nodes', 'omitted_nodes', 'view_bytes',
                'prepared_files', 'byte_budget', 'returned_bytes'):
        candidate = value.get(key)
        result[key] = max(0, min(candidate, 67108864)) if type(candidate) is int else None
    source = value.get('source_digest')
    if isinstance(source, str) and re.fullmatch('[0-9a-f]{64}', source):
        result['source_digest'] = source
    return result


def admitted_graph_receipt(receipt, payload, *, dispatched=False):
    """Measure the actual prompt view, including transport repairs and omissions."""
    result = public_graph_receipt(receipt) or {}
    try:
        body = json.loads(payload['messages'][-1]['content'])
        graph = body.get('code_graph')
    except (KeyError, TypeError, ValueError, IndexError):
        graph = None
    nodes = graph.get('nodes', []) if isinstance(graph, dict) else []
    included = len(nodes) if isinstance(nodes, list) else 0
    requested = result.get('requested_nodes') or 0
    omitted = max(0, requested - included)
    result.update(included_nodes=included, omitted_nodes=omitted,
                  view_bytes=len(canonical(graph).encode()) if graph is not None else 0,
                  dispatched=bool(dispatched))
    result['view_status'] = ('PARTIAL' if omitted and included else 'OMITTED' if requested and not included else
                             'INCLUDED' if included else 'NOT_AVAILABLE')
    return result


def project_graph(graph, maximum):
    """Reserve a small graph view before unrelated evidence consumes context.

    Ranked anchors retain exact source identity; dangling relationships are
    excluded. Index completeness and visible-context completeness are separate.
    """
    if graph is None:
        return None
    if not isinstance(graph, dict):
        return {'status': 'UNAVAILABLE', 'view_complete': False}
    if len(canonical(graph).encode()) <= maximum:
        return graph
    metadata = graph.get('graph') if isinstance(graph.get('graph'), dict) else {}
    nodes = graph.get('nodes') if isinstance(graph.get('nodes'), list) else []
    edges = graph.get('edges') if isinstance(graph.get('edges'), list) else []
    view = {key: graph[key] for key in ('status', 'stale', 'source_level_only', 'runtime_verified')
            if key in graph and isinstance(graph[key], (str, bool)) and len(str(graph[key])) <= 64}
    view['graph'] = {key: metadata[key] for key in ('schema_version', 'repository', 'branch', 'commit_sha',
        'complete', 'coverage_complete', 'processing_complete', 'expected_files', 'processed_files', 'pending_files',
        'unresolved_references', 'source_level_only', 'runtime_verified')
        if key in metadata and type(metadata[key]) in {str, int, bool} and len(str(metadata[key])) <= 400}
    view.update(nodes=[], edges=[], view_complete=False, omitted_nodes=len(nodes), omitted_edges=len(edges),
                omission_reason='CONTEXT_BUDGET')
    # Reserve space for final counts and JSON framing before admitting anchors.
    allowance = maximum - 64
    for original in nodes[:48]:
        if not isinstance(original, dict):
            continue
        node = {key: original[key] for key in ('id', 'path', 'line', 'end_line', 'commit_sha', 'blob_sha', 'name', 'kind')
                if key in original and type(original[key]) in {str, int} and len(str(original[key])) <= 400}
        if not node.get('id') or not node.get('path'):
            continue
        if isinstance(original.get('excerpt'), str):
            node['excerpt'] = original['excerpt'][:min(256, maximum // 8)]
        node['view_complete'] = False
        trial = dict(view, nodes=view['nodes'] + [node])
        if len(canonical(trial).encode()) > allowance:
            node.pop('excerpt', None)
            trial = dict(view, nodes=view['nodes'] + [node])
        if len(canonical(trial).encode()) <= allowance:
            view['nodes'].append(node)
        elif view['nodes']:
            break
    ids = {node['id'] for node in view['nodes']}
    for original in edges[:256]:
        if not isinstance(original, dict) or not isinstance(original.get('source'), str) or not isinstance(original.get('target'), str) or original['source'] not in ids or original['target'] not in ids:
            continue
        edge = {key: original[key] for key in ('source', 'target', 'relation', 'confidence', 'line')
                if key in original and type(original[key]) in {str, int} and len(str(original[key])) <= 400}
        if len(canonical(dict(view, edges=view['edges'] + [edge])).encode()) > allowance:
            break
        view['edges'].append(edge)
    view.update(omitted_nodes=max(0, len(nodes) - len(view['nodes'])), omitted_edges=max(0, len(edges) - len(view['edges'])))
    for key in ('expected_files', 'processed_files', 'pending_files', 'unresolved_references', 'branch', 'repository'):
        if len(canonical(view).encode()) <= maximum:
            break
        view['graph'].pop(key, None)
        view['metadata_view_complete'] = False
    return view
