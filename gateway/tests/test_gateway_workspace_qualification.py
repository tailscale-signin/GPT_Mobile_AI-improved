from copy import deepcopy
from unittest import TestCase
from unittest.mock import Mock

from benchmarks import workspace_qualification as workspace
from benchmarks.engineering_qualification import run_manifest


class WorkspaceQualificationTests(TestCase):
    def test_empty_manifest_never_submits_or_qualifies(self):
        client = Mock()
        route = dict(executor='gateway', model='model', profile_uid='p', endpoint_identity='host', environment='sha256:'+'a'*64)
        doc = dict(schema=workspace.SCHEMA, test_repository='owner/repo', route=route, cases=[dict(scenario=case) for case in workspace.CASES])
        result = run_manifest(client, doc)
        self.assertEqual(8, result['total'])
        self.assertFalse(result['qualified'])
        self.assertTrue(all(c['status'] == 'NOT_EXECUTED' for c in result['cases']))
        self.assertEqual([], client.method_calls)
        with self.assertRaises(ValueError):
            run_manifest(client, dict(doc, route=dict(route, environment='latest')))

    def test_real_attempts_must_match_binding_and_same_failing_kotlin_check(self):
        case, head, source = 'workspace_kotlin_repair', 'a'*40, 'b'*40
        check = dict(status='FAILED', exit_code=1, source_sha=source, check_index=1, patch_identity='before',
                     policy_sha256='pin', candidate_ref={'sha256':'retained'}, output_sha256='digest')
        job = {'request': {'model':'m', 'source_sha':source}, 'execution_binding':{'profile_uid':'p', 'backend_endpoint_identity':'endpoint'},
               'data': {'head_sha':head, 'workspace_checks': {'before':check, 'after':dict(check, status='PASSED', exit_code=0, patch_identity='after')},
                        'admission':{'policy_sha256':'pin', 'development_environment':{'profile':'Android / Kotlin', 'image':'sha256:'+'c'*64}},
                        'local_validation':{'status':'PASSED', 'patch_identity':'after'},
                        'completion_verification':{'requirements':[dict(id=case, status='BEHAVIOR_VERIFIED', head_sha=head, receipt_ids=['semantic'])]}}}
        attempt = dict(binding_revision='binding', backend_endpoint_identity='endpoint', requested_model_id='m', observed_model_id='m')
        def grade(state, row):
            return workspace.measurements(state, case, [row], True, 0, False)['passed']
        self.assertTrue(grade(job, attempt))
        self.assertFalse(grade(job, dict(attempt, observed_model_id='other')))
        changed = deepcopy(job)
        changed['data']['workspace_checks']['after']['check_index'] = 2
        self.assertFalse(grade(changed, attempt))
        self.assertFalse(workspace.measurements(job, case, [], True, 0, False)['passed'])
