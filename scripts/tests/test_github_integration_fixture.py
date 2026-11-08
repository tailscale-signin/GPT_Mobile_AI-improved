"""Execute the actual inline fixture guard with synthetic CI metadata; no GitHub requests."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml


class GitHubIntegrationFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.load(
            (Path(__file__).resolve().parents[2] / '.github/workflows/integration-test.yml').read_text(),
            Loader=yaml.BaseLoader,
        )
        cls.program = cls.workflow['jobs']['fixture']['steps'][0]['run'].split("python3 - <<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]

    def run_fixture(self, **changes):
        with tempfile.TemporaryDirectory() as folder:
            env = dict(os.environ, FIXTURE_ID='gptmobile-ci-' + 'b' * 24, FIXTURE_SHA='a' * 40,
                       FIXTURE_CONFIRMATION='disposable-test-only', FIXTURE_MODE='success',
                       GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/codex-self-test/' + 'c' * 20,
                       GITHUB_SHA='a' * 40, GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_ID='41',
                       GITHUB_REPOSITORY='test-owner/synthetic-repo', RUNNER_TEMP=folder,
                       GITHUB_OUTPUT=str(Path(folder, 'output')))
            env.update(changes)
            result = subprocess.run([sys.executable, '-c', self.program], env=env, capture_output=True, text=True, timeout=5)
            receipt = Path(folder, 'github-integration-fixture.json')
            return result.returncode, json.loads(receipt.read_text()) if receipt.exists() else None

    def test_only_manual_disposable_ref_can_admit_a_synthetic_fixture(self):
        self.assertEqual({'workflow_dispatch'}, set(self.workflow['on']))
        self.assertEqual({'contents': 'read'}, self.workflow['permissions'])
        self.assertFalse(any('checkout' in step.get('uses', '') for step in self.workflow['jobs']['fixture']['steps']))
        code, receipt = self.run_fixture()
        self.assertEqual(0, code)
        self.assertEqual('SYNTHETIC_CI_API_FIXTURE', receipt['evidence_scope'])
        self.assertEqual('NOT_TESTED', receipt['application_acceptance'])
        self.assertEqual('NOT_TESTED', receipt['device_execution'])
        self.assertEqual('a' * 40, receipt['commit_sha'])

    def test_ref_revision_confirmation_and_shell_shaped_markers_reject_before_receipt(self):
        for field, value in [('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REF', 'refs/heads/main'),
                             ('GITHUB_SHA', 'd' * 40), ('FIXTURE_SHA', 'main'),
                             ('FIXTURE_ID', '$(touch escaped)'), ('FIXTURE_CONFIRMATION', ''),
                             ('FIXTURE_MODE', 'production')]:
            with self.subTest(field=field):
                code, receipt = self.run_fixture(**{field: value})
                self.assertNotEqual(0, code)
                self.assertIsNone(receipt)

    def test_controlled_first_attempt_failure_and_rerun_keep_exact_attribution(self):
        first, first_receipt = self.run_fixture(FIXTURE_MODE='fail-first-attempt')
        rerun, rerun_receipt = self.run_fixture(FIXTURE_MODE='fail-first-attempt', GITHUB_RUN_ATTEMPT='2')
        self.assertNotEqual(0, first)
        self.assertEqual(0, rerun)
        self.assertEqual(first_receipt['fixture_id'], rerun_receipt['fixture_id'])
        self.assertEqual(first_receipt['run_id'], rerun_receipt['run_id'])
        self.assertEqual(first_receipt['commit_sha'], rerun_receipt['commit_sha'])
        self.assertEqual(2, rerun_receipt['run_attempt'])


if __name__ == '__main__':
    unittest.main()
