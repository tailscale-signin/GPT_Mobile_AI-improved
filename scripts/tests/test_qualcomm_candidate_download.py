import hashlib
import io
from email.message import Message
from email.utils import formatdate
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import call, patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_qualcomm_dispatch_candidate as candidate


class DownloadResponse(io.BytesIO):
    def __init__(self, content, url="https://publisher.example/artifact.jar"):
        super().__init__(content)
        self.url = url

    def geturl(self):
        return self.url


class QualcommCandidateDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "artifact.jar"
        self.payload = b"verified publisher artifact\n"
        self.record = {
            "url": "https://publisher.example/artifact.jar",
            "bytes": len(self.payload),
            "sha256": hashlib.sha256(self.payload).hexdigest(),
            "publisherSha1": hashlib.sha1(self.payload).hexdigest(),
        }

    def http_error(self, status=429, retry_after=None):
        headers = Message()
        for value in ([] if retry_after is None else retry_after if isinstance(retry_after, list) else [retry_after]):
            headers.add_header("Retry-After", value)
        return urllib.error.HTTPError(self.record["url"], status, "publisher response", headers, io.BytesIO(b"not retained"))

    def assert_no_partial(self):
        self.assertFalse(self.destination.with_suffix(".jar.part").exists())

    def test_rejected_get_waits_then_publishes_only_verified_bytes(self):
        error = self.http_error(retry_after="7")
        response = DownloadResponse(self.payload)
        with patch.object(candidate.urllib.request, "urlopen", side_effect=[error, response]) as opener, patch.object(candidate.time, "sleep") as wait:
            self.assertEqual(self.destination, candidate.checked_download(self.record, self.destination))
        self.assertEqual([call(self.record["url"], timeout=60)] * 2, opener.call_args_list)
        wait.assert_called_once_with(7)
        self.assertEqual(self.payload, self.destination.read_bytes())
        self.assertTrue(error.closed)
        self.assertTrue(response.closed)
        self.assert_no_partial()

    def test_missing_hint_has_finite_backoff_and_original_terminal_error(self):
        errors = [self.http_error() for _ in range(3)]
        with patch.object(candidate.urllib.request, "urlopen", side_effect=errors) as opener, patch.object(candidate.time, "sleep") as wait:
            with self.assertRaises(urllib.error.HTTPError) as caught:
                candidate.checked_download(self.record, self.destination)
        self.assertIs(errors[-1], caught.exception)
        self.assertEqual(3, opener.call_count)
        self.assertEqual([call(5), call(10)], wait.call_args_list)
        self.assertTrue(all(error.closed for error in errors))
        self.assertFalse(self.destination.exists())
        self.assert_no_partial()

    def test_http_date_wait_is_respected_without_changing_the_url(self):
        error = self.http_error(retry_after=formatdate(1020, usegmt=True))
        with patch.object(candidate.time, "time", return_value=1000), patch.object(candidate.time, "sleep") as wait, patch.object(candidate.urllib.request, "urlopen", side_effect=[error, DownloadResponse(self.payload)]) as opener:
            candidate.checked_download(self.record, self.destination)
        wait.assert_called_once_with(20)
        self.assertEqual([call(self.record["url"], timeout=60)] * 2, opener.call_args_list)

    def test_long_malformed_or_ambiguous_hint_never_retries_early(self):
        values = [
            "61", "3600", "9" * 128, "", "nonsense", "-1", "1.5", "1, 60", ["1", "60"], formatdate(1061, usegmt=True),
            "Thu, 01 Jan 1970 00:17:00 GMT trailing",
            "Thu, 01 Jan 1970 00:17:00 GMT, Thu, 01 Jan 1970 00:17:40 GMT",
            "Thu, 01 Jan 1970 00:17:00 UNKNOWN",
        ]
        for value in values:
            with self.subTest(hint=value):
                error = self.http_error(retry_after=value)
                with patch.object(candidate.time, "time", return_value=1000), patch.object(candidate.time, "sleep") as wait, patch.object(candidate.urllib.request, "urlopen", side_effect=error) as opener:
                    with self.assertRaises(urllib.error.HTTPError) as caught:
                        candidate.checked_download(self.record, self.destination)
                self.assertIs(error, caught.exception)
                opener.assert_called_once()
                wait.assert_not_called()
                self.assertTrue(error.closed)
                self.assert_no_partial()

    def test_wait_boundary_and_past_date_keep_bounded_backoff(self):
        for value, expected in [("60", 60), ("0", 5), (formatdate(999, usegmt=True), 5)]:
            with self.subTest(hint=value):
                self.destination.unlink(missing_ok=True)
                error = self.http_error(retry_after=value)
                with patch.object(candidate.time, "time", return_value=1000), patch.object(candidate.time, "sleep") as wait, patch.object(candidate.urllib.request, "urlopen", side_effect=[error, DownloadResponse(self.payload)]):
                    candidate.checked_download(self.record, self.destination)
                wait.assert_called_once_with(expected)
                self.assert_no_partial()

    def test_other_http_and_transport_errors_are_not_retried(self):
        errors = [self.http_error(status=status) for status in (400, 401, 403, 404, 500, 503)]
        errors += [urllib.error.URLError("transport failure"), TimeoutError("timed out")]
        for error in errors:
            with self.subTest(error=type(error).__name__, status=getattr(error, "code", None)):
                with patch.object(candidate.urllib.request, "urlopen", side_effect=error) as opener, patch.object(candidate.time, "sleep") as wait:
                    with self.assertRaises(type(error)) as caught:
                        candidate.checked_download(self.record, self.destination)
                self.assertIs(error, caught.exception)
                opener.assert_called_once()
                wait.assert_not_called()
                self.assert_no_partial()

    def test_successful_transfer_still_requires_all_publisher_pins(self):
        for field, value in [("bytes", len(self.payload) - 1), ("bytes", len(self.payload) + 1), ("sha256", "0" * 64), ("publisherSha1", "0" * 40)]:
            with self.subTest(field=field, value=value):
                old = b"pre-existing unverified file"
                self.destination.write_bytes(old)
                record = dict(self.record, **{field: value})
                with patch.object(candidate.urllib.request, "urlopen", return_value=DownloadResponse(self.payload)) as opener, patch.object(candidate.time, "sleep") as wait:
                    with self.assertRaises(ValueError):
                        candidate.checked_download(record, self.destination)
                opener.assert_called_once()
                wait.assert_not_called()
                self.assertEqual(old, self.destination.read_bytes())
                self.assert_no_partial()

    def test_retry_success_does_not_waive_integrity_or_redirect_checks(self):
        for response in [DownloadResponse(b"corrupt"), DownloadResponse(self.payload, url="http://publisher.example/artifact.jar")]:
            with self.subTest(url=response.geturl()):
                with patch.object(candidate.urllib.request, "urlopen", side_effect=[self.http_error(), response]) as opener, patch.object(candidate.time, "sleep") as wait:
                    with self.assertRaises(ValueError):
                        candidate.checked_download(self.record, self.destination)
                self.assertEqual(2, opener.call_count)
                wait.assert_called_once_with(5)
                self.assertFalse(self.destination.exists())
                self.assert_no_partial()

    def test_body_failure_is_not_replayed_and_removes_partial_bytes(self):
        response = DownloadResponse(self.payload)
        error = self.http_error()
        response.read = unittest.mock.Mock(side_effect=[b"partial", error])
        with patch.object(candidate.urllib.request, "urlopen", return_value=response) as opener, patch.object(candidate.time, "sleep") as wait:
            with self.assertRaises(urllib.error.HTTPError) as caught:
                candidate.checked_download(self.record, self.destination)
        self.assertIs(error, caught.exception)
        opener.assert_called_once()
        wait.assert_not_called()
        self.assertFalse(self.destination.exists())
        self.assert_no_partial()
        error.close()

    def test_verified_cache_and_invalid_source_do_not_open_a_request(self):
        self.destination.write_bytes(self.payload)
        with patch.object(candidate.urllib.request, "urlopen") as opener, patch.object(candidate.time, "sleep") as wait:
            self.assertEqual(self.destination, candidate.checked_download(self.record, self.destination))
            self.destination.unlink()
            for record in [dict(self.record, url="http://publisher.example/artifact.jar"), dict(self.record, sha256="invalid")]:
                with self.assertRaises(ValueError):
                    candidate.checked_download(record, self.destination)
        opener.assert_not_called()
        wait.assert_not_called()
        self.assert_no_partial()

    def test_interrupted_wait_does_not_start_a_second_get(self):
        error = self.http_error()
        with patch.object(candidate.urllib.request, "urlopen", side_effect=error) as opener, patch.object(candidate.time, "sleep", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                candidate.checked_download(self.record, self.destination)
        opener.assert_called_once()
        self.assertTrue(error.closed)
        self.assertFalse(self.destination.exists())
        self.assert_no_partial()


if __name__ == "__main__":
    unittest.main()
