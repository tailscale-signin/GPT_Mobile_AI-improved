import threading
import unittest

from crawler_server.contracts import CrawlerError, digest
from crawler_server.extraction import Extractor, PINS, installed_version
from crawler_server.network import CapturedResponse


class CapturedExtractionTest(unittest.TestCase):
    def response(self, body, mime="text/html"):
        return CapturedResponse("https://example.org/article", "https://example.org/article", 200,
                                {"content-type": mime}, body, ("physical-attempt",), fetched_at=1234.5)

    @unittest.skipUnless(installed_version("trafilatura") == PINS["trafilatura"], "Pinned HTML extractor not installed")
    def test_actual_html_worker_preserves_acquisition_identity_and_untrusted_text(self):
        article = ("This public research article describes a controlled observation of coastal water quality. "
                   "Its sampling protocol compares repeated measurements and records uncertainty. ") * 8
        html = ("<html><head><title>Coastal observations</title></head><body><nav>Menu</nav><main><article><h1>Study</h1><p>"
                + article + '</p><a href="/methods">Methods</a></article></main></body></html>').encode()
        document, links = Extractor().extract(self.response(html), "scope", threading.Event())
        self.assertIn("controlled observation", document["text"])
        self.assertEqual("trafilatura", document["engine"])
        self.assertEqual(1234.5, document["fetchedAt"])
        self.assertEqual(["physical-attempt"], document["attemptIds"])
        self.assertEqual(digest(document["text"]), document["contentHash"])
        self.assertIsNone(document["coverage"]["fullText"])
        self.assertEqual(["https://example.org/methods"], links)
        changed, _ = Extractor().extract(self.response(html.replace(b"water quality", b"water temperature")), "scope", threading.Event())
        self.assertNotEqual(document["documentRevision"], changed["documentRevision"])

    def test_login_wall_is_not_published_as_evidence(self):
        with self.assertRaises(CrawlerError) as caught:
            Extractor().extract(self.response(b'<html><title>Sign in</title><input type="password"></html>'), "scope", threading.Event())
        self.assertEqual("LOGIN_REQUIRED", caught.exception.code)

    def test_cancelled_extraction_never_publishes_plain_text(self):
        cancelled = threading.Event()
        cancelled.set()
        with self.assertRaises(CrawlerError) as caught:
            Extractor().extract(self.response(b"Retained text", "text/plain"), "scope", cancelled)
        self.assertEqual("CANCELLED", caught.exception.code)
