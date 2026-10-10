"""Exact catalog-bound dispatch; display labels and alias prefixes are not owners."""
import collections
import re


class CatalogBindingError(ValueError):
    pass


def install_exact_routing(runtime):
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
            if not isinstance(schema, dict) or schema.get('type', 'object') != 'object' or not isinstance(schema.get('properties', {}), dict) or not isinstance(schema.get('required', []), list):
                continue
            if not all(isinstance(field, str) for field in schema.get('required', [])) or not set(schema.get('required', [])) <= schema.get('properties', {}).keys():
                continue
            valid.append(dict(item, function=dict(fn, parameters=schema)))
        with runtime.mcp_cache_lock:
            runtime.mcp_tools_cache = list(valid)
        return valid

    def dispatch(name, arguments, progress_callback=None, cancel_event=None):
        definitions = {item['function']['name']: item for item in catalog()}
        if name not in definitions:
            raise CatalogBindingError('Unknown, disabled, malformed or ambiguous gateway tool binding')
        meta = runtime.mcp_tool_metadata[name]
        if meta['server'] not in runtime.load_mcp_config():
            raise CatalogBindingError('Connection no longer enabled')
        schema = definitions[name]['function']['parameters']
        if not isinstance(arguments, dict) or any(field not in arguments for field in schema.get('required', [])):
            raise CatalogBindingError('Required tool arguments missing')
        if cancel_event is not None and cancel_event.is_set():
            raise InterruptedError('Tool canceled before dispatch')
        return runtime.mcp_call(meta['server'], 'tools/call', {'name': meta['tool'], 'arguments': arguments},
                                progress_callback=progress_callback, cancel_event=cancel_event)
    runtime.get_all_mcp_tools = catalog
    runtime._execute_mcp_tool_single = dispatch
