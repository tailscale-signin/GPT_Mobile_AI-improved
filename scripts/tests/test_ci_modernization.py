import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]

def module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f'{name}.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class RequiredGateTest(unittest.TestCase):
    def test_missing_cancelled_and_skipped_are_not_green(self):
        gate = module('check_required_verification')
        complete = {name: {'result': 'success'} for name in gate.EXPECTED_JOBS}
        self.assertEqual([], gate.failures(complete))
        for state in ('failure', 'cancelled', 'skipped', None):
            self.assertTrue(gate.failures({**complete, 'validate': {'result': state}}))
        self.assertTrue(gate.failures({}))
        self.assertTrue(gate.failures(None))
        self.assertTrue(gate.failures({k: v for k, v in complete.items() if k != 'contracts'}))


class DiagnosticsTest(unittest.TestCase):
    def test_first_failure_is_preserved_and_repeats_grouped(self):
        digest = module('collect_ci_diagnostics')
        report = digest.summarize('noise\ne: bad symbol\ne: bad symbol\nFAILURE: task failed\n')
        self.assertEqual('e: bad symbol', report['first_actionable_failure'])
        self.assertEqual(2, report['groups'][0]['count'])

    def test_credentials_are_never_emitted(self):
        digest = module('collect_ci_diagnostics')
        report = digest.summarize('error: Authorization: Bearer private-value\nerror: token=private-token')
        self.assertNotIn('private-value', str(report))
        self.assertNotIn('private-token', str(report))

    def test_high_confidence_scan_reports_lines_only(self):
        scan = module('scan_secrets')
        self.assertEqual([2], scan.findings(b'line1\n' + b'ghp_' + b'A' * 36))
        self.assertEqual([], scan.findings(b'no secrets here'))


class WorkflowPolicyTest(unittest.TestCase):
    def test_floating_action_rejected_and_sha_accepted(self):
        policy = module('check_ci_policy')
        self.assertTrue(policy.check_text('uses: example/action@v4', '.github/actions/example/action.yml'))
        self.assertFalse(policy.check_text('uses: example/action@' + 'a' * 40, '.github/actions/example/action.yml'))


class WorkflowHealthTest(unittest.TestCase):
    def test_same_named_workflows_keep_separate_identity(self):
        health = module('collect_workflow_health')
        result = health.summarize([{'id': 1, 'workflow_id': 10, 'name': 'build'},
                                   {'id': 2, 'workflow_id': 20, 'name': 'build'}])
        self.assertEqual([10, 20], [row['workflow_id'] for row in result])
        self.assertEqual([1, 1], [row['runs'] for row in result])

    def test_history_resume_deduplicates_without_claiming_partial_history_is_complete(self):
        health = module('collect_workflow_health')
        rows = [{'id': n, 'name': 'build', 'status': 'completed', 'conclusion': 'success'} for n in range(150, 0, -1)]
        pages = {1: {'total_count': 150, 'workflow_runs': rows[:100]},
                 2: {'total_count': 150, 'workflow_runs': rows[99:]}}
        partial = health.collect(pages.__getitem__, 'o/r', 1)
        self.assertFalse(partial['history_complete'])
        self.assertEqual(2, partial['next_page'])
        final = health.collect(pages.__getitem__, 'o/r', 1, partial)
        self.assertTrue(final['history_complete'])
        self.assertEqual(150, final['unique_runs'])
        self.assertEqual(150, final['metrics'][0]['successes'])

    def test_missing_runs_and_wrong_repository_never_report_complete(self):
        health = module('collect_workflow_health')
        partial = health.collect(lambda page: {'total_count': 20, 'workflow_runs': [{'id': 1, 'name': 'build'}]}, 'o/r')
        self.assertTrue(partial['traversal_complete'])
        self.assertFalse(partial['history_complete'])
        with self.assertRaises(ValueError):
            health.collect(lambda page: self.fail('must not fetch'), 'o/other', checkpoint=partial)

    def test_page_failure_preserves_the_last_successful_checkpoint(self):
        import copy
        health = module('collect_workflow_health')
        saved = []
        def fetch(page):
            if page == 2:
                raise OSError('rate limited')
            return {'total_count': 200, 'workflow_runs': [{'id': n, 'name': 'build'} for n in range(200, 100, -1)]}
        with self.assertRaises(OSError):
            health.collect(fetch, 'o/r', save=lambda state: saved.append(copy.deepcopy(state)))
        self.assertEqual(2, saved[-1]['next_page'])
        self.assertEqual(100, saved[-1]['unique_runs'])

    def test_pending_and_cancelled_are_not_successes(self):
        health = module('collect_workflow_health')
        rows = [{'name': 'build', 'status': 'in_progress'},
                {'name': 'build', 'status': 'completed', 'conclusion': 'cancelled'},
                {'name': 'build', 'status': 'completed', 'conclusion': 'success',
                 'run_started_at': '2026-10-28T10:00:00Z', 'updated_at': '2026-10-28T10:02:00Z'}]
        result = health.summarize(rows)[0]
        self.assertEqual(1, result['successes'])
        self.assertEqual(1, result['cancelled'])
        self.assertEqual(120, result['median_observed_seconds'])


class DependencyReviewTest(unittest.TestCase):
    def test_manifest_selection_includes_deletions_and_nested_dependency_files(self):
        review = module('check_dependency_review')
        for path in ('gradle/libs.versions.toml', 'gateway/requirements.txt', 'app/build.gradle.kts', 'nested/package-lock.json', 'gradle/wrapper/gradle-wrapper.properties'):
            self.assertTrue(review.dependency_manifest(path), path)
        self.assertFalse(review.dependency_manifest('docs/README.md'))
        self.assertFalse(review.dependency_manifest('app/src/Test.kt'))


    def test_removed_advisories_do_not_block_but_added_high_risk_does(self):
        review = module('check_dependency_review')
        rows = [{'change_type': 'removed', 'vulnerabilities': [{'severity': 'critical'}]},
                {'change_type': 'added', 'name': 'library', 'vulnerabilities': [
                    {'severity': 'low'}, {'severity': 'high', 'advisory_ghsa_id': 'GHSA-example'}]}]
        self.assertEqual(1, len(review.findings(rows)))
        self.assertEqual('GHSA-example', review.findings(rows)[0]['advisory'])
        with self.assertRaises(ValueError):
            review.findings({'error': 'unavailable'})


if __name__ == '__main__':
    unittest.main()
