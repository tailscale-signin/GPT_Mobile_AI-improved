"""Normalized, bounded capability inventory shared by Android and Windows.

Only tools returned by the admitted Gateway discovery surface are projected.
The legacy discovery API cannot prove that an empty server is complete, so the
inventory deliberately remains PARTIAL instead of turning missing tools into a
zero or claiming downstream readiness.
"""
from collections import Counter
from copy import deepcopy
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


def _tool_row(source, name, stable, contract, function, metadata, health, metrics):
    parameters = function.get('parameters') or {}
    properties = parameters.get('properties') if isinstance(parameters, dict) else {}
    properties = properties if isinstance(properties, dict) else {}
    observed = metrics.get(name, {})
    durations = observed.get('durations', [])
    return {
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


def register_local_tool(runtime, definition, authorized=None):
    # External tools can advertise the same wire name. Only the installer's own
    # definition can represent this local route in the authorized inventory.
    if not hasattr(runtime, 'gateway_local_inventory_tools'):
        runtime.gateway_local_inventory_tools = {}
    runtime.gateway_local_inventory_tools[definition['function']['name']] = (definition, authorized)


class InventoryChanged(ValueError):
    """A continuation cannot use a changed catalog or a different principal."""


def capability_inventory(runtime, tracker, principal=None, now=time.time, *, page_size=None, cursor=None):
    if page_size is not None and (type(page_size) is not int or not 1 <= page_size <= 256):
        raise ValueError('Inventory pages must contain between 1 and 256 tools.')
    if cursor is not None and (page_size is None or not isinstance(cursor, str) or not re.fullmatch(r'[a-f0-9]{64}:[1-9][0-9]{0,9}', cursor)):
        raise ValueError('Invalid inventory cursor.')
    config_reader = getattr(runtime, 'load_mcp_config', None)
    before_discovery = deepcopy(config_reader()) if callable(config_reader) else None
    discover = getattr(runtime, 'get_all_mcp_tools', None)
    discovery_error = None
    try:
        definitions = discover() if callable(discover) else []
    except Exception:
        definitions, discovery_error = [], 'Gateway MCP discovery is temporarily unavailable.'
    if not isinstance(definitions, list):
        definitions = []
    descriptors = []
    sources = Counter()
    catalogue = getattr(runtime, 'gateway_catalogue', None)
    observed_config = config_reader() if callable(config_reader) else None
    if observed_config != before_discovery:
        definitions, discovery_error = [], 'Gateway MCP connections changed during discovery.'
    local_tools = getattr(runtime, 'gateway_local_inventory_tools', {})
    bindings = getattr(runtime, 'mcp_tool_metadata', {})
    configured = observed_config if isinstance(observed_config, dict) else {}
    source_health = {}
    config_keys = {}
    for source, config in configured.items():
        config_keys[source] = hashlib.sha256(json.dumps(config, sort_keys=True, separators=(',', ':'), default=str).encode()).hexdigest()
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
        if name in local_tools:
            installed, authorized = local_tools[name]
            if definition is not installed or (principal is not None and authorized is not None and not authorized(principal)):
                continue
            source, metadata, health = 'gateway-mcp', {}, {}
        else:
            metadata = bindings.get(name) if isinstance(bindings, dict) else None
            if not isinstance(metadata, dict) or not isinstance(metadata.get('server'), str):
                continue
            source = metadata['server']
            if not source or source != _text(source, 80) or (isinstance(observed_config, dict) and source not in configured):
                continue
            health = source_health.get(source, {})
            if health.get('authorized') is False or health.get('catalogRejected') or configured.get(source, {}).get('enabled') is False:
                continue
        contract = hashlib.sha256(json.dumps(parameters, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        stable = hashlib.sha256(f'{source}\0{name}\0{contract}'.encode()).hexdigest()
        descriptors.append((source, name, stable, contract, function, metadata, health))
        sources[source] += 1
    descriptors.sort(key=lambda item: (item[0].casefold(), item[1].casefold(), item[2]))
    discovered_count = len(descriptors)
    # Bind continuations to the configured source and permissions even when two servers
    # report the same names and argument schemas. Configuration values stay local.
    states = [(s, config_keys[s], source_health[s].get('authorized'), source_health[s].get('schemaKnown'),
               source_health[s].get('connectionHealthy'), source_health[s].get('catalogRejected'),
               source_health[s].get('executionTool'), source_health[s].get('executionAvailable'),
               source_health[s].get('reasonCode')) for s in sorted(configured)]
    fingerprint = hashlib.sha256(json.dumps([principal, states, discovery_error], separators=(',', ':')).encode())
    for _, _, stable, _, function, _, _ in descriptors:
        fingerprint.update(stable.encode())
        fingerprint.update(hashlib.sha256(str(function.get('description') or '').encode()).digest())
    lineage = fingerprint.hexdigest()
    offset = 0
    if cursor is not None:
        previous, position = cursor.split(':')
        offset = int(position)
        if previous != lineage or offset >= discovered_count:
            raise InventoryChanged('Tool inventory changed. Restart discovery.')
    end = min(offset + (page_size or MAX_CAPABILITIES), discovered_count)
    has_more = end < discovered_count
    metrics = _metric_rows(tracker, principal)
    rows = [_tool_row(*item, metrics) for item in descriptors[offset:end]]
    visible_by_source = Counter(row['sourceId'] for row in rows)
    remaining_sources = {item[0] for item in descriptors[end:]}
    observed_at = now()
    source_rows = []
    for source in sorted(set(sources) | set(configured)):
        health = source_health.get(source, {})
        enabled = configured.get(source, {}).get('enabled') is not False
        source_complete = bool(enabled and health.get('authorized', True) and health.get('schemaKnown') and health.get('connectionHealthy') and not health.get('catalogRejected'))
        source_rows.append({
            'sourceId': source,
            'discoveryState': 'COMPLETE' if source_complete else 'BLOCKED' if not enabled or health.get('authorized') is False else 'PARTIAL',
            'discoveredCount': sources.get(source, 0),
            'authorizedCount': sources.get(source, 0) if health.get('authorized', True) else 0,
            'visibleCount': visible_by_source[source],
            'hasMore': source in remaining_sources,
            'lastVerifiedAt': health.get('lastDiscoverySuccessAtMs', 0) / 1000 if isinstance(health.get('lastDiscoverySuccessAtMs'), int) else None,
            'reason': 'MCP_CONNECTION_DISABLED' if not enabled else _text(health.get('reasonCode'), 160) or None,
        })
    complete = bool(configured) and not discovery_error and all(row['discoveryState'] == 'COMPLETE' for row in source_rows)
    catalog_status = 'COMPLETE' if complete else 'BLOCKED' if discovery_error else 'NOT_DISCOVERED' if not configured and not rows else 'PARTIAL'
    status = 'PARTIAL' if complete and (has_more or offset) else catalog_status
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
        **({'pageOffset': offset, 'catalogStatus': catalog_status, 'nextCursor': f'{lineage}:{end}' if has_more else None}
           if page_size is not None else {}),
    }
