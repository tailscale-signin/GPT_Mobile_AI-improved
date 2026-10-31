"""Dependency-aware read bundles over exact, authorized MCP bindings.

Native Android tools stay client-owned. Writes use their existing individual
approval/transaction path and are never accepted by this aggregate read tool.
"""
import contextvars
import hashlib
import json
import random
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait

from gateway_security import current_device
from v14.actions import current_gateway_job_id, canonical_json
from v14.contracts import validate_arguments
from v14.operations import MUTATION, operation_class
from v14.results import bound_result

TOOL_NAME = 'gateway_batch'
MAX_CALLS = 20
MAX_RESULT_BYTES = 8 * 1024 * 1024


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def validate_plan(arguments, definitions, metadata):
    if not isinstance(arguments, dict) or set(arguments) - {'calls'}:
        raise ValueError('A batch requires only calls')
    calls = arguments.get('calls')
    if not isinstance(calls, list) or not 1 <= len(calls) <= MAX_CALLS:
        raise ValueError('A batch requires 1–20 calls')
    ids, nodes = set(), []
    for call in calls:
        if not isinstance(call, dict) or set(call) - {'id', 'tool', 'arguments', 'depends_on'}:
            raise ValueError('Invalid batch call')
        node_id, name, args = call.get('id'), call.get('tool'), call.get('arguments')
        if not isinstance(node_id, str) or not 1 <= len(node_id) <= 64 or node_id in ids:
            raise ValueError('Batch IDs must be unique bounded strings')
        if not isinstance(name, str) or name not in definitions or name == TOOL_NAME or not isinstance(args, dict):
            raise ValueError('Unknown batch tool or malformed arguments')
        meta = metadata.get(name, {})
        # A remote readOnlyHint or a read-looking alias is not authorization.
        if operation_class(meta.get('tool'), args) == MUTATION:
            raise ValueError('Only reviewed read operations may enter a gateway batch')
        validate_arguments(meta.get('inputSchema', definitions[name]['function']['parameters']), args)
        deps = call.get('depends_on', [])
        if not isinstance(deps, list) or any(not isinstance(item, str) for item in deps) or len(set(deps)) != len(deps):
            raise ValueError('Invalid dependencies')
        ids.add(node_id)
        nodes.append({'id': node_id, 'tool': name, 'arguments': args, 'depends_on': deps})
    if any(node['id'] in node['depends_on'] or not set(node['depends_on']) <= ids for node in nodes):
        raise ValueError('Unknown or self dependency')
    checked = set()
    while len(checked) < len(nodes):
        ready = [node['id'] for node in nodes if node['id'] not in checked and set(node['depends_on']) <= checked]
        if not ready:
            raise ValueError('Batch dependency cycle')
        checked.update(ready)
    canonical_json(nodes, maximum_bytes=512 * 1024)
    return nodes


class BatchJournal:
    """Same SQLite journal as gateway jobs/actions; complete receipts precede progress."""
    def __init__(self, runtime):
        self.runtime = runtime

    def transaction(self, operation):
        if not self.runtime.GATEWAY_DURABLE_JOBS:
            raise ValueError('Durable jobs are required for batch execution')
        if not self.runtime.durable_job_initialized:
            self.runtime.init_durable_job_store()
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                with db:
                    db.execute('CREATE TABLE IF NOT EXISTS gateway_batches ('
                               'principal TEXT NOT NULL, job_id TEXT NOT NULL, batch_id TEXT NOT NULL, '
                               'plan_hash TEXT NOT NULL, plan_json TEXT NOT NULL, receipts_json TEXT NOT NULL, '
                               'updated_at REAL NOT NULL, PRIMARY KEY(principal,job_id,batch_id))')
                    return operation(db)
            finally:
                db.close()

    def open(self, principal, job_id, batch_id, plan, binding):
        raw, plan_hash = canonical_json({'plan': plan, 'binding': binding}, maximum_bytes=1024 * 1024)
        def read(db):
            row = db.execute('SELECT plan_hash,receipts_json FROM gateway_batches WHERE principal=? AND job_id=? AND batch_id=?',
                             (principal, job_id, batch_id)).fetchone()
            if row:
                if row[0] != plan_hash:
                    raise ValueError('Batch identity, connection or arguments changed')
                return json.loads(row[1])
            db.execute('DELETE FROM gateway_batches WHERE updated_at<?', (time.time() - 7 * 86400,))
            if db.execute('SELECT COUNT(*) FROM gateway_batches').fetchone()[0] >= 512:
                raise ValueError('Batch journal capacity reached')
            db.execute('INSERT INTO gateway_batches VALUES(?,?,?,?,?,?,?)',
                       (principal, job_id, batch_id, plan_hash, raw, '{}', time.time()))
            return {}
        return self.transaction(read)

    def save(self, principal, job_id, batch_id, receipts):
        raw, _ = canonical_json(receipts, maximum_bytes=MAX_RESULT_BYTES)
        def write(db):
            updated = db.execute('UPDATE gateway_batches SET receipts_json=?,updated_at=? WHERE principal=? AND job_id=? AND batch_id=?',
                                 (raw, time.time(), principal, job_id, batch_id))
            if updated.rowcount != 1:
                raise ValueError('Batch checkpoint disappeared')
        self.transaction(write)


