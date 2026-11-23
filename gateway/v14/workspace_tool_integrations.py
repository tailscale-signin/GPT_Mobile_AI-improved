"""Opt-in tools through the existing exact MCP dispatcher and durable ledger.

Host policy identifies exact allowed tools. A familiar server name never grants
access, and catalogue annotations are not treated as mutation permissions.
"""
import copy
import re
from contextlib import contextmanager

from gateway_security import current_device
from v14.actions import ActionContext
from v14.contracts import validate_arguments
from v14.engineering_validation import load_policy
from v14.execution_binding import canonical, fingerprint
from v14.workspace_process import identifier
from v14.workspace_checkpoint import save

INTEGRATIONS = ('context7', 'playwright', 'github')
GITHUB_COMPLEMENTARY_READS = frozenset({'list_code_scanning_alerts', 'get_code_scanning_alert',
    'list_secret_scanning_alerts', 'get_secret_scanning_alert', 'list_dependabot_alerts', 'get_dependabot_alert'})


def configuration(backend, job):
    policy, *_ = load_policy(job['request']['repository'])
    if fingerprint(policy) != job['data'].get('admission', {}).get('policy_sha256'):
        raise ValueError('Development tools require the unchanged admitted repository policy')
    rows = policy.get('workspace_integrations', {})
    return validate_configuration(rows)


