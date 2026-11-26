"""Offline installed-app transport contracts; no Gateway or provider requests."""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat, NoEncryption

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import campaign_gateway as bridge
from benchmarks import product_trials
import grade_external_reference as external
from desktop.conversation_client import Client, ClientError


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


LABELS = ("ChatGPT", "Codex", "Claude Code", "GPT Mobile AI")
TASK_KEYS = ("id", "seed", "taskPack", "prompt", "outputSchema", "taskDigest", "graderDigest", "environmentDigest")


def reference(source):
    return {"model": source + " (unverified external reference)", "provider_kind": "MANUAL_EXTERNAL_REFERENCE",
            "profile_uid": "external-reference:" + source, "profile_revision": "unobserved", "settings_digest": "unobserved",
            "route_identity": "manual-import", "request_ids": [], "identity_verified": False,
            "settings_confirmed": False, "usage_complete": False}


def request(answer="answer\r\n"):
    return {"request_id": "extref_" + "1" * 32, "task_id": "pagination-1", "task_pack": "python", "seed": 17,
            "fixture_digest": digest("task"), "grader_digest": digest("grader"), "environment_digest": digest("environment"),
            "proposal_digest": hashlib.sha256(answer.encode()).hexdigest(), "evidence_lane": "EXECUTABLE",
            "generation_receipt": reference("ChatGPT")}


class FakeClient:
    origin_value = "http://127.0.0.1:8090"

    def __init__(self, root=None):
        self.origin = self.origin_value
        self.calls = []
        self.responses = {}

    def request(self, method, path, *, params=None, payload=None, user_action=None):
        self.calls.append((method, path, params, payload, user_action))
        return self.responses.get((method, path), {})


class OriginTests(unittest.TestCase):
    def test_literal_local_origins(self):
        self.assertEqual("http://127.0.0.1:8090", bridge.canonical_origin("http://127.0.0.1:8090/"))
        self.assertEqual("https://10.2.3.4:443", bridge.canonical_origin("https://10.2.3.4"))
        for url in ("http://172.16.0.1:8090", "http://192.168.1.5:8090", "http://[::1]:8090", "http://[fd00::2]:8090", "http://100.101.102.103:8090"):
            self.assertTrue(bridge.canonical_origin(url))

    def test_public_dns_credentials_metadata_and_ambiguous_origins_rejected(self):
        for url in ("http://100.63.255.255:8090", "http://100.128.0.1:8090", "http://example.com:8090", "http://localhost:8090", "http://8.8.8.8:8090",
                    "http://169.254.169.254:80", "http://0.0.0.0:8090", "http://[::]:8090", "http://[fe80::1]:8090",
                    "http://127.0.0.1:8090/v1", "http://127.0.0.1:8090?target=x", "http://user:secret@127.0.0.1:8090",
                    "http://127.0.0.1:0", "file:///etc/passwd", " http://127.0.0.1:8090", "http://[::1%eth0]:8090"):
            with self.subTest(url=url), self.assertRaises(bridge.BridgeError):
                bridge.canonical_origin(url)


