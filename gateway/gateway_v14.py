"""v14 composition root over the characterized v13 execution engine."""
import argparse
import os
import sys
import re
import asyncio
import uuid
import json
import sqlite3
import copy
import hashlib
import importlib.metadata
from pathlib import Path

from v14 import VERSION
from v14.config import Config
from v14.package import validate_package

config = Config.from_env()
# Validate even direct gateway_v14 imports; do not trust a version label.
manifest = validate_package(Path(__file__).parent)


def build_identity():
    build = {'sourceCommit': manifest['sourceCommit'], 'manifestVersion': 1, 'status': 'development'}
    if getattr(sys, 'frozen', False):
        packaged = json.loads((Path(__file__).parent / 'build-info.json').read_text(encoding='utf-8'))
        if not re.fullmatch('[a-f0-9]{40}', packaged.get('sourceCommit', '')):
            raise ValueError('Invalid build provenance')
        build.update(packaged)
        build['status'] = 'packaged'
    return build


import gateway_v13 as runtime
from fastapi import FastAPI, Request
from starlette.datastructures import Headers
from fastapi.responses import JSONResponse, Response, StreamingResponse
from v14.identity import Admission, principal_can_chat
from v14.research import run_searches
from v14.jobs import install_terminal_guard, migrate_legacy_owner, TERMINAL, DurableCommitError
from v14.errors import GatewayFailure
from v14.deadlines import request_deadline
from v14.actions import IdempotencyConflict, IdempotencyExpired
from v14.events import replay_snapshot
from v14.memory import apply_client_recall
from v14.tools import install_exact_routing
from v14.github import install_github_fast_path
from v14.batch import install_batch
from v14.catalog import install_catalog_cache
from v14.browser import BOOTSTRAP_PATH, LANDING_PATHS, gateway_landing, install_browser_proxy
from v14.service import ServiceState, LoadGuard
from v14.activity import ActivityTracker, ActivityMiddleware, desktop_snapshot, client_snapshot
from v14.mcp import install_mcp_resilience
from v14.health import readiness, diagnostics
from v14.backend_observation import model_response, backend_root
from v14.debug import DebugLogStore, DebugMiddleware, lifecycle as debug_lifecycle, install_routes as install_debug_routes
from v14.journal import backup_database
from v14.engineering import EngineeringStore, EngineeringService, principal_authorized
from gateway_security import device_store
from v14.engineering_backend import GitHubEngineeringBackend
from v14.engineering_routes import install_engineering_routes
from v14.usage import install_usage
from v14.conversations import admission_context, ConversationConflict, QueueFull, FEATURES
from v14.job_catalog import install_routes as install_conversation_routes
from v14.profile_metadata import profile_metadata
from v14.conversation_policy import conversation_retention, verify_mutation_identity, MUTATION_IDENTITY_HEADERS, ORDINARY_RETENTION_POLICY
from v14.execution_binding import ExecutionBindingError, configured_route, fingerprint

runtime.GATEWAY_VERSION = VERSION
runtime.AUTO_MEMORY_RETRIEVE = False
runtime.AUTO_MEMORY_STORE = False
runtime.AUTO_LEARN_FROM_TOOL_RESULTS = False
runtime.MEMORY_BEHAVIOR_PROMPT = "Personal memory is client-authoritative. Do not invent recalled facts or store a competing vault."
runtime.run_searches = run_searches
runtime.gateway_replay_snapshot = replay_snapshot
install_terminal_guard(runtime)
_old_config = runtime.load_mcp_config


def approved_mcp_config():
    # Preserve the config file and Angruvadal data for rollback. PC memory has
    # no authority handshake yet, so exclude known memory servers in v14.
    return {name: value for name, value in _old_config().items() if not runtime.is_memory_tool_name(name) and "memory" not in name.lower()}



