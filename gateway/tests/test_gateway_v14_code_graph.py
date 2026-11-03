import base64
import copy
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.code_graph.extract import extract, permitted
from v14.code_graph.engine import Graph, GraphStore, bounded, encoded, scope
from v14.code_graph.service import GraphService, validate
from v14.engineering import EngineeringStore
from v14.engineering_backend import blob_sha


def snapshot(files, sha='a' * 40):
    return {'schema_version': 1, 'repository': 'owner/repo', 'branch': 'feature', 'sha': sha,
            'manifest': {p: {'sha': blob_sha(t), 'size': len(t.encode())} for p, t in files.items()},
            'fragments': {p: extract(p, t) for p, t in files.items()}}


class ExtractionTests(unittest.TestCase):
    def test_python_structure_preserves_nested_owners_and_call_direction(self):
        graph = Graph(snapshot({'service.py': 'class Service:\n    def send(self):\n        return 1\n    def run(self):\n        return self.send()\n'}))
        result = graph.path('Service.run', 'Service.send')
        self.assertEqual('FOUND', result['status'])
        self.assertEqual('INFERRED', result['confidence'])
        self.assertEqual('NO_PATH_IN_INDEX', graph.path('Service.send', 'Service.run')['status'])
        self.assertTrue(all(n['commit_sha'] == 'a' * 40 and '#L' in n['url'] for n in result['nodes']))

    def test_same_name_and_dynamic_receivers_are_not_guessed(self):
        graph = Graph(snapshot({'a.py': 'def send(): pass\n', 'b.py': 'def send(): pass\n', 'c.py': 'def run(client):\n    client.send()\n'}))
        self.assertEqual('AMBIGUOUS', graph.neighbors('send')['status'])
        self.assertEqual([], graph.neighbors('run', 'outgoing', 'calls')['edges'])
        self.assertGreater(graph.metadata()['unresolved_references'], 0)

    def test_python_syntax_failure_and_sensitive_paths(self):
        self.assertEqual('failed', extract('bad.py', 'def broken(')['status'])
        for path in ['../a.py', '/a.py', 'x/.git/config.json', 'secrets.json', 'build/a.py', '.env.json']:
            self.assertFalse(permitted(path))

    def test_kotlin_java_javascript_typescript_structural_parsers(self):
        cases = {'Service.kt': 'class Service {\n fun send(): Int { return 1 }\n fun run(): Int { return send() }\n}\n',
                 'Service.java': 'class Service { int send() {return 1;} int run() {return send();} }',
                 'service.js': 'function send(){return 1;} function run(){return send();}',
                 'service.ts': 'function send(): number {return 1;} function run(): number {return send();}'}
        for path, source in cases.items():
            with self.subTest(path=path):
                graph = Graph(snapshot({path: source}))
                self.assertNotEqual('unsupported', graph.snapshot['fragments'][path]['status'])
                self.assertEqual('FOUND', graph.path('run', 'send')['status'])

    def test_imported_test_is_ranked_by_relationship(self):
        graph = Graph(snapshot({'service.py': 'def run(): return 1\n', 'tests/test_service.py': 'from service import run\ndef test_run():\n    assert run() == 1\n'}))
        result = graph.impact(['service.py'])
        self.assertEqual('tests/test_service.py', result['tests'][0]['path'])
        self.assertEqual('NOT_RUN', result['tests'][0]['execution_status'])
        self.assertTrue(result['required_ci_unchanged'])

    def test_changing_callee_invalidates_unchanged_callers(self):
        files = {'service.py': 'def run(): return 1\n', 'tests/test_service.py': 'from service import run\ndef test_run():\n    run()\n'}
        old = Graph(snapshot(files))
        files['service.py'] = 'def renamed(): return 2\n'
        new = Graph(snapshot(files, 'b' * 40))
        self.assertEqual('NO_PATH_IN_INDEX', new.path('test_run', 'renamed')['status'])
        self.assertTrue(new.compare(old)['removed_relationships'])

    def test_relative_javascript_import_links_to_exact_module(self):
        graph = Graph(snapshot({'client.js': 'export function send() { return 1; }',
                                'app.js': 'import {send} from "./client"; function run() { return send(); }'}))
        self.assertEqual('FOUND', graph.path('run', 'send')['status'])

    def test_missing_import_cannot_resolve_to_unrelated_same_name(self):
        graph = Graph(snapshot({'other.py': 'def send(): pass\n', 'app.py': 'from missing import send\ndef run(): send()\n'}))
        self.assertEqual('NO_PATH_IN_INDEX', graph.path('run', 'send')['status'])

    def test_contract_comparison_and_diagnostic_mapping(self):
        graph = Graph(snapshot({'server.py': 'def response():\n    return {"head_sha": "abc"}\n',
                                'Client.kt': 'fun read(data: Map<String, String>) {\n data.get("head_sha")\n data.get("wrong_name")\n}\n'}))
        result = graph.contracts('server.py', 'Client.kt')
        self.assertEqual(['head_sha'], result['matched_fields'])
        self.assertEqual('wrong_name', result['consumer_fields_without_producer'][0]['name'])
        self.assertFalse(graph.diagnose('at server.py:2 in response')['root_cause_proven'])

    def test_diagnostics_select_containing_declaration_from_source_line(self):
        graph = Graph(snapshot({'service.py': 'def first():\n    return 1\n\ndef second():\n    return 2\n'}))
        for log in ['at service.py:5', 'File "service.py", line 5']:
            result = graph.diagnose(log)
            self.assertEqual('second', result['nodes'][0]['name'])
            self.assertNotIn('first', [n['name'] for n in result['nodes']])
            self.assertEqual(5, result['nodes'][0]['diagnostic_locations'][0]['line'])

    def test_cycles_documentation_and_pr_overlap_are_review_signals(self):
        graph = Graph(snapshot({'a.py': 'import b\ndef one(): pass\n', 'b.py': 'import a\ndef two(): pass\n'}))
        self.assertTrue(graph.architecture()['dependency_cycles'])
        self.assertIn('```mermaid', graph.wiki()['markdown'])
        result = graph.pr_overlap([{'number': 1, 'files': ['a.py']}, {'number': 2, 'files': ['b.py']}])
        self.assertEqual(1, len(result['overlaps']))
        self.assertFalse(result['merge_authorized'])

    def test_context_budget_retains_valid_json_and_no_dangling_edges(self):
        graph = Graph(snapshot({'a.py': '\n'.join(f'def f{i}(): return {i}' for i in range(200))}))
        result = bounded(graph.query('def', 100), 2048)
        self.assertLessEqual(len(encoded(result).encode()), 2048)
        self.assertTrue(result['truncated'])
        ids = {n['id'] for n in result.get('nodes', [])}
        self.assertTrue(all(e['source'] in ids and e['target'] in ids for e in result.get('edges', [])))


class GraphServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / 'jobs.db'
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                  _durable_connect=lambda: sqlite3.connect(self.database))
        self.storage = EngineeringStore(runtime)
        self.actor = 7
        self.files = {'a.py': 'def private_function(): return 1\n', 'b.py': 'from a import private_function\ndef run(): return private_function()\n'}
        self.head = 'a' * 40
        self.denied = False
        self.blob_reads = 0
        self.backend = SimpleNamespace(api=self.api)
        self.service = GraphService(self.storage, self.backend)
        self.request = {'action': 'index', 'repository': 'owner/repo', 'branch': 'feature', 'commit_sha': self.head}

    def tearDown(self):
        self.temp.cleanup()

    def api(self, path):
        if path == '/user':
            return {'id': self.actor}
        if path == '/repos/owner/repo':
            if self.denied:
                raise PermissionError('Access revoked')
            return {'full_name': 'owner/repo', 'permissions': {'pull': True}}
        if '/commits/' in path:
            return {'sha': path.rsplit('/', 1)[-1]}
        if '/git/ref/' in path:
            return {'object': {'sha': self.head}}
        if '/git/trees/' in path:
            return {'truncated': False, 'tree': [{'path': p, 'sha': blob_sha(t), 'size': len(t.encode()), 'mode': '100644', 'type': 'blob'} for p, t in self.files.items()]}
        if '/git/blobs/' in path:
            self.blob_reads += 1
            text = next(t for t in self.files.values() if blob_sha(t) == path.rsplit('/', 1)[-1])
            return {'encoding': 'base64', 'size': len(text.encode()), 'content': base64.b64encode(text.encode()).decode()}
        raise AssertionError(path)

    def call(self, action, **extra):
        return self.service.execute('principal', dict(self.request, action=action, **extra))

    def test_persistence_encryption_and_account_branch_principal_isolation(self):
        self.call('index')
        self.assertNotIn(b'private_function', self.database.read_bytes())
        restored = GraphService(self.storage, self.backend)
        self.assertEqual('FOUND', restored.execute('principal', dict(self.request, action='query', query='private_function'))['status'])
        self.assertEqual('NOT_INDEXED', restored.execute('another', dict(self.request, action='query'))['status'])
        self.actor = 8
        self.assertEqual('NOT_INDEXED', self.call('status')['status'])
        self.actor = 7
        self.assertEqual('NOT_INDEXED', self.call('status', branch='other')['status'])

    def test_permission_revocation_blocks_cached_queries(self):
        self.call('index')
        self.denied = True
        with self.assertRaises(PermissionError):
            self.call('query', query='private_function')

    def test_key_loss_does_not_regenerate_key_for_graph_records(self):
        self.call('index')
        key = self.database.with_suffix('.engineering.key')
        key.unlink()
        with self.assertRaises(ValueError):
            self.call('status')
        self.assertFalse(key.exists())

    def test_incremental_update_reuses_blobs_and_removes_deleted_files(self):
        self.call('index')
        self.assertEqual(2, self.blob_reads)
        self.head = 'b' * 40
        self.request['commit_sha'] = self.head
        self.files.pop('b.py')
        result = self.call('index')
        self.assertEqual(2, self.blob_reads)
        self.assertEqual(1, result['graph']['index_metrics']['cache_hits'])
        self.assertEqual('NOT_FOUND_IN_INDEX', self.call('query', query='run')['status'])

    def test_resumable_batches_and_wrong_cursor_rejection(self):
        self.files = {f'f{i}.py': f'def f{i}(): return {i}\n' for i in range(30)}
        first = self.call('index')
        self.assertTrue(first['has_more'])
        with self.assertRaises(ValueError):
            self.call('index', index_cursor='wrong')
        second = self.call('index', index_cursor=first['index_cursor'])
        self.assertFalse(second['has_more'])
        self.assertEqual(30, self.blob_reads)

    def test_old_snapshot_is_marked_stale_and_metrics_are_measured(self):
        self.call('index')
        self.head = 'b' * 40
        self.assertTrue(self.call('query', query='private')['stale'])
        metrics = self.call('metrics')
        self.assertEqual('Not measured', metrics['token_savings'])
        self.assertGreater(metrics['measurements'][0]['requests'], 0)

    def test_notes_supersession_requirement_links_and_changed_source_review(self):
        self.call('index')
        first = self.call('note', kind='decision', content='Keep this decision private', paths=['a.py'])
        self.call('note', kind='decision', content='Use the newer design', paths=['a.py'], supersedes=first['note_id'])
        self.call('note', kind='requirement', content='Preserve private_function behavior', paths=['a.py'])
        self.assertEqual('UNTESTED', self.call('requirements')['requirements'][0]['validation']['status'])
        self.assertTrue(any(n['superseded'] for n in self.call('notes')['notes']))
        self.head = 'b' * 40
        self.request['commit_sha'] = self.head
        self.files['a.py'] = 'def private_function(): return 2\n'
        self.call('index')
        self.assertTrue(all(n['review_required'] for n in self.call('notes')['notes']))

    def test_no_successful_api_read_is_misreported_as_a_test(self):
        note = {'receipt': {'payload': {'head_sha': self.head, 'success': True, 'source': 'github_api'}}}
        self.assertEqual('RECEIPT_LINKED_REVIEW_REQUIRED', self.service.requirement_evidence(note, {'sha': self.head})['status'])

    def test_workflow_receipt_requires_exact_repository_and_executed_head(self):
        note = {'receipt': {'payload': {'source': 'github_api', 'method': 'GET', 'trusted': True, 'success': True,
                'path': '/repos/owner/repo/actions/runs/42', 'head_sha': self.head,
                'result': {'head_sha': self.head, 'status': 'completed', 'conclusion': 'success'}}}}
        data = {'sha': self.head, 'repository': 'owner/repo'}
        self.assertEqual('LINKED_WORKFLOW_PASSED', self.service.requirement_evidence(note, data)['status'])
        note['receipt']['payload']['result']['head_sha'] = 'b' * 40
        self.assertEqual('RECEIPT_LINKED_REVIEW_REQUIRED', self.service.requirement_evidence(note, data)['status'])

    def test_federation_rechecks_every_repository(self):
        self.call('index')
        request = dict(self.request)
        request.pop('action')
        result = self.call('federated', query='private', targets=[request])
        self.assertEqual('owner/repo', result['repositories'][0]['repository'])
        with self.assertRaises((PermissionError, AssertionError)):
            self.call('federated', targets=[dict(request, repository='other/private')])

    def test_input_bounds_and_graphify_is_explicitly_configured(self):
        self.call('index')
        for extra in [{'depth': -1}, {'budget_bytes': 0}, {'commit_sha': 'main'}, {'paths': ['a'] * 101}]:
            with self.assertRaises(ValueError):
                self.call('query', **extra)
        with patch.dict('os.environ', {}, clear=True), self.assertRaises(ValueError):
            self.call('provider', query='private')

    def test_captured_read_can_resume_indexing_and_excludes_symlinks(self):
        tree = {p: {'sha': blob_sha(t), 'mode': '100644'} for p, t in self.files.items()}
        tree['link.py'] = {'sha': 'f' * 40, 'mode': '120000'}
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        self.service.capture(job, 'READ', {'tree': tree, 'files': {'a.py': {'content': self.files['a.py']}}})
        result = self.call('index')
        self.assertFalse(result['has_more'])
        self.assertEqual(1, self.blob_reads)
        self.assertEqual(1, result['graph']['index_metrics']['cache_hits'])

    def test_private_graph_recall_requires_local_model_and_persistent_opt_in(self):
        self.call('index')
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        self.storage.runtime.LLAMA_BASE = 'https://remote.example'
        self.assertEqual('DISABLED', self.service.recall(job, 'private_function')['status'])
        self.storage.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        self.assertEqual('FOUND', self.service.recall(job, 'private_function')['status'])
        job['request']['memory_mode'] = 'SESSION_ONLY'
        self.assertEqual('DISABLED', self.service.recall(job, 'private_function')['status'])


if __name__ == '__main__':
    unittest.main()
