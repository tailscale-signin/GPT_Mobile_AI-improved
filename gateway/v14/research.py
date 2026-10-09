"""Deterministic bounded search merging; attribution survives deduplication."""
import concurrent.futures
import contextvars
import json
import threading
import time
from urllib.parse import unquote_plus, urlsplit, urlunsplit

_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="v14-search")
_TRACKING = {"fbclid", "gclid", "dclid", "msclkid"}
_COUNT = ("maxResults", "max_results", "count", "limit", "numResults", "num_results", "num")


def url_identity(url):
    if not isinstance(url, str) or len(url) > 8192 or any(ord(c) < 32 for c in url) or "\\" in url:
        return None
    try:
        parts = urlsplit(url)
        if parts.scheme.lower() not in {"http", "https"} or not parts.hostname or parts.username is not None or parts.password is not None:
            return None
        port = parts.port
        hostname = parts.hostname.lower()
        host = "[" + hostname + "]" if ":" in hostname else hostname
        if port and not (parts.scheme.lower() == "http" and port == 80 or parts.scheme.lower() == "https" and port == 443):
            host += ":" + str(port)
        # Keep raw encoding, ordering, repeated query keys, path case, non-root
        # slashes and fragments. Only reviewed tracking keys are removed.
        query = "&".join(p for p in parts.query.split("&") if p and not (unquote_plus(p.split("=", 1)[0]).lower().startswith("utm_") or unquote_plus(p.split("=", 1)[0]).lower() in _TRACKING))
        return urlunsplit((parts.scheme.lower(), host, parts.path or "/", query, parts.fragment))
    except ValueError:
        return None


def sources(value, depth=0):
    if depth > 12:
        return
    if isinstance(value, dict):
        url = value.get("url") or value.get("link") or value.get("uri")
        identity = url_identity(url)
        if identity:
            yield identity, {"url": url, "title": str(value.get("title", value.get("name", "")))[:512], "snippet": str(value.get("snippet", value.get("description", "")))[:1500]}
        for child in value.values():
            yield from sources(child, depth + 1)
    elif isinstance(value, list):
        for child in value[:500]:
            yield from sources(child, depth + 1)
    elif isinstance(value, str) and len(value) <= 1_000_000 and value.lstrip().startswith(("{", "[")):
        try:
            yield from sources(json.loads(value), depth + 1)
        except (ValueError, RecursionError):
            pass


def merge(outcomes, order, contribution=10, total=50):
    grouped, engine_lists = {}, {}
    for engine in order:
        outcome = outcomes.get(engine, {})
        candidates = list(sources(outcome.get("result"))) if outcome.get("status") == "completed" else []
        unique = []
        for rank, (identity, source) in enumerate(candidates[:500], 1):
            if identity not in grouped:
                grouped[identity] = dict(source, foundBy=[], providerRanks={})
            record = grouped[identity]
            if engine not in record["foundBy"]:
                record["foundBy"].append(engine)
                record["providerRanks"][engine] = rank
            if identity not in unique:
                unique.append(identity)
        engine_lists[engine] = unique[:contribution]
    kept, seen = [], set()
    for rank in range(contribution):
        for engine in order:
            values = engine_lists[engine]
            if rank < len(values) and values[rank] not in seen:
                seen.add(values[rank])
                kept.append(grouped[values[rank]])
    return kept[:total]


def run_searches(targets, execute, *, cancelled=None, progress=None, timeout_seconds=12.0, total_results=None):
    if len(targets) > 32 or len({name for name, _ in targets}) != len(targets):
        raise ValueError("Search requires at most 32 unique approved engines")
    if cancelled is not None and cancelled.is_set():
        raise InterruptedError("Search cancelled")
    if timeout_seconds <= 0:
        raise ValueError("Search deadline must be positive")
    contribution = next((args[k] for _, args in targets for k in _COUNT if isinstance(args.get(k), int) and not isinstance(args.get(k), bool)), 10)
    contribution = min(10, max(1, contribution))
    total = min(50, max(1, total_results if total_results is not None else contribution * len(targets)))
    stop, started, attempted = threading.Event(), time.monotonic(), set()
    lock = threading.Lock()
    outcomes, pending = {}, {}
    def invoke(name, args):
        if stop.is_set() or (cancelled and cancelled.is_set()) or time.monotonic() - started >= timeout_seconds:
            raise InterruptedError("Search phase exhausted before dispatch")
        with lock:
            attempted.add(name)
        return execute(name, args, cancel_event=stop)
    for name, args in targets:
        pending[_POOL.submit(contextvars.copy_context().run, invoke, name, dict(args))] = name
    try:
        while pending and time.monotonic() - started < timeout_seconds:
            if cancelled and cancelled.is_set():
                raise InterruptedError("Search cancelled")
            done, _ = concurrent.futures.wait(pending, timeout=min(.05, max(0, timeout_seconds - (time.monotonic() - started))), return_when=concurrent.futures.FIRST_COMPLETED)
            for future in done:
                name = pending.pop(future)
                try:
                    value = future.result()
                    bad = isinstance(value, dict) and (value.get("isError") is True or value.get("error") is not None)
                    outcomes[name] = {"engine": name, "status": "failed" if bad else "completed", "result": value}
                except InterruptedError:
                    outcomes[name] = {"engine": name, "status": "timed_out" if name in attempted else "skipped"}
                except Exception:
                    outcomes[name] = {"engine": name, "status": "failed", "error": "engine_unavailable"}
                if progress:
                    progress({"message": f"Search engine {name}: {outcomes[name]['status']}"})
    finally:
        stop.set()
        for future in pending:
            future.cancel()
    if cancelled and cancelled.is_set():
        raise InterruptedError("Search cancelled")
    order = [name for name, _ in targets]
    results = merge(outcomes, order, contribution, total)
    statuses = []
    for name in order:
        outcome = dict(outcomes.get(name, {"engine": name, "status": "timed_out" if name in attempted else "skipped"}))
        value = outcome.pop("result", None)
        if value is not None:
            # Structured projection, never a broken substring of serialized JSON.
            count = sum(1 for _ in sources(value))
            outcome["sourceCandidates"] = count
            outcome["rawEvidenceOmitted"] = True
        statuses.append(outcome)
    payload = {"schemaVersion": 1, "engines": statuses, "results": results, "engines_attempted": len(attempted),
               "contentDedupe": "off", "omission": {"rawProviderPayloads": True}, "ordering": "engine-rank-round-robin"}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
            "isError": not any(s["status"] == "completed" for s in statuses), "gateway_engine_calls": len(attempted)}
