import concurrent.futures
import tempfile
import threading
import time
import unittest
from pathlib import Path

from crawler_server.contracts import CrawlerError, WorkGrant, canonical_json, request
from crawler_server.coordinator import Coordinator
from crawler_server.network import CapturedResponse
from crawler_server.server import Server, sign_grant
from crawler_server.store import Store


class CapturedNetwork:
    decoded_limit = 32 * 1024 * 1024

    def __init__(self, store):
        self.store = store
        self.started = threading.Event()
        self.block = False
        self.cancelled = []

    def fetch(self, job, grant, generation, url, hosts, cancelled):
        attempt = self.store.reserve_request(job, grant, generation, url)
        self.started.set()
        if self.block:
            cancelled.wait(3)
            self.store.finish_attempt(attempt, "cancelled")
            raise CrawlerError("CANCELLED", "Fixture cancelled.")
        body = ('Unicode source quotation: "évidence".\n' * 240).encode()
        self.store.record_bytes(attempt, len(body), len(body))
        self.store.finish_attempt(attempt)
        return CapturedResponse(url, url, 200, {"content-type": "text/plain"}, body, (attempt,))

    def cancel(self, job):
        self.cancelled.append(job)


class ServerCoordinatorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.directory.name) / "crawler.sqlite")
        self.grant = WorkGrant("principal", "grant", "root", "work", "policy", 1, 20, 100_000, time.time() + 120)
        self.network = CapturedNetwork(self.store)
        self.coordinator = Coordinator(self.store, network=self.network)
        self.key = b"test authenticated metadata key!" * 2
        self.server = Server(self.coordinator, self.key)
        self.meta = {"gptmobile/crawlerGrant": sign_grant(self.grant, self.key)}

    def tearDown(self):
        self.coordinator.close()
        self.store.close()
        self.directory.cleanup()

    def test_authenticated_fetch_bounds_the_complete_rpc_envelope_and_retains_remaining_chunks(self):
        response = self.server.call("fetch", {"url": "https://example.org/source", "outputTokens": 10_000, "outputBytes": 16_000}, self.meta, 1)
        self.assertFalse(response["isError"], response)
        encoded = canonical_json({"jsonrpc": "2.0", "id": 1, "result": response}).encode() + b"\n"
        self.assertLessEqual(len(encoded), 16_000)
        evidence = response["structuredContent"]["data"]["evidence"]
        self.assertTrue(evidence["hasMoreWithinSnapshot"])
        self.assertTrue(evidence["chunks"])
        self.assertEqual(1, evidence["accounting"]["physicalRequests"])
        self.assertTrue(evidence["accounting"]["final"])

    def test_missing_or_changed_metadata_is_rejected_before_network(self):
        for metadata in ({}, [], {"gptmobile/crawlerGrant": {"payload": {}, "signature": "bad"}}):
            result = self.server.call("fetch", {"url": "https://example.org/source"}, metadata, 1)
            self.assertTrue(result["isError"])
        self.assertFalse(self.network.started.is_set())

    def test_protocol_cancel_stops_active_fetch_and_returns_inspectable_job_id(self):
        self.network.block = True
        with concurrent.futures.ThreadPoolExecutor() as pool:
            future = pool.submit(self.server.call, "fetch", {"url": "https://example.org/source"}, self.meta, "request")
            self.assertTrue(self.network.started.wait(1))
            self.server.cancel_request("request")
            response = future.result(timeout=2)
        self.assertEqual("CANCELLED", response["structuredContent"]["error"]["code"])
        job = response["structuredContent"]["error"]["jobId"]
        self.assertEqual("cancelled", self.store.status(job, self.grant)["state"])
        self.assertIn(job, self.network.cancelled)
        self.assertFalse(self.coordinator.running)

    def test_too_small_fetch_response_has_retained_job_reference_instead_of_hidden_work(self):
        response = self.server.call("fetch", {"url": "https://example.org/source", "outputTokens": 128, "outputBytes": 1024}, self.meta, 1)
        error = response["structuredContent"]["error"]
        self.assertEqual("OUTPUT_LIMIT_TOO_SMALL", error["code"])
        status = self.store.status(error["jobId"], self.grant)
        self.assertEqual("completed", status["state"])
        self.assertTrue(status["resultsAvailable"])

    def test_same_parent_grant_cannot_multiply_allowance_across_new_jobs(self):
        from dataclasses import replace
        grant = replace(self.grant, max_requests=1)
        first = self.store.start(request("start", {"seeds": ["https://example.org/one"], "idempotencyKey": "one"}), grant)
        second = self.store.start(request("start", {"seeds": ["https://example.org/two"], "idempotencyKey": "two"}), grant)
        attempt = self.store.reserve_request(first, grant, 0, "https://example.org/one")
        self.store.finish_attempt(attempt)
        with self.assertRaises(CrawlerError) as caught:
            self.store.reserve_request(second, grant, 0, "https://example.org/two")
        self.assertEqual("BUDGET_EXHAUSTED", caught.exception.code)
