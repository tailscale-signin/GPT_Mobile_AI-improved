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

class OfflineOcrTest(unittest.TestCase):
    def test_ocr_is_opt_in_and_capability_reports_its_bounds(self):
        health = Extractor().health()
        self.assertFalse(health["ocrReady"])
        self.assertEqual(3, health["ocrPageLimit"])
        self.assertFalse(health["browserReady"])

    @unittest.skipUnless(Extractor(enable_ocr=True).ocr_ready(), "Local OCR tools/data not installed")
    def test_real_scanned_pdf_retains_partial_and_ocr_provenance(self):
        import io
        try:
            from PIL import Image, ImageDraw, ImageFont
        except ImportError:
            self.skipTest("Pillow fixture generator unavailable")
        image = Image.new("RGB", (1400, 800), "white")
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype("DejaVuSans.ttf", 48)
        draw.text((70, 140), "Coastal research evidence", fill="black", font=font)
        draw.text((70, 230), "Observation sample count 42", fill="black", font=font)
        buffer = io.BytesIO()
        image.save(buffer, format="PDF", resolution=120)
        response = CapturedResponse("https://example.org/scan.pdf", "https://example.org/scan.pdf", 200,
                                    {"content-type": "application/pdf"}, buffer.getvalue(), ("attempt",), fetched_at=1234.5)
        document, _ = Extractor(enable_ocr=True).extract(response, "scope", threading.Event())
        self.assertIn("Coastal", document["text"])
        self.assertTrue(document["coverage"]["ocrUsed"])
        self.assertEqual("partial", document["retrievalStatus"])
        self.assertEqual("tesseract", document["engine"])
        self.assertIsNone(document["coverage"]["fullText"])
