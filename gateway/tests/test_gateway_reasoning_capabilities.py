"""Synthetic, read-only template proofs for the exact selected backend/model."""
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.inference_capabilities import InferenceCapabilities
from v14.execution_binding import configured_route


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
                parameter, enabled = next(iter(body['chat_template_kwargs'].items()))
                if fixture.behavior == 'malformed':
                    return self.reply({'prompt': ['invalid']})
                if fixture.behavior == 'ignored' or fixture.behavior == 'mixed' and parameter == 'enable_thinking':
                    return self.reply({'prompt': 'Synthetic prompt\n<think>'})
                if fixture.behavior in ('changed-prefix', 'mixed') and not enabled:
                    return self.reply({'prompt': 'Changed prompt\n<think></think>'})
                if fixture.behavior == 'missing-marker':
                    return self.reply({'prompt': 'Enabled' if enabled else 'Disabled'})
                if fixture.behavior == 'changed-suffix' and not enabled:
                    return self.reply({'prompt': 'Synthetic prompt\n<think>unexpected'})
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
                self.assertEqual('VERIFIED', result['reasoningProbe']['outcome'])
                self.assertEqual(2, result['reasoningProbe']['attempts'])
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
        for behavior, outcome, attempts, state in (
            ('ignored', 'IDENTICAL_PROMPTS', 2, 'UNSUPPORTED'),
            ('missing-marker', 'MISSING_THINK_MARKER', 2, 'UNSUPPORTED'),
            ('changed-prefix', 'PREFIX_MISMATCH', 2, 'UNSUPPORTED'),
            ('changed-suffix', 'SUFFIX_MISMATCH', 2, 'UNSUPPORTED'),
            ('malformed', 'INVALID_PROMPT', 2, 'UNVERIFIED'),
            ('unsupported', 'PROBE_UNAVAILABLE', 1, 'UNVERIFIED'),
            ('reload', 'METADATA_CHANGED', 2, 'UNVERIFIED'),
        ):
            with self.subTest(behavior=behavior):
                self.behavior, self.template_revision = behavior, 'one'
                capability = InferenceCapabilities(self.runtime)
                result = capability.selected_model('A')
                self.assertTrue(result['limitsVerified'])
                self.assertNotIn('reasoningControl', result)
                self.assertEqual(state, result['reasoningControlState'])
                self.assertEqual(outcome, result['reasoningProbe']['outcome'])
                self.assertEqual(attempts, result['reasoningProbe']['attempts'])
                count = len(self.calls)
                self.assertNotIn('reasoningControl', capability.selected_model('A'))
                self.assertEqual(count, len(self.calls))

    def test_missing_control_or_wrong_model_never_probes_or_issues_inference(self):
        self.parameter = 'no_control_variable'
        observed = self.capabilities.selected_model('A')
        self.assertTrue(observed['limitsVerified'])
        self.assertNotIn('reasoningControl', observed)
        self.assertNotIn('reasoningProbe', observed)
        self.props_model = 'B'
        mismatch = InferenceCapabilities(self.runtime).selected_model('A')
        self.assertFalse(mismatch['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_MISMATCH', mismatch['failureCode'])
        missing = self.capabilities.selected_model('missing')
        self.assertFalse(missing['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_UNAVAILABLE', missing['failureCode'])
        self.assertFalse(any(call[0] == 'POST' for call in self.calls))

    def test_metadata_refresh_does_not_reset_cached_negative_probe_age_or_add_requests(self):
        self.behavior = 'ignored'
        clock = [100.0]
        capability = InferenceCapabilities(self.runtime, clock=lambda: clock[0])
        first = capability.selected_model('A')
        self.assertEqual(0, first['observationAgeMs'])
        self.assertEqual(0, first['reasoningProbe']['observationAgeMs'])
        clock[0] += 8
        refreshed = capability.selected_model('A')
        self.assertEqual(0, refreshed['observationAgeMs'])
        self.assertEqual(8000, refreshed['reasoningProbe']['observationAgeMs'])
        self.assertEqual('UNSUPPORTED', refreshed['reasoningControlState'])
        self.assertEqual(2, len([call for call in self.calls if call[0] == 'POST']))
        # The public projection is copied; a caller cannot rewrite cached evidence.
        refreshed['reasoningProbe']['outcome'] = 'VERIFIED'
        clock[0] += 1
        cached = capability.selected_model('A')
        self.assertEqual(1000, cached['observationAgeMs'])
        self.assertEqual({'outcome': 'IDENTICAL_PROMPTS', 'attempts': 2, 'observationAgeMs': 9000}, cached['reasoningProbe'])
        clock[0] += 21
        expired = capability.selected_model('A')
        self.assertEqual(0, expired['reasoningProbe']['observationAgeMs'])
        self.assertEqual(4, len([call for call in self.calls if call[0] == 'POST']))

    def test_actual_request_starts_include_timeouts_but_exclude_deadline_prevention(self):
        route = configured_route(self.runtime, 'LLAMA')
        self.capabilities.inspect(route, 'A')
        self.capabilities.deadline_at = 0
        with patch.object(self.session, 'post', side_effect=AssertionError('must not dispatch')):
            prevented = self.capabilities.selected_model('A')
        self.assertTrue(prevented['limitsVerified'])
        self.assertEqual('UNVERIFIED', prevented['reasoningControlState'])
        self.assertEqual(0, prevented['reasoningProbe']['attempts'])
        self.assertEqual('PROBE_DEADLINE', prevented['reasoningProbe']['outcome'])

        capability = InferenceCapabilities(self.runtime)
        with patch.object(self.session, 'post', side_effect=requests.Timeout('private timeout')) as post:
            timed_out = capability.selected_model('A')
        self.assertEqual(1, post.call_count)
        self.assertEqual(1, timed_out['reasoningProbe']['attempts'])
        self.assertEqual('PROBE_UNAVAILABLE', timed_out['reasoningProbe']['outcome'])
        self.assertTrue(timed_out['limitsVerified'])
        self.assertNotIn('reasoningControl', timed_out)
        self.assertNotIn('private timeout', json.dumps(timed_out))

        capability = InferenceCapabilities(self.runtime)
        original = self.session.post
        def expire_after_start(*args, **kwargs):
            response = original(*args, **kwargs)
            capability.deadline_at = 0
            return response
        with patch.object(self.session, 'post', side_effect=expire_after_start) as post:
            partially_started = capability.selected_model('A')
        self.assertEqual(1, post.call_count)
        self.assertEqual(1, partially_started['reasoningProbe']['attempts'])
        self.assertEqual('PROBE_DEADLINE', partially_started['reasoningProbe']['outcome'])
        self.assertFalse(any('/chat/completions' in call[1] for call in self.calls))

    def test_mixed_candidates_count_each_existing_request_without_assigning_one_negative_cause(self):
        self.parameter, self.behavior = 'enable_thinking or thinking', 'mixed'
        result = self.capabilities.selected_model('A')
        self.assertEqual('UNSUPPORTED', result['reasoningControlState'])
        self.assertEqual('MIXED_NEGATIVE_RESULTS', result['reasoningProbe']['outcome'])
        self.assertEqual(4, result['reasoningProbe']['attempts'])
        self.assertEqual(4, len([call for call in self.calls if call[0] == 'POST']))

    def test_revision_and_generation_changes_cannot_republish_old_probe_evidence(self):
        self.behavior = 'ignored'
        first = self.capabilities.selected_model('A')
        self.template_revision, self.behavior = 'two', 'changed-prefix'
        replaced = self.capabilities.selected_model('A', force=True)
        self.assertNotEqual(first['capabilityRevision'], replaced['capabilityRevision'])
        self.assertEqual('PREFIX_MISMATCH', replaced['reasoningProbe']['outcome'])
        route = configured_route(self.runtime, 'LLAMA')
        self.capabilities.invalidate_model(route, 'A')
        original = self.session.post
        def invalidate_after_start(*args, **kwargs):
            response = original(*args, **kwargs)
            self.capabilities.invalidate_model(route, 'A')
            return response
        with patch.object(self.session, 'post', side_effect=invalidate_after_start):
            invalidated = self.capabilities.selected_model('A')
        self.assertFalse(invalidated['limitsVerified'])
        self.assertNotIn('reasoningProbe', invalidated)
        self.assertNotIn('reasoningControl', invalidated)
        self.assertEqual({}, self.capabilities.reasoning_cache)
        self.behavior = 'verified'
        self.assertEqual('VERIFIED', self.capabilities.selected_model('A')['reasoningProbe']['outcome'])

    def test_public_probe_and_verdict_come_from_one_snapshot_during_concurrent_refresh(self):
        clock = [100.0]
        capability = InferenceCapabilities(self.runtime, clock=lambda: clock[0])
        original = capability._reasoning_observation
        for first_behavior, next_behavior, first_state, first_outcome in (
            ('verified', 'unsupported', 'VERIFIED', 'VERIFIED'),
            ('ignored', 'verified', 'UNSUPPORTED', 'IDENTICAL_PROMPTS'),
        ):
            route = configured_route(self.runtime, 'LLAMA')
            capability.invalidate_model(route, 'A')
            self.behavior = first_behavior
            def replace_after_snapshot(*args, **kwargs):
                snapshot = original(*args, **kwargs)
                # Interpose a same-generation refresh after the verdict is
                # returned but before the selected-model response is projected.
                clock[0] += 61
                self.behavior = next_behavior
                replacement = original(*args, **kwargs)
                self.assertNotEqual(snapshot[3], replacement[3])
                return snapshot
            with patch.object(capability, '_reasoning_observation', side_effect=replace_after_snapshot):
                result = capability.selected_model('A')
            self.assertTrue(result['limitsVerified'])
            self.assertEqual(first_state, result['reasoningControlState'])
            self.assertEqual(first_state == 'VERIFIED', 'reasoningControl' in result)
            self.assertEqual({'outcome': first_outcome, 'attempts': 2, 'observationAgeMs': 61000}, result['reasoningProbe'])
        self.assertEqual(7, len([call for call in self.calls if call[0] == 'POST']))

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
