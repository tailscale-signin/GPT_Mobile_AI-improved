"""Normalized, bounded capability inventory shared by Android and Windows.

Only tools returned by the admitted Gateway discovery surface are projected.
The legacy discovery API cannot prove that an empty server is complete, so the
inventory deliberately remains PARTIAL instead of turning missing tools into a
zero or claiming downstream readiness.
"""
import hashlib
import json
import math
import re
import time


MAX_CAPABILITIES = 2048


def _text(value, limit):
    return ''.join(char for char in str(value or '') if char.isprintable())[:limit]


def _percentile(values, percentile):
    values = sorted(value for value in values if isinstance(value, (int, float)) and value >= 0)
    if not values:
        return None
    return round(values[max(0, math.ceil(len(values) * percentile) - 1)] * 1000)


def _metric_rows(tracker, principal):
    metrics = {}
    for activity in tracker.snapshot(principal).get('activities', []):
        if activity.get('kind') not in {'tool', 'search', 'github'}:
            continue
        name = activity.get('title')
        if not isinstance(name, str) or not name:
            continue
        row = metrics.setdefault(name, {'total': 0, 'success': 0, 'failure': 0, 'durations': [], 'lastUsed': None})
        row['total'] += 1
        status = str(activity.get('status') or '').lower()
        row['success'] += int(status == 'completed')
        row['failure'] += int(status == 'failed')
        duration = activity.get('duration')
        if status == 'completed' and isinstance(duration, (int, float)) and duration >= 0:
            row['durations'].append(duration)
        updated = activity.get('updated')
        if isinstance(updated, (int, float)):
            row['lastUsed'] = max(updated, row['lastUsed'] or 0)
    return metrics


