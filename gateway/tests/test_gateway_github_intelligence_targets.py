import unittest

from benchmarks.engineering_qualification import PRODUCTION_SCHEMA, SCENARIOS, run_manifest
from benchmarks.github_intelligence import score
from benchmarks.github_intelligence_targets import accepted_provider_posts, measure
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

    def test_payload_target_needs_success_for_every_physical_dispatch_across_the_frozen_set(self):
        bound = report()
        bound['githubIntelligenceScore'] = score(bound)
        counts = []
        for index, case in enumerate(bound['cases']):
            binding, ident = 'selected-binding', case['job_id']
            job = {'id': ident, 'request': {'execution_binding_id': binding}, 'revision': 4, 'state': 'COMPLETED',
                   'data': {'final_report': {'terminal_receipt': {'schema': 'gateway.terminal-receipt.v1',
                             'job_id': ident, 'checkpoint_revision': 4, 'status': 'COMPLETED'}}}}
            posts = [{'attempt_id': f'attempt_{n:032x}', 'job_id': ident, 'binding_id': binding, 'job_revision': 3,
                      'dispatched': True, 'state': 'COMPLETED', 'identity_status': 'VERIFIED', 'http_status': 200,
                      'response_ref': {'result_ref': 'artifact_' + 'b' * 40, 'sha256': 'c' * 64}} for n in range(index + 1)]
            scan = {'complete': True, 'expected': len(posts), 'observed': len(posts)}
            counts.append(len(posts))
            case['accepted_provider_posts'] = accepted_provider_posts(job, posts, scan)
            self.assertEqual(len(posts), case['accepted_provider_posts'])
        metric = measure(bound)['metrics']['preventable_provider_payload_failures']
        self.assertEqual((0, sum(counts), 'MET'), (metric['value'], metric['samples'], metric['status']))
        for incomplete in (dict(posts[-1], http_status=413), dict(posts[-1], state='UNKNOWN_OUTCOME'),
                           dict(posts[-1], response_ref=None), dict(posts[-1], identity_status='UNAVAILABLE'),
                           dict(posts[-1], dispatched=False), dict(posts[-1], job_revision=5),
                           dict(posts[-1], binding_id='other'), posts[0]):
            with self.subTest(receipt=incomplete):
                case['accepted_provider_posts'] = accepted_provider_posts(job, posts[:-1] + [incomplete], scan)
                self.assertIsNone(case['accepted_provider_posts'])
                self.assertEqual('UNAVAILABLE', measure(bound)['metrics']['preventable_provider_payload_failures']['status'])
        self.assertIsNone(accepted_provider_posts(job, [], dict(complete=True, expected=0, observed=0)))
        self.assertIsNone(accepted_provider_posts(job, posts, dict(scan, complete=False)))
        self.assertIsNone(accepted_provider_posts(job, posts, None))
        self.assertIsNone(accepted_provider_posts(dict(job, revision=5), posts, scan))

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