class RequestGuardTests(unittest.TestCase):
    def client(self, operation):
        return bridge.guarded_client_class(FakeClient, "http://127.0.0.1:8090", operation,
                                           [reference(source) for source in LABELS])()

    def test_supplied_answer_only_preserves_exact_candidate(self):
        client = self.client("submit")
        payload = dict(request(), proposal="answer\r\n")
        client.request("POST", bridge.API + "/evaluate", payload=payload)
        self.assertEqual(payload, client.calls[0][3])
        self.assertEqual("answer\r\n", client.calls[0][3]["proposal"])

    def test_generation_and_live_qualification_endpoints_never_reach_transport(self):
        client = self.client("submit")
        for path in (bridge.API, "/v1/chat/completions", "/gateway/engineering/jobs", bridge.API + "/qualifications",
                     bridge.API + "/qualifications/one/resume", "/gateway/conversations/one/messages"):
            with self.subTest(path=path), self.assertRaises(bridge.BridgeError):
                client.request("POST", path, payload={"model": "paid"})
        self.assertEqual([], client.calls)

    def test_live_lane_provider_route_or_modified_digest_rejected(self):
        for change in ({"evidence_lane": "LIVE_GITHUB"}, {"generation_receipt": {"provider_kind": "OPENROUTER"}},
                       {"proposal_digest": "a" * 64}, {"repository": "owner/repo"}):
            client = self.client("submit")
            payload = dict(request(), proposal="answer\r\n")
            payload.update(change)
            with self.subTest(change=change), self.assertRaises(bridge.BridgeError):
                client.request("POST", bridge.API + "/evaluate", payload=payload)
            self.assertEqual([], client.calls)

    def test_status_cannot_submit_and_prepare_cannot_read(self):
        with self.assertRaises(bridge.BridgeError):
            self.client("status").request("POST", bridge.API + "/evaluate", payload=dict(request(), proposal="answer\r\n"))
        with self.assertRaises(bridge.BridgeError):
            self.client("prepare").request("GET", "/gateway/identity")

    def test_readback_requires_exact_original_style_request_id(self):
        client = self.client("status")
        client.request("GET", bridge.API, params={"request_id": request()["request_id"]})
        self.assertEqual(1, len(client.calls))
        for params in (None, {}, {"request_id": "new"}, {"request_id": request()["request_id"], "limit": 10}):
            with self.subTest(params=params), self.assertRaises(bridge.BridgeError):
                client.request("GET", bridge.API, params=params)
        self.assertEqual(1, len(client.calls))

    def test_retirement_requires_same_bound_request_id(self):
        client = self.client("retire")
        original = request()["request_id"]
        with self.assertRaises(bridge.BridgeError):
            client.request("POST", bridge.API + "/cancel-request", payload={"request_id": original})
        client.bridge_retire_request_id = original
        client.request("POST", bridge.API + "/cancel-request", payload={"request_id": original})
        with self.assertRaises(bridge.BridgeError):
            client.request("POST", bridge.API + "/cancel-request", payload={"request_id": "extref_" + "2" * 32})
        self.assertEqual(1, len(client.calls))

    def test_origin_change_rejected_before_transport(self):
        client = self.client("status")
        client.origin = "http://192.168.1.99:8090"
        with self.assertRaises(bridge.BridgeError):
            client.request("GET", "/gateway/identity")
        self.assertEqual([], client.calls)

    def test_conversation_readback_only(self):
        client = self.client("read-capture")
        client.request("GET", "/gateway/conversations/synthetic-session")
        client.request("GET", "/gateway/conversations/synthetic-session/messages", params={"limit": 100, "after_cursor": "opaque"})
        with self.assertRaises(bridge.BridgeError):
            client.request("PATCH", "/gateway/conversations/synthetic-session/metadata", payload={})
        with self.assertRaises(bridge.BridgeError):
            client.request("GET", "/gateway/conversations/../identity")
        self.assertEqual(2, len(client.calls))

    def test_receipt_boolean_is_not_number(self):
        bad = reference("ChatGPT")
        bad["identity_verified"] = 0
        self.assertFalse(bridge.same_reference(bad, [reference("ChatGPT")]))

    def test_digest_values_are_strings_and_seed_is_not_boolean(self):
        refs = [reference(source) for source in LABELS]
        for key, value in (("fixture_digest", int("1" * 64)), ("task_id", 1), ("seed", True)):
            bad = request()
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(bridge.BridgeError):
                bridge.validate_frozen_request(bad, refs)


