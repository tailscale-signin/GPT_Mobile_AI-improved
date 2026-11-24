"""Track the proposed engineering targets against observed production qualification cases."""
import re

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


def accepted_provider_posts(job, attempts, scan):
    """Count the fenced model POSTs only when ALL outcomes prove payload acceptance.

    A 413 or an unknown outcome has no authoritative preventability label. A
    complete set of successful responses, however, proves zero rejections on
    exactly those requests. Each transport retry has its own attempt receipt.
    """
    terminal = (job['data'].get('final_report') or {}).get('terminal_receipt') or {}
    revision = job.get('revision')
    statuses = {'COMPLETED': {'COMPLETED', 'EVIDENCE_MISSING'}, 'FAILED': {'FAILED'},
                'CANCELLED': {'CANCELLED'}, 'NEEDS_INPUT': {'BLOCKED'}}
    if (not isinstance(scan, dict) or scan.get('complete') is not True or not attempts
            or scan.get('expected') != len(attempts) or scan.get('observed') != len(attempts)
            or type(revision) is not int or terminal.get('schema') != 'gateway.terminal-receipt.v1'
            or terminal.get('job_id') != job['id'] or terminal.get('checkpoint_revision') != revision
            or terminal.get('status') not in statuses.get(job['state'], set())):
        return None
    binding = job['request'].get('execution_binding_id')
    if not binding:
        return None
    seen = set()
    for row in attempts:
        attempt = row.get('attempt_id')
        reference = row.get('response_ref')
        if (not isinstance(attempt, str) or not re.fullmatch(r'attempt_[0-9a-f]{32}', attempt) or attempt in seen
                or row.get('job_id') != job['id'] or row.get('binding_id') != binding
                or type(row.get('job_revision')) is not int or not 0 <= row['job_revision'] <= revision
                or row.get('dispatched') is not True or row.get('state') != 'COMPLETED'
                or type(row.get('http_status')) is not int or not 200 <= row['http_status'] < 300
                or row.get('identity_status') != 'VERIFIED' or not isinstance(reference, dict)
                or not re.fullmatch(r'artifact_[0-9a-f]{40}', str(reference.get('result_ref')))
                or not re.fullmatch(r'[0-9a-f]{64}', str(reference.get('sha256')))):
            return None
        seen.add(attempt)
    return len(seen)


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

    # This denominator covers the observed Gateway-bound POSTs, including
    # retries. Never extrapolate it to other clients, providers or future jobs.
    accepted = [case.get('accepted_provider_posts') for case in cases.values()]
    if all(type(count) is int and count > 0 for count in accepted):
        metrics['preventable_provider_payload_failures'].update(
            value=0, samples=sum(accepted), status='MET',
            basis='Every fenced Gateway engineering model POST in these case snapshots returned a verified successful provider response.')

    efficiency = dims['tool_efficiency']
    if efficiency['points'] is not None:
        result['proxies']['repeated_source_reads'] = {
            'value': 100 * (1 - efficiency['points'] / efficiency['weight']), 'unit': 'percent',
            'samples': efficiency['observed'], 'basis': 'Mean fraction of repeated exact source-range reads',
            'interpretation': 'Verification can legitimately reread a range. Necessity was not graded.'}
    return result