def capability_inventory(runtime, tracker, principal=None, now=time.time):
    discover = getattr(runtime, 'get_all_mcp_tools', None)
    discovery_error = None
    try:
        definitions = discover() if callable(discover) else []
    except Exception:
        definitions, discovery_error = [], 'Gateway MCP discovery is temporarily unavailable.'
    if not isinstance(definitions, list):
        definitions = []
    metrics = _metric_rows(tracker, principal)
    rows = []
    sources = {}
    catalogue = getattr(runtime, 'gateway_catalogue', None)
    observed_config = runtime.load_mcp_config() if callable(getattr(runtime, 'load_mcp_config', None)) else None
    configured = observed_config if isinstance(observed_config, dict) else {}
    source_health = {}
    for source, config in configured.items():
        if catalogue is None or not callable(getattr(catalogue, 'key', None)):
            source_health[source] = {}
            continue
        try:
            key = catalogue.key(source, config)
            source_health[source] = dict(catalogue.health.get(key, {}))
        except (AttributeError, TypeError, ValueError):
            source_health[source] = {}
    for definition in definitions:
        function = definition.get('function') if isinstance(definition, dict) else None
        if not isinstance(function, dict):
            continue
        name = function.get('name')
        if not isinstance(name, str) or not name or len(name) > 200 or any(not char.isprintable() for char in name):
            continue
        parameters = function.get('parameters') if isinstance(function.get('parameters'), dict) else {}
        properties = parameters.get('properties') if isinstance(parameters.get('properties'), dict) else {}
        metadata = runtime.mcp_tool_metadata.get(name, {}) if isinstance(getattr(runtime, 'mcp_tool_metadata', None), dict) else {}
        source = _text(metadata.get('server'), 80) or 'gateway-mcp'
        health = source_health.get(source, {})
        if isinstance(observed_config, dict) and metadata.get('server') and source not in configured:
            continue
        if health.get('authorized') is False or (source in configured and configured[source].get('enabled') is False):
            continue
        contract = hashlib.sha256(json.dumps(parameters, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        stable = hashlib.sha256(f'{source}\0{name}\0{contract}'.encode()).hexdigest()
        observed = metrics.get(name, {})
        durations = observed.get('durations', [])
        row = {
            'id': stable,
            'featureId': 'external-connections',
            'name': name,
            'sourceId': source,
            'category': 'GitHub' if name.lower().startswith('github_') else 'Web' if any(word in name.lower() for word in ('web', 'search', 'browse')) else 'Gateway MCP',
            'contractVersion': contract,
            'description': _text(function.get('description'), 400),
            'inputFields': sorted(field for field in properties if isinstance(field, str) and re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}', field))[:32],
            'authorized': True,
            'enabled': True,
            # A same-contract execution observation establishes readiness only
            # for the exact tool that produced it. Discovery alone does not.
            'executableNow': health.get('executionAvailable') if metadata.get('tool') and health.get('executionTool') == metadata.get('tool') else None,
            'blockedReason': _text(health.get('reasonCode'), 160) or None,
            'metrics': {
                'scope': 'retained_gateway_activity',
                'total': observed.get('total', 0),
                'success': observed.get('success', 0),
                'failure': observed.get('failure', 0),
                'p50Millis': _percentile(durations, .50),
                'p95Millis': _percentile(durations, .95),
                'sampleCount': len(durations),
                'lastUsed': observed.get('lastUsed'),
            },
        }
        rows.append(row)
        sources[source] = sources.get(source, 0) + 1
    rows.sort(key=lambda item: (item['sourceId'].casefold(), item['name'].casefold()))
    discovered_count = len(rows)
    has_more = discovered_count > MAX_CAPABILITIES
    rows = rows[:MAX_CAPABILITIES]
    lineage = hashlib.sha256('\n'.join(f"{row['id']}:{row['contractVersion']}" for row in rows).encode()).hexdigest()
    observed_at = now()
    source_rows = []
    for source in sorted(set(sources) | set(configured)):
        health = source_health.get(source, {})
        enabled = configured.get(source, {}).get('enabled') is not False
        source_complete = bool(enabled and health.get('authorized', True) and health.get('schemaKnown') and health.get('connectionHealthy'))
        source_rows.append({
            'sourceId': source,
            'discoveryState': 'COMPLETE' if source_complete else 'BLOCKED' if not enabled or health.get('authorized') is False else 'PARTIAL',
            'discoveredCount': sources.get(source, 0),
            'authorizedCount': sources.get(source, 0) if health.get('authorized', True) else 0,
            'visibleCount': sum(row['sourceId'] == source for row in rows),
            'hasMore': sources.get(source, 0) > sum(row['sourceId'] == source for row in rows),
            'lastVerifiedAt': health.get('lastDiscoverySuccessAtMs', 0) / 1000 if isinstance(health.get('lastDiscoverySuccessAtMs'), int) else None,
            'reason': 'MCP_CONNECTION_DISABLED' if not enabled else _text(health.get('reasonCode'), 160) or None,
        })
    complete = bool(configured) and not discovery_error and not has_more and all(row['discoveryState'] == 'COMPLETE' for row in source_rows)
    status = 'COMPLETE' if complete else 'BLOCKED' if discovery_error else 'NOT_DISCOVERED' if not configured and not rows else 'PARTIAL'
    reason = (discovery_error or
              ('All configured, authorized Gateway MCP catalogs were fully discovered.' if complete else
               'Gateway MCP discovery is bounded and visible; unavailable sources and capabilities outside the selected Gateway remain explicit.'))
    return {
        'schema': 'gptmobile.gateway.capability-inventory.v1',
        'schemaVersion': 1,
        'status': status,
        'scope': 'selected_gateway_authorized_mcp',
        'observedAt': observed_at,
        'discoveredCount': discovered_count,
        'authorizedCount': discovered_count,
        'visibleCount': len(rows),
        'hasMore': has_more,
        'cursorLineage': lineage,
        'reason': reason,
        'sources': source_rows,
        'tools': rows,
    }
