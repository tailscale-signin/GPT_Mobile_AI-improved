"""Synthetic, read-only template proofs for the exact selected backend/model."""
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.inference_capabilities import InferenceCapabilities


class ReasoningCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.parameter, self.behavior, self.props_model = 'enable_thinking', 'verified', 'A'
        self.template_revision = 'one'
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body, status=200):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                path = urlsplit(self.path)
                fixture.calls.append(('GET', path.path, parse_qs(path.query), None))
                if path.path == '/v1/models':
                    return self.reply({'data': [{'id': 'A'}]})
                if path.path == '/props':
                    return self.reply({'model_alias': fixture.props_model, 'model_sha256': 'fixture-weights',
                                       'chat_template': '{% if ' + fixture.parameter + ' %} fixture-' + fixture.template_revision,
                                       'default_generation_settings': {'n_ctx': 8192}})
                return self.reply({}, 404)

            def do_POST(self):
                path = urlsplit(self.path)
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                fixture.calls.append(('POST', path.path, parse_qs(path.query), body))
                if path.path != '/apply-template' or fixture.behavior == 'unsupported':
                    return self.reply({}, 404)
                enabled = body['chat_template_kwargs'].get(fixture.parameter)
                if fixture.behavior == 'malformed':
                    return self.reply({'prompt': ['invalid']})
                if fixture.behavior == 'ignored':
                    return self.reply({'prompt': 'Synthetic prompt\n<think>'})
                if fixture.behavior == 'changed-prefix' and not enabled:
                    return self.reply({'prompt': 'Changed prompt\n<think></think>'})
                if fixture.behavior == 'reload' and not enabled:
                    fixture.template_revision = 'two'
                return self.reply({'prompt': 'Synthetic prompt\n<think>' + ('' if enabled else '</think>')})

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = 'http://127.0.0.1:' + str(self.server.server_port)
        self.session = requests.Session()
        self.runtime = SimpleNamespace(LLAMA_BASE=self.endpoint, GATEWAY_INFERENCE_PROVIDER='LLAMA',
                                       http_get=self.session.get, get_http_session=lambda: self.session,
                                       gateway_instance_id='fixture-instance')
        self.capabilities = InferenceCapabilities(self.runtime)

    def tearDown(self):
        self.session.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_verified_control_matches_capacity_revision_and_uses_no_inference(self):
        for parameter in ('enable_thinking', 'thinking'):
            with self.subTest(parameter=parameter):
                self.parameter = parameter
                capability = InferenceCapabilities(self.runtime)
                result = capability.selected_model('A')
                self.assertTrue(result['limitsVerified'])
                self.assertEqual(8192, result['contextTokens'])
                proof = result['reasoningControl']
                self.assertEqual(parameter, proof['parameter'])
                self.assertIs(True, proof['templateProbeVerified'])
                self.assertEqual('GATEWAY_OBSERVED_TEMPLATE_PROBE', proof['evidenceOrigin'])
                self.assertEqual(1, proof['schemaVersion'])
                self.assertEqual(result['capabilityRevision'], proof['capabilityRevision'])
                self.assertGreater(proof['observedAtMillis'], 0)
                self.assertIsNone(proof['observedComputationEnabled'])
                for field in ('templateDigest', 'enabledPromptDigest', 'disabledPromptDigest'):
                    self.assertRegex(proof[field], r'^[0-9a-f]{64}$')
                self.assertNotEqual(proof['enabledPromptDigest'], proof['disabledPromptDigest'])
                self.assertNotIn(self.endpoint, json.dumps(result))
                self.assertNotIn('Synthetic prompt', json.dumps(result))
                self.assertNotIn('fixture-one', json.dumps(result))
                count = len(self.calls)
                self.assertEqual(proof, capability.selected_model('A')['reasoningControl'])
                self.assertEqual(count, len(self.calls))
        posts = [call for call in self.calls if call[0] == 'POST']
        self.assertEqual(4, len(posts))
        for method, path, params, body in posts:
            self.assertEqual('/apply-template', path)
            self.assertEqual({'model': ['A'], 'autoload': ['false']}, params)
            self.assertEqual('A', body['model'])
            self.assertEqual([{'role': 'user', 'content': 'Template capability check.'}], body['messages'])
        self.assertFalse(any('/chat/completions' in call[1] for call in self.calls))

    def test_unproven_control_does_not_invalidate_known_capacity_and_is_negatively_cached(self):
        for behavior in ('ignored', 'changed-prefix', 'malformed', 'unsupported', 'reload'):
            with self.subTest(behavior=behavior):
                self.behavior, self.template_revision = behavior, 'one'
                capability = InferenceCapabilities(self.runtime)
                result = capability.selected_model('A')
                self.assertTrue(result['limitsVerified'])
                self.assertNotIn('reasoningControl', result)
                count = len(self.calls)
                self.assertNotIn('reasoningControl', capability.selected_model('A'))
                self.assertEqual(count, len(self.calls))

    def test_missing_control_or_wrong_model_never_probes_or_issues_inference(self):
        self.parameter = 'no_control_variable'
        observed = self.capabilities.selected_model('A')
        self.assertTrue(observed['limitsVerified'])
        self.assertNotIn('reasoningControl', observed)
        self.props_model = 'B'
        mismatch = InferenceCapabilities(self.runtime).selected_model('A')
        self.assertFalse(mismatch['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_MISMATCH', mismatch['failureCode'])
        missing = self.capabilities.selected_model('missing')
        self.assertFalse(missing['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_UNAVAILABLE', missing['failureCode'])
        self.assertFalse(any(call[0] == 'POST' for call in self.calls))

    def test_optional_probe_timeout_is_bounded_and_does_not_hide_context_limits(self):
        original = self.capabilities._json
        calls = []
        def request(method, url, **kwargs):
            if method == 'POST':
                calls.append(kwargs)
                raise requests.Timeout('fixture unavailable')
            return original(method, url, **kwargs)
        self.capabilities._json = request
        result = self.capabilities.selected_model('A')
        self.assertTrue(result['limitsVerified'])
        self.assertNotIn('reasoningControl', result)
        self.assertEqual(1, len(calls))
        self.assertIsInstance(calls[0]['deadline_at'], float)


if __name__ == '__main__':
    unittest.main()
