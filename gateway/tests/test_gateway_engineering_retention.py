import base64
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_gateway_selected_execution import SelectedExecutionTests
from v14.execution_binding import ExecutionBindingError
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_report import build_report
from v14.engineering_validation import behavior_receipts, run_bounded
from v14.evidence import completion_gate


class RetentionTests(unittest.TestCase):
    setUp = SelectedExecutionTests.setUp
    tearDown = SelectedExecutionTests.tearDown
    runtime_for = SelectedExecutionTests.runtime_for
    service_for = SelectedExecutionTests.service_for

    def mutation(self):
        self.backend.model(self.job, 'planner', {})
        self.store.complete(self.job, self.job['_execution_lease'], 'COMMIT', {})
        self.job = self.store.claim(self.job['id'])[0]

    def test_large_encrypted_artifact_pages_reconstruct_digest_after_restart(self):
        original = {'file': 'private Unicode evidence π文\n' * 17000}
        reference = self.service.artifacts.put_json(self.job, original, 'test-result')
        reopened = EngineeringArtifacts(self.store)
        raw, offset = bytearray(), 0
        while True:
            page = reopened.page(self.job, reference['result_ref'], offset, 12345)
            block = base64.b64decode(page['content_base64'])
            self.assertEqual(page['page_sha256'], hashlib.sha256(block).hexdigest())
            raw.extend(block)
            offset = page['next_offset']
            if not page['has_more']:
                break
        self.assertEqual(original, json.loads(raw))
        self.assertEqual(reference['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertNotIn(b'private Unicode evidence', (Path(self.temp.name) / 'jobs.db').read_bytes())
        self.assertEqual(reference, reopened.put_json(self.job, original, 'second-pin'))
        with self.assertRaises(ExecutionBindingError):
            reopened.page(dict(self.job, principal='another-account'), reference['result_ref'])

    def test_large_checkpoint_retains_references_and_restores_only_for_stage(self):
        files = {'src/code.py': {'sha': 'a' * 40, 'content': 'large source\n' * 100000}}
        self.job['data']['files'] = files
        self.store.complete(self.job, self.job['_execution_lease'], 'IMPLEMENT', {'files': files})
        saved = self.store.get(self.job['id'])
        self.assertEqual('AVAILABLE', saved['data']['files']['availability'])
        self.assertLess(len(json.dumps(saved)), 20000)
        claimed = self.store.claim(self.job['id'])[0]
        self.service.artifacts.restore_stage(claimed)
        self.assertEqual(files, claimed['data']['files'])

    def test_unknown_mutation_survives_owner_turnover_and_is_never_replayed(self):
        self.mutation()
        operation = self.service.operations.prepare(self.job, 'POST', '/repos/owner/repo/pulls', {'title': 'change'})
        self.assertFalse(operation['dispatched'])
        self.service.operations.dispatch(self.job, operation)
        self.service.operations.failed(self.job, operation, TimeoutError('lost response'))
        self.store.recover()
        replacement = self.store.claim(self.job['id'])[0]
        self.assertNotEqual(self.job['_owner_epoch'], replacement['_owner_epoch'])
        with self.assertRaises(ExecutionBindingError) as failure:
            self.service.operations.prepare(replacement, 'POST', '/repos/owner/repo/pulls', {'title': 'change'})
        self.assertEqual('EXECUTION_OPERATION_UNKNOWN_OUTCOME', failure.exception.code)
        self.assertEqual('UNKNOWN_OUTCOME', self.service.operations.page(replacement)['operations'][0]['state'])
        with self.assertRaises(ExecutionBindingError):
            self.service.operations.dispatch(self.job, operation)

    def test_confirmed_operation_reuses_retained_result_and_precheck_is_not_dispatch(self):
        self.mutation()
        operation = self.service.operations.prepare(self.job, 'POST', '/repos/owner/repo/git/refs', {'ref': 'refs/heads/fix'})
        self.service.operations.dispatch(self.job, operation)
        self.service.operations.finish(self.job, operation, {'ref': 'refs/heads/fix', 'object': {'sha': 'a' * 40}}, 201)
        recovered = self.service.operations.prepare(self.job, 'POST', '/repos/owner/repo/git/refs', {'ref': 'refs/heads/fix'})
        self.assertEqual('COMPLETED', recovered['state'])
        self.assertEqual('a' * 40, self.service.artifacts.json_value(self.job, recovered['response_ref'])['object']['sha'])
        rejected = self.service.operations.prepare(self.job, 'POST', '/repos/owner/repo/git/refs', {'ref': 'refs/heads/other'})
        self.service.bindings.revoke('owner', self.binding['binding_id'])
        with self.assertRaises(ExecutionBindingError):
            self.service.operations.dispatch(self.job, rejected)
        self.assertEqual('PRECHECK_REJECTED', rejected['state'])
        self.assertFalse(rejected['dispatched'])

    def test_real_behavior_case_produces_trusted_requirement_evidence_and_report(self):
        # Actual subprocess executes the selected token/reserve behavior test;
        # only its exact JUnit case (not the process's generic success) can pass.
        test_name = 'test_explicit_output_and_reserves_never_silently_shrink'
        case_id = 'BudgetAndSchedulerTests.' + test_name
        directory = Path(self.temp.name) / 'validation'
        directory.mkdir()
        script = (
            "import sys,unittest,xml.etree.ElementTree as E\n"
            f"sys.path[:0]={[path for path in sys.path if 'site-packages' in path]!r}\n"
            f"sys.path.insert(0,{str(Path(__file__).resolve().parent)!r})\n"
            "from test_gateway_selected_execution import BudgetAndSchedulerTests\n"
            f"suite=unittest.TestSuite([BudgetAndSchedulerTests({test_name!r})])\n"
            "result=unittest.TextTestRunner().run(suite)\n"
            "root=E.Element('testsuite')\n"
            f"case=E.SubElement(root,'testcase',classname='BudgetAndSchedulerTests',name={test_name!r})\n"
            "if not result.wasSuccessful(): E.SubElement(case,'failure')\n"
            "E.ElementTree(root).write('behavior.xml')\n"
            "sys.exit(0 if result.wasSuccessful() else 1)\n"
        )
        command = [sys.executable, '-c', script]
        result = dict(run_bounded(command, directory, 20), command=command)
        self.assertEqual(0, result['exit_code'], result['output_tail'])
        mapping = [{'requirement_id': 'budget', 'case_id': case_id, 'command_index': 0, 'scope': 'behavior', 'report_path': 'behavior.xml'}]
        receipts = behavior_receipts(mapping, [result], directory, 'a' * 40, 'host-fixture-not-container-qualification')
        self.assertEqual('PASSED', receipts[0]['status'])
        self.job['request']['acceptance'] = [{'id': 'budget', 'paths': ['budget.py'], 'checks': ['behavior-suite'], 'evidence': [{'kind': 'behavior_test', 'name': case_id}]}]
        self.job['data']['head_sha'] = 'a' * 40
        payload = dict(receipts[0], trusted=True, source='HOST_VALIDATION', repository='owner/repo', branch='feature/job',
                       head_sha='a' * 40, binding_id=self.binding['binding_id'])
        ref = self.service.evidence.append(self.job, 'requirement_evidence', payload)
        self.job['data']['requirement_evidence_refs'] = [ref['receipt_id']]
        readiness = {'head_sha': 'a' * 40, 'checks_passed': True, 'checks': [{'name': 'behavior-suite', 'status': 'SUCCESS', 'required': True}], 'changed_files': ['budget.py']}
        verified = self.service.completion_verification(self.job, readiness)
        self.assertTrue(verified['passed'])
        self.assertEqual('BEHAVIOR_VERIFIED', verified['requirements'][0]['status'])
        self.assertFalse(completion_gate(self.job, readiness)['passed'])
        self.assertEqual({'BEHAVIOR_VERIFIED': 1}, build_report(self.job, 'NEEDS_INPUT', verified)['requirement_counts'])
        (directory / 'behavior.xml').write_text(f'<testsuite><testcase classname="BudgetAndSchedulerTests" name="{test_name}"><skipped/></testcase></testsuite>')
        self.assertEqual('SKIPPED', behavior_receipts(mapping, [result], directory, 'a' * 40, 'fixture')[0]['status'])
        (directory / 'behavior.xml').write_text('<testsuite><testcase classname="Unrelated" name="build"/></testsuite>')
        self.assertEqual('UNAVAILABLE', behavior_receipts(mapping, [result], directory, 'a' * 40, 'fixture')[0]['status'])

    def test_failed_behavior_cannot_be_hidden_by_another_passing_receipt(self):
        self.job['request']['acceptance'] = [{'id': 'feature', 'paths': ['feature'], 'checks': ['suite']}]
        self.job['data']['head_sha'] = 'a' * 40
        base = {'requirement_id': 'feature', 'kind': 'behavior_test', 'name': 'feature', 'scope': 'behavior',
                'repository': 'owner/repo', 'branch': 'feature/job', 'head_sha': 'a' * 40, 'binding_id': self.binding['binding_id'],
                'trusted': True, 'source': 'HOST_VALIDATION'}
        refs = [self.service.evidence.append(self.job, 'requirement_evidence', dict(base, status=status))['receipt_id'] for status in ('PASSED', 'FAILED')]
        self.job['data']['requirement_evidence_refs'] = refs
        gate = self.service.completion_verification(self.job, {'head_sha': 'a' * 40, 'checks_passed': True, 'changed_files': ['feature'], 'checks': [{'name': 'suite', 'status': 'SUCCESS'}]})
        self.assertFalse(gate['passed'])
        self.assertEqual('CONTRADICTED', gate['requirements'][0]['status'])


if __name__ == '__main__':
    unittest.main()
