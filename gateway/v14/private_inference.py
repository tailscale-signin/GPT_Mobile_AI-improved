"""Stateless local text inference. This path never enters the job, tool or conversation runtimes."""
import ipaddress
import json
import os
import re
from urllib.parse import urlsplit

import httpx
from fastapi import Request
from fastapi.responses import JSONResponse

BASE = "/gateway/privacy/inference"
MAX_BYTES = 2 * 1024 * 1024


def local_backend(base):
    url = urlsplit(base)
    if url.scheme not in {"http", "https"} or url.username or url.password or url.query or url.fragment or url.path.rstrip("/") not in {"", "/v1"}:
        raise ValueError()
    if url.hostname != "localhost" and not ipaddress.ip_address(url.hostname).is_loopback:
        raise ValueError()
    # No DNS lookups, environment proxy, redirects or user-selected upstreams.
    host = "127.0.0.1" if url.hostname == "localhost" else url.hostname
    authority = f"[{host}]" if ":" in host else host
    return f"{url.scheme}://{authority}" + (f":{url.port}" if url.port else "")


async def bounded_json(parts):
    body = bytearray()
    async for part in parts:
        if len(body) + len(part) > MAX_BYTES:
            raise ValueError()
        body.extend(part)
    return json.loads(body)


async def backend_json(client, method, url, payload=None):
    async with client.stream(method, url, json=payload) as response:
        response.raise_for_status()
        return await bounded_json(response.aiter_bytes())


async def installed_models(client, root):
    props = await backend_json(client, "GET", root + "/props")
    # llama.cpp model-server, not router mode or an Ollama cloud model/relay.
    path = props.get("model_path")
    if not isinstance(path, str) or not path.lower().endswith(".gguf") or "://" in path:
        raise ValueError()
    data = await backend_json(client, "GET", root + "/v1/models")
    rows = data["data"]
    if len(rows) != 1 or not isinstance(rows[0]["id"], str) or not rows[0]["id"]:
        raise ValueError()
    return [rows[0]["id"]]


async def installed_ollama_models(client, root):
    catalog = await backend_json(client, "GET", root + "/api/tags")
    return [m["name"] for m in catalog["models"] if isinstance(m, dict)
            and isinstance(m.get("name"), str) and not m["name"].endswith((":cloud", "-cloud"))
            and not m.get("remote_host") and not m.get("remote_model")
            and re.fullmatch(r"(?:sha256:)?[0-9a-f]{64}", m.get("digest", ""))
            and m.get("details", {}).get("format") == "gguf" and m.get("size", 0) > 0]


async def local_ollama_text(client, root, payload):
    show = await backend_json(client, "POST", root + "/api/show", {"model": payload["model"]})
    if (show.get("remote_model") or show.get("remote_host") or show.get("details", {}).get("format") != "gguf"
            or not show.get("model_info", {}).get("general.architecture")):
        raise ValueError()
    answer = await backend_json(client, "POST", root + "/api/chat",
                                {"model": payload["model"], "messages": payload["messages"],
                                 "options": {"num_predict": payload["max_tokens"]}, "stream": False})
    if answer.get("remote_host") or answer.get("remote_model") or answer.get("model") != payload["model"]:
        raise ValueError()
    return answer["message"]


def text_request(payload, models):
    if not isinstance(payload, dict) or set(payload) != {"messages", "model", "max_tokens"} or payload["model"] not in models:
        raise ValueError()
    messages, limit = payload["messages"], payload["max_tokens"]
    if type(limit) is not int or not 1 <= limit <= 1048576 or not isinstance(messages, list) or not 1 <= len(messages) <= 4096:
        raise ValueError()
    if any(not isinstance(m, dict) or set(m) != {"role", "content"} or m["role"] not in ("user", "assistant", "system") or not isinstance(m["content"], str) for m in messages):
        raise ValueError()
    return dict(payload, stream=False, cache_prompt=False)


def install_private_inference(app, runtime):
    @app.api_route(BASE, methods=["GET", "POST"])
    async def private_inference(request: Request):
        try:
            root = local_backend(runtime.LLAMA_BASE)
            async with httpx.AsyncClient(follow_redirects=False, trust_env=False, timeout=120) as client:
                kind = str(getattr(runtime, "GATEWAY_INFERENCE_PROVIDER", None) or os.getenv("GATEWAY_INFERENCE_PROVIDER", "LLAMA")).upper()
                if kind not in {"LLAMA", "OLLAMA"}:
                    raise ValueError()
                ollama = kind == "OLLAMA"
                models = await (installed_ollama_models(client, root) if ollama else installed_models(client, root))
                if request.method == "GET":
                    return {"protocol": "private-local-text-v1", "provider_kind": kind, "models": models}
                payload = text_request(await bounded_json(request.stream()), models)
                if ollama:
                    message = await local_ollama_text(client, root, payload)
                else:
                    answer = await backend_json(client, "POST", root + "/v1/chat/completions", payload)
                    message = answer["choices"][0]["message"]
                if message.get("tool_calls") or not isinstance(message["content"], str):
                    raise ValueError()
                return {"text": message["content"]}
        except (ValueError, KeyError, IndexError, TypeError, AttributeError, httpx.HTTPError):
            return JSONResponse({"error": "PRIVATE_LOCAL_ROUTE_UNAVAILABLE"}, status_code=409)
