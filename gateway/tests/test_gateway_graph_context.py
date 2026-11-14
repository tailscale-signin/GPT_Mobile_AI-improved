import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.graph_context import admitted_graph_receipt, graph_receipt, project_graph, public_graph_receipt, stage_selection


class GraphContextTests(unittest.TestCase):
    def test_review_focus_prioritizes_changes_and_repair_uses_bounded_ci_context(self):
        job = {'request': {'objective': 'Repair voice output'}, 'data': {'changes': [{'path': 'voice.py'}],
            'plan': {'paths': ['unrelated.py', 'voice.py']}}}
        query, paths = stage_selection(job, 'selected-model reviewer', {'ci': {'failure': 'decoder error' * 1000}})
        self.assertEqual('voice.py', paths[0])
        self.assertEqual(1, paths.count('voice.py'))
        self.assertIn('decoder error', query)
        self.assertLessEqual(len(query), 2000)
        _, initial = stage_selection({'request': {'objective': 'Inspect'}, 'data': {}}, 'planner',
                                      {'paths': [f'file-{i}.py' for i in range(10000)]})
        self.assertEqual(12, len(initial))

    def test_partial_prompt_receipt_counts_visible_nodes_without_source_names(self):
        graph = {'status': 'FOUND', 'graph': {'commit_sha': 'a' * 40, 'complete': False},
            'nodes': [{'id': str(i), 'path': f'private-{i}.py', 'name': 'privateFunction', 'line': 1,
                       'excerpt': 'private source ' * 100} for i in range(30)], 'edges': []}
        job = {'state': 'IMPLEMENT', 'data': {'head_sha': 'a' * 40}}
        receipt = graph_receipt(graph, job, 'coding agent')
        view = project_graph(graph, 1600)
        payload = {'messages': [{'content': json.dumps({'code_graph': view})}]}
        used = admitted_graph_receipt(receipt, payload, dispatched=True)
        self.assertEqual(len(view['nodes']), used['included_nodes'])
        self.assertEqual(30 - len(view['nodes']), used['omitted_nodes'])
        self.assertEqual('PARTIAL', used['view_status'])
        self.assertTrue(used['revision_match'])
        self.assertFalse(used['coverage_complete'])
        self.assertNotIn('private', json.dumps(used))
        self.assertLessEqual(len(json.dumps(view, separators=(',', ':'), sort_keys=True).encode()), 1600)

    def test_restored_receipt_cannot_publish_source_fields_or_arbitrary_enum_values(self):
        source = {'status': 'FOUND', 'stage': 'PLAN', 'role': 'planner', 'provider': 'private URL with token',
            'reason': {'private_path': 'secret.py'}, 'requested_nodes': 1, 'included_nodes': True,
            'source_digest': 'private source', 'selected_paths': ['secret.py'], 'source': 'secret code'}
        result = public_graph_receipt(source)
        self.assertIsNone(result['provider'])
        self.assertIsNone(result['reason'])
        self.assertIsNone(result['included_nodes'])
        self.assertEqual(1, result['requested_nodes'])
        self.assertNotIn('secret', json.dumps(result))
        self.assertNotIn('private', json.dumps(result))

    def test_retrieval_receipt_keeps_fixed_provider_reason_and_cache_budget_evidence(self):
        graph = {'status': 'FOUND', 'nodes': [], 'graph': {'commit_sha': 'a' * 40, 'coverage_complete': False},
                 'retrieval': {'reason': 'MATCHES_FOUND', 'provider': 'public_cache', 'cache_hit': True,
                               'prepared_files': 24, 'byte_budget': 10000, 'returned_bytes': 9000}}
        receipt = graph_receipt(graph, {'state': 'PLAN', 'data': {'head_sha': 'a' * 40}}, 'planner')
        self.assertEqual('MATCHES_FOUND', receipt['reason'])
        self.assertEqual('public_cache', receipt['provider'])
        self.assertTrue(receipt['cache_hit'])
        self.assertEqual(24, receipt['prepared_files'])
        self.assertEqual(10000, receipt['byte_budget'])
        self.assertEqual(9000, receipt['returned_bytes'])
        self.assertFalse(receipt['coverage_complete'])

    def test_graph_projection_preserves_first_ranked_anchor_and_never_dangling_edges(self):
        graph = {'status': 'FOUND', 'graph': {'repository': 'owner/repo', 'branch': 'main',
            'commit_sha': 'a' * 40, 'complete': True, 'source_level_only': True, 'runtime_verified': False},
            'nodes': [{'id': f'n{i}', 'path': f'p{i}.py', 'line': 1, 'name': 'relevant', 'excerpt': 'é' * 3000}
                      for i in range(20)],
            'edges': [{'source': 'n0', 'target': 'n19', 'relation': 'calls', 'confidence': 'INFERRED'}]}
        view = project_graph(graph, 1024)
        self.assertEqual('n0', view['nodes'][0]['id'])
        self.assertEqual('a' * 40, view['graph']['commit_sha'])
        self.assertFalse(view['graph']['runtime_verified'])
        self.assertTrue(view['graph']['complete'])
        self.assertFalse(view['view_complete'])
        self.assertLessEqual(len(json.dumps(view, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode()), 1024)
        ids = {node['id'] for node in view['nodes']}
        self.assertTrue(all(edge['source'] in ids and edge['target'] in ids for edge in view['edges']))


if __name__ == '__main__':
    unittest.main()
