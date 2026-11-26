"""Regression contracts for source attribution, catalog projection and matched retrieval QA."""
import json
import sqlite3
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from benchmarks.github_intelligence import public_failure_matches
from benchmarks.source_qa import ASSETS, evaluate
from v14 import job_catalog
from v14.code_graph.engine import Graph, index_cursor, snapshot_metadata
from v14.code_graph.extract import extract
from v14.code_graph.service import GraphService
from v14.copilot_tasks import CopilotTaskError, validate_request
from v14.engineering_backend import blob_sha
from v14.task_context import _features, admitted_receipt, build_bundle, project_bundle


class EvidenceTests(unittest.TestCase):
    sha = 'a' * 40

    def test_six_anchors_survive_packet_and_exact_response_citations(self):
        for language in ('py', 'kt', 'cs', 'ts', 'cpp', 'java'):
            with self.subTest(language=language):
                owner = 'engine/source.' + language
                files = {owner: '\n'.join('value_' + str(i) for i in range(800))}
                files.update({'request/context_%d.py' % n: 'code request context\n' * 150 for n in range(22)})
                tree = {p: dict(sha=blob_sha(s), mode='100644') for p, s in files.items()}
                bundle = build_bundle(repository='o/r', source_sha=self.sha, tree=tree,
                        load_source=lambda p: dict(content=files[p], sha=tree[p]['sha']), query='request context',
                        graph={'nodes': [dict(path=owner, blob_sha=tree[owner]['sha'], name='owner', line=632, end_line=710)]})
                self.assertLessEqual(bundle['bytes'], 12000)
                self.assertIn(owner, bundle['candidate_paths'])
                self.assertTrue(any(r['path'] == owner and r['start_line'] <= 632 <= r['end_line'] for r in bundle['selected_ranges']))
                view = project_bundle(bundle, 2400)
                request = {'messages': [{'content': json.dumps({'task_context': view})}]}
                answer = 'https://github.com/o/r/blob/' + self.sha + '/' + owner + '#L632'
                result = admitted_receipt(bundle, request, state='VERIFIED_RESPONSE',
                                          response={'choices': [{'message': {'content': answer}}]})['admission']
                self.assertEqual(632, result['cited_ranges'][0]['start_line'])
                self.assertEqual('UNMEASURED', admitted_receipt(bundle, request)['admission']['citation_status'])

    def test_manifest_bindings_only_grant_verified_guidance(self):
        path = 'core.py'
        tree = {path: {'mode': '100644', 'sha': self.sha}}
        feature = {'id': 'core', 'title': 'Core', 'paths': [path], 'invariants': ['bounded']}
        for binding, expected in ((None, []), ({}, []), ({path: 9}, []), ({path: 'b' * 40}, []),
                                  ({path: self.sha}, ['bounded']), ({path: {'blob_sha': self.sha}}, ['bounded'])):
            row = _features(dict(schema_version=1, features=[feature], file_blobs=binding), tree)[0]
            self.assertEqual(expected, row['invariants'])

    def test_copilot_unicode_and_branch_contract(self):
        request = dict(request_id='original', repository='owner/repo', base_ref='main', expected_base_sha=self.sha,
                       expected_actor_id=9, execution_owner='GATEWAY', copilot_enabled=True, conversation_copilot_enabled=True)
        shared = json.loads((Path(__file__).resolve().parents[1] / 'contracts' / 'github-intelligence-client-cases-v1.json').read_text(encoding='utf-8'))
        for case in shared['promptCases']:
            value = case['character'] * case['count']
            if case['accepted']:
                self.assertEqual(value, validate_request(dict(request, prompt=value))['prompt'])
            else:
                with self.assertRaises(CopilotTaskError):
                    validate_request(dict(request, prompt=value))
        with self.assertRaises(CopilotTaskError):
            validate_request(dict(request, prompt='\ud800'))
        with self.assertRaises(CopilotTaskError):
            validate_request(dict(request, prompt='review', head_ref='feature', expected_head_sha=self.sha, create_pull_request=True))

    def test_factual_equivalence_and_contradictions(self):
        facts = {'pr': 7, 'head_sha': self.sha, 'ci': {'pr': 7, 'head_sha': self.sha, 'checks_failed': True, 'ci_verdict': 'FAILED', 'checks': ['host']}}
        def grade(text):
            return public_failure_matches('terminal_ci_blocker', facts, dict(benchmark_status='NOT_MEASURED', device_performance='NOT_MEASURED', summary=text))
        valid = 'PR #7 failed CI at aaaaaaaaaaaa. Review the checks for this commit.'
        self.assertTrue(grade(valid))
        for text in (valid.replace('#7', '#8'), valid.replace('aaaa', 'bbbb'), valid.replace('failed', 'never failed'),
                     valid.replace('failed', 'blocked'), valid + ' The release is published.', valid.replace('failed', 'passed')):
            self.assertFalse(grade(text))

    def test_catalog_materializes_only_visible_page_and_respects_new_ownership(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.row_factory = sqlite3.Row
        db.executescript("""
            CREATE TABLE gateway_catalog_keys(sequence INTEGER PRIMARY KEY,job_kind TEXT,job_id TEXT);
            CREATE TABLE gateway_jobs(job_id TEXT PRIMARY KEY,public_json TEXT);
            CREATE TABLE gateway_conversations(id TEXT,principal TEXT);
            CREATE UNIQUE INDEX catalog_idx ON gateway_catalog_keys(job_kind,job_id);
        """)
        def add(i):
            jid = 'j' + str(i)
            db.execute("INSERT INTO gateway_catalog_keys VALUES(?,'generation',?)", (i, jid))
            db.execute("INSERT INTO gateway_jobs VALUES(?,?)", (jid, json.dumps(dict(job_id=jid, status='completed'))))
        for i in range(1, 10001):
            add(i)
        denied = {'j10000'}
        api = SimpleNamespace(now=lambda: 1, identity=lambda _: 'host', read=lambda f: f(db),
                              encode_cursor=lambda _, v: json.dumps(v), decode_cursor=lambda _, v: json.loads(v))
        owner = SimpleNamespace(owned_job_ids=lambda ids, principal: set(ids) - denied)
        principal = SimpleNamespace(id='owner', scopes=['jobs'], local_admin=False)
        with patch.object(job_catalog, 'generation_summary', wraps=job_catalog.generation_summary) as project:
            page = job_catalog.catalog(api, None, principal, owner, limit=30)
        self.assertEqual(30, project.call_count)
        self.assertEqual(9999, page['state_counts']['completed'])
        add(10001)
        denied.add('j9969')
        second = job_catalog.catalog(api, None, principal, owner, cursor=page['next_cursor'], limit=30)
        self.assertEqual(9998, second['state_counts']['completed'])
        self.assertEqual('j9968', second['items'][0]['job_id'])

    def test_session_graph_uses_only_observed_blobs_and_no_store(self):
        service = object.__new__(GraphService)
        service.cancelled = lambda job: False
        service.authorized = lambda principal: True
        service.authorize = lambda *args: ('session', '/repos/o/r', self.sha, 8)
        service.backend = SimpleNamespace(api=lambda p: {'id': 8})
        # There is no storage or embedding API on this test object.
        service.store = SimpleNamespace(store=SimpleNamespace(runtime=SimpleNamespace(LLAMA_BASE='http://127.0.0.1:8080')))
        src = 'def retained_symbol():\n    return 42\n'
        job = {'principal': 'device', 'request': {'repository': 'o/r', 'branch': 'main', 'memory_mode': 'SESSION_ONLY'},
               'data': {'head_sha': self.sha, 'actor_id': 8, 'tree': {'src.py': dict(mode='100644', sha=blob_sha(src))},
                        'files': {'src.py': dict(sha=blob_sha(src), content=src)}}}
        got = service.recall(job, 'retained_symbol', paths=['src.py'])
        self.assertTrue(any(row['name'] == 'retained_symbol' for row in got['nodes']))
        self.assertFalse(got['graph']['coverage_complete'])
        job['data']['files'].clear()
        self.assertFalse(service.recall(job, 'retained_symbol').get('nodes'))

    def test_window_command_and_xaml_handler_are_revision_bound(self):
        texts = {'client/Jobs.cs': 'namespace Lab;\npartial class Jobs {\n public void Cancel() { Call("job-cancel"); }\n}',
                 'client/Jobs.xaml': '<Window xmlns:x="urn:x" x:Class="Lab.Jobs"><Button Click="Cancel"/></Window>',
                 'host/native_bridge.py': 'def dispatch(action):\n    if action == "job-cancel":\n        return 1'}
        fragments = {p: extract(p, s) for p, s in texts.items()}
        graph = Graph(dict(repository='o/r', branch='main', sha=self.sha, fragments=fragments,
                           manifest={p: {'sha': blob_sha(s)} for p, s in texts.items()}))
        edges = {e['relation'] for e in graph.edges if e['confidence'] == 'INFERRED'}
        self.assertTrue({'invokes_bridge', 'binds_handler'} <= edges)

    def test_large_tree_and_pinned_directory_continuation(self):
        service = object.__new__(GraphService)
        service.store = Mock()
        service.store.load.return_value = None
        service.reuse_fragments = lambda _, snap: snap
        req = dict(repository='o/r', branch='main', commit_sha=self.sha)
        for n in (7999, 8000, 8001):
            entries = [dict(path=f'src{i}.py', type='blob', mode='100644', sha=self.sha) for i in range(n)]
            service.backend = SimpleNamespace(api=lambda _, e=entries: {'tree': e, 'truncated': False, 'sha': self.sha})
            self.assertEqual(n, len(service.snapshot('owner', req, '/repo')['manifest']))
        sub = 'b' * 40
        def api(url):
            if '?recursive' in url:
                return dict(truncated=True, sha=self.sha)
            if url.endswith('/' + self.sha):
                return dict(truncated=False, sha=self.sha, tree=[dict(path='late', type='tree', mode='040000', sha=sub)])
            return dict(truncated=False, sha=sub, tree=entries)
        service.backend.api = api
        snap = service.snapshot('owner', req, '/repo')
        self.assertNotEqual(index_cursor('other', snap), index_cursor('owner', snap))
        while snap['tree_frontier']:
            service.discover(snap, req, '/repo')
        self.assertIn('late/src8000.py', snap['manifest'])
        self.assertTrue(snapshot_metadata(snap)['tree_complete'])

    def test_holdout_labels_are_separate_and_unknown_stages_survive(self):
        public, gold = [json.loads((ASSETS / group / 'source-qa.json').read_text()) for group in ('public', 'controller')]
        self.assertEqual(30, len(gold['cases']))
        self.assertTrue(all('path' not in case for case in public['cases']))
        label = gold['cases'][0]
        trace = dict(schema='github.source-qa-capture.v1', repository=gold['repository'], source_sha=gold['source_sha'],
                     controls=dict(product='fixture', build='fixture', model='fixture', capabilities_digest='a'*64,
                                   memory_mode='SESSION_ONLY', index_state='warm', budget_bytes=12000),
                     trials=[dict(id=label['id'], arm='candidate', attempt=1, candidate=[label])])
        metrics = evaluate(trace)['metrics'][0]['metrics']
        self.assertEqual(1, metrics['candidate']['mean'])
        self.assertIsNone(metrics['cited']['mean'])
