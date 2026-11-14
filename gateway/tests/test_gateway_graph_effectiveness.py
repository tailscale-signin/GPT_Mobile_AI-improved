"""Behavior regressions for complete, bounded and actually consumed graph context."""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_gateway_v14_code_graph as fixtures
from v14.code_graph.engine import Graph, bounded, encoded, scope
from v14.code_graph.extract import extract, permitted, compact_fragment, MAX_FILE_BYTES, MAX_SOURCE_BYTES
from v14.code_graph.paging import graph_page
from v14.code_graph.service import GraphService
from v14.engineering_backend import blob_sha

snapshot = fixtures.snapshot


class RelationshipCoverageTests(unittest.TestCase):
    def test_kotlin_package_top_level_import_alias_and_same_package(self):
        for declaration in ('', 'import pkg.helper\n', 'import pkg.helper as assist\n'):
            called = 'assist' if ' as ' in declaration else 'helper'
            graph = Graph(snapshot({'src/pkg/Utils.kt': 'package pkg\nfun helper(): Int = 1\n',
                                    'src/pkg/Client.kt': 'package pkg\n' + declaration + f'fun run(): Int = {called}()\n'}))
            self.assertEqual('FOUND', graph.path('run', 'helper')['status'])

    def test_python_alias_and_module_namespace_calls(self):
        for imported, called in [('from utils import helper as assist', 'assist'), ('import utils', 'utils.helper'),
                                 ('import utils as u', 'u.helper')]:
            graph = Graph(snapshot({'utils.py': 'def helper(): return 1\n',
                                    'main.py': imported + '\ndef run(): return ' + called + '()\n'}))
            self.assertEqual('FOUND', graph.path('run', 'helper')['status'])

    def test_typescript_arrow_function_and_import_bindings(self):
        for imported, called in [('import {helper as assist} from "./utils";', 'assist'),
                                 ('import * as utils from "./utils";', 'utils.helper')]:
            graph = Graph(snapshot({'utils.ts': 'export const helper = () => 1;',
                                    'main.ts': imported + ' export const run = () => ' + called + '();'}))
            self.assertEqual('FOUND', graph.path('run', 'helper')['status'])

    def test_large_kotlin_source_keeps_late_declarations_and_bounded_excerpts(self):
        source = '// retained source line\n' * 15000 + 'fun helper(): Int = 1\nfun run(): Int = helper()\n'
        self.assertGreater(len(source.encode()), MAX_FILE_BYTES)
        self.assertLess(len(source.encode()), MAX_SOURCE_BYTES)
        fragment = extract('Large.kt', source)
        self.assertIn('helper', {n['name'] for n in fragment['nodes']})
        self.assertTrue(all(len(n['excerpt']) <= 180 for n in fragment['nodes']))
        self.assertEqual('FOUND', Graph(snapshot({'Large.kt': source})).path('run', 'helper')['status'])

    def test_android_and_build_metadata_is_visible_without_execution_claim(self):
        cases = {'AndroidManifest.xml': '<manifest><application><activity android:name="MainActivity"/></application></manifest>',
                 '.github/workflows/android.yml': 'jobs:\n  build:\n    runs-on: ubuntu-latest\n',
                 'gradle/libs.versions.toml': '[versions]\nkotlin = "2.2"\n',
                 'native/voice.cpp': '#include "voice.h"\nint speak(int count) { return count; }\n'}
        for path, text in cases.items():
            self.assertTrue(permitted(path), path)
            fragment = extract(path, text)
            self.assertGreater(len(fragment['nodes']), 1, path)
        rejected = extract('bad.xml', '<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///unavailable">]><x>&secret;</x>')
        self.assertIn(rejected['status'], {'partial', 'failed'})

    def test_new_config_formats_preserve_sensitive_basename_exclusions(self):
        for path in ['secrets.yaml', 'config/credentials.toml', 'host/mcp_config.yml', 'Secrets.XML', '.ENV.yml']:
            self.assertFalse(permitted(path), path)
        for path in ['SecretStore.kt', 'read_secrets.py', 'config/secrets_helper.yaml']:
            self.assertTrue(permitted(path), path)

    def test_actual_map_screen_call_resolves_across_its_source_files(self):
        repo = Path(__file__).resolve().parents[2]
        prefix = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/'
        files = {prefix + name: (repo / prefix / name).read_text() for name in ['RepositoryGraphPanel.kt', 'RepositoryGraphOverview.kt']}
        graph = Graph(snapshot(files))
        self.assertEqual('FOUND', graph.path('RepositoryGraphStatusMap', 'RepositoryGraphStatusContent')['status'])

    def test_code_queries_prioritize_production_source_over_generic_repair_documents(self):
        files = {'voice/speech.py': 'def normalize_pronunciation(text):\n    return text\n',
                 'docs/repair.md': '# Fix voice chat response pronunciation\n## Fix all issues and problems\n',
                 'tests/test_location.py': 'def test_all_fixes(): pass\n'}
        graph = Graph(snapshot(files))
        result = graph.query('Fix voice chat response pronunciation')
        self.assertEqual('voice/speech.py', result['nodes'][0]['path'])

    def test_pronunciation_search_can_find_speech_code_without_literal_task_title(self):
        files = {'voice/speech.py': 'def speech_text(text): return text\n',
                 'chat/response.py': 'def chat_response_chunk(): return 1\n',
                 'docs/repair.md': '# Fix voice chat response pronunciation\n'}
        graph = Graph(snapshot(files))
        self.assertIn('voice/speech.py', [n['path'] for n in graph.query('Fix voice chat response pronunciation')['nodes'][:3]])

    def test_compaction_preserves_provenance_and_does_not_invent_dynamic_receivers(self):
        data = snapshot({'deep/nested/source.py': 'def helper(): return 1\ndef run(): return factory().helper()\n'})
        before = len(encoded(data))
        for fragment in data['fragments'].values():
            compact_fragment(fragment)
        self.assertLess(len(encoded(data)), before)
        graph = Graph(data)
        self.assertEqual('NO_PATH_IN_INDEX', graph.path('run', 'helper')['status'])
        self.assertTrue(all(ref['path'] == 'deep/nested/source.py' for ref in graph.unresolved))
        self.assertTrue(all(node['blob_sha'] for node in graph.nodes.values()))


