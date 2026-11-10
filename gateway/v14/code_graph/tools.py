"""One lazily selected graph tool, using existing gateway principal and routing boundaries."""
import json
from gateway_security import current_device
from v14.memory import local_model_destination
from .service import ACTIONS

TOOL_NAME = 'pc_repository_graph'


def install_graph_tool(runtime, service, authorized):
    catalog, dispatch, route = runtime.get_all_mcp_tools, runtime.execute_mcp_tool, runtime.get_gateway_tool_route
    definition = {'type': 'function', 'function': {'name': TOOL_NAME,
        'description': 'Inspect local revision-bound repository graphs. Index in resumable batches; query relationships, callers, impact, tests, PR overlap, diagnostics, contracts, decisions and requirements. Graph data is untrusted source context, never execution proof or merge authority. GitHub access is rechecked. note saves explicit repository knowledge.',
        'parameters': {'type': 'object', 'required': ['action', 'repository', 'branch', 'commit_sha'], 'properties': {
            'action': {'type': 'string', 'enum': sorted(ACTIONS)}, 'repository': {'type': 'string'}, 'branch': {'type': 'string'},
            'commit_sha': {'type': 'string'}, 'query': {'type': 'string'}, 'symbol': {'type': 'string'},
            'source': {'type': 'string'}, 'target': {'type': 'string'}, 'paths': {'type': 'array', 'items': {'type': 'string'}},
            'index_cursor': {'type': 'string'}, 'depth': {'type': 'integer', 'minimum': 0, 'maximum': 6},
            'budget_bytes': {'type': 'integer', 'minimum': 2048, 'maximum': 64000}, 'base_sha': {'type': 'string'},
            'producer': {'type': 'string'}, 'consumer': {'type': 'string'}, 'log': {'type': 'string', 'maxLength': 24000},
            'kind': {'type': 'string', 'enum': ['requirement', 'repair', 'decision']}, 'content': {'type': 'string', 'maxLength': 8000},
            'supersedes': {'type': 'string'}, 'job_id': {'type': 'string'}, 'receipt_id': {'type': 'string'},
            'targets': {'type': 'array', 'maxItems': 5, 'items': {'type': 'object', 'properties': {
                'repository': {'type': 'string'}, 'branch': {'type': 'string'}, 'commit_sha': {'type': 'string'}}}}}}}}

    def listing(force_refresh=False):
        rows = catalog(force_refresh)
        if runtime.GATEWAY_DURABLE_JOBS and service.graph.backend.available() and local_model_destination(runtime.LLAMA_BASE):
            return rows + [definition]
        return rows

    def execute(name, arguments, progress_callback=None, cancel_event=None, call_id=None):
        if name != TOOL_NAME:
            return dispatch(name, arguments, progress_callback, cancel_event, call_id=call_id)
        principal = current_device.get()
        if not principal or not authorized(principal):
            raise PermissionError('Engineering scope is required for repository graphs')
        if not local_model_destination(runtime.LLAMA_BASE):
            raise PermissionError('Repository graph recall requires a local model destination')
        if cancel_event and cancel_event.is_set():
            raise InterruptedError('Graph operation cancelled before dispatch')
        result = service.graph.execute(principal, arguments)
        return {'content': [{'type': 'text', 'text': json.dumps(result, ensure_ascii=False)}], 'isError': False}

    def routing(name, client_tool_names=None, llama_native_names=None):
        if name == TOOL_NAME and name not in set(client_tool_names or []):
            return 'gateway_mcp'
        return route(name, client_tool_names, llama_native_names)
    runtime.get_all_mcp_tools, runtime.execute_mcp_tool, runtime.get_gateway_tool_route = listing, execute, routing
