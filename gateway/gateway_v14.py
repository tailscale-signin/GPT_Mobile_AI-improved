"""v14 composition root over the characterized v13 execution engine."""
import argparse
import asyncio
import json
import importlib.metadata
from pathlib import Path

from v14 import VERSION
from v14.config import Config
from v14.package import validate_package

config = Config.from_env()
# Validate even direct gateway_v14 imports; do not trust a version label.
manifest = validate_package(Path(__file__).parent)

import gateway_v13 as runtime
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from v14.identity import Admission
from v14.research import run_searches
from v14.jobs import install_terminal_guard, migrate_legacy_owner, TERMINAL, DurableCommitError
from v14.memory import apply_client_recall
from v14.tools import install_exact_routing

runtime.GATEWAY_VERSION = VERSION
runtime.AUTO_MEMORY_RETRIEVE = False
runtime.AUTO_MEMORY_STORE = False
runtime.AUTO_LEARN_FROM_TOOL_RESULTS = False
runtime.MEMORY_BEHAVIOR_PROMPT = "Personal memory is client-authoritative. Do not invent recalled facts or store a competing vault."
runtime.run_searches = run_searches
install_terminal_guard(runtime)
_old_config = runtime.load_mcp_config


def approved_mcp_config():
    # Preserve the config file and Angruvadal data for rollback. PC memory has
    # no authority handshake yet, so exclude known memory servers in v14.
    return {name: value for name, value in _old_config().items() if not runtime.is_memory_tool_name(name) and "memory" not in name.lower()}



runtime.load_mcp_config = approved_mcp_config
install_exact_routing(runtime)
app = FastAPI(title="Private Gateway v14", version=VERSION, lifespan=runtime.gateway_lifespan,
              docs_url="/gateway/docs", openapi_url="/gateway/openapi.json")
app.add_middleware(Admission, config=config)


@app.post("/v1/chat/completions")
@app.post("/chat/completions")
async def chat(request: Request):
    try:
        payload = await request.json()
        runtime.validate_chat_request(payload)
        request._json = apply_client_recall(payload, runtime.LLAMA_BASE)
        return await runtime.chat_completions(request)
    except DurableCommitError:
        return JSONResponse({"error": {"code": "durable_commit_failed"}}, status_code=503)
    except PermissionError:
        return JSONResponse({"error": {"code": "principal_or_destination_denied"}}, status_code=403)
    except (ValueError, runtime.GatewayContractError) as exc:
        return JSONResponse({"error": {"code": "invalid_v14_request", "message": str(exc)}}, status_code=400)


@app.post("/gateway/jobs/{job_id}/cancel")
@app.post("/v1/gateway/jobs/{job_id}/cancel")
def cancel(job_id: str):
    runtime.ensure_gateway_job_loaded(job_id)
    with runtime.job_registry_lock:
        job = runtime.job_registry.get(job_id)
        if job and job.get("status") in TERMINAL:
            return {"job_id": job_id, "canceled": job["status"] in {"cancelled", "canceled"}, "status": job["status"], "message": "Job already terminal"}
        return runtime.cancel_gateway_job(job_id)


def capabilities():
    result = runtime.gateway_capabilities()
    result.update({"gatewayVersion": VERSION, "contractVersion": 1,
                   "build": {"sourceCommit": manifest["sourceCommit"], "manifestVersion": 1, "status": "development"},
                   "auth": {"mode": config.mode, "apiKeyRequired": False, "legacyBearerEnabled": config.legacy_bearer},
                   "identityScope": "user" if config.mode == "trusted_proxy" else "local-machine",
                   "memory": {"authority": "client", "protocolVersion": 1, "workspaceAdapterEnabled": False},
                   "supportedMcpEras": ["legacy"], "eventVersions": [runtime.GATEWAY_PROGRESS_PROTOCOL],
                   "features": dict(result["features"], keyFreePrivateAccess=True, deterministicSearch=True,
                                    structuredSearch=True, terminalStateProtection=True, memory_novelty_filter=False, structuredEvidence=False, mcpFacade=False,
                                    modernMcp=False, clientMemoryAuthority=True, packageValidation=True)})
    return result


@app.get("/gateway/capabilities")
@app.get("/v1/gateway/capabilities")
def v14_capabilities():
    return capabilities()


@app.get("/gateway/v14")
@app.get("/v1/gateway/v14")
def v14_contract():
    return capabilities()


@app.get("/gateway/ready")
@app.get("/v1/gateway/ready")
async def ready():
    # Blocking legacy HTTP probe stays off the ASGI event loop.
    return await asyncio.to_thread(runtime.v13_ready)


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


@app.get("/sw.js")
def disabled_service_worker():
    return Response("self.addEventListener('activate', () => self.registration.unregister());", media_type="application/javascript")


@app.get("/gateway/admin/doctor")
async def local_doctor():
    return await asyncio.to_thread(doctor)


# New routes precede the retained catch-all UI proxy. Authentication is replaced
# on this app only; the v13 app remains usable through its original launcher.
app.router.routes.extend(runtime.app.router.routes)


def doctor():
    try:
        probe = runtime.http_get(f"{runtime.LLAMA_BASE}/health", timeout=(2, 3))
        backend = "ready" if probe.status_code == 200 else "unavailable"
    except runtime.requests.RequestException:
        backend = "unreachable"
    return {"version": VERSION, "manifest": "valid", "sourceCommit": manifest["sourceCommit"],
            "bindMode": config.mode, "apiKeyRequired": False, "memoryAuthority": "client",
            "mcpFacade": "disabled", "modernMcp": "not-certified", "backend": backend,
            "loadedModules": {"launcher": str(Path(__file__).with_name("gateway.py")), "composition": __file__, "runtime": runtime.__file__},
            "dependencies": {name: importlib.metadata.version(name) for name in ("fastapi", "uvicorn", "requests", "httpx")},
            "remainingGates": ["Windows install/rollback", "Tailscale identity", "Android build", "physical phone"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=["serve", "doctor", "validate-config", "migrate-owner"], default="serve")
    parser.add_argument("--legacy-device")
    parser.add_argument("--principal")
    args = parser.parse_args()
    if args.command == "migrate-owner":
        print(json.dumps({"ownershipRecordsMigrated": migrate_legacy_owner(runtime.device_store, args.legacy_device or "", args.principal or "")}))
        return
    if args.command in {"doctor", "validate-config"}:
        result = doctor() if args.command == "doctor" else {"configuration": "valid", "manifest": "valid", "version": VERSION}
        print(json.dumps(result, indent=2))
        return
    import uvicorn
    # Forwarded headers cannot rewrite the socket identity used for admission.
    uvicorn.run(app, host=config.host, port=config.port, proxy_headers=False)


if __name__ == "__main__":
    main()
