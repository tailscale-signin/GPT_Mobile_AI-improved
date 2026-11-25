import hashlib
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.code_graph.jvm_impact import CompilerEvidence
from v14.code_graph.engine import bounded
from v14.code_graph.service import GraphService
from v14.code_graph.extract import extract

JAVA = 'src/main/Api.java'
KOTLIN = 'src/main/Client.kt'
TEST = 'src/test/ClientTest.kt'
KEY = 'demo/Api.send(I)V'
SECOND = 'demo/Api.send(Ljava/lang/String;)V'
CALLER = 'demo/Client.run()V'
CHECK = 'demo/ClientTest.check()V'


class Actions:
    def __init__(self):
        self.rows = {}
        self.downloads = 0

    def fixture(self, sha, run, fingerprint='b', change=None):
        data = {'schema': 1, 'commit_sha': sha, 'analyzed_sha': sha, 'tree_sha': 'e' * 40,
                'run_id': str(run), 'run_attempt': '1',
                'files': [[JAVA, sha, 15], [KOTLIN, 'c' * 40, 12], [TEST, 'd' * 40, 11]],
                'methods': [[KEY, 0, 3, fingerprint * 64], [SECOND, 0, 8, 'f' * 64],
                            [CALLER, 1, 4, '1' * 64], [CHECK, 2, 5, '2' * 64]],
                'calls': [[2, 0, 6], [3, 2, 7]]}
        if change:
            change(data)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('jvm-call-index.json', json.dumps(data))
        raw = buffer.getvalue()
        repo = {'full_name': 'demo/repo'}
        workflow = {'id': run, 'head_sha': sha, 'repository': repo, 'head_repository': repo, 'status': 'completed',
                    'conclusion': 'success', 'path': '.github/workflows/pr-validation.yml', 'run_attempt': 1}
        artifact = {'id': run + 100, 'expired': False, 'name': 'jvm-call-index-' + sha, 'workflow_run': {'id': run},
                    'size_in_bytes': len(raw), 'digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
        self.rows[sha] = (artifact, workflow, raw)
        return {'repository': 'demo/repo', 'sha': sha, 'manifest': {p: {'sha': blob} for p, blob, _ in data['files']}}

    def api(self, path):
        prefix = '/repos/demo/repo/'
        assert path.startswith(prefix)
        if '/artifacts?name=' in path:
            sha = path.split('jvm-call-index-')[1].split('&')[0]
            return {'artifacts': [self.rows[sha][0]] if sha in self.rows else []}
        if '/actions/runs/' in path:
            return next(run for _, run, _ in self.rows.values() if run['id'] == int(path.rsplit('/', 1)[1]))
        if '/git/commits/' in path:
            return {'sha': path.rsplit('/', 1)[1], 'tree': {'sha': 'e' * 40}, 'parents': []}
        raise AssertionError(path)

    def download_graph_artifact(self, repo, artifact_id, maximum):
        self.downloads += 1
        return next(raw for artifact, _, raw in self.rows.values() if artifact['id'] == artifact_id)


class CompilerEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.api = Actions()
        self.head = self.api.fixture('a' * 40, 12)
        self.service = CompilerEvidence(self.api)
        self.node = {'kind': 'method', 'name': 'send', 'path': JAVA, 'line': 2, 'end_line': 4}

    def query(self, **kwargs):
        return self.service.impact('account-one', self.head, (), self.node, 3, 10, **kwargs)

    def test_normal_gateway_impact_and_diff_entry_points(self):
        graph_service = GraphService(MagicMock(), MagicMock())
        api = self.api
        files = {JAVA: 'package demo;\nclass Api {\n int send(int n) {return n;}\n}\n',
                 KOTLIN: 'package demo\nfun relay() = 42\n', TEST: 'package demo\nfun checkRelay() = relay()\n'}
        base = api.fixture('b' * 40, 11, fingerprint='9')
        snapshots = {row['sha']: dict(row, schema_version=1, branch='feature',
                                      fragments={path: extract(path, content) for path, content in files.items()})
                     for row in [self.head, base]}
        def read(path):
            if path == '/user':
                return {'id': 10}
            if path == '/repos/demo/repo':
                return {'full_name': 'demo/repo', 'permissions': {'pull': True}}
            if '/git/ref/heads/' in path:
                return {'object': {'sha': self.head['sha']}}
            if '/commits/' in path and '/git/commits/' not in path:
                return {'sha': path.rsplit('/', 1)[1]}
            return api.api(path)
        graph_service.backend.api.side_effect = read
        graph_service.backend.download_graph_artifact.side_effect = api.download_graph_artifact
        graph_service.store.load = lambda _, sha: snapshots[sha]
        graph_service.store.measure = MagicMock()
        request = {'action': 'impact', 'repository': 'demo/repo', 'branch': 'feature', 'commit_sha': self.head['sha']}
        for operation in [dict(request, symbol=KEY), dict(request, action='diff', base_sha=base['sha'])]:
            result = graph_service.execute('principal', operation)
            self.assertEqual('COMPILER_BACKED', result['compiler_impact']['status'])
            self.assertEqual([CHECK, CALLER, KEY], result['compiler_impact']['tests'][0]['chain'])

    def test_overload_and_exact_call_chain_to_kotlin_test(self):
        result = self.query()
        self.assertEqual('COMPILER_BACKED', result['status'])
        self.assertEqual(1, result['selected_methods'])
        self.assertEqual({'path': TEST, 'blob_sha': 'd' * 40, 'line': 7, 'chain': [CHECK, CALLER, KEY]}, result['tests'][0])
        self.assertEqual('NOT_RUN', result['test_execution'])
        self.assertEqual('JVM_INVOKE_INSTRUCTION', result['confidence'])
        self.assertEqual([], self.query(descriptor=SECOND)['tests'])
        self.assertEqual(1, self.api.downloads)
        self.service.impact('account-two', self.head, (), self.node, 2, 6)
        self.assertEqual(2, self.api.downloads)
        self.node['end_line'] = 10
        self.assertEqual('METHOD_MAPPING_AMBIGUOUS', self.query()['reason'])

    def test_synthetic_merge_can_serve_a_head_only_with_an_identical_git_tree(self):
        merge_sha = '8' * 40
        self.api.fixture(self.head['sha'], 12, change=lambda data: data.update(analyzed_sha=merge_sha))
        original = self.api.api
        for expected, actual_tree in [('COMPILER_BACKED', 'e' * 40), ('LEXICAL_FALLBACK', '7' * 40)]:
            with self.subTest(expected=expected):
                def git(path):
                    if path.endswith('git/commits/' + merge_sha):
                        return {'sha': merge_sha, 'tree': {'sha': 'e' * 40}, 'parents': [{'sha': self.head['sha']}]}
                    if path.endswith('git/commits/' + self.head['sha']):
                        return {'sha': self.head['sha'], 'tree': {'sha': actual_tree}}
                    return original(path)
                self.api.api = git
                self.service = CompilerEvidence(self.api)
                self.assertEqual(expected, self.query()['status'])

    def test_diff_detects_changed_body_with_same_source_signature(self):
        base = self.api.fixture('b' * 40, 11, fingerprint='5')
        result = self.query(previous=base)
        self.assertEqual(base['sha'], result['base_sha'])
        self.assertEqual(1, result['modified_method_count'])
        self.assertEqual(KEY, result['methods'][0]['symbol'])
        self.assertEqual([CHECK, CALLER, KEY], result['tests'][0]['chain'])
        same = self.api.fixture('c' * 40, 13)
        self.assertEqual('NO_CHANGED_HEAD_METHODS', self.query(previous=same)['status'])

    def test_changed_test_still_reports_its_call_to_changed_production_code(self):
        def changed_test(data):
            data['methods'][3][3] = '3' * 64
        base = self.api.fixture('b' * 40, 11, fingerprint='5', change=changed_test)
        result = self.query(previous=base)
        self.assertEqual(2, result['modified_method_count'])
        self.assertEqual([CHECK, CALLER, KEY], result['tests'][0]['chain'])

    def test_diff_traces_removed_methods_to_separate_base_tests(self):
        def removed_method(data):
            data['methods'].append(['demo/Api.removed()V', 0, 11, '8' * 64])
            data['calls'].append([3, 4, 9])
        base = self.api.fixture('b' * 40, 11, change=removed_method)
        result = self.query(previous=base)
        self.assertEqual('NO_CHANGED_HEAD_METHODS', result['status'])
        self.assertEqual(1, result['removed_method_count'])
        self.assertEqual(base['sha'], result['base_sha'])
        self.assertEqual([CHECK, 'demo/Api.removed()V'], result['base_tests'][0]['chain'])

    def test_rejects_wrong_attempt_fork_digest_blob_tree_and_absent_artifact(self):
        for field in ('attempt', 'fork', 'digest', 'blob', 'tree', 'missing', 'foreign_run', 'bad_call'):
            with self.subTest(field=field):
                self.setUp()
                artifact, workflow, _ = self.api.rows[self.head['sha']]
                if field == 'attempt':
                    workflow['run_attempt'] = 2
                elif field == 'fork':
                    workflow['head_repository'] = {'full_name': 'other/repo'}
                elif field == 'digest':
                    artifact['digest'] = 'sha256:' + '0' * 64
                elif field == 'blob':
                    self.head['manifest'][JAVA]['sha'] = '0' * 40
                elif field == 'tree':
                    self.api.fixture(self.head['sha'], 12, change=lambda data: data.update(tree_sha='2' * 40))
                elif field == 'foreign_run':
                    workflow['head_sha'] = '9' * 40
                elif field == 'bad_call':
                    self.api.fixture(self.head['sha'], 12, change=lambda data: data['calls'].append([2, True, 0]))
                elif field == 'missing':
                    self.api.rows.clear()
                self.assertEqual('LEXICAL_FALLBACK', self.query()['status'])

    def test_bounded_result_retains_compiler_confidence(self):
        result = self.query()
        result['tests'] *= 50
        compact = bounded({'compiler_impact': result, 'paths': list(range(1000))}, 2048)
        self.assertEqual('COMPILER_BACKED', compact['compiler_impact']['status'])
        self.assertEqual(result['confidence'], compact['compiler_impact']['confidence'])
