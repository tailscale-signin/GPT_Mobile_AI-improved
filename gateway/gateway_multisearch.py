"""Same-query fan-out for discovered, explicitly web-search MCP tools only.

The caller supplies authorized catalog entries and the original executor. Device
context is copied into workers. No writes, GitHub, memory or file search is inferred.
"""
import concurrent.futures
import contextvars
import json
import re
import threading
import time
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="gateway-search")
_QUERY_KEYS = ("query", "q", "search_query", "searchQuery")
_COUNT_KEYS = ("maxResults", "max_results", "count", "limit", "numResults", "num_results", "num")
_WEB_NAMES = {"web_search", "search_web", "brave_web_search", "brave_search", "google_search", "bing_search", "tavily_search", "duckduckgo_search", "exa_search"}


def search_shape(tool):
    fn = tool.get("function", {})
    name = fn.get("name", "")
    description = fn.get("description", "")
    if not (any(name == n or name.endswith("_" + n) for n in _WEB_NAMES) or re.search(r"\b(?:web|internet)[ _-]*search\b|search (?:the )?web|search engines?", description, re.I)):
        return None
    if re.search(r"github|repository|filesystem|memory|file_search|read_url|crawl", name, re.I):
        return None
    schema = fn.get("parameters", {})
    props = schema.get("properties", {})
    query = next((k for k in _QUERY_KEYS if props.get(k, {}).get("type") == "string"), None)
    if query is None:
        return None
    return name, query, props, schema.get("required", [])


def plan_searches(selected_name, arguments, catalog):
    shapes = [shape for tool in catalog if (shape := search_shape(tool))]
    selected = next((shape for shape in shapes if shape[0] == selected_name), None)
    if selected is None or not isinstance(arguments.get(selected[1]), str):
        return []
    query = arguments[selected[1]].strip()
    if not query:
        return []
    count = next((arguments[k] for k in _COUNT_KEYS if k in arguments), None)
    # Do not silently drop provider-specific filters: only engines that can accept
    # these exact filters participate. The selected engine keeps all original args.
    filters = {k: v for k, v in arguments.items() if k not in _QUERY_KEYS + _COUNT_KEYS}
    targets = [(selected_name, dict(arguments))]
    seen = {selected_name}
    for name, key, props, required in shapes:
        if name in seen or any(k not in props for k in filters):
            continue
        mapped = {key: query, **filters}
        count_key = next((k for k in _COUNT_KEYS if k in props), None)
        if count_key is not None and isinstance(count, int) and not isinstance(count, bool):
            field = props[count_key]
            mapped[count_key] = min(max(count, field.get("minimum", 1)), field.get("maximum", count))
        for field in required:
            if field not in mapped and "default" in props.get(field, {}):
                mapped[field] = props[field]["default"]
        if any(field not in mapped for field in required):
            continue
        targets.append((name, mapped))
        seen.add(name)
    return targets


def _sources(value):
    if isinstance(value, dict):
        url = value.get("url") or value.get("link") or value.get("uri")
        if isinstance(url, str):
            try:
                parts = urlsplit(url)
                if parts.scheme in {"http", "https"} and parts.hostname:
                    pairs = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if not k.lower().startswith("utm_") and k.lower() not in {"fbclid", "gclid"}]
                    canonical = urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path.rstrip("/"), urlencode(pairs), ""))
                    yield canonical, {"url": url, "title": str(value.get("title", value.get("name", ""))), "snippet": str(value.get("snippet", value.get("description", "")))[:1500]}
            except ValueError:
                pass
        for nested in value.values():
            yield from _sources(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _sources(nested)
    elif isinstance(value, str) and value.lstrip().startswith(("{", "[")):
        try:
            yield from _sources(json.loads(value))
        except (ValueError, RecursionError):
            pass


def run_searches(targets, execute, *, cancelled=None, progress=None, timeout_seconds=20.0):
    """Bound wall time/concurrency; one failure never erases another engine's data."""
    if cancelled is not None and cancelled.is_set():
        raise InterruptedError("Multi-engine search cancelled before dispatch")
    if timeout_seconds <= 0:
        raise ValueError("Search timeout must be positive")
    stop = threading.Event()
    deadline = time.monotonic() + timeout_seconds
    pending = {}
    waiting = iter(targets)
    outcomes = {}
    attempted = set()
    attempt_lock = threading.Lock()

    def invoke(name, args):
        if stop.is_set() or (cancelled is not None and cancelled.is_set()):
            raise InterruptedError("Search cancelled before dispatch")
        with attempt_lock:
            attempted.add(name)
        return execute(name, args, cancel_event=stop)

    def fill():
        while len(pending) < 2 and not stop.is_set():
            if cancelled is not None and cancelled.is_set():
                raise InterruptedError("Multi-engine search cancelled before dispatch")
            item = next(waiting, None)
            if item is None:
                break
            name, args = item
            context = contextvars.copy_context()
            pending[_POOL.submit(context.run, invoke, name, args)] = name

    try:
        fill()
        while pending:
            if cancelled is not None and cancelled.is_set():
                raise InterruptedError("Multi-engine search cancelled")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            done, _ = concurrent.futures.wait(pending, timeout=min(0.05, remaining), return_when=concurrent.futures.FIRST_COMPLETED)
            for future in done:
                name = pending.pop(future)
                try:
                    value = future.result()
                    is_error = isinstance(value, dict) and (value.get("isError") is True or value.get("error") is not None)
                    outcomes[name] = {"engine": name, "status": "failed" if is_error else "completed", "result": value}
                except InterruptedError:
                    if cancelled is not None and cancelled.is_set():
                        raise
                    outcomes[name] = {"engine": name, "status": "failed", "error": "Engine cancelled or timed out"}
                except Exception:
                    outcomes[name] = {"engine": name, "status": "failed", "error": "Engine unavailable"}
                if progress:
                    progress({"message": f"Search engine {name}: {outcomes[name]['status']}"})
            fill()
    finally:
        stop.set()
        for future in pending:
            future.cancel()
    if cancelled is not None and cancelled.is_set():
        raise InterruptedError("Multi-engine search cancelled")
    sources = {}
    for outcome in outcomes.values():
        if outcome["status"] == "completed":
            for canonical, source in _sources(outcome.get("result")):
                sources.setdefault(canonical, source)
        # Bound raw evidence in the combined result; preserve source URLs separately.
        if "result" in outcome:
            rendered = json.dumps(outcome["result"], ensure_ascii=False)
            if len(rendered) > 4000:
                outcome["result"] = rendered[:4000]
                outcome["truncated"] = True
    statuses = [outcomes.get(name, {"engine": name, "status": "timed_out" if name in attempted else "skipped", "error": "Search time budget exhausted"}) for name, _ in targets]
    payload = {"engines": statuses, "results": list(sources.values())[:50], "engines_attempted": len(attempted)}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
            "isError": not any(s["status"] == "completed" for s in statuses), "gateway_engine_calls": len(attempted)}
