"""Real local Git histories and executable grading for the phone-only CI contract."""
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from benchmarks import fixtures, sandbox
from benchmarks.contracts import content_digest, digest
from benchmarks.phone_ci import evaluate, verify_bundle
from benchmarks.phone_provision import (PREFIX, RESULT_SCHEMA, SUBMISSION_SCHEMA, branch_name, build_bundle,
    json_bytes, raw_digest, render_workflow, submission_path, verify_source_revision, workflow_path, write_bundle)
from benchmarks.verified_python import evaluate as interpret, UnsupportedLanguage, StepLimit
from test_gateway_benchmark_independent_grader import proposal, submission


def command(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


def configure(root):
    command(root, 'config', 'user.email', 'benchmark-fixture@example.invalid')
    command(root, 'config', 'user.name', 'Benchmark contract test')


def sandbox_observation(sources, inputs, *_args, **_kwargs):
    try:
        result = interpret(sources, inputs)
    except UnsupportedLanguage:
        return {'status': 'ERROR', 'executed': True, 'error': 'GRADER_LANGUAGE_UNSUPPORTED', 'outputs': []}
    except StepLimit:
        return {'status': 'FAILED', 'executed': True, 'error': 'CANDIDATE_STEP_LIMIT', 'outputs': []}
    except SyntaxError:
        return {'status': 'FAILED', 'executed': True, 'error': 'CANDIDATE_SYNTAX_ERROR', 'outputs': []}
    return dict(result, status='COMPLETED', executed=True, outputDigest=digest(result), durationMs=1)


class PhoneBenchmarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.base = Path(cls.directory.name) / 'trusted'
        cls.base.mkdir()
        command(cls.base, 'init')
        configure(cls.base)
        cls.files, cls.catalog = build_bundle(source_commit='f' * 40, python_image='python@sha256:' + 'a' * 64,
                                             repository_image='native@sha256:' + 'b' * 64)
        write_bundle(cls.base / '.benchmark-phone', cls.files)
        command(cls.base, 'add', '.benchmark-phone')
        command(cls.base, 'commit', '-m', 'Provision immutable test fixtures')
        cls.base_sha = command(cls.base, 'rev-parse', 'HEAD')

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.submitted = Path(self.directory.name) / 'submitted'
        subprocess.run(['git', 'clone', '--local', '--no-hardlinks', str(self.base), str(self.submitted)], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        configure(self.submitted)
        self.ident = 'phone_' + 'c' * 32
        self.branch = branch_name(self.ident, 'cohort0001')
        command(self.submitted, 'checkout', '-b', self.branch)

    def candidate(self, text=None, *, pack='python', task_id='pagination-1'):
        from benchmarks.external import select_task
        task = select_task(task_id, 17, pack)
        selected = self.catalog['packs'][pack]
        text = proposal(task) if text is None else text
        value = {'schema': SUBMISSION_SCHEMA, 'request_id': self.ident, 'task_id': task_id, 'task_pack': pack,
                 'seed': 17, 'fixture_digest': task['taskDigest'], 'grader_digest': task['graderDigest'],
                 'environment_digest': selected['environmentDigest'], 'bundle_digest': self.catalog['bundleDigest'],
                 'base_sha': self.base_sha, 'repository': 'owner/disposable-fixture', 'branch': self.branch,
                 'workflow_path': workflow_path(self.ident), 'proposal_digest': content_digest(text), 'proposal': text,
                 'generation_receipt': submission()['generation_receipt']}
        path = self.submitted / submission_path(self.ident)
        path.parent.mkdir(parents=True)
        path.write_bytes(json_bytes(value))
        template = self.files[selected['workflowTemplatePath']].decode()
        workflow = self.submitted / workflow_path(self.ident)
        workflow.parent.mkdir(parents=True)
        workflow.write_text(render_workflow(template, self.ident, self.base_sha, self.branch))
        return value

    def run_case(self, *, grader=None):
        command(self.submitted, 'add', '.')
        command(self.submitted, 'commit', '-m', 'Submit one frozen phone attempt')
        context = {'repository': 'owner/disposable-fixture', 'branch': self.branch,
                   'head_sha': command(self.submitted, 'rev-parse', 'HEAD'), 'event': 'push', 'run_id': 71, 'run_attempt': 1}
        options = {'grader': grader} if grader else {}
        with patch.object(sandbox, 'preflight', return_value='docker'), \
                patch.object(sandbox, 'execute', side_effect=sandbox_observation):
            return evaluate(self.base, self.submitted, self.ident, self.base_sha, context, **options)

    def test_catalog_raw_and_manifest_digests_cover_both_real_packs(self):
        catalog = verify_bundle(self.base)
        self.assertEqual(self.catalog, catalog)
        self.assertEqual(digest(catalog['bundleManifest']), catalog['bundleDigest'])
        for pack, sizes in [('python', [12, 36, 100]), ('repository', [4, 8, 12])]:
            selected = catalog['packs'][pack]
            self.assertEqual(sizes, [len(selected['presets'][preset]) for preset in ('quick', 'standard', 'deep')])
            for task_id, metadata in selected['tasksBySeed']['17'].items():
                path = selected['fixturePathTemplate'].replace('{seed}', '17').replace('{task_id}', task_id)
                self.assertEqual(raw_digest(self.files[path]), metadata['descriptorDigest'])
                descriptor = json.loads(self.files[path])
                self.assertEqual(selected['graderDigest'], descriptor['graderDigest'])
                self.assertEqual(selected['environmentDigest'], descriptor['environmentDigest'])
                self.assertNotIn('referenceSources', descriptor)
            self.assertEqual(raw_digest(self.files[selected['workflowTemplatePath']]), selected['workflowTemplateDigest'])

    def test_exact_two_file_git_revision_receives_actual_independent_grade(self):
        value = self.candidate()
        result = self.run_case()
        self.assertEqual(RESULT_SCHEMA, result['schema'])
        self.assertEqual('PASSED', result['status'], result)
        self.assertTrue(result['qualified'])
        self.assertTrue(result['resolved'])
        self.assertEqual(1000, result['score'])
        self.assertEqual(value['generation_receipt'], result['generationReceipt'])
        self.assertEqual(value['proposal_digest'], result['proposalDigest'])
        self.assertEqual(value['proposal_digest'], result['case']['proposalDigest'])
        self.assertEqual(command(self.submitted, 'rev-parse', 'HEAD'), result['testedRevision'])
        self.assertEqual(result['testedRevision'], result['publishedRevision'])
        self.assertFalse(result['independentProviderIdentityVerified'])
        self.assertEqual(digest({key: item for key, item in result.items() if key != 'receiptDigest'}), result['receiptDigest'])
        self.assertNotIn('proposal', result)

    def test_completed_empty_answer_is_typed_model_failure_even_without_compiler(self):
        value = self.candidate('')
        result = self.run_case()
        self.assertEqual('FAILED', result['status'])
        self.assertEqual('MODEL', result['failureDomain'])
        self.assertTrue(result['outcomeKnown'])
        self.assertFalse(result['executed'])
        self.assertEqual(value['proposal_digest'], result['proposalDigest'])

    def test_copied_runtime_cli_produces_typed_artifact_without_a_gateway_or_docker(self):
        value = self.candidate('')
        value['repository'] = 'OWNER/Disposable-Fixture'
        (self.submitted / submission_path(self.ident)).write_bytes(json_bytes(value))
        command(self.submitted, 'add', '.')
        command(self.submitted, 'commit', '-m', 'Completed malformed phone answer')
        revision = command(self.submitted, 'rev-parse', 'HEAD')
        output = Path(self.directory.name) / 'result.json'
        environment = dict(os.environ, GITHUB_REPOSITORY='owner/disposable-fixture', GITHUB_REF_NAME=self.branch,
                           GITHUB_SHA=revision, GITHUB_EVENT_NAME='push', GITHUB_RUN_ID='71', GITHUB_RUN_ATTEMPT='1')
        process = subprocess.run([sys.executable, str(self.base / PREFIX / 'runtime/benchmarks/phone_ci.py'),
            '--trusted-root', str(self.base), '--submitted-root', str(self.submitted), '--submission-id', self.ident,
            '--base-sha', self.base_sha, '--output', str(output)], check=True, env=environment,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        result = json.loads(output.read_bytes())
        self.assertEqual('FAILED', result['status'], result)
        self.assertEqual('MODEL', result['failureDomain'])
        self.assertTrue(result['outcomeKnown'])
        self.assertEqual('PATCH_JSON_INVALID', result['errorCode'])
        self.assertEqual(revision, result['testedRevision'])
        self.assertEqual(result['receiptDigest'], json.loads(process.stdout)['receiptDigest'])

    def test_correct_output_input_mutation_is_an_observed_model_failure(self):
        task = fixtures.find_task('pagination-1')
        candidate = dict(task['referenceSources'])
        candidate['service.py'] = "from core import solve\ndef handle(request):\n    if not isinstance(request, dict) or not isinstance(request.get('items'), list):\n        return {'error': 'invalid_request'}\n    answer = {'result': solve(request['items'], request.get('config', {}))}\n    request['items'].clear()\n    return answer\n"
        self.candidate(proposal(task, candidate))
        result = self.run_case()
        self.assertEqual('FAILED', result['status'])
        self.assertTrue(result['outcomeKnown'])
        self.assertTrue(result['case']['outputCorrect'])
        self.assertEqual('CANDIDATE_INPUT_MUTATION', result['errorCode'])

    def test_extra_file_and_modified_trusted_workflow_are_infrastructure_not_model_failures(self):
        self.candidate()
        (self.submitted / 'unrelated-source.py').write_text('print("not allowed")')
        result = self.run_case(grader=lambda *_: self.fail('Scope violation cannot grade'))
        self.assertEqual('PHONE_PROPOSAL_SCOPE_CHANGED', result['errorCode'])
        self.assertFalse(result['outcomeKnown'])
        self.assertEqual('HARNESS', result['failureDomain'])

    def test_workflow_bytes_are_checked_against_exact_pinned_template(self):
        self.candidate()
        path = self.submitted / workflow_path(self.ident)
        path.write_text(path.read_text().replace('contents: read', 'contents: write'))
        result = self.run_case(grader=lambda *_: self.fail('Changed workflow cannot grade'))
        self.assertEqual('PHONE_WORKFLOW_CONTENT_CHANGED', result['errorCode'])
        self.assertFalse(result['qualified'])

    def test_changed_fixture_digest_is_never_a_model_failure(self):
        value = self.candidate()
        value['fixture_digest'] = '0' * 64
        (self.submitted / submission_path(self.ident)).write_bytes(json_bytes(value))
        result = self.run_case(grader=lambda *_: self.fail('Changed fixture cannot grade'))
        self.assertEqual('PHONE_FIXTURE_IDENTITY_CHANGED', result['errorCode'])
        self.assertFalse(result['outcomeKnown'])

    def test_missing_evaluator_preserves_typed_setup_failure(self):
        self.candidate()
        def unavailable(*_args):
            raise sandbox.SandboxUnavailable('Runtime is missing', 'IMAGE_NOT_INSTALLED')
        result = self.run_case(grader=unavailable)
        self.assertEqual('BLOCKED', result['status'])
        self.assertEqual('SETUP', result['failureDomain'])
        self.assertFalse(result['outcomeKnown'])
        self.assertEqual('IMAGE_NOT_INSTALLED', result['errorCode'])

    def test_provisioning_refuses_mutable_images_or_existing_bundle(self):
        with self.assertRaises(ValueError):
            build_bundle(source_commit='a' * 40, python_image='python:latest')
        with self.assertRaises(ValueError):
            write_bundle(self.base / '.benchmark-phone', self.files)
        template = self.files[self.catalog['packs']['python']['workflowTemplatePath']].decode()
        with self.assertRaises(ValueError):
            render_workflow(template, self.ident, self.base_sha, 'main')

    def test_source_commit_verification_checks_actual_git_archive(self):
        root = Path(self.directory.name) / 'source'
        root.mkdir()
        command(root, 'init')
        configure(root)
        (root / 'gateway').mkdir()
        sources = {'example.py': b'VALUE = 1\n', 'requirements.txt': b'--require-hashes\n'}
        for name, raw in sources.items():
            (root / 'gateway' / name).write_bytes(raw)
        command(root, 'add', 'gateway')
        command(root, 'commit', '-m', 'Pinned source')
        revision = command(root, 'rev-parse', 'HEAD')
        verify_source_revision(root, revision, sources)
        with self.assertRaises(ValueError):
            verify_source_revision(root, revision, dict(sources, **{'example.py': b'VALUE = 2\n'}))


if __name__ == '__main__':
    unittest.main()