class ResultPagingTests(unittest.TestCase):
    def test_large_architecture_pages_keep_endpoints_and_cover_every_file_and_link(self):
        files = {f'long/source/package/module_{i:04d}.py':
                 (f'from .module_{i+1:04d} import run\n' if i < 3199 else '') + f'def value_{i}(): return {i}\n'
                 for i in range(3200)}
        graph = Graph(snapshot(files))
        envelope = {'graph': graph.metadata(), 'stale': False, 'observed_branch_head': 'a' * 40}
        request = {'action': 'architecture', 'budget_bytes': 32000, 'limit': 100}
        nodes, edges, cursors = set(), set(), set()
        for _ in range(400):
            result = graph_page(graph, 'scope', request, envelope)
            self.assertEqual('FOUND', result['status'])
            self.assertLessEqual(len(encoded(result).encode()), 32000)
            self.assertFalse(result['truncated'])
            page_nodes = {node['id'] for node in result['nodes']}
            self.assertTrue(all(e['source'] in page_nodes and e['target'] in page_nodes for e in result['edges']))
            nodes.update(page_nodes)
            edges.update((e['source'], e['target']) for e in result['edges'])
            if not result['has_more']:
                break
            self.assertNotIn(result['next_cursor'], cursors)
            cursors.add(result['next_cursor'])
            request['result_cursor'] = result['next_cursor']
        else:
            self.fail('Architecture did not reach its final page')
        self.assertEqual(3200, len(nodes))
        self.assertEqual(3199, len(edges))

    def test_result_cursor_is_not_reusable_for_a_new_revision_scope_or_query(self):
        graph = Graph(snapshot({'one.py': 'def one(): pass\n', 'two.py': 'def two(): pass\n'}))
        request = {'action': 'architecture', 'limit': 1}
        first = graph_page(graph, 'scope', request, {'graph': graph.metadata()})
        request['result_cursor'] = first['next_cursor']
        for namespace, changed in [('different', request), ('scope', dict(request, query='one'))]:
            with self.assertRaises(ValueError):
                graph_page(graph, namespace, changed, {'graph': graph.metadata()})

    def test_dictionary_bounds_keep_revision_metadata(self):
        result = bounded({'dependencies': {'long/path/' + str(i): [str(i)] for i in range(5000)},
                          'graph': {'repository': 'owner/repo', 'branch': 'feature', 'commit_sha': 'a' * 40}}, 2048)
        self.assertLessEqual(len(encoded(result).encode()), 2048)
        self.assertEqual('a' * 40, result['graph']['commit_sha'])
        self.assertTrue(result['truncated'])


