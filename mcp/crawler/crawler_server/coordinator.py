"""One acquisition owner per durable job, with separate foreground/background shares."""
from __future__ import annotations

import concurrent.futures
import threading
import uuid

from .contracts import CrawlerError, request
from .extraction import Extractor, PINS
from .network import NetworkBoundary
from .store import TERMINAL


class Coordinator:
    def __init__(self, store, network=None, extractor=None):
        self.store = store
        self.network = network or NetworkBoundary(store)
        self.extractor = extractor or Extractor()
        self.background = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="crawler-job")
        self.interactive = concurrent.futures.ThreadPoolExecutor(max_workers=2, thread_name_prefix="crawler-fetch")
        self.lock = threading.RLock()
        self.running = {}

    def start(self, arguments, grant):
        accepted = request("start", arguments)
        job = self.store.start(accepted, grant)
        with self.lock:
            status = self.store.status(job, grant)
            if job not in self.running and status["state"] not in TERMINAL:
                cancelled = threading.Event()
                # Publish the owner before launching; completion cannot race registration.
                self.running[job] = (cancelled, None, grant)
                future = self.background.submit(self._run, job, grant, cancelled)
                self.running[job] = (cancelled, future, grant)
                future.add_done_callback(lambda _: self._finished(job))
        return self.store.status(job, grant)

    def _finished(self, job):
        with self.lock:
            self.running.pop(job, None)

    def fetch(self, arguments, grant, cancelled=None, on_job=lambda job: None, measure=None):
        accepted = request("fetch", arguments)
        new_job = request("start", {"seeds": [accepted["url"]], "idempotencyKey": uuid.uuid4().hex,
                                    "sourceGoal": 1, "maxPages": 1, "maxDepth": 0, "mode": accepted["mode"],
                                    "deadlineMs": accepted["deadlineMs"], "outputTokens": accepted["outputTokens"],
                                    "outputBytes": accepted["outputBytes"], "format": accepted["format"]})
        cached = None if accepted.get("refresh", False) else self.store.cached_fetch(
            accepted["url"], accepted["mode"], grant, dict(PINS, utf8="1", pdftotext="system"))
        if cached:
            new_job.pop("seeds")
            new_job.pop("scope")
            new_job["resumeFromJobId"] = cached["jobId"]
        job = self.store.start(new_job, grant)
        cancelled = cancelled or threading.Event()
        on_job(job)
        with self.lock:
            self.running[job] = (cancelled, None, grant)
            future = self.interactive.submit(self._run, job, grant, cancelled)
            self.running[job] = (cancelled, future, grant)
            future.add_done_callback(lambda _: self._finished(job))
        try:
            future.result(timeout=accepted["deadlineMs"] / 1000)
        except concurrent.futures.TimeoutError:
            # fetch never escapes its deadline as an invisible background crawl.
            self.store.cancel(job, grant)
            cancelled.set()
            self.network.cancel(job)
            raise CrawlerError("NETWORK_TIMEOUT", "Synchronous read deadline reached; retained job can be inspected but will not continue.", {"jobId": job}) from None
        if cancelled.is_set():
            self.store.cancel(job, grant)
            raise CrawlerError("CANCELLED", "Synchronous read cancelled; retained evidence remains inspectable.", {"jobId": job})
        status = self.store.status(job, grant)
        if not status["resultsAvailable"]:
            raise CrawlerError(status["stopReason"] or "PARSE_FAILED", "No readable evidence was acquired. Inspect component readiness and the source access condition.", {"jobId": job})
        try:
            cache = {"hit": cached is not None, **(cached or {})}
            return {"job": status, "cache": cache, "evidence": self.store.results(job, grant, accepted["format"], accepted["outputTokens"], accepted["outputBytes"],
                    measure=(lambda evidence: measure({"job": status, "cache": cache, "evidence": evidence})) if measure else None)}
        except CrawlerError as failure:
            failure.details["jobId"] = job
            raise

    def _run(self, job, grant, cancelled):
        reason = "frontier_exhausted"
        while not cancelled.is_set():
            status = self.store.status(job, grant)
            if status["state"] in TERMINAL:
                return
            settings = status["effectiveSettings"]
            if status["progress"]["newDocuments"] >= settings["sourceGoal"]:
                reason = "source_goal"
                break
            claim = None
            try:
                claim = self.store.claim(job, grant, lease_seconds=max(1, grant.expires_at - self.store.clock()))
                if claim is None:
                    break
                response = self.network.fetch(job, grant, claim["generation"], claim["url"], settings["scope"]["allowedHosts"], cancelled)
                document, links = self.extractor.extract(response, grant.scope, cancelled)
                self.store.publish(claim, grant, document, response.body, links)
                if response.truncated:
                    reason = "download_or_extraction_limit"
                    break
            except CrawlerError as failure:
                reason = failure.code
                if claim:
                    self.store.fail_claim(claim, failure.code)
                if failure.code in ("BUDGET_EXHAUSTED", "CANCELLED", "ACCOUNTING_UNAVAILABLE", "ENGINE_UNAVAILABLE", "STORAGE_FULL"):
                    break
                if self.store.status(job, grant)["accounting"]["uncertainRequests"]:
                    reason = "ACCOUNTING_UNAVAILABLE"
                    break
            except Exception:
                if claim:
                    self.store.fail_claim(claim, "INTERNAL_ERROR")
                reason = "INTERNAL_ERROR"
                break
        self.store.finish(job, grant, reason)

    def cancel(self, job, grant):
        # Fence publication durably before signalling running workers.
        status = self.store.cancel(job, grant)
        with self.lock:
            active = self.running.get(job)
            if active:
                active[0].set()
        self.network.cancel(job)
        return status

    def close(self):
        with self.lock:
            active = list(self.running.items())
        for job, (cancelled, _, grant) in active:
            self.store.cancel(job, grant)
            cancelled.set()
            self.network.cancel(job)
        self.background.shutdown(wait=True, cancel_futures=True)
        self.interactive.shutdown(wait=True, cancel_futures=True)
