"""Track the proposed engineering targets against observed production qualification cases."""

TARGETS = {
    'valid_task_intent': ('>=', 98, 'percent', 'Independently labelled user intent'),
    'supported_task_completion': ('>=', 95, 'percent', 'Supported qualification tasks with verified completion'),
    'recoverable_failure_recovery': ('>=', 95, 'percent', 'Recovery cases with an observed triggering failure'),
    'relevant_source_retrieval': ('>=', 95, 'percent', 'Mean recall of required source paths in the two source retrieval cases'),
    'unnecessary_tool_calls': ('<', 2, 'percent', 'Independently classified unnecessary tool calls'),
    'false_implementation_claims': ('==', 0, 'percent', 'Implementation claims independently checked against outcomes'),
    'unreconciled_duplicate_writes': ('==', 0, 'count', 'Repeated remote effects outstanding after reconciliation'),
    'preventable_provider_payload_failures': ('==', 0, 'count', 'Provider payload failures independently classified as preventable'),
}


def measure(report):
    metrics = {name: {'comparison': operator, 'target': threshold, 'unit': unit, 'basis': basis,
                      'value': None, 'samples': 0, 'status': 'UNAVAILABLE'}
               for name, (operator, threshold, unit, basis) in TARGETS.items()}
    result = {'schema': 'gateway.github-intelligence-targets.v1', 'scope': 'OBSERVED_QUALIFICATION_CASES_ONLY',
              'metrics': metrics, 'proxies': {}}
    # The score has just been computed from the host-qualified cases. Its task
    # digest exists only for a complete, distinct, source- and route-bound set.
    rubric = report['githubIntelligenceScore']
    if (report.get('execution_scope') != 'PRODUCTION_JOBS' or report.get('fixture_results_included') is not False
            or not rubric.get('taskSetDigest')):
        return result
    dims = rubric['dimensions']

    def observe(target, dimension):
        item = dims[dimension]
        if item['points'] is None or not item['planned'] or item['observed'] != item['planned']:
            return
        percent = 100 * item['points'] / item['weight']
        metric = metrics[target]
        metric.update(value=percent, samples=item['observed'], status='MET' if percent >= metric['target'] else 'MISSED')

    observe('supported_task_completion', 'task_completion')
    observe('relevant_source_retrieval', 'repository_understanding')
    cases = {row['scenario']: row for row in report['cases']}
    if (cases['ci_repair'].get('repair_attempts', 0) > 0 and
            cases['lost_mutation'].get('lost_mutation_reconciliation_observed') is True and
            cases['gateway_restart'].get('required_scenario_signal_observed') is True):
        observe('recoverable_failure_recovery', 'error_recovery')

    efficiency = dims['tool_efficiency']
    if efficiency['points'] is not None:
        result['proxies']['repeated_source_reads'] = {
            'value': 100 * (1 - efficiency['points'] / efficiency['weight']), 'unit': 'percent',
            'samples': efficiency['observed'], 'basis': 'Mean fraction of repeated exact source-range reads',
            'interpretation': 'Verification can legitimately reread a range. Necessity was not graded.'}
    return result
