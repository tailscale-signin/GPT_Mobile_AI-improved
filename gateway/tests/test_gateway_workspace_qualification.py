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
                     policy_sha256='pin', candidate_ref={'sha256':'retained'}, output_sha256='digest',
                     case_results=[dict(name='Demo.sum', status='FAILED', report_sha256='d'*64)])
        job = {'request': {'model':'m', 'source_sha':source}, 'execution_binding':{'profile_uid':'p', 'backend_endpoint_identity':'endpoint'},
               'data': {'head_sha':head, 'workspace_checks': {'before':check, 'after':dict(check, status='PASSED', exit_code=0, patch_identity='after',
                            case_results=[dict(name='Demo.sum', status='PASSED', report_sha256='e'*64)])},
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
        changed['data']['workspace_checks']['after']['check_index'] = 1
        changed['data']['workspace_checks']['after']['case_results'][0]['name'] = 'Demo.unrelated'
        self.assertFalse(grade(changed, attempt))

    def test_local_and_remote_templates_keep_eight_cases_and_cannot_cross_qualify(self):
        head = 'a'*40
        job = {'request': {'model': 'selected', 'source_sha': head}, 'execution_binding': {
            'profile_uid': 'actual', 'backend_endpoint_identity': 'endpoint', 'provider_kind': 'OLLAMA'},
            'data': {'head_sha': head, 'admission': {'development_environment': {'image': 'sha256:'+'a'*64}},
                     'completion_verification': {'requirements': [dict(id=case, status='BEHAVIOR_VERIFIED',
                         head_sha=head, receipt_ids=['receipt']) for case in (workspace.LOCAL_PROFILE, 'workspace_remote_profile')]}}}
        attempt = dict(binding_revision='bound', backend_endpoint_identity='endpoint', requested_model_id='selected',
                       observed_model_id='selected', graph_retrieval={'dispatched': True, 'included_nodes': 2})
        def grade(scenario):
            return workspace.measurements(job, scenario, [attempt], True, 0, False)['passed']
        self.assertTrue(grade(workspace.LOCAL_PROFILE))
        self.assertFalse(grade('workspace_remote_profile'))
        job['execution_binding']['provider_kind'] = 'OPENROUTER'
        self.assertTrue(grade('workspace_remote_profile'))
        self.assertFalse(grade(workspace.LOCAL_PROFILE))
        attempt['graph_retrieval']['included_nodes'] = 0
        self.assertFalse(grade('workspace_remote_profile'))
        cases = [{'scenario': name} for name in workspace.LOCAL_CASES]
        self.assertEqual(8, len(workspace.manifest_cases({'cases': cases})))
        self.assertNotIn('workspace_remote_profile', workspace.manifest_cases({'cases': cases}))
