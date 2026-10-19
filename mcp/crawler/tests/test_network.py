import gzip
import io
import tempfile
import threading
import unittest
from pathlib import Path

from crawler_server.contracts import CrawlerError, WorkGrant, request
from crawler_server.network import NetworkBoundary
from crawler_server.store import Store


class Reply:
    def __init__(self, status, body, headers=()):
        self.status, self.body, self.headers = status, io.BytesIO(body), headers

    def getheaders(self):
        return self.headers

    def read1(self, size):
        return self.body.read(size)


class NetworkAccountingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name) / "jobs.sqlite", clock=lambda: 1000)
        self.grant = WorkGrant("a", "grant", "root", "work", "policy", 0, 100, 10000, 2000)
        self.job = self.store.start(request("start", {"seeds": ["https://example.org/"], "idempotencyKey": "one"}), self.grant)
        self.responses = {"/robots.txt": (200, b"User-agent: *\nAllow: /", ())}
        self.sent, self.pins = [], []
        outer = self

        class Connection:
            sock = None

            def __init__(self, host, address, port, timeout, tls):
                outer.pins.append((host, address, tls))

            def request(self, method, path, headers):
                outer.sent.append(path)
                self.path = path

            def getresponse(self):
                return Reply(*outer.responses[self.path])

            def close(self):
                pass

        self.network = NetworkBoundary(self.store, interval=0, resolver=lambda *_: ["93.184.216.34"], connection=Connection)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def fetch(self, path="/", check_robots=True):
        return self.network.fetch(self.job, self.grant, 0, "https://example.org" + path, ["example.org"], threading.Event(), check_robots=check_robots)

    def test_robots_and_redirects_are_physical_children_and_recheck_policy(self):
        self.responses["/"] = (302, b"redirect", [("Location", "/article")])
        self.responses["/article"] = (200, b"A short useful article", [])
        response = self.fetch()
        receipt = self.store.status(self.job, self.grant)["accounting"]
        self.assertEqual(["/robots.txt", "/", "/robots.txt", "/article"], self.sent)
        self.assertEqual(4, receipt["physicalRequests"])
        self.assertEqual(4, len(response.attempts))
        self.assertEqual(sum(len(self.responses[path][1]) for path in self.sent), receipt["downloadedBytes"])
        self.assertTrue(all(pin == ("example.org", "93.184.216.34", True) for pin in self.pins))

    def test_disallowed_redirect_never_sends_the_destination(self):
        self.responses["/robots.txt"] = (200, b"User-agent: *\nDisallow: /private", [])
        self.responses["/"] = (302, b"", [("Location", "/private")])
        with self.assertRaises(CrawlerError) as caught:
            self.fetch()
        self.assertEqual("ROBOTS_DISALLOWED", caught.exception.code)
        self.assertNotIn("/private", self.sent)

    def test_private_dns_and_redirect_destinations_are_rejected(self):
        self.network.resolver = lambda *_: ["127.0.0.1"]
        with self.assertRaises(CrawlerError):
            self.fetch()
        self.assertEqual([], self.sent)
        self.network.resolver = lambda *_: ["93.184.216.34"]
        self.responses["/"] = (302, b"", [("Location", "https://127.0.0.1/secrets")])
        with self.assertRaises(CrawlerError):
            self.fetch(check_robots=False)
        self.assertEqual(["/"], self.sent)

    def test_compression_counts_transferred_and_decoded_bytes_separately(self):
        original = b"Compressed article evidence. " * 100
        packed = gzip.compress(original)
        self.responses["/"] = (200, packed, [("Content-Encoding", "gzip")])
        response = self.fetch(check_robots=False)
        receipt = self.store.status(self.job, self.grant)["accounting"]
        self.assertEqual(original, response.body)
        self.assertEqual(len(packed), receipt["downloadedBytes"])
        self.assertEqual(len(original), receipt["decodedBytes"])
        self.assertEqual(1, receipt["physicalRequests"])

    def test_unknown_length_stream_stops_at_the_grant_without_trimming_receipts(self):
        self.responses["/"] = (200, b"a" * 20000, [])
        response = self.fetch(check_robots=False)
        receipt = self.store.status(self.job, self.grant)["accounting"]
        self.assertTrue(response.truncated)
        self.assertEqual(10000, len(response.body))
        self.assertEqual(10000, receipt["downloadedBytes"])
        self.assertEqual(10000, receipt["decodedBytes"])
        with self.assertRaises(CrawlerError):
            self.fetch(check_robots=False)
        self.assertEqual(1, self.store.status(self.job, self.grant)["accounting"]["physicalRequests"])

    def test_cooldown_survives_restart_without_charging_a_blocked_attempt(self):
        self.responses["/"] = (429, b"Limited", [("Retry-After", "172800")])
        self.fetch(check_robots=False)
        self.assertEqual(173800, self.store.domain_cooldown("example.org"))
        self.store.close()
        self.store = Store(Path(self.temp.name) / "jobs.sqlite", clock=lambda: 1001)
        self.network.store = self.store
        with self.assertRaises(CrawlerError) as caught:
            self.fetch(check_robots=False)
        self.assertEqual("RATE_LIMITED", caught.exception.code)
        self.assertEqual(["/"], self.sent)
        self.assertEqual(1, self.store.status(self.job, self.grant)["accounting"]["physicalRequests"])

    def test_http_date_retry_after_and_invalid_default(self):
        from crawler_server.network import retry_after_deadline
        self.assertEqual(1445412480, retry_after_deadline("Wed, 21 Oct 2015 07:28:00 GMT", 1000))
        self.assertEqual(1060, retry_after_deadline("not a date", 1000))
        self.assertEqual(1000, retry_after_deadline("0", 1000))

    def test_cancellation_before_dispatch_does_no_physical_work(self):
        cancelled = threading.Event()
        cancelled.set()
        with self.assertRaises(CrawlerError):
            self.network.fetch(self.job, self.grant, 0, "https://example.org/", ["example.org"], cancelled)
        self.assertEqual([], self.sent)
        self.assertEqual(0, self.store.status(self.job, self.grant)["accounting"]["physicalRequests"])


if __name__ == "__main__":
    unittest.main()