class FixtureGateway:
    """Use the real Client identity/read methods with an in-memory transport."""
    def __init__(self):
        self.owner = {'gateway_id': 'fixture-gateway', 'principal_id': 'fixture-owner'}
        self.task = dict(schema='gateway.coding-task-public.v2', id='pagination-1', seed=17,
                         taskPack='python', prompt='Synthetic public task', outputSchema={},
                         taskDigest=external.digest('task'), graderDigest=external.digest('grader'),
                         environmentDigest=external.digest('environment'), family='pagination')
        self.origin = 'http://127.0.0.1:8090'
        self.calls, self.run = [], None
        self.lose_submit = False
        self.summary = {'qualified': True, 'outcomeComplete': True, 'resolved': 1, 'score': None}
        self.switch_owner_after_fixture = False

    def request(self, method, path, *, params=None, payload=None, user_action=None):
        self.calls.append((method, path, params, payload, user_action))
        if method == 'GET' and path == '/gateway/identity':
            return dict(self.owner)
        if method == 'GET' and path == bridge.API + '/fixtures/pagination-1':
            if self.switch_owner_after_fixture:
                self.owner['principal_id'] = 'other-owner'
            return dict(self.task)
        if method == 'GET' and path == bridge.API:
            return {'submissionState': 'OBSERVED', 'run': self.run} if self.run else {'submissionState': 'NOT_OBSERVED'}
        if method == 'POST' and path == bridge.API + '/evaluate':
            if self.lose_submit:
                raise ClientError('Private capture diagnostic must not escape', code='connection_unavailable')
            self.run = {'id': 'fixture-grade-run', 'state': 'COMPLETED',
                        'request': dict({k: v for k, v in payload.items() if k != 'proposal'}, suite='coding_submission'),
                        'summary': self.summary}
            return self.run
        if method == 'POST' and path == bridge.API + '/cancel-request':
            return {'submissionState': 'REMOVED', 'request_id': payload['request_id']}
        if method == 'GET' and path == '/gateway/conversations/synthetic-session':
            return {'conversation_id': 'synthetic-session', 'history_complete': True, 'job_state': 'COMPLETED'}
        if method == 'GET' and path == '/gateway/conversations/synthetic-session/messages':
            text = ' exact answer\r\n '
            return {'messages': [{'message_id': 'answer', 'revision': 1, 'role': 'assistant', 'content': text,
                                  'content_sha256': hashlib.sha256(text.encode()).hexdigest()}],
                    'history_complete': True, 'has_more': False, 'revision': 1}
        raise AssertionError('Unexpected fixture request')

    def client_class(self):
        gateway = self

        class FixtureClient(Client):
            def __init__(self, root):
                self.origin = gateway.origin
                self.expected_identity = None

            def request(self, method, path, **values):
                return gateway.request(method, path, **values)

        return FixtureClient


class InstalledOperationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # Keep strong references so the test cannot accidentally rely on GC to
        # release SQLite handles before Windows removes the fixture directory.
        self.sqlite_connections = []
        self.addCleanup(self.assert_sqlite_connections_closed)
        self.root = Path(self.temp.name).resolve()
        self.gateway = FixtureGateway()
        self.client = self.gateway.client_class()
        self.task_path, self.answer_path = self.root / 'task.json', self.root / 'candidate.txt'
        self.trial = self.root / 'original.sqlite'

    def open_trial_database(self, path):
        connection = sqlite3.connect(path)
        self.sqlite_connections.append(connection)
        return connection

    def assert_sqlite_connections_closed(self):
        unclosed = []
        for connection in self.sqlite_connections:
            try:
                connection.execute('SELECT 1').close()
            except sqlite3.ProgrammingError:
                continue
            unclosed.append(connection)
        # Release resources after detecting a violation, then fail the test;
        # this prevents a second cleanup error from obscuring the ownership bug.
        for connection in unclosed:
            connection.close()
        self.assertEqual(0, len(unclosed), 'Every SQLite connection must be closed by its owner before fixture cleanup')

    def execute(self, operation, body, **values):
        return bridge.execute(self.root, operation, body, client_factory=self.client, **values)

    def prepare(self, answer=' invalid candidate\r\n '):
        task = {key: self.gateway.task[key] for key in external.TASK_KEYS}
        task['gatewayOwnerDigest'] = external.digest(external.scope_key(self.gateway.owner))
        self.task_path.write_text(json.dumps(task), encoding='utf-8')
        self.answer_path.write_bytes(answer.encode('utf-8'))
        return self.execute('prepare', {'task': str(self.task_path), 'source': 'ChatGPT',
                                      'candidate': str(self.answer_path), 'trial': str(self.trial)})

    def test_prepare_uses_installed_canonical_grader_without_client_or_checkout(self):
        self.prepare()
        self.assertEqual([], self.gateway.calls)
        self.assertFalse((self.root / '.git').exists())
        with closing(self.open_trial_database(self.trial)) as database:
            raw, attempted = database.execute('SELECT plan, attempted FROM trial').fetchone()
        saved = json.loads(raw)
        self.assertEqual(0, attempted)
        self.assertEqual(hashlib.sha256(b' invalid candidate\r\n ').hexdigest(), saved['request']['proposal_digest'])
        with self.assertRaises(bridge.BridgeError):
            self.prepare('replacement')
        self.assertEqual(saved, bridge.load_trial(self.trial, [external.reference(p) for p in external.LABELS]))

    def test_export_keeps_authoritative_family_and_original_owner(self):
        value = self.execute('export', {'tasks': [{'id': 'pagination-1', 'pack': 'python', 'seed': 17, 'family': 'pagination'}]})
        self.assertEqual('pagination', value['tasks'][0]['family'])
        self.assertEqual(external.digest(external.scope_key(self.gateway.owner)), value['ownerDigest'])
        self.assertEqual(value['ownerDigest'], value['tasks'][0]['gatewayOwnerDigest'])
        self.assertEqual({'GET'}, {row[0] for row in self.gateway.calls})

    def test_export_rejects_family_or_owner_substitution(self):
        body = {'tasks': [{'id': 'pagination-1', 'pack': 'python', 'seed': 17, 'family': 'not-the-fixture'}]}
        with self.assertRaisesRegex(bridge.BridgeError, 'family'):
            self.execute('export', body)
        body['tasks'][0]['family'] = 'pagination'
        self.gateway.switch_owner_after_fixture = True
        with self.assertRaisesRegex(bridge.BridgeError, 'owner'):
            self.execute('export', body)

    def test_desktop_owner_change_blocks_public_tasks_and_capture_before_read(self):
        frozen_owner = external.digest(external.scope_key(self.gateway.owner))
        self.gateway.owner['principal_id'] = 'owner-selected-after-desktop-precheck'
        for operation, body in (
                ('export', {'tasks': [{'id': 'pagination-1', 'pack': 'python', 'seed': 17, 'family': 'pagination'}]}),
                ('read-capture', {'conversation_id': 'synthetic-session'})):
            self.gateway.calls.clear()
            with self.subTest(operation=operation), self.assertRaises(bridge.BridgeError) as raised:
                self.execute(operation, body, expected_owner_digest=frozen_owner)
            self.assertEqual('gateway_owner_changed', raised.exception.code)
            self.assertEqual([('GET', '/gateway/identity')], [(c[0], c[1]) for c in self.gateway.calls])

    def test_registered_desktop_owner_can_read_and_invalid_owner_binding_is_rejected(self):
        frozen_owner = external.digest(external.scope_key(self.gateway.owner))
        value = self.execute('read-capture', {'conversation_id': 'synthetic-session'}, expected_owner_digest=frozen_owner)
        self.assertEqual('synthetic-session', value['conversation']['conversation_id'])
        self.gateway.calls.clear()
        with self.assertRaisesRegex(bridge.BridgeError, 'binding'):
            self.execute('read-capture', {'conversation_id': 'synthetic-session'}, expected_owner_digest='wrong')
        self.assertEqual([], self.gateway.calls)

    def test_submit_preserves_bytes_and_readback_does_not_generate(self):
        prepared = self.prepare()
        body = {'trial': str(self.trial), 'candidate': str(self.answer_path)}
        result = self.execute('submit', body)
        self.assertEqual(prepared['request_id'], result['request_id'])
        self.assertEqual('OBSERVED', result['submissionState'])
        self.assertIsNone(result['score'])
        self.assertTrue(result['qualified'])
        for _ in range(2):
            self.execute('submit', body)
            self.execute('status', {'trial': str(self.trial)})
        posts = [row for row in self.gateway.calls if row[0] == 'POST']
        self.assertEqual(1, len(posts))
        self.assertEqual(' invalid candidate\r\n ', posts[0][3]['proposal'])
        self.assertEqual('EXECUTABLE', posts[0][3]['evidence_lane'])

    def test_uncertain_admission_never_reposts_and_diagnostic_hides_private_text(self):
        prepared = self.prepare()
        self.gateway.lose_submit = True
        body = {'trial': str(self.trial), 'candidate': str(self.answer_path)}
        with self.assertRaises(bridge.BridgeError) as raised:
            self.execute('submit', body)
        self.assertNotIn('Private capture', str(raised.exception))
        self.assertNotIn(str(self.trial), str(raised.exception))
        for _ in range(2):
            result = self.execute('submit', body)
            self.assertTrue(result['submissionAttemptRecorded'])
            self.assertEqual('NOT_OBSERVED', result['submissionState'])
            self.assertEqual(prepared['request_id'], result['request_id'])
        self.assertEqual(1, len([row for row in self.gateway.calls if row[0] == 'POST']))

    def test_task_or_candidate_change_blocks_admission(self):
        self.prepare()
        self.answer_path.write_bytes(b'different answer')
        with self.assertRaises(bridge.BridgeError):
            self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        self.answer_path.write_bytes(b' invalid candidate\r\n ')
        self.gateway.task['environmentDigest'] = external.digest('different environment')
        with self.assertRaises(bridge.BridgeError):
            self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        self.assertEqual([], [row for row in self.gateway.calls if row[0] == 'POST'])

    def test_original_owner_and_frozen_origin_are_required(self):
        self.prepare()
        self.gateway.owner['principal_id'] = 'different-owner'
        with self.assertRaises(bridge.BridgeError):
            self.execute('status', {'trial': str(self.trial)})
        self.gateway.calls.clear()
        with self.assertRaisesRegex(bridge.BridgeError, 'origin'):
            self.execute('status', {'trial': str(self.trial)}, allowed_origin='http://192.168.0.3:8090')
        self.assertEqual([], self.gateway.calls)

    def test_incomplete_grader_outcome_keeps_unknown_result(self):
        self.prepare()
        self.gateway.summary = {'qualified': False, 'outcomeComplete': False, 'resolved': 0, 'score': 0}
        result = self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        self.assertIsNone(result['resolved'])
        self.assertIsNone(result['score'])
        self.assertFalse(result['outcomeComplete'])

    def test_missing_trial_is_not_created(self):
        with self.assertRaises(bridge.BridgeError):
            self.execute('status', {'trial': str(self.trial)})
        self.assertFalse(self.trial.exists())
        self.assertEqual([], self.gateway.calls)

    def test_retirement_binds_original_request_without_new_admission(self):
        prepared = self.prepare()
        before = self.trial.read_bytes()
        result = self.execute('retire', {'trial': str(self.trial)})
        self.assertEqual(prepared['request_id'], result['request_id'])
        self.assertEqual(before, self.trial.read_bytes())
        posts = [row for row in self.gateway.calls if row[0] == 'POST']
        self.assertEqual([(bridge.API + '/cancel-request', {'request_id': prepared['request_id']})],
                         [(row[1], row[3]) for row in posts])

    def test_saved_capture_uses_installed_history_assembler_and_exact_bytes(self):
        value = self.execute('read-capture', {'conversation_id': 'synthetic-session'})
        self.assertEqual(' exact answer\r\n ', value['messages'][0]['content'])
        self.assertTrue(value['conversation']['history_complete'])
        self.assertEqual({'GET'}, {row[0] for row in self.gateway.calls})

    def native_evidence(self):
        observer, grader = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
        task = self.gateway.task
        plan = dict(schema=product_trials.PLAN, cache='cold', controlsDigest=external.digest('controls'),
                    orderSeed=external.digest('order'), versions={'GPT Mobile AI': 'fixture-1', 'ChatGPT': 'fixture-1'},
                    maxSeconds=30, maxCostUsd=1, repeats=1, marginPoints=5,
                    cases=[{'taskDigest': task['taskDigest'], 'family': 'pagination',
                            'graderDigest': task['graderDigest'], 'environmentDigest': task['environmentDigest']}])
        order = product_trials.schedule(plan)
        index = next(i for i, row in enumerate(order) if row['product'] == 'ChatGPT')
        row = dict(order[index], schema=product_trials.OBSERVATION, state='DELIVERED',
                   planDigest=external.digest(plan), controlsDigest=plan['controlsDigest'], cache='cold',
                   version='fixture-1', seconds=2, costUsd=None, interventions=0, scheduleIndex=index,
                   runId=external.digest('native-session'), artifactDigest=hashlib.sha256(self.answer_path.read_bytes()).hexdigest())
        paths = {key: self.root / name for key, name in
                 (('plan', 'plan.json'), ('observation', 'observation.json'),
                  ('observer_public', 'observer-public.pem'), ('grader_key', 'grader-private.pem'))}
        for name, payload in (('plan', plan), ('observation', row)):
            paths[name].write_text(json.dumps({'payload': payload, 'signature': observer.sign(product_trials.signed_bytes(payload)).hex()}))
        paths['observer_public'].write_bytes(observer.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
        paths['grader_key'].write_bytes(grader.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        return dict({name: str(path) for name, path in paths.items()}, trial=str(self.trial)), observer, grader

    def test_grade_uses_canonical_signed_contract_and_preserves_null_score(self):
        self.prepare()
        self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        body, _, grader = self.native_evidence()
        self.gateway.calls.clear()
        result = self.execute('grade', body)
        verified = product_trials.verified(result, grader.public_key(), product_trials.GRADE)
        self.assertEqual('PASS', verified['result'])
        self.assertEqual('fixture-grade-run', verified['graderRunId'])
        self.assertNotIn('product', verified)
        self.assertEqual({'GET'}, {call[0] for call in self.gateway.calls})

    def test_grade_unavailable_remains_unknown(self):
        self.prepare()
        self.gateway.summary = {'qualified': False, 'outcomeComplete': False, 'resolved': 0, 'score': 0}
        self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        body, _, grader = self.native_evidence()
        result = self.execute('grade', body)
        self.assertEqual('GRADER_UNAVAILABLE', product_trials.verified(result, grader.public_key(), product_trials.GRADE)['result'])

    def test_independent_signer_and_untampered_plan_are_required(self):
        self.prepare()
        self.execute('submit', {'trial': str(self.trial), 'candidate': str(self.answer_path)})
        body, observer, _ = self.native_evidence()
        Path(body['grader_key']).write_bytes(observer.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        with self.assertRaises(bridge.BridgeError):
            self.execute('grade', body)
        envelope = json.loads(Path(body['plan']).read_text())
        envelope['payload']['maxCostUsd'] = 999
        Path(body['plan']).write_text(json.dumps(envelope))
        self.gateway.calls.clear()
        with self.assertRaises(bridge.BridgeError):
            self.execute('grade', body)
        self.assertEqual([], self.gateway.calls)

    def test_paths_cannot_escape_into_installed_source_through_symlink(self):
        with self.assertRaises(bridge.BridgeError):
            bridge.absolute_path('relative.json')
        with self.assertRaises(bridge.BridgeError):
            bridge.absolute_path(bridge.SOURCE_ROOT / 'candidate.json', repo=bridge.SOURCE_ROOT)
        alias = self.root / 'source-alias'
        try:
            alias.symlink_to(bridge.SOURCE_ROOT, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('Symlink creation is unavailable on this host')
        with self.assertRaises(bridge.BridgeError):
            bridge.absolute_path(alias / 'candidate.json', repo=bridge.SOURCE_ROOT)

    def test_duplicate_json_and_nonfinite_values_are_not_evidence(self):
        for value in ('{"state":"DELIVERED","state":"FAILED"}', '{"seconds":NaN}', '{"cost":Infinity}'):
            with self.subTest(value=value), self.assertRaises(bridge.BridgeError):
                bridge.strict_json(value)


if __name__ == '__main__':
    unittest.main()
