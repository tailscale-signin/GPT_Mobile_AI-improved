import unittest

from benchmarks.engineering_qualification import PRODUCTION_SCHEMA, SCENARIOS, run_manifest
from benchmarks.github_intelligence import score
from benchmarks.github_intelligence_targets import measure
from test_gateway_github_intelligence import report


class GitHubIntelligenceTargetsTests(unittest.TestCase):
    def test_unexecuted_manifest_keeps_all_eight_targets_unknown(self):
        manifest = dict(schema=PRODUCTION_SCHEMA, test_repository='org/repo',
                        cases=[{'scenario': scenario} for scenario in SCENARIOS])
        targets = run_manifest(object(), manifest)['githubIntelligenceTargets']
        self.assertEqual(8, len(targets['metrics']))
        self.assertTrue(all(row['status'] == 'UNAVAILABLE' and row['value'] is None
                            for row in targets['metrics'].values()))
        self.assertFalse(targets['proxies'])

    def test_bound_sample_reports_measured_rates_and_keeps_repeated_reads_as_a_proxy(self):
        observed = report()
        observed.update(execution_scope='PRODUCTION_JOBS', fixture_results_included=False)
        by_case = {row['scenario']: row for row in observed['cases']}
        by_case['ci_repair']['repair_attempts'] = 1
        by_case['lost_mutation']['lost_mutation_reconciliation_observed'] = True
        by_case['gateway_restart']['required_scenario_signal_observed'] = True
        by_case['ci_repair']['intelligence']['measures']['task_completion'] = 0
        observed['githubIntelligenceScore'] = score(observed)
        targets = measure(observed)
        self.assertEqual(87.5, targets['metrics']['supported_task_completion']['value'])
        self.assertEqual('MISSED', targets['metrics']['supported_task_completion']['status'])
        self.assertEqual('MET', targets['metrics']['relevant_source_retrieval']['status'])
        self.assertEqual('MET', targets['metrics']['recoverable_failure_recovery']['status'])
        self.assertEqual('UNAVAILABLE', targets['metrics']['unnecessary_tool_calls']['status'])
        self.assertEqual(50, targets['proxies']['repeated_source_reads']['value'])
        by_case['lost_mutation']['lost_mutation_reconciliation_observed'] = False
        self.assertEqual('UNAVAILABLE', measure(observed)['metrics']['recoverable_failure_recovery']['status'])
        observed['fixture_results_included'] = True
        self.assertTrue(all(row['value'] is None for row in measure(observed)['metrics'].values()))
