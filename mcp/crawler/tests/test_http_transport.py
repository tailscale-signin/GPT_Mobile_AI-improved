import http.client
import json
import threading
import unittest
from dataclasses import replace

from crawler_server.contracts import CrawlerError
from crawler_server.http_transport import CrawlerHttpServer
from crawler_server.server import sign_grant
import test_server_coordinator as fixtures


class HttpTransportTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ServerCoordinatorTest()
        self.fixture.setUp()
        self.token = "test-http-bearer-token-at-least-32-bytes"
        self.http = CrawlerHttpServer(("127.0.0.1", 0), self.fixture.server, self.token)
        self.worker = threading.Thread(target=self.http.serve_forever, kwargs={"poll_interval": 0.01})
        self.worker.start()

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.worker.join(2)
        self.fixture.tearDown()

    def send(self, message=None, method="POST", headers=None):
        client = http.client.HTTPConnection("127.0.0.1", self.http.server_port, timeout=3)
        try:
            client.request(method, "/mcp", json.dumps(message) if message is not None else None,
                           {"Authorization": "Bearer " + self.token, "Content-Type": "application/json", **(headers or {})})
            response = client.getresponse()
            body = response.read()
            return response.status, json.loads(body) if body else None
        finally:
            client.close()

    def test_initialize_discovery_and_health_share_the_stdio_facade(self):
        status, result = self.send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}})
        self.assertEqual(200, status)
        self.assertEqual("2025-03-26", result["result"]["protocolVersion"])
        status, result = self.send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(5, len(result["result"]["tools"]))
        self.assertEqual(202, self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})[0])
        self.assertEqual(405, self.send(method="GET")[0])
        self.assertFalse(self.fixture.network.started.is_set())

    def test_bad_auth_origin_protocol_and_transport_never_reach_acquisition(self):
        request = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
        for headers, expected in [({"Authorization": "Bearer wrong"}, 401), ({"Origin": "https://untrusted.example"}, 403),
                                  ({"MCP-Protocol-Version": "future"}, 400), ({"Transfer-Encoding": "chunked"}, 400),
                                  ({"Content-Type": "text/plain"}, 415), ({"Content-Length": "99999999"}, 413)]:
            self.assertEqual(expected, self.send(request, headers=headers)[0])
        self.assertFalse(self.fixture.network.started.is_set())

    def test_authenticated_fetch_still_requires_a_trusted_work_grant(self):
        request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "fetch", "arguments": {"url": "https://example.org/read", "outputBytes": 60000, "outputTokens": 20000}}}
        status, result = self.send(request)
        self.assertEqual(200, status)
        self.assertTrue(result["result"]["isError"])
        self.assertFalse(self.fixture.network.started.is_set())
        request["params"]["_meta"] = self.fixture.meta
        status, result = self.send(request)
        self.assertEqual(200, status)
        self.assertFalse(result["result"]["isError"], result)
        self.assertEqual(1, result["result"]["structuredContent"]["data"]["evidence"]["accounting"]["physicalRequests"])

    def test_early_cancellation_is_bound_to_the_signed_principal(self):
        other = replace(self.fixture.grant, principal="other")
        notification = {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "read", "_meta": {"gptmobile/crawlerGrant": sign_grant(other, self.fixture.key)}}}
        self.assertEqual(202, self.send(notification)[0])
        request = {"jsonrpc": "2.0", "id": "read", "method": "tools/call", "params": {"name": "fetch", "arguments": {"url": "https://example.org/read", "outputBytes": 60000, "outputTokens": 20000}, "_meta": self.fixture.meta}}
        self.assertFalse(self.send(request)[1]["result"]["isError"])
        notification["params"]["_meta"] = self.fixture.meta
        self.assertEqual(202, self.send(notification)[0])
        self.assertEqual("CANCELLED", self.send(request)[1]["result"]["structuredContent"]["error"]["code"])

    def test_concurrent_principals_can_reuse_rpc_ids_without_cancellation_cross_talk(self):
        import concurrent.futures
        import time
        self.fixture.network.block = True
        other = replace(self.fixture.grant, principal="other", grant_id="other-grant")
        other_meta = {"gptmobile/crawlerGrant": sign_grant(other, self.fixture.key)}
        def request(metadata):
            return self.send({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "fetch", "arguments": {"url": "https://example.org/read"}, "_meta": metadata}})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(request, self.fixture.meta)
            second = pool.submit(request, other_meta)
            deadline = time.monotonic() + 2
            while len(self.fixture.server.active) < 2 and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(2, len(self.fixture.server.active))
            self.assertEqual(202, self.send({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1, "_meta": self.fixture.meta}})[0])
            self.assertEqual("CANCELLED", first.result(timeout=2)[1]["result"]["structuredContent"]["error"]["code"])
            with self.fixture.server.lock:
                remaining = self.fixture.server.active[self.fixture.server.request_key(1, other)]
                self.assertFalse(remaining["cancelled"].is_set())
            self.send({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1, "_meta": other_meta}})
            self.assertEqual("CANCELLED", second.result(timeout=2)[1]["result"]["structuredContent"]["error"]["code"])

    def test_remote_plaintext_listener_is_rejected(self):
        with self.assertRaises(CrawlerError):
            CrawlerHttpServer(("0.0.0.0", 0), self.fixture.server, self.token)
