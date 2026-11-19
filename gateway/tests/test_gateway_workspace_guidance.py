import base64
import copy
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest import TestCase

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.control_server import proxy_permission
from desktop.control_store import ControlError
from v14.engineering_backend import blob_sha
from v14.engineering_workspace import act, view
from v14.inference_budget import fit_engineering_payload
from v14.workspace_guidance import contents, instruction_paths, requested_procedure, start_procedure


class GuidanceTests(TestCase):
    def setUp(self):
        sources = {'CLAUDE.md': 'general', 'AGENTS.md': 'root', 'app/AGENTS.md': 'android', 'app/a.kt': 'val answer = 42',
                   'gateway/CLAUDE.md': 'gateway', 'gateway/a.py': 'answer = 42',
                   '.agents/skills/triage/SKILL.md': 'read logs'}
        self.tree = {p: {'sha': blob_sha(t), 'mode': '100644'} for p, t in sources.items()}
        blobs = {blob_sha(t): {'size': len(t), 'encoding': 'base64', 'content': base64.b64encode(t.encode()).decode()} for t in sources.values()}
        self.backend = SimpleNamespace(root=lambda _: '', api=lambda url, **kw: blobs[url.rpartition('/')[2]])
        self.job = {'id': 'eng_' + '1' * 32, 'principal': 'paired:' + '2' * 32,
                    'request': {'repository': 'owner/repo'},
                    'data': {'tree': self.tree, 'head_sha': 'a' * 40, 'plan': {}, 'files': {}}}

    def action(self, kind, **args):
        receipt = act(self.backend, self.job, dict(action=kind, head_sha='a' * 40, **args))
        self.job['data'].setdefault('workspace_receipts', []).append(receipt)
        return receipt

    def test_adaptive_read_changes_active_rules_and_rejects_symlinks(self):
        self.tree['gateway/AGENTS.md'] = {'mode': '120000', 'sha': 'f' * 40}
        self.assertEqual(['CLAUDE.md', 'AGENTS.md', 'app/AGENTS.md'], instruction_paths(self.tree, ['app/new.kt']))
        self.action('read', path='gateway/a.py')
        self.assertEqual(['general', 'root', 'gateway'], [r['text'] for r in contents(self.job['data'])])
        self.assertEqual(['CLAUDE.md', 'AGENTS.md', 'gateway/CLAUDE.md'], view(self.job['data'])['active_instructions'])

    def test_repository_skill_and_resumable_step_need_pinned_evidence(self):
        skill = '.agents/skills/triage/SKILL.md'
        self.assertIn(skill, self.action('skills')['repository_skills'])
        self.assertEqual('read logs', self.action('skill', name=skill)['instructions'])
        self.action('skill', name='repair-ci')
        with self.assertRaises(ValueError):
            self.action('workflow_step', step=0, receipt_sha256='missing')
        observed = self.action('read', path='gateway/a.py')
        self.action('workflow_step', step=0, receipt_sha256=observed['receipt_sha256'])
        self.assertEqual(1, len(self.action('skill', name='repair-ci')['workflow']['completed']))
        with self.assertRaises(ValueError):
            self.action('workflow_step', step=0, receipt_sha256=observed['receipt_sha256'])

    def test_direct_procedure_requests_and_same_revision_continuation(self):
        self.assertEqual(('repair-ci', 'Fix this CI failure in #42'), requested_procedure('Fix this CI failure in #42'))
        self.assertEqual(('prepare-release', 'Build the reviewed revision'), requested_procedure('/prepare-release Build the reviewed revision'))
        self.assertIsNone(requested_procedure('Explain what “fix this CI failure” means')[0])
        self.assertIsNone(requested_procedure('/repair-ci-other')[0])
        workflow = start_procedure(self.job['data'], 'repair-ci')
        workflow['completed'].append({'step': 0})
        self.assertIs(workflow, start_procedure(self.job['data'], 'repair-ci'))
        self.job['data']['head_sha'] = 'b' * 40
        self.assertFalse(start_procedure(self.job['data'], 'repair-ci')['completed'])

    def test_skill_dependencies_follow_the_admitted_execution_host(self):
        path = '.agents/skills/triage/requirements.json'
        raw = json.dumps({'tools': ['check', 'code_intelligence'], 'executor_capabilities': ['language-server-kotlin']})
        self.tree[path] = {'mode': '100644', 'sha': blob_sha(raw)}
        self.job['data']['files'][path] = {'sha': blob_sha(raw), 'content': raw}
        skill = '.agents/skills/triage/SKILL.md'
        blocked = self.action('skill', name=skill)
        self.assertEqual(['check', 'code_intelligence'], blocked['requirements']['missing_tools'])
        self.assertNotIn('instructions', blocked)
        self.job['data']['admission'] = {'policy_sha256': 'policy', 'workspace_terminal_enabled': True,
                                         'development_environment': {'profile': 'Android / Kotlin'}}
        ready = self.action('skill', name=skill)
        self.assertEqual('read logs', ready['instructions'])
        self.assertEqual([], ready['requirements']['missing_capabilities'])
        self.assertIn('check', self.action('skills')['procedure_requirements']['repair-ci']['tools'])

    def fit(self, rules, receipts=None):
        body = {'head_sha': self.job['data']['head_sha'], 'objective': 'Inspect this selected repository',
                'user_instructions': ['Keep the accepted repository and owner'],
                'repository_instructions': rules, 'evidence': {'head_sha': self.job['data']['head_sha'],
                    'workspace_receipts': receipts or []}, 'historical_memory': None, 'code_graph': None}
        request = {'model': 'selected-fixture-model', 'max_tokens': 512,
                   'messages': [{'role': 'system', 'content': 'Honor the accepted task and host permissions.'},
                                {'role': 'user', 'content': json.dumps(body)}]}
        original = copy.deepcopy(request)
        admitted, budget, projection = fit_engineering_payload(request,
            {'context_tokens': 4096, 'max_output_tokens': 512, 'limits_verified': True},
            lambda value: (len(json.dumps(value).encode()), 'CONSERVATIVE_UTF8_BOUND'))
        self.assertEqual(original, request, 'The retained original must remain unchanged')
        self.assertEqual('selected-fixture-model', admitted['model'])
        self.assertEqual(512, admitted['max_tokens'])
        self.assertGreaterEqual(budget['remaining_tokens'], 0)
        fitted = json.loads(admitted['messages'][-1]['content'])
        self.assertEqual(body['objective'], fitted['objective'])
        self.assertEqual(body['user_instructions'], fitted['user_instructions'])
        return fitted, projection

    def test_4096_token_projection_keeps_instruction_identity_and_exact_read_recovery(self):
        original = ''.join('Rule %03d: preserve the approved behavior.\n' % number for number in range(1000))
        identity = blob_sha(original)
        self.tree['AGENTS.md']['sha'] = identity
        data = self.job['data']
        data['files']['AGENTS.md'] = {'sha': identity, 'content': original, 'preview_characters': 0}
        data['active_instructions'] = ['AGENTS.md']
        fitted, projection = self.fit(contents(data))
        rule = fitted['repository_instructions'][0]
        self.assertEqual(('AGENTS.md', identity), (rule['path'], rule['blob_sha']))
        self.assertIs(rule['text']['complete'], False)
        self.assertTrue(original.startswith(rule['text']['excerpt']))
        self.assertEqual(len(original.encode()), rule['text']['retained_bytes'])
        self.assertFalse(fitted['evidence_view']['complete'])
        self.assertTrue(any(row['pointer'] == '/repository_instructions/0/text' for row in projection['omissions']))
        recovered = self.action('read', path='AGENTS.md', start_line=51, line_count=2)
        self.assertEqual(identity, recovered['blob_sha'])
        self.assertEqual(''.join(original.splitlines(True)[50:52]), recovered['content'])
        resumed, _ = self.fit(contents(data), [recovered])
        recovered_view = resumed['evidence']['workspace_receipts'][0]
        self.assertEqual(identity, recovered_view['blob_sha'])
        self.assertEqual(recovered['content'], recovered_view['content'])
        self.assertEqual(original, data['files']['AGENTS.md']['content'])

    def test_wide_guidance_catalog_explicitly_reports_omitted_rules(self):
        rules = [{'path': 'component' + str(index) + '/AGENTS.md', 'blob_sha': format(index, '040x'),
                  'text': 'A repository convention.\n' * 1000} for index in range(20)]
        fitted, projection = self.fit(rules)
        self.assertLess(len(fitted['repository_instructions']), len(rules))
        self.assertTrue(any(row['pointer'] == '/repository_instructions' and row['retained_count'] == len(rules)
                            for row in projection['omissions']))
        for visible, retained in zip(fitted['repository_instructions'], rules):
            self.assertEqual(retained['path'], visible['path'])
            self.assertEqual(retained['blob_sha'], visible['blob_sha'])
            self.assertFalse(visible['text']['complete'])

    def test_paging_active_root_guidance_preserves_nested_source_scope(self):
        original = 'A root instruction that needs paging.\n' * 1000
        identity = blob_sha(original)
        self.tree['AGENTS.md']['sha'] = identity
        self.job['data']['files']['AGENTS.md'] = {'sha': identity, 'content': original, 'preview_characters': 0}
        nested = self.action('read', path='gateway/a.py')['active_instructions']
        self.assertEqual(['CLAUDE.md', 'AGENTS.md', 'gateway/CLAUDE.md'], nested)
        fitted, _ = self.fit(contents(self.job['data']))
        self.assertFalse(next(rule for rule in fitted['repository_instructions'] if rule['path'] == 'AGENTS.md')['text']['complete'])
        recovered = self.action('read', path='AGENTS.md', start_line=11, line_count=2)
        self.assertEqual(nested, recovered['active_instructions'])
        self.assertEqual(nested, self.job['data']['active_instructions'])
        self.assertEqual(nested, [rule['path'] for rule in contents(self.job['data'])])
        self.assertEqual(identity, recovered['blob_sha'])
        self.assertEqual(''.join(original.splitlines(True)[10:12]), recovered['content'])
        ordinary = self.action('read', path='app/a.kt')
        self.assertEqual(['CLAUDE.md', 'AGENTS.md', 'app/AGENTS.md'], ordinary['active_instructions'])
        self.assertEqual(ordinary['active_instructions'], self.job['data']['active_instructions'])

    def test_guidance_actions_keep_the_paired_job_owner_and_repository_scope(self):
        ownership = self.job['id'], self.job['principal'], copy.deepcopy(self.job['request'])
        self.action('instructions', paths=['gateway/a.py'])
        self.action('skill', name='.agents/skills/triage/SKILL.md')
        self.action('progress', hypothesis='Inspect the saved task', next_action='Read its pinned source', tried='')
        self.assertEqual(ownership, (self.job['id'], self.job['principal'], self.job['request']))
        grant = {'role': 'owner', 'repositories': json.dumps(['owner/repo'])}
        proxy_permission('/gateway/engineering/graph', 'POST', {'repository': 'owner/repo'}, grant)
        for payload in ({'repository': 'other/repo'}, {'guidance': {'repository': 'other/repo'}}):
            with self.assertRaises(ControlError) as error:
                proxy_permission('/gateway/engineering/graph', 'POST', payload, grant)
            self.assertEqual('repository_permission_required', error.exception.code)
        with self.assertRaises(ControlError) as error:
            proxy_permission('/gateway/workspace/skills', 'POST', {}, grant)
        self.assertEqual('control_route_not_admitted', error.exception.code)
        with self.assertRaises(ValueError):
            self.action('read', path='../other/AGENTS.md')
