"""Exact catalog-bound dispatch; display labels and alias prefixes are not owners."""
import collections
import hashlib
import json
import re
import uuid
from gateway_security import current_device
from v14.actions import ActionLedger, ActionReplay, ExternalOutcomeUnknown, current_gateway_job_id, current_gateway_tool_call_id, logical_action_id
from v14.contracts import SchemaValidationError, validate_arguments, validate_schema


class CatalogBindingError(ValueError):
    pass


def install_exact_routing(runtime):
    ledger = ActionLedger(runtime)
    runtime.gateway_action_ledger = ledger
    original_catalog = runtime.get_all_mcp_tools
    def catalog(force_refresh=False):
        definitions = original_catalog(force_refresh)
        names = [item['function'].get('name') for item in definitions
                 if isinstance(item, dict) and isinstance(item.get('function'), dict)
                 and isinstance(item['function'].get('name'), str)]
        counts = collections.Counter(names)
        valid = []
        servers = runtime.load_mcp_config()
        for item in definitions:
            if not isinstance(item, dict) or not isinstance(item.get('function'), dict):
                continue
            fn = item['function']
            name = fn.get('name')
            if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', name) or counts[name] != 1:
                continue
            meta = runtime.mcp_tool_metadata.get(name, {})
            if meta.get('server') not in servers or not isinstance(meta.get('tool'), str) or name != meta['server'] + '_' + meta['tool']:
                continue
            schema = fn.get('parameters', {})
            if not isinstance(schema, dict):
                continue
            try:
                validate_schema(schema)
            except SchemaValidationError:
                continue
            valid.append(dict(item, function=dict(fn, parameters=schema)))
        with runtime.mcp_cache_lock:
            runtime.mcp_tools_cache = list(valid)
        return valid

    def dispatch(name, arguments, progress_callback=None, cancel_event=None, call_id=None):
        definitions = {item['function']['name']: item for item in catalog()}
        if name not in definitions:
            raise CatalogBindingError('Unknown, disabled, malformed or ambiguous gateway tool binding')
        meta = runtime.mcp_tool_metadata[name]
        if meta['server'] not in runtime.load_mcp_config():
            raise CatalogBindingError('Connection no longer enabled')
        schema = definitions[name]['function']['parameters']
        try:
            validate_arguments(schema, arguments)
        except SchemaValidationError as exc:
            raise CatalogBindingError('Tool arguments did not satisfy the admitted schema') from exc
        if cancel_event is not None and cancel_event.is_set():
            raise InterruptedError('Tool canceled before dispatch')
        schema_digest = hashlib.sha256(json.dumps(schema, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()
        binding = {'server': meta['server'], 'tool': meta['tool'], 'schemaDigest': schema_digest,
                   'bindingRevision': 'gptmobile.tool-binding.v1'}
        model_call_id = str(call_id or current_gateway_tool_call_id.get() or "") or None
        job_id = str(current_gateway_job_id.get() or ("internal_" + model_call_id if model_call_id else "internal_" + uuid.uuid4().hex))
        principal_id = str(current_device.get() or 'unowned')
        action_id = logical_action_id(job_id, binding, arguments)
        admitted = ledger.begin(principal_id, job_id, action_id, binding, arguments, model_call_id=model_call_id)
        if isinstance(admitted, ActionReplay):
            return admitted.result
        if admitted is not None:
            raise ExternalOutcomeUnknown('Prior gateway action state is not replayable')
        try:
            result = runtime.mcp_call(meta['server'], 'tools/call', {'name': meta['tool'], 'arguments': arguments},
                                      progress_callback=progress_callback, cancel_event=cancel_event)
        except BaseException:
            ledger.uncertain(principal_id, job_id, action_id)
            raise
        return ledger.finish(principal_id, job_id, action_id, result)
    runtime.get_all_mcp_tools = catalog
    runtime._execute_mcp_tool_single = dispatch
