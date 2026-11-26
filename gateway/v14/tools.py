"""Exact catalog-bound dispatch; display labels and alias prefixes are not owners."""
import collections
import copy
import hashlib
import json
import re
import uuid
import threading
from gateway_security import current_device
from v14.actions import ActionLedger, ActionReplay, ExternalOutcomeUnknown, current_gateway_job_id, current_gateway_tool_call_id, logical_action_id
from v14.contracts import SchemaValidationError, validate_arguments, compile_schema, model_schema
from v14.github import ImmutableGitHubReads
from v14.operations import IMMUTABLE_READ, MUTATION, MUTABLE_READ, operation_class, select_equivalent


_COMPILED_SCHEMA_LIMIT = 2048


class CatalogBindingError(ValueError):
    pass


def install_exact_routing(runtime):
    ledger = ActionLedger(runtime)
    runtime.gateway_action_ledger = ledger
    immutable_reads = ImmutableGitHubReads()
    runtime.gateway_github_reads = immutable_reads
    original_catalog = runtime.get_all_mcp_tools
    compiled = collections.OrderedDict()
    state_lock = threading.RLock()
    runtime.gateway_schema_rejections = {}
    runtime.gateway_schema_metrics = {'compiled': 0, 'cacheHits': 0, 'rejected': 0}
    def revision(servers):
        return hashlib.sha256(json.dumps(servers,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
    def check_scope():
        check = getattr(getattr(runtime, 'gateway_catalogue', None), 'check_discovery_scope', None)
        if callable(check):
            check()

    def compile_catalog(force_refresh=False):
        definitions = original_catalog(force_refresh)
        check_scope()
        names = [item['function'].get('name') for item in definitions
                 if isinstance(item, dict) and isinstance(item.get('function'), dict)
                 and isinstance(item['function'].get('name'), str)]
        counts = collections.Counter(names)
        valid = []
        servers = runtime.load_mcp_config()
        config_revision = revision(servers)
        next_admitted = {}
        with state_lock:
            # Finish reading the old cache before rotating entries. A full scan of
            # a catalog just over capacity still hits its previously retained schemas.
            pending = collections.OrderedDict()
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
                # Compilation depends only on the schema. Bind each admitted alias
                # to its live source, metadata and configuration revision below.
                key = hashlib.sha256(json.dumps(schema, sort_keys=True, separators=(',', ':')).encode()).digest()
                if key in compiled:
                    result = compiled[key]
                    compiled.move_to_end(key)
                    runtime.gateway_schema_metrics['cacheHits'] += 1
                elif key in pending:
                    result = pending[key]
                    pending.move_to_end(key)
                    runtime.gateway_schema_metrics['cacheHits'] += 1
                else:
                    try:
                        result = (compile_schema(schema), model_schema(schema), None)
                        runtime.gateway_schema_metrics['compiled'] += 1
                    except SchemaValidationError as exc:
                        result = (None, None, str(exc))
                        runtime.gateway_schema_metrics['rejected'] += 1
                    if len(pending) == _COMPILED_SCHEMA_LIMIT:
                        pending.popitem(last=False)
                    pending[key] = result
                execution, presentation, rejection = result
                if rejection:
                    runtime.gateway_schema_rejections[name] = {'revision':config_revision,'reason':rejection}
                    continue
                runtime.gateway_schema_rejections.pop(name,None)
                next_admitted[name] = (execution,config_revision,dict(meta))
                valid.append(dict(item, function=dict(fn, description=str(fn.get('description',''))[:240], parameters=copy.deepcopy(presentation))))
            valid = select_equivalent(valid,runtime.mcp_tool_metadata)
            if revision(runtime.load_mcp_config()) != config_revision:
                raise CatalogBindingError('Connection revision changed during catalogue compilation')
            for key, result in pending.items():
                if len(compiled) == _COMPILED_SCHEMA_LIMIT:
                    compiled.popitem(last=False)
                compiled[key] = result
        with runtime.mcp_cache_lock:
            scoped = getattr(getattr(runtime, 'gateway_catalogue', None), 'has_discovery_scope', None)
            if not callable(scoped) or not scoped():
                runtime.mcp_tools_cache = list(valid)
        return valid, next_admitted

    def catalog(force_refresh=False):
        return compile_catalog(force_refresh)[0]

    def dispatch(name, arguments, progress_callback=None, cancel_event=None, call_id=None):
        definitions, bindings = compile_catalog()
        definitions = {item['function']['name']: item for item in definitions}
        if name not in definitions:
            raise CatalogBindingError('Unknown, disabled, malformed or ambiguous gateway tool binding')
        snapshot = bindings.get(name)
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
                check_scope()
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
