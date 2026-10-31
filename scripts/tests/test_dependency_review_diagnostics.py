import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('dependency_review_diagnostics', ROOT / 'check_dependency_review.py')
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class DependencyReviewDiagnosticsTest(unittest.TestCase):
    def run_blocked_review(self, body, status=403):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                output = io.StringIO()
                error = HTTPError('https://api.github.com/example', status, 'blocked', {}, io.BytesIO(body))
                arguments = ['check_dependency_review.py', '--repository', 'o/r', '--base', 'a' * 40, '--head', 'b' * 40]
                with patch('sys.argv', arguments), patch.dict(os.environ, {'GH_TOKEN': 'configured-test-token'}), \
                        patch.object(review, 'changed_manifests', return_value=['settings.gradle.kts']), \
                        patch.object(review.urllib.request, 'urlopen', side_effect=error), contextlib.redirect_stdout(output):
                    code = review.main()
                report = json.loads(Path('dependency-review.json').read_text())
                return code, report, output.getvalue()
            finally:
                os.chdir(previous)

    def test_denied_api_produces_a_blocked_artifact_and_keeps_failing(self):
        code, report, output = self.run_blocked_review(b'{"message":"Dependency review requires the dependency graph"}')
        self.assertEqual(1, code)
        self.assertEqual('BLOCKED_API', report['status'])
        self.assertFalse(report['scan_performed'])
        self.assertEqual(403, report['http_status'])
        self.assertIn('dependency graph', report['message'])
        self.assertIn('not a passing scan', output)

    def test_response_does_not_leak_the_configured_token(self):
        code, report, output = self.run_blocked_review(b'{"message":"Denied configured-test-token and Bearer another-token"}')
        self.assertEqual(1, code)
        self.assertNotIn('configured-test-token', json.dumps(report) + output)
        self.assertNotIn('another-token', json.dumps(report) + output)
        self.assertIn('[REDACTED]', report['message'])

    def test_non_json_error_is_still_blocked_and_retains_the_http_status(self):
        code, report, output = self.run_blocked_review(b'<html>unavailable</html>', status=503)
        self.assertEqual(1, code)
        self.assertEqual(503, report['http_status'])
        self.assertFalse(report['scan_performed'])
        self.assertIn('No upstream diagnostic', output)


if __name__ == '__main__':
    unittest.main()