def validate_configuration(rows):
    """Shared host editor/runtime grammar; this performs no discovery or I/O."""
    if not isinstance(rows, dict) or set(rows) - set(INTEGRATIONS):
        raise ValueError('Unknown workspace integration policy')
    for name, row in rows.items():
        if (not isinstance(row, dict) or set(row) - {'enabled', 'tools', 'allow_remote', 'allow_mutations'} or
                type(row.get('enabled', False)) is not bool or type(row.get('allow_remote', False)) is not bool or
                type(row.get('allow_mutations', False)) is not bool or not isinstance(row.get('tools', []), list) or
                len(row.get('tools', [])) > 32 or any(not isinstance(tool, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', tool)
                                                   for tool in row.get('tools', []))):
            raise ValueError('Integrations need explicit enabled, remote, mutation and exact tool settings')
    return rows


def _local_only(job):
    binding = job.get('execution_binding') or {}
    return 'LOCAL_ONLY' in binding.get('required_capability_policy', []) or job.get('request', {}).get('private') is True


def _cancellation(backend, job):
    cancel = backend.cancellation(job)
    if cancel.is_set():
        raise InterruptedError('Integration cancelled before discovery')
    return cancel


def _tools(name, entry, catalog):
    configured = entry.get('tools', [])
    if name != 'github':
        return configured
    allowed = []
    for tool in configured:
        try:
            _, operation = catalog.tool_connection(tool)
        except (AttributeError, PermissionError):
            continue
        if operation in GITHUB_COMPLEMENTARY_READS:
            allowed.append(tool)
    return allowed


@contextmanager
def _discovery(backend, job, config, tools):
    cancel = _cancellation(backend, job)
    principal = job.get('principal')
    if not principal:
        raise PermissionError('The parent job principal is required before discovery')
    catalog = getattr(backend.runtime, 'gateway_catalogue', None)
    if not callable(getattr(catalog, 'discovery_scope', None)):
        raise PermissionError('Scoped catalogue discovery is unavailable')
    def admitted():
        if job.get('principal') != principal or _local_only(job) or configuration(backend, job) != config:
            raise PermissionError('The parent job or integration policy changed before discovery')
    token = current_device.set(principal)
    try:
        with catalog.discovery_scope(tools, cancel_event=cancel, admit=admitted):
            # The exact router refreshes this same catalogue again during dispatch.
            # Keep both refreshes inside the invocation-local connection scope.
            backend.runtime.get_all_mcp_tools()
            yield cancel
    finally:
        current_device.reset(token)


def status(backend, job, *, discover=False):
    if discover:
        _cancellation(backend, job)
    try:
        config = copy.deepcopy(configuration(backend, job))
    except (OSError, ValueError, KeyError):
        return {'state': 'SETUP_REQUIRED', 'integrations': [dict(id=name, state='SETUP_REQUIRED', tools=[]) for name in INTEGRATIONS]}
    runtime = getattr(backend, 'runtime', None)
    catalog = getattr(runtime, 'gateway_catalogue', None)
    local_only = _local_only(job)
    # Discovery can start processes and network reads before any tools/call.
    # A blocked job or host policy must remain passive at this boundary too.
    if discover and not local_only and callable(getattr(runtime, 'get_all_mcp_tools', None)):
        tools = [tool for name, entry in config.items() if entry.get('enabled') and entry.get('allow_remote')
                 for tool in _tools(name, entry, catalog)]
        if tools:
            with _discovery(backend, job, config, tools):
                pass
    metadata = copy.deepcopy(getattr(runtime, 'mcp_tool_metadata', {}))
    rows = []
    for name in INTEGRATIONS:
        entry = config.get(name, {})
        configured_names = entry.get('tools', [])
        names = _tools(name, entry, catalog)
        if not entry.get('enabled'):
            state, readiness = 'DISABLED', {}
        elif name == 'github' and configured_names and not names:
            state, readiness = 'NATIVE_GITHUB_SELECTED', {}
        elif local_only or not entry.get('allow_remote'):
            state, readiness = 'LOCAL_ONLY_POLICY', {}
        elif catalog is None or not callable(getattr(runtime, '_execute_mcp_tool_single', None)):
            state, readiness = 'RUNTIME_UNAVAILABLE', {}
        else:
            readiness = catalog.task_readiness(names)
            state = 'READY_FOR_DISPATCH' if names and readiness['ready'] else 'DISCOVERY_REQUIRED'
        tools = []
        for tool in names:
            spec = metadata.get(tool, {})
            tools.append({'name': tool, 'input_schema': spec.get('inputSchema'),
                          'operation': spec.get('tool'), 'advertised': bool(spec.get('inputSchema'))})
        rows.append(dict(id=name, state=state, tools=tools, readiness=readiness,
                         mutations_enabled=name == 'playwright' and entry.get('allow_mutations') is True,
                         suppressed_tool_count=len(configured_names) - len(names)))
    return {'state': 'AVAILABLE', 'integrations': rows, 'execution_qualification': 'Exact observed operations only'}


def call(backend, job, action):
    name, tool, arguments = action.get('integration'), action.get('tool'), action.get('arguments', {})
    invocation = identifier(action.get('invocation_id'))
    if name not in INTEGRATIONS or not isinstance(arguments, dict) or len(canonical(arguments).encode()) > 16384:
        raise ValueError('Choose a configured integration and at most 16 KiB of structured arguments')
    _cancellation(backend, job)
    configs = copy.deepcopy(configuration(backend, job))
    entry = configs.get(name, {})
    catalog = getattr(backend.runtime, 'gateway_catalogue', None)
    if (not entry.get('enabled') or not entry.get('allow_remote') or _local_only(job) or
            tool not in _tools(name, entry, catalog)):
        raise PermissionError('This exact integration tool is not currently admitted for this job')
    with _discovery(backend, job, configs, [tool]):
        return _call_admitted(backend, job, configs, name, tool, arguments, invocation)


def _call_admitted(backend, job, configs, name, tool, arguments, invocation):
    runtime = backend.runtime
    if not runtime.gateway_catalogue.task_readiness([tool])['ready']:
        raise PermissionError('This exact integration tool is not currently admitted for this job')
    if getattr(runtime, 'gateway_action_ledger', None) is None:
        raise ValueError('The exact durable MCP execution wrapper is required')
    metadata = copy.deepcopy(runtime.mcp_tool_metadata.get(tool, {}))
    validate_arguments(metadata['inputSchema'], arguments)
    # Fixed reviewed reads, never a remote readOnlyHint. All other tools require
    # the host's explicit mutation setting and the job's existing write gate.
    read_operations = {'resolve-library-id', 'query-docs', 'get-library-docs',
                       'browser_snapshot', 'browser_take_screenshot', 'browser_console_messages', 'browser_network_requests'}
    readonly = metadata.get('tool') in read_operations or name == 'github' and metadata.get('tool') in GITHUB_COMPLEMENTARY_READS
    store = getattr(getattr(backend, 'private_artifacts', None), 'store', None)
    if store is None or not callable(getattr(store, 'assert_claim', None)):
        raise ValueError('The active parent execution lease is required before tool dispatch')
    store.assert_claim(job)
    if not readonly:
        from v14.engineering_scope import accepted_scope
        if name != 'playwright' or not configs[name].get('allow_mutations') or accepted_scope(job['request']) != 'implement':
            raise PermissionError('This configured browser tool requires explicit host and implementation authority')
        # Browser interaction is a scoped tool operation, not a GitHub commit.
        # Reuse current job authority without making COMMIT/MERGE phases writable
        # from the workspace or granting a second repository mutation path.
        owner = getattr(getattr(backend, 'operations', None), 'service', None)
        if owner is None or not owner.authorized(job):
            raise PermissionError('Current parent job authority is required for browser interaction')
        selected = owner.bindings.read(job['principal'], job['request'].get('execution_binding_id'))
        if selected != job.get('execution_binding') or job['data'].get('execution_integrity') == 'CONTRADICTED':
            raise PermissionError('The browser operation no longer matches the admitted execution identity')
    current = configuration(backend, job)
    if current != configs:
        raise PermissionError('The integration policy changed before dispatch')
    if backend.private_artifacts is None:
        raise ValueError('Private result storage is required')
    cancellation = backend.cancellation(job)
    if cancellation.is_set():
        raise InterruptedError('Integration cancelled before dispatch')
    scope = fingerprint([job['data']['head_sha'], job.get('execution_binding'), configs, name, tool, arguments])
    prior = job['data'].get('workspace_integrations', {}).get(invocation)
    if prior and prior.get('scope_binding') != scope:
        raise ValueError('This invocation ID belongs to a different source, policy or tool request')
    if prior and prior.get('result_ref'):
        return dict(prior)
    pending = {'invocation_id': invocation, 'integration': name, 'tool': tool, 'source_sha': job['data']['head_sha'],
               'scope_binding': scope, 'status': 'DISPATCH_PENDING'}
    job['data'].setdefault('workspace_integrations', {})[invocation] = pending
    save(backend, job, 'workspace_integrations', pending, member=invocation)
    token = current_device.set(job['principal'])
    # The existing dispatcher journals before I/O and fences changed invocation
    # arguments, stale catalogues and unknown outcomes across process restarts.
    call_id = 'workspace_' + invocation
    try:
        with ActionContext(job['id'], call_id):
            value = runtime._execute_mcp_tool_single(tool, arguments, cancel_event=cancellation, call_id=call_id)
    finally:
        current_device.reset(token)
    reference = backend.private_artifacts.put_json(job, value, 'workspace-integration-result')
    result = {'scope_binding': scope, 'integration': name, 'tool': tool, 'invocation_id': invocation, 'result_ref': reference,
              'status': 'FAILED' if isinstance(value, dict) and (value.get('isError') or value.get('error')) else 'COMPLETED',
              'source_sha': job['data']['head_sha'], 'execution_owner': 'EXISTING_MCP_DISPATCHER',
              'evidence_scope': 'External tool output; not source execution or acceptance proof'}
    job['data'].setdefault('workspace_integrations', {})[invocation] = result
    return result
