import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sys
import types
from unittest.mock import Mock

requests = types.ModuleType("requests")
requests.RequestException = type("RequestException", (Exception,), {})
requests.ConnectionError = requests.RequestException
requests.request = Mock()

SPEC = importlib.util.spec_from_file_location('github_live_acceptance', Path(__file__).resolve().parents[1] / 'github_live_acceptance.py')
acceptance = importlib.util.module_from_spec(SPEC)
with patch.dict(sys.modules, {"requests": requests}):
    SPEC.loader.exec_module(acceptance)


class Response:
    def __init__(self, status, value):
        self.status_code, self.value = status, value
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def iter_content(self, _): yield json.dumps(self.value).encode()


class LiveAcceptanceTests(unittest.TestCase):
    def test_missing_live_inputs_are_blocked_not_simulated_success(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'nested/report.json'
            with patch.dict(os.environ, {}, clear=True), patch('sys.argv', ['acceptance', '--repository', 'fixture/private', '--source-sha', 'a' * 40, '--output', str(output)]):
                self.assertEqual(2, acceptance.main())
            report = json.loads(output.read_text())
            self.assertFalse(report['complete'])
            self.assertIsNone(report['running_app_source_sha'])
            self.assertTrue(all(row['status'] == 'BLOCKED' for row in report['results']))

    def test_lost_write_response_is_read_reconciled_and_owned_fixture_cleaned_once(self):
        state = dict(created=False, posts=0, deletes=0)
        def request(method, url, **kwargs):
            if url.endswith('/user'): return Response(200, {'id': 1})
            if url.endswith('/repos/fixture/private'): return Response(200, {'private': True, 'permissions': {'push': True}})
            if method == 'POST':
                state['posts'] += 1
                state['created'] = True
                raise requests.ConnectionError('Lost accepted response')
            if method == 'DELETE':
                state['deletes'] += 1
                state['created'] = False
                return Response(204, {})
            return Response(200, {'object': {'sha': 'a' * 40}}) if state['created'] else Response(404, {})
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'nested/report.json'
            with patch.dict(os.environ, {'GITHUB_ACCEPTANCE_TOKEN': 'fixture', 'GITHUB_ACCEPTANCE_ROTATED_TOKEN': 'rotated'}, clear=True), patch('sys.argv', ['acceptance', '--repository', 'fixture/private', '--source-sha', 'a' * 40, '--output', str(output), '--allow-fixture-writes']), patch.object(acceptance.requests, 'request', side_effect=request):
                self.assertEqual(2, acceptance.main())  # Device cases still blocked.
            statuses = {row['name']: row['status'] for row in json.loads(output.read_text())['results']}
            self.assertEqual('PASSED', statuses['lost_response_rotation_reconciliation'])
            self.assertEqual('PASSED', statuses['owned_fixture_cleanup'])
            self.assertEqual(1, state['posts'])
            self.assertEqual(1, state['deletes'])