runtime.load_mcp_config = approved_mcp_config
install_catalog_cache(runtime)
install_exact_routing(runtime)
install_mcp_resilience(runtime)
install_browser_proxy(runtime, config)
install_github_fast_path(runtime)
install_batch(runtime)
install_usage(runtime)
service = ServiceState(config.max_workers)
service.install(runtime)
runtime.gateway_service = service
service.authorized = lambda principal: principal_can_chat(config, principal, runtime.device_store)
activity = ActivityTracker(os.getenv("GATEWAY_ACTIVITY_STATE"))
activity.install_tools(runtime)
engineering = EngineeringService(EngineeringStore(runtime), GitHubEngineeringBackend(runtime),
                                 public_repository_maps=config.public_repository_maps)
runtime.gateway_engineering = engineering
engineering.authorized = lambda job: principal_authorized(config, job['principal'], device_store)
engineering.backend.cancelled = lambda job_id: (engineering.store.get(job_id) or {}).get('state') in {'CANCELLED', 'CANCELLING'}
from v14.code_graph.tools import install_graph_tool
install_graph_tool(runtime, engineering, lambda principal: principal_authorized(config, principal, device_store))
debug_log = DebugLogStore(os.getenv("GATEWAY_DEBUG_LOG") or str(Path(runtime.GATEWAY_JOB_DB_PATH).with_name("gateway-debug.jsonl")),
                          enabled=os.getenv("GATEWAY_DEBUG_MODE", "false").lower() == "true")
