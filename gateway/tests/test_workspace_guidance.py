import base64
from types import SimpleNamespace
from unittest import TestCase

from v14.engineering_backend import blob_sha
from v14.engineering_workspace import act, view
from v14.workspace_guidance import contents, instruction_paths


class GuidanceTests(TestCase):
    def setUp(self):
        sources = {'CLAUDE.md': 'general', 'AGENTS.md': 'root', 'app/AGENTS.md': 'android',
                   'gateway/CLAUDE.md': 'gateway', 'gateway/a.py': 'answer = 42',
                   '.agents/skills/triage/SKILL.md': 'read logs'}
        self.tree = {p: {'sha': blob_sha(t), 'mode': '100644'} for p, t in sources.items()}
        blobs = {blob_sha(t): {'size': len(t), 'encoding': 'base64', 'content': base64.b64encode(t.encode()).decode()} for t in sources.values()}
        self.backend = SimpleNamespace(root=lambda _: '', api=lambda url, **kw: blobs[url.rpartition('/')[2]])
        self.job = {'data': {'tree': self.tree, 'head_sha': 'a' * 40, 'plan': {}, 'files': {}}}

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