def failed(result):
    return not isinstance(result, dict) or result.get('isError') is True or result.get('error') is not None


def retry_delay(error, attempt):
    """Retry transport failures/explicit throttling, never auth, contracts or writes."""
    response = getattr(error, 'response', None)
    status = getattr(response, 'status_code', None)
    if status is not None and status not in {429, 502, 503, 504}:
        return None
    if status is None:
        import requests
        if not isinstance(error, (TimeoutError, requests.Timeout, requests.ConnectionError)):
            return None
    headers = getattr(response, 'headers', {}) or {}
    try:
        retry_after = max(0, float(headers.get('Retry-After', 0)))
    except (TypeError, ValueError):
        retry_after = 0
    return max(min(retry_after, 120), min(8, .5 * 2 ** attempt) + random.uniform(0, .25))


class BatchExecutor:
    def __init__(self, maximum=20):
        self.maximum = max(1, min(20, maximum))
        self.admission = threading.BoundedSemaphore(2)

    def execute(self, nodes, invoke, receipts=None, checkpoint=lambda results: None,
                progress=lambda value: None, cancel_event=None):
        if not self.admission.acquire(blocking=False):
            raise TimeoutError('Batch capacity occupied')
        cancel = cancel_event if cancel_event is not None else threading.Event()
        results = dict(receipts or {})
        if not set(results) <= {node['id'] for node in nodes}:
            self.admission.release()
            raise ValueError('Batch receipts do not match the plan')
        started, width, healthy = time.monotonic(), min(5, self.maximum), 0
        pending = [node for node in nodes if node['id'] not in results]
        active = {}
        def report():
            progress({'progress': len(results), 'total': len(nodes), 'active': len(active),
                      'queued': len(pending), 'concurrency': width,
                      'failed': sum(failed(value) for value in results.values()),
                      'elapsed_ms': round((time.monotonic() - started) * 1000),
                      'message': f'Batch {len(results)}/{len(nodes)} · {len(active)} running · {len(pending)} queued'})
        def run(node):
            before = time.monotonic()
            for attempt in range(3):
                if cancel.is_set():
                    raise InterruptedError('Batch canceled')
                try:
                    return invoke(node), (time.monotonic() - before) * 1000, attempt
                except Exception as error:
                    delay = retry_delay(error, attempt)
                    if delay is None or attempt == 2:
                        # No source, arguments, credentials or exception text in telemetry.
                        return {'isError': True, 'error': {'code': 'BATCH_READ_FAILED', 'type': type(error).__name__}}, (time.monotonic() - before) * 1000, attempt
                    if cancel.wait(delay):
                        raise InterruptedError('Batch canceled during retry backoff')
        try:
            with ThreadPoolExecutor(max_workers=self.maximum, thread_name_prefix='github-batch') as pool:
                report()
                while pending or active:
                    if cancel.is_set():
                        raise InterruptedError('Batch canceled; completed receipts retained')
                    for node in pending[:]:
                        if not set(node['depends_on']) <= results.keys():
                            continue
                        if any(failed(results[dep]) for dep in node['depends_on']):
                            results[node['id']] = {'isError': True, 'error': {'code': 'DEPENDENCY_FAILED'}, 'dispatched': False}
                            pending.remove(node)
                            checkpoint(results)
                            continue
                        if len(active) >= width:
                            continue
                        # Identical reads in this bundle share a receipt. A dependency
                        # cannot be skipped just because another node has equal arguments.
                        key = digest([node['tool'], node['arguments']])
                        same = next((other for other in nodes if other['id'] in results and
                                     digest([other['tool'], other['arguments']]) == key and not failed(results[other['id']])), None)
                        if same:
                            results[node['id']] = results[same['id']]
                            pending.remove(node)
                            checkpoint(results)
                            continue
                        if any(digest([other['tool'], other['arguments']]) == key for other in active.values()):
                            continue
                        context = contextvars.copy_context()
                        active[pool.submit(context.run, run, node)] = node
                        pending.remove(node)
                    report()
                    if not active:
                        if pending:
                            raise ValueError('Batch dependencies cannot advance')
                        break
                    done, _ = wait(active, timeout=.1, return_when=FIRST_COMPLETED)
                    for future in done:
                        node = active.pop(future)
                        result, elapsed, retries = future.result()
                        results[node['id']] = result
                        checkpoint(results)
                        if failed(result) or retries or elapsed >= 15000:
                            width, healthy = max(1, width // 2), 0
                        else:
                            healthy += 1
                            if healthy >= width:
                                width, healthy = min(self.maximum, width + 1), 0
                        report()
            return results
        finally:
            self.admission.release()


def install_batch(runtime):
    catalog, dispatch, route = runtime.get_all_mcp_tools, runtime.execute_mcp_tool, runtime.get_gateway_tool_route
    journal, executor = BatchJournal(runtime), BatchExecutor()
    runtime.gateway_batch_executor = executor
    definition = {'type': 'function', 'function': {
        'name': TOOL_NAME,
        'description': 'Retrieve 1–20 independent GitHub reads in one durable batch. Use exact advertised PC tool names and fully specified arguments. depends_on contains call IDs in this batch. Writes, client tools and approvals must use their individual tools after reading the results.',
        'parameters': {'type': 'object', 'additionalProperties': False, 'required': ['calls'], 'properties': {
            'calls': {'type': 'array', 'minItems': 1, 'maxItems': MAX_CALLS, 'items': {
                'type': 'object', 'additionalProperties': False, 'required': ['id', 'tool', 'arguments'], 'properties': {
                    'id': {'type': 'string', 'minLength': 1, 'maxLength': 64}, 'tool': {'type': 'string'},
                    'arguments': {'type': 'object'}, 'depends_on': {'type': 'array', 'items': {'type': 'string'}, 'maxItems': MAX_CALLS}}}}}}}}

    def list_tools(force_refresh=False):
        definitions = catalog(force_refresh)
        if runtime.GATEWAY_DURABLE_JOBS and any(operation_class(runtime.mcp_tool_metadata.get(item['function']['name'], {}).get('tool'), {}) != MUTATION for item in definitions):
            return definitions + [definition]
        return definitions

    def execute(name, arguments, progress_callback=None, cancel_event=None, call_id=None):
        if name != TOOL_NAME:
            return dispatch(name, arguments, progress_callback, cancel_event, call_id=call_id)
        principal, job_id = current_device.get(), current_gateway_job_id.get()
        if not principal or principal == 'unowned' or not job_id or not call_id:
            raise ValueError('Batch execution requires a trusted principal, job and call ID')
        definitions = {item['function']['name']: item for item in catalog()}
        nodes = validate_plan(arguments, definitions, runtime.mcp_tool_metadata)
        binding = digest([runtime.load_mcp_config(), definitions])
        batch_id = digest(str(call_id))
        receipts = journal.open(principal, job_id, batch_id, nodes, binding)
        def invoke(node):
            if digest([runtime.load_mcp_config(), {item['function']['name']: item for item in catalog()}]) != binding:
                raise ValueError('Batch connection revision changed')
            return runtime._execute_mcp_tool_single(node['tool'], node['arguments'], cancel_event=cancel_event,
                                                    call_id='batch_' + digest([call_id, node['id']]))
        results = executor.execute(nodes, invoke, receipts, lambda value: journal.save(principal, job_id, batch_id, value),
                                   progress_callback or (lambda value: None), cancel_event)
        body = {'schema': 'gateway.batch.v1', 'batch_id': batch_id, 'job_id': job_id,
                'complete': not any(failed(value) for value in results.values()),
                'results': [{'id': node['id'], 'result': json.loads(bound_result(results[node['id']], 16000))} for node in nodes]}
        return {'content': [{'type': 'text', 'text': json.dumps(body, ensure_ascii=False)}],
                'isError': not body['complete'], 'gateway_engine_calls': len(nodes)}

    def owned_route(name, client_tool_names=None, llama_native_names=None):
        if name == TOOL_NAME and name not in set(client_tool_names or []):
            return 'gateway_mcp'
        return route(name, client_tool_names, llama_native_names)
    runtime.get_all_mcp_tools = list_tools
    runtime.execute_mcp_tool = execute
    runtime.get_gateway_tool_route = owned_route
