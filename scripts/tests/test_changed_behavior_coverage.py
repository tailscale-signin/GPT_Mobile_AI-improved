from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from changed_behavior_coverage import CONTRACTS, measured_lines, summarize, workflow_summary


class ChangedBehaviorEvidenceTest(unittest.TestCase):
    def test_only_named_current_passing_regression_satisfies_a_hit_declared_route(self):
        source, contracts = next(iter(CONTRACTS.items()))
        needle, case = next(iter(contracts.items()))
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            file = root / source
            file.parent.mkdir(parents=True)
            file.write_text('package example.runtime\n' + needle + ' throw Blocked()\n// retry description\n')
            changed = {source: {2, 3}}
            key = ('example/runtime', file.name)
            for counters, passed, missing in [
                ({}, {case}, True), ({2: (0, 0, 2)}, {case}, True),
                ({2: (6, 1, 1)}, {case}, True), ({2: (6, 2, 0)}, {'other.Test.passes'}, True), ({2: (6, 2, 0)}, {case}, False)
            ]:
                result = summarize(root, changed, {key: counters}, passed, [])
                self.assertEqual(missing, bool(result['missing_required_evidence']))
                self.assertEqual(1, len(result['changed_behavior']))
                self.assertEqual(2, result['counts']['changed'])

    def test_measured_partial_branch_and_associated_case_remain_distinct(self):
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            name, test = 'Retry.kt', 'RetryTest.kt'
            (root / name).write_text('package sample\nif (retry) call()\n')
            (root / test).write_text('package sample.tests\nclass RetryTest')
            xml = ET.fromstring('<report><package name="sample"><sourcefile name="Retry.kt"><line nr="2" ci="3" cb="1" mb="1"/></sourcefile></package></report>')
            features = [{'id': 'retry', 'paths': [name], 'tests': [test]}]
            case = 'sample.tests.RetryTest.boundedRetry'
            result = summarize(root, {name: {2}}, measured_lines(xml), {case}, features)
            self.assertEqual([case], result['files'][name]['passing_feature_tests'])
            self.assertEqual('partial-branch', result['changed_behavior'][0]['line_status'])
            self.assertEqual({}, result['changed_behavior'][0]['required_tests'])
            self.assertEqual(1, result['counts']['partial_branches'])
            self.assertIn('Retry.kt#2', workflow_summary(result))
            self.assertIn('no direct named regression linked', workflow_summary(result))


class ChangedBehaviorCommandTest(unittest.TestCase):
    def test_bound_revisions_require_current_session_and_exact_named_case(self):
        script = Path(__file__).resolve().parents[1] / 'changed_behavior_coverage.py'
        source, contract = next(iter(CONTRACTS.items()))
        guard, case = next(iter(contract.items()))
        owner, name = case.rsplit('.', 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*arguments):
                return subprocess.check_output(['git', '-c', 'user.name=Fixture', '-c', 'user.email=test@example.invalid', *arguments],
                                               cwd=root, text=True).strip()
            git('init', '-q')
            file = root / source
            file.parent.mkdir(parents=True)
            file.write_text('package example\n')
            feature_map = root / 'docs/architecture/feature-map.json'
            feature_map.parent.mkdir(parents=True)
            feature_map.write_text('{"features":[]}')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            file.write_text('package example\n' + guard + ' throw Blocked()\n')
            git('commit', '-qam', 'guard')
            now = datetime.now(timezone.utc) - timedelta(seconds=2)
            (root/'started').write_text((now - timedelta(seconds=2)).isoformat())
            suite = root / 'results'
            suite.mkdir()
            (suite/'TEST-fixture.xml').write_text(f'<testsuite tests="1" timestamp="{now.isoformat()}"><testcase classname="{owner}" name="{name}"/></testsuite>')
            output = root / 'report.json'
            args = [sys.executable, str(script), '--base', base, '--coverage', 'coverage.xml',
                    '--started-at-file', 'started', '--junit-dir', 'results', '--output', str(output), '--enforce']
            for age, expected_code in [(0, 0), (60, 1)]:
                ms = int((now - timedelta(seconds=age)).timestamp() * 1000)
                (root/'coverage.xml').write_text(f'<report><sessioninfo start="{ms}" dump="{ms}"/><package name="example"><sourcefile name="{file.name}"><line nr="2" ci="4" cb="2" mb="0"/></sourcefile></package></report>')
                command = subprocess.run(args, cwd=root, capture_output=True, text=True)
                self.assertEqual(expected_code, command.returncode, command.stderr)
                evidence = json.loads(output.read_text())
                self.assertEqual(git('rev-parse', 'HEAD'), evidence['tested_commit'])
                self.assertEqual(bool(expected_code), bool(evidence['missing_required_evidence']))