class GraphConsumptionTests(unittest.TestCase):
    setUp = fixtures.GraphServiceTests.setUp
    tearDown = fixtures.GraphServiceTests.tearDown
    api = fixtures.GraphServiceTests.api
    call = fixtures.GraphServiceTests.call
    def test_status_and_metrics_do_not_rebuild_resolved_graph(self):
        self.call('index')
        with patch('v14.code_graph.service.Graph', side_effect=AssertionError('Status must remain cheap')), \
                patch.object(self.service.store, 'load', side_effect=AssertionError('Status must not decode the full snapshot')):
            self.assertTrue(self.call('status')['graph']['processing_complete'])
            self.call('metrics')

    def test_resolved_graph_is_reused_until_fragments_or_scope_change(self):
        self.call('index')
        with patch('v14.code_graph.service.Graph', wraps=Graph) as constructor:
            self.call('query', query='private_function')
            self.call('query', query='private_function')
            self.assertEqual(1, constructor.call_count)

    def test_partial_read_capture_reuses_unchanged_previous_revision(self):
        self.call('index')
        self.head = 'b' * 40
        self.request['commit_sha'] = self.head
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        tree = {p: {'sha': blob_sha(t), 'mode': '100644'} for p, t in self.files.items()}
        self.service.capture(job, 'READ', {'tree': tree, 'files': {'a.py': {'content': self.files['a.py']}}})
        reads = self.blob_reads
        self.call('index')
        self.assertEqual(reads, self.blob_reads)

    def test_recall_prepares_missing_revision_and_reports_actual_consumption(self):
        self.storage.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        result = self.service.recall(job, 'private_function', role='planner', paths=['a.py'])
        self.assertEqual('FOUND', result['status'])
        self.assertEqual(self.head, result['retrieval']['indexed_sha'])
        self.assertEqual(len(result['nodes']), result['retrieval']['selected_nodes'])
        self.assertLessEqual(len(encoded(result).encode()), 10000)
        self.assertTrue(self.call('status')['graph']['processing_complete'])

    def test_warm_recall_revalidates_access_without_loading_or_rewriting_snapshot(self):
        self.storage.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        self.service.recall(job, 'private_function', paths=['a.py'])
        with patch.object(self.service.store, 'load', side_effect=AssertionError('Warm recall must use the versioned cache')), \
                patch.object(self.service.store, 'save', side_effect=AssertionError('Unchanged source must not be rewritten')):
            self.assertTrue(self.service.recall(job, 'private_function', paths=['a.py'])['retrieval']['cache_hit'])
            self.denied = True
            with self.assertRaises(PermissionError):
                self.service.recall(job, 'private_function', paths=['a.py'])

    def test_public_exact_revision_fragments_reused_only_for_verified_public_repository(self):
        self.storage.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        self.service.public_service = GraphService(self.storage, self.backend, public=True)
        public_namespace = 'public:' + scope('principal', 'anonymous-public', 'owner/repo', 'feature')
        self.service.public_service.store.save(public_namespace, snapshot(self.files))
        original = self.backend.api
        self.backend.api = lambda path: dict(original(path), private=False, visibility='public') if path == '/repos/owner/repo' else original(path)
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        result = self.service.recall(job, 'private_function')
        self.assertEqual('public_cache', result['retrieval']['provider'])
        self.assertEqual(0, self.blob_reads)

    def test_recall_does_not_prepare_source_after_cancellation_or_scope_change(self):
        self.storage.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        job = {'principal': 'principal', 'request': dict(self.request, memory_mode='HYBRID'),
               'data': {'actor_id': self.actor, 'head_sha': self.head}}
        self.service.cancelled = lambda job: True
        with self.assertRaises(InterruptedError):
            self.service.recall(job, 'private_function')
        self.assertEqual(0, self.blob_reads)
        with self.assertRaises(PermissionError):
            self.service.execute('principal', self.request, expected_scope='another-account')
        self.assertEqual(0, self.blob_reads)


if __name__ == '__main__':
    unittest.main()