runtime.gateway_debug = debug_log
from v14.diagnostic_support import engineering_inventory
debug_log.support_inventory = lambda principal, session: engineering_inventory(engineering, principal, session)
try:
    diagnostic_route = configured_route(runtime, str(getattr(runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper())
except ExecutionBindingError:
    diagnostic_route = {'runtime_config_revision': fingerprint({'invalid_backend': runtime.LLAMA_BASE}),
                        'endpoint_identity': fingerprint(runtime.LLAMA_BASE), 'provider_kind': None}
debug_log.configure_identity(gatewayVersion=VERSION, sourceCommit=build_identity()['sourceCommit'],
                             buildStatus=build_identity()['status'],
                             manifestDigest=hashlib.sha256((Path(__file__).parent / 'manifest-v14.json').read_bytes()).hexdigest(),
                             instanceId=service.instance_id, backendType=diagnostic_route['provider_kind'],
                             backendIdentity=diagnostic_route['endpoint_identity'],
                             configurationFingerprint=fingerprint({'backend': diagnostic_route['runtime_config_revision'],
                                                                   'mode': config.mode, 'durableJobs': runtime.GATEWAY_DURABLE_JOBS}),
                             startupReason=os.getenv('GATEWAY_STARTUP_REASON', 'process_start'))
app = FastAPI(title="Private Gateway 16", version=VERSION, lifespan=debug_lifecycle(debug_log, engineering.lifecycle(service.lifespan(runtime, config.shutdown_grace))),
              docs_url="/gateway/docs", openapi_url="/gateway/openapi.json")
app.add_middleware(LoadGuard, config=config, state=service)
app.add_middleware(ActivityMiddleware, tracker=activity)
app.add_middleware(Admission, config=config)
app.add_middleware(DebugMiddleware, store=debug_log, instance_id=service.instance_id)
install_debug_routes(app, debug_log)
install_engineering_routes(app, engineering)
install_conversation_routes(app, runtime.gateway_conversations, engineering, service, lambda: runtime.device_store)
from v14.maintenance import install_maintenance
install_maintenance(app, runtime, service, engineering)


@app.get("/gateway/admin/dashboard")
async def dashboard_snapshot():
    return await asyncio.to_thread(desktop_snapshot, runtime, service, activity)


@app.get("/gateway/dashboard")
@app.get("/v1/gateway/dashboard")
async def mobile_dashboard_snapshot(request: Request):
    return await asyncio.to_thread(client_snapshot, runtime, service, activity, request.state.principal, device_store)


@app.post("/v1/chat/completions")
@app.post("/chat/completions")
async def chat(request: Request):
    payload = None
    context_token = None
    stage = "request_validation"
    request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
    def rejected(code, message, status=400, retryable=False, details=None):
        source = getattr(request, "_json", payload)
        source = source if isinstance(source, dict) else {}
        job_id = request.headers.get("x-gateway-job-id") or source.get("gateway_job_id") or source.get("_gateway_job_id")
        error = {"code": code, "failureCode": code, "message": message,
                 "retryable": retryable, "failed_stage": stage,
                 "dispatch_state": "not_dispatched" if stage != "model_execution" else "unknown",
                 **(details or {})}
        if isinstance(job_id, str) and len(job_id) <= 256:
            error["job_id"] = job_id
        if request_id and len(request_id) <= 256:
            error["request_id"] = request_id
        return JSONResponse({"error": error}, status_code=status)
    try:
        payload = await request.json()
        runtime.validate_chat_request(payload)
        verify_mutation_identity(request.headers, request.state.principal.id, runtime.gateway_conversations.gateway_id)
        presentation = profile_metadata(request.headers)
        retention = conversation_retention(payload, request.headers, presentation)
        request.state.gateway_conversation_retention = retention
        supplied_profile = (payload.get('gateway') or {}).get('profile_id')
        if supplied_profile and presentation['profile_id'] and supplied_profile != presentation['profile_id']:
            raise ConversationConflict('Conflicting original profile identities')
        stage = "run_control_validation"
        try:
            request_deadline(payload)
        except ValueError:
            return JSONResponse({"error": {"code": "invalid_v14_request", "failureCode": "invalid_request",
                                             "message": "The gateway run-control fields are unsupported or invalid.", "retryable": False}}, status_code=400)
        stage = "recall_preparation"
        request._json = apply_client_recall(payload, runtime.LLAMA_BASE)
        stage = "job_admission"
        metadata = payload.get('gateway') or {}
        command_id = metadata.get('command_id') or request.headers.get('idempotency-key') or request_id
        if metadata.get('command_id') and metadata.get('store_conversation') is not False:
            # The existing durable request ledger coalesces lost-response retries.
            # The client gives each tool-continuation segment its own command ID.
            supplied = request.headers.get('idempotency-key')
            if supplied and supplied != command_id:
                raise ConversationConflict('Conflicting command and idempotency identities')
            request.scope['headers'] = [(key, value) for key, value in request.scope.get('headers', []) if key.lower() != b'idempotency-key'] + [(b'idempotency-key', command_id.encode())]
            request._headers = Headers(scope=request.scope)
        context_token = admission_context.set({'principal': request.state.principal.id, 'command_id': command_id,
                                               'payload': copy.deepcopy(request._json), 'profile': presentation, 'retention': retention})
        response = await runtime.chat_completions(request)
        if isinstance(response, StreamingResponse):
            # Registration happens before the first keepalive. Prime it here,
            # while a failed journal commit can still return an HTTP 503.
            iterator = response.body_iterator
            first = await anext(iterator)
            async def admitted_stream():
                try:
                    yield first
                    async for chunk in iterator:
                        yield chunk
                finally:
                    await iterator.aclose()
            response.body_iterator = admitted_stream()
        job_id = response.headers.get('x-gateway-job-id')
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id) or {}
            if job.get('gateway_id'):
                response.headers['X-Gateway-ID'] = job['gateway_id']
                response.headers['X-Gateway-Principal-ID'] = job['principal_id']
                response.headers['X-Gateway-Conversation-ID'] = job['conversation_id']
                response.headers['X-Gateway-Command-ID'] = command_id
            response.headers['X-Gateway-Detach-Safe'] = 'true' if job.get('_durable_background_admitted') and not job.get('_ephemeral') else 'false'
        response.headers['Access-Control-Expose-Headers'] = response.headers.get('Access-Control-Expose-Headers', '') + ', X-Gateway-ID, X-Gateway-Principal-ID, X-Gateway-Conversation-ID, X-Gateway-Command-ID, X-Gateway-Detach-Safe'
        return response
    except ExecutionBindingError as error:
        return rejected(error.code, str(error), error.status)
    except QueueFull:
        return JSONResponse({'error': {'code': 'queue_full', 'retryable': True, 'dispatch_state': 'not_dispatched'}}, status_code=429)
    except ConversationConflict as error:
        return JSONResponse({'error': {'code': 'conversation_conflict', 'message': str(error), 'retryable': False, 'dispatch_state': 'not_dispatched'}}, status_code=409)
    except DurableCommitError:
        return JSONResponse({"error": {"code": "durable_commit_failed", "failureCode": "persistence_failed",
                                         "message": "The gateway could not durably commit this run.", "retryable": True}}, status_code=503)
    except GatewayFailure as exc:
        return JSONResponse(exc.payload(), status_code=exc.status_code)
    except IdempotencyConflict as exc:
        return JSONResponse({"error": {"code": "idempotency_conflict", "failureCode": "invalid_request",
                                         "message": str(exc), "retryable": False}}, status_code=409)
    except IdempotencyExpired as exc:
        return JSONResponse({"error": {"code": "idempotency_expired", "failureCode": "invalid_request",
                                         "message": str(exc), "retryable": False}}, status_code=409)
    except PermissionError:
        return JSONResponse({"error": {"code": "principal_or_destination_denied", "failureCode": "permission_denied",
                                         "message": "The gateway principal is not permitted to run this request.", "retryable": False}}, status_code=403)
    except runtime.GatewayContractError as exc:
        # These contract messages describe schema/state, never request content.
        # Keep the code/status so clients do not retry deterministic rejection.
        if exc.code == "context_capacity_unverified":
            stage = "context_admission"
        elif exc.status >= 500:
            stage = "model_execution"
        elif exc.code.startswith(("client_tool_", "terminal_job_")):
            stage = "tool_continuation"
        return rejected(exc.code, str(exc), exc.status, details=exc.details)
    except ValueError:
        # Unknown exception text may contain prompt content. Preserve location and
        # identifiers without exposing raw input or misclassifying it as verification.
        return rejected("invalid_v14_request", "The gateway rejected the request during " + stage + ". Correct the request before retrying.")
    finally:
        if context_token is not None:
            admission_context.reset(context_token)


@app.post("/gateway/jobs/{job_id}/cancel")
@app.post("/v1/gateway/jobs/{job_id}/cancel")
def cancel(job_id: str, request: Request):
    try:
        verify_mutation_identity(request.headers, request.state.principal.id, runtime.gateway_conversations.gateway_id)
    except ExecutionBindingError as error:
        return JSONResponse({'error': {'code': error.code, 'message': str(error), 'retryable': False}}, status_code=error.status)
    runtime.ensure_gateway_job_loaded(job_id)
    with runtime.job_registry_lock:
        job = runtime.job_registry.get(job_id)
        if job and job.get("status") in TERMINAL:
            canceled = job["status"] in {"cancelled", "canceled"}
            return {"job_id": job_id, "canceled": canceled, "cancellationRequested": False,
                    "cancellationOutcome": "confirmed" if canceled else "not_canceled",
                    "status": job["status"], "message": "Job already terminal"}
        return runtime.cancel_gateway_job(job_id)


def capabilities(model=""):
    build = build_identity()
    result = runtime.gateway_capabilities()
    diagnostic_snapshot = debug_log.snapshot()
    gateway_id = None
    if runtime.GATEWAY_DURABLE_JOBS:
        try:
            gateway_id = runtime.gateway_conversations.gateway_id()
        except (DurableCommitError, sqlite3.Error, OSError, ValueError):
            pass
    result.update({"gatewayVersion": VERSION, "contractVersion": 1,
                   "build": build,
                   "accepted_request_headers": ["X-Gateway-Job-ID", "X-Gateway-Performance-Profile", "X-Gateway-Slot-Pinning", "X-Gateway-Slot-Count", "X-Gateway-Cache-Prompt", "X-Gateway-Cache-Reuse", "X-Gateway-Reasoning-Effort", "X-Gateway-Tool-Optimization", "X-Gateway-Tool-Surface-Limit", "X-Gateway-Stable-Tool-Surface", "X-Gateway-Intermediate-Max-Tokens", "X-Gateway-Soft-Synthesis-Round", "X-Gateway-Result-Char-Limit", "X-Gateway-Delegated-Worker", "X-Gateway-Allow-Local-Tools", "X-Gateway-Request-Role", "X-Gateway-Attempt-ID", "X-Gateway-Client-Diagnostics", "X-Gateway-Diagnostic-Session", "X-Gateway-Diagnostic-Started", "traceparent"],
                   "auth": {"mode": config.mode, "apiKeyRequired": False, "legacyBearerEnabled": config.legacy_bearer},
                   "desktop": {"managed": bool(config.management_token), "maintenanceProtocol": 1,
                               "stateSchema": 1, "updateAuthority": "local-manager", "idleUpdates": True},
                   "identityScope": "shared-network" if config.mode == "open_network" else "user" if config.mode == "trusted_proxy" else "local-machine",
                   "diagnostics": {key: diagnostic_snapshot.get(key) for key in ("protocolVersion", "clientMetadata", "bidirectionalEvents", "retainedLineLimit", "recordByteLimit", "debugEnabled", "storageReady", "storageChecked")},
                   "instanceId": service.instance_id, "gateway_id": gateway_id, "gatewayId": gateway_id,
                   "mutationIdentityPreconditions": MUTATION_IDENTITY_HEADERS,
                   "ordinaryConversationRetention": ORDINARY_RETENTION_POLICY,
                   "memory": {"authority": "client", "protocolVersion": 1, "workspaceAdapterEnabled": False},
                   "supportedMcpEras": ["legacy", "2026-07-28"], "eventVersions": [runtime.GATEWAY_PROGRESS_PROTOCOL],
                   "mcpTransportAdapters": {"stdio": ["legacy", "2026-07-28"], "streamableHttp": [], "continuationCapabilities": []},
                   "contracts": {
                       "gatewayProgress": [runtime.GATEWAY_PROGRESS_PROTOCOL],
                       "gatewayDashboard": ["gptmobile.gateway.dashboard.v1"],
                       "toolArguments": ["gptmobile.tool-arguments.v1"]
                   },
                   "features": dict(result["features"], keyFreePrivateAccess=config.mode != "open_network", keyFreeNetworkAccess=config.mode == "open_network", deterministicSearch=True,
                                    structuredSearch=True, terminalStateProtection=True, memory_novelty_filter=False, structuredEvidence=False, mcpFacade=False,
                                    modernMcp=True, clientMemoryAuthority=True, packageValidation=True,
                                    validatedToolArguments=True, githubClientOwnedFastPath=True,
                                    githubImmutableReadCache=True, githubPerformanceMetrics=True, intelligentToolBatch=True, mobileDashboard=True,
                                    batchDependencies=True, batchDurableReceipts=True, durableEngineeringJobs=gateway_id is not None,
                                    shaBoundMergeApproval=True, engineeringSupervisor=True, repositoryCodeGraph=True,
                                    loopbackBrowserCsrf=config.mode == "loopback", boundedAdmission=True,
                                    versionedJournal=True, mcpCircuitBreakers=True, supervisedShutdown=True,
                                    selectedModelLimits=True, mutation_identity_preconditions_v1=True,
                                    ordinary_conversation_retention_v1=True, **{key: gateway_id is not None for key in FEATURES})})
    result['accepted_request_headers'].extend(['X-Gateway-Profile-ID', 'X-Gateway-Profile-Name-Base64', 'X-Gateway-Conversation-Retention', *MUTATION_IDENTITY_HEADERS])
    result['endpoints'].update(identity='/gateway/identity', job_catalog='/gateway/job-catalog', conversations='/gateway/conversations', conversation_messages='/gateway/conversations/{conversation_id}/messages', command='/gateway/commands/{command_id}')
    result['headers'].update(gateway_id='X-Gateway-ID', principal_id='X-Gateway-Principal-ID', conversation_id='X-Gateway-Conversation-ID', command_id='X-Gateway-Command-ID', detach_safe='X-Gateway-Detach-Safe')
    result['contracts'].update(gatewayJobCatalog=['gptmobile.gateway.job-catalog.v1'], gatewayConversations=['gptmobile.gateway.conversations.v1'])
    if model:
        result['selectedModel'] = engineering.capabilities.selected_model(model)
    return result


@app.get("/gateway/capabilities")
@app.get("/v1/gateway/capabilities")
async def v14_capabilities(request: Request, model: str = ""):
    if len(model) > 512 or model != model.strip() or any(ord(c) < 32 for c in model):
        return JSONResponse({'error': {'code': 'invalid_model_identifier'}}, status_code=400)
    result = await asyncio.to_thread(capabilities, model)
    result["principal_id"] = request.state.principal.id
    return result


@app.get("/gateway/v16")
@app.get("/v1/gateway/v16")
@app.get("/gateway/v15")
@app.get("/v1/gateway/v15")
@app.get("/gateway/v14")
@app.get("/v1/gateway/v14")
def v14_contract():
    return capabilities()


@app.get("/gateway/ready")
@app.get("/v1/gateway/ready")
async def ready(model: str = ""):
    if len(model) > 512:
        return JSONResponse({"error": {"code": "invalid_model_identifier"}}, status_code=400)
    result = await asyncio.to_thread(readiness, runtime, service, model)
    return JSONResponse(result, status_code=200 if result['ready'] else 503)


@app.get('/models')
@app.get('/v1/models')
async def observed_models():
    status, body = await asyncio.to_thread(model_response, runtime)
    return JSONResponse(body, status_code=status, headers={'Cache-Control': 'no-store'})


@app.get('/gateway/live')
@app.get('/v1/gateway/live')
def live():
    return {'live': True, 'version': VERSION, 'instanceId': service.instance_id, 'draining': service.snapshot()['draining']}


@app.get('/gateway/admin/status')
async def admin_status():
    return await asyncio.to_thread(diagnostics, runtime, service)


@app.get("/gateway/connections")
@app.get("/v1/gateway/connections")
def connections():
    # Only IDs/state; commands, environment and credential values stay private.
    return {"schemaVersion": 1, "connections": [
        {"serviceId": name, "connectionUid": "gateway:" + name, "owner": "gateway", "kind": "pc-managed-mcp",
         "enabled": value.get("enabled", True), "supportedActions": ["configure-locally"], "health": "not-probed"}
        for name, value in sorted(approved_mcp_config().items())]}


@app.api_route("/mcp", methods=["GET", "POST", "DELETE"])
def disabled_facade():
    return JSONResponse({"error": {"code": "mcp_facade_disabled", "message": "Configure the existing external MCP bridge; v14 facade is not certified"}}, status_code=404)


@app.get(BOOTSTRAP_PATH)
def browser_bootstrap():
    return Response(Path(__file__).with_name("v14").joinpath("browser-bootstrap.js").read_text(encoding="utf-8"),
                    media_type="application/javascript", headers={"Cache-Control": "no-store"})


@app.get("/sw.js")
def disabled_service_worker():
    return Response("self.addEventListener('activate', () => self.registration.unregister());", media_type="application/javascript")


@app.get("/gateway/admin/doctor")
async def local_doctor(model: str = ''):
    if len(model) > 512:
        return JSONResponse({'error': {'code': 'invalid_model_identifier'}}, status_code=400)
    return await asyncio.to_thread(doctor, model)


async def browser_entry(request: Request):
    if config.mode == "open_network":
        return gateway_landing()
    return await runtime.proxy_to_llama(request, request.scope["route"].path[1:])


for landing_path in LANDING_PATHS:
    app.add_api_route(landing_path, browser_entry, methods=["GET", "HEAD"], include_in_schema=False)


# New routes precede the retained catch-all UI proxy. Authentication is replaced
# on this app only; the v13 app remains usable through its original launcher.
app.router.routes.extend(runtime.app.router.routes)


def doctor(model=''):
    health = readiness(runtime, service, model)
    try:
        target = backend_root(runtime)
    except ExecutionBindingError:
        target = None
    selected = engineering.capabilities.selected_model(model) if model else None
    return {"version": VERSION, "manifest": "valid", "sourceCommit": manifest["sourceCommit"],
            "bindMode": config.mode, "apiKeyRequired": False, "memoryAuthority": "client",
            "mcpFacade": "disabled", "modernMcp": "stdio-adapter", "backend": health['checks']['backend'],
            "configuredBackend": {'metadataBase': target, 'modelListPath': '/v1/models', 'healthPath': '/health'},
            "selectedModel": selected, "readiness": health, "inferenceTested": False,
            "nextSteps": [
                'Run this doctor on the gateway host with --model and the exact selected model ID.',
                'For connection refusal or timeout, check the configured backend listener, address, port and local firewall.',
                'For denied metadata, authorize gateway-to-backend metadata access; keep remote gateway restrictions enabled.',
                'For an advertised but unloaded llama.cpp router model, run prepare-model --model with that exact ID on the gateway host, then rerun doctor. This explicit command sends at most one load request and reconciles its outcome.',
                'For invalid metadata or context, verify the backend version and expose the loaded model allocation.',
                'Confirm readiness and verified selected-model metadata before a separate generation smoke test.'],
            "mcpTransportAdapters": capabilities()['mcpTransportAdapters'],
            "loadedModules": {"launcher": str(Path(__file__).with_name("gateway.py")), "composition": __file__, "runtime": runtime.__file__},
            "dependencies": {name: importlib.metadata.version(name) for name in ("fastapi", "uvicorn", "requests", "httpx")},
            "stability": diagnostics(runtime, service),
            "remainingGates": ["Windows install/rollback", "LAN/firewall acceptance", "Android build", "physical phone"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=["serve", "doctor", "prepare-model", "validate-config", "migrate-owner", "backup-journal"], default="serve")
    parser.add_argument("--legacy-device")
    parser.add_argument("--principal")
    parser.add_argument('--output', help='New local SQLite snapshot path for backup-journal')
    parser.add_argument('--model', default='', help='Exact selected model ID for doctor or the explicit prepare-model host action')
    args = parser.parse_args()
    if args.command == 'prepare-model':
        from v14.model_preparation import prepare_model
        if not args.model or len(args.model) > 512 or args.model != args.model.strip() or any(ord(c) < 32 for c in args.model):
            parser.error('prepare-model requires --model with one exact model identifier')
        print(json.dumps(prepare_model(engineering.capabilities, args.model), indent=2))
        return
    if args.command == 'backup-journal':
        if not args.output:
            parser.error('backup-journal requires --output')
        # Read-only source: a backup command never initializes, migrates or prunes.
        import sqlite3
        conn = sqlite3.connect(runtime.GATEWAY_JOB_DB_PATH.resolve().as_uri() + '?mode=ro', uri=True)
        try:
            print(json.dumps({'snapshot': str(backup_database(conn, args.output))}))
        finally:
            conn.close()
        return
    if args.command == "migrate-owner":
        print(json.dumps({"ownershipRecordsMigrated": migrate_legacy_owner(runtime.device_store, args.legacy_device or "", args.principal or "")}))
        return
    if args.command in {"doctor", "validate-config"}:
        if len(args.model) > 512:
            parser.error('--model must contain at most 512 characters')
        result = doctor(args.model) if args.command == "doctor" else {"configuration": "valid", "manifest": "valid", "version": VERSION}
        print(json.dumps(result, indent=2))
        return
    import uvicorn
    # Forwarded headers cannot rewrite the socket identity used for admission.
    runtime.logger.info("Gateway %s: mode=%s, key-free access, source=%s, browser bridge=%s",
                        VERSION, config.mode, manifest["sourceCommit"][:12], config.mode == "loopback")
    server = uvicorn.Server(uvicorn.Config(app, host=config.host, port=config.port, proxy_headers=False,
                            timeout_graceful_shutdown=config.shutdown_grace + 5))
    app.state.server = server
    server.run()


if __name__ == "__main__":
    main()
