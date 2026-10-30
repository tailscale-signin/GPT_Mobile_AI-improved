"""Exact catalog-bound dispatch; display labels and alias prefixes are not owners."""
import collections
import hashlib
import json
import re
import uuid
import threading
from gateway_security import current_device
from v14.actions import ActionLedger, ActionReplay, ExternalOutcomeUnknown, current_gateway_job_id, current_gateway_tool_call_id, logical_action_id
from v14.contracts import SchemaValidationError, validate_arguments, validate_schema, compile_schema, model_schema
from v14.github import ImmutableGitHubReads
from v14.operations import IMMUTABLE_READ, MUTATION, MUTABLE_READ, operation_class, select_equivalent


class CatalogBindingError(ValueError):
    pass


def install_exact_routing(runtime):
    ledger = ActionLedger(runtime)
    runtime.gateway_action_ledger = ledger
    immutable_reads = ImmutableGitHubReads()
    runtime.gateway_github_reads = immutable_reads
    original_catalog = runtime.get_all_mcp_tools
    compiled = {}
    admitted = {}
    state_lock = threading.RLock()
    runtime.gateway_schema_rejections = {}
    runtime.gateway_schema_metrics = {'compiled': 0, 'cacheHits': 0, 'rejected': 0}
    def revision(servers):
        return hashlib.sha256(json.dumps(servers,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
    def catalog(force_refresh=False):
        nonlocal admitted
        definitions = original_catalog(force_refresh)
        names = [item['function'].get('name') for item in definitions
                 if isinstance(item, dict) and isinstance(item.get('function'), dict)
                 and isinstance(item['function'].get('name'), str)]
        counts = collections.Counter(names)
        valid = []
        servers = runtime.load_mcp_config()
        config_revision = revision(servers)
        next_admitted = {}
        with state_lock:
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
                schema = meta.get('inputSchema', fn.get('parameters', {}))
                if not isinstance(schema, dict):
                    continue
                key = (config_revision, name, hashlib.sha256(json.dumps(schema,sort_keys=True,separators=(',',':')).encode()).hexdigest())
                if key not in compiled:
                    try:
                        compiled[key] = (compile_schema(schema), model_schema(schema), None)
                        runtime.gateway_schema_metrics['compiled'] += 1
                    except SchemaValidationError as exc:
                        compiled[key] = (None,None,str(exc))
                        runtime.gateway_schema_metrics['rejected'] += 1
                    while len(compiled) > 2048: compiled.pop(next(iter(compiled)))
                else: runtime.gateway_schema_metrics['cacheHits'] += 1
                execution, presentation, rejection = compiled[key]
                if rejection:
                    runtime.gateway_schema_rejections[name] = {'revision':config_revision,'reason':rejection}
                    continue
                runtime.gateway_schema_rejections.pop(name,None)
                next_admitted[name] = (execution,config_revision,dict(meta))
                valid.append(dict(item, function=dict(fn, description=str(fn.get('description',''))[:240], parameters=presentation)))
            valid = select_equivalent(valid,runtime.mcp_tool_metadata)
            if revision(runtime.load_mcp_config()) != config_revision:
                raise CatalogBindingError('Connection revision changed during catalogue compilation')
            admitted = next_admitted
        with runtime.mcp_cache_lock:
            runtime.mcp_tools_cache = list(valid)
        return valid

    def dispatch(name, arguments, progress_callback=None, cancel_event=None, call_id=None):
        definitions = {item['function']['name']: item for item in catalog()}
        if name not in definitions:
            raise CatalogBindingError('Unknown, disabled, malformed or ambiguous gateway tool binding')
        with state_lock:
            snapshot = admitted.get(name)
        if snapshot is None:
            raise CatalogBindingError("Binding changed during dispatch admission")
        schema, config_revision, meta = snapshot
        if meta['server'] not in runtime.load_mcp_config():
            raise CatalogBindingError('Connection no longer enabled')
        if revision(runtime.load_mcp_config()) != config_revision:
            raise CatalogBindingError('Connection revision changed before dispatch')
        try:
            validate_arguments(schema, arguments)
        except SchemaValidationError as exc:
            raise CatalogBindingError('Tool arguments did not satisfy the admitted schema') from exc
        if cancel_event is not None and cancel_event.is_set():
            raise InterruptedError('Tool canceled before dispatch')
        schema_digest = hashlib.sha256(json.dumps(schema, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()
        binding = {'server': meta['server'], 'tool': meta['tool'], 'schemaDigest': schema_digest,
                   'bindingRevision': 'gptmobile.tool-binding.v2', 'connectionRevision': config_revision}
        model_call_id = str(call_id or current_gateway_tool_call_id.get() or "") or None
        job_id = str(current_gateway_job_id.get() or ("internal_" + model_call_id if model_call_id else "internal_" + uuid.uuid4().hex))
        principal_id = str(current_device.get() or 'unowned')
        kind = operation_class(meta['tool'], arguments)
        identity_binding = dict(binding)
        if kind == MUTABLE_READ:
            identity_binding['observationId'] = model_call_id or uuid.uuid4().hex
        action_id = logical_action_id(job_id, identity_binding, arguments)
        decision = ledger.begin(principal_id, job_id, action_id, binding, arguments, model_call_id=model_call_id, operation_kind=kind)
        if isinstance(decision, ActionReplay):
            return decision.result
        if decision is not None:
            raise ExternalOutcomeUnknown('Prior gateway action state is not replayable')
        try:
            def invoke():
                # Recheck connection revision after queueing, immediately before I/O.
                if revision(runtime.load_mcp_config()) != config_revision:
                    raise CatalogBindingError('Connection revision changed before tool I/O')
                return runtime.mcp_call(meta['server'], 'tools/call', {'name': meta['tool'], 'arguments': arguments},
                                        progress_callback=progress_callback, cancel_event=cancel_event)
            if kind == IMMUTABLE_READ and principal_id != 'unowned':
                cache_key = immutable_reads.key(principal_id, binding, arguments)
                result = immutable_reads.execute(cache_key, invoke, cancel_event)
                if revision(runtime.load_mcp_config()) != config_revision:
                    raise CatalogBindingError('Connection revision changed while reading')
            else:
                result = invoke()
        except BaseException:
            ledger.uncertain(principal_id, job_id, action_id)
            raise
        return ledger.finish(principal_id, job_id, action_id, result)
    runtime.get_all_mcp_tools = catalog
    runtime._execute_mcp_tool_single = dispatch
