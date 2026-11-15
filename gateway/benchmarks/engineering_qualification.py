"""Ten production-task scenarios, graded only from durable execution evidence.

This evaluator does not turn fixture tests or missing telemetry into live passes.
The caller supplies already-authorized jobs in a designated test repository.
"""
import json

SCENARIOS = {
    'unfamiliar_bug': 'Diagnose and repair an unfamiliar bug',
    'source_paging': 'Inspect source beyond one excerpt',
    'ci_repair': 'Repair CI at the published parent revision',
    'existing_pr': 'Keep the same PR across jobs',
    'context_rotation': 'Continue across context compaction',
    'lost_mutation': 'Reconcile a lost write without duplication',
    'gateway_restart': 'Resume after a Gateway restart',
    'phone_disconnect': 'Resume after the phone disconnects',
    'terminal_ci_blocker': 'Report a terminal CI blocker truthfully',
    'post_merge_failure': 'Retain merge receipt and subsequent main failure',
}


def snapshot(service, principal, job_id, scenario):
    if scenario not in SCENARIOS:
        raise ValueError('Unknown production qualification scenario')
    job = service.evidence_access(principal, job_id)
    data = job['data']
    def records(db):
        attempts = [json.loads(row[0]) for row in db.execute(
            'SELECT payload FROM engineering_model_attempts WHERE job_id=? ORDER BY rowid LIMIT 513', (job_id,))]
        from v14.engineering_operations import EngineeringOperations
        EngineeringOperations.schema(db)
        operations = [json.loads(row[0]) for row in db.execute(
            'SELECT payload FROM engineering_operations WHERE job_id=? ORDER BY rowid LIMIT 513', (job_id,))]
        return attempts, operations
    attempts, operations = service.store.tx(records)
    complete_records = len(attempts) <= 512 and len(operations) <= 512
    dispatched = [row for row in attempts[:512] if row.get('dispatched')]
    completed = job['state'] == 'COMPLETED' and data.get('completion_verification', {}).get('passed') is True
    false_completion = job['state'] == 'COMPLETED' and not completed
    usage = [row.get('usage', {}).get('total_tokens') for row in dispatched]
    tokens = sum(usage) if usage and all(type(n) is int and n >= 0 for n in usage) and complete_records else None
    boot_ids = {row.get('boot_id') for row in dispatched if row.get('boot_id')}
    writes = [row for row in operations[:512] if row.get('dispatched')]
    # Distinct operation IDs do not prove distinct intended mutations. Until
    # reconciliation attests intent identities, duplicate writes remain unknown.
    intents = [row.get('request_ref', {}).get('sha256') for row in writes]
    duplicates = len(intents) - len(set(intents)) if intents and all(intents) and complete_records else None
    verdicts = data.get('completion_verification', {}).get('requirements', [])
    accepted = {row['id'] for row in verdicts if row.get('status') == 'BEHAVIOR_VERIFIED'}
    signals = {
        'unfamiliar_bug': completed and bool(data.get('changed_paths')) and 'unfamiliar_bug' in accepted,
        'source_paging': completed and any(row.get('action') == 'read' and row.get('start_line', 1) > 200 for row in data.get('workspace_receipts', [])),
        'ci_repair': completed and bool(job.get('repairs')) and 'ci_repair' in accepted,
        'existing_pr': completed and bool(job.get('continuation_pr')) and job['continuation_pr'] == data.get('pr'),
        'context_rotation': completed and bool(data.get('context_compaction')),
        'lost_mutation': completed and duplicates == 0 and 'lost_mutation' in accepted,
        'gateway_restart': completed and len(boot_ids) > 1,
        'phone_disconnect': completed and 'phone_disconnect' in accepted,
        'terminal_ci_blocker': job['state'] in {'NEEDS_INPUT', 'FAILED'} and bool(data.get('ci')) and not completed and 'terminal_ci_blocker' in accepted,
        'post_merge_failure': bool(data.get('merge_sha')) and data.get('post_merge', {}).get('status') == 'FAILED' and not completed,
    }
    passed = signals[scenario] and complete_records and not false_completion
    latest = dispatched[-1] if dispatched else {}
    return {'schema': 'gateway.production-qualification.v1', 'scenario': scenario, 'title': SCENARIOS[scenario],
            'job_id': job_id, 'repository': job['request']['repository'], 'source_sha': job['request'].get('source_sha'),
            'head_sha': data.get('head_sha'), 'model': job['request']['model'],
            'model_revision': latest.get('model_revision'), 'quantization': latest.get('hardware', {}).get('quantization'),
            'binding_id': job['request'].get('execution_binding_id'), 'route_identity': latest.get('backend_endpoint_identity'),
            'execution_mode': job['request'].get('execution_mode', 'standard'),
            'status': 'PASSED' if passed else 'FAILED' if false_completion else 'NOT_VERIFIED',
            'liveGitHub': 'OBSERVED' if writes else 'NOT_EXECUTED', 'completion': completed,
            'first_attempt_success': completed and not bool(job.get('repairs')) and all(row.get('state') == 'COMPLETED' for row in dispatched),
            'repair_attempts': sum(job.get('repairs', {}).values()), 'duplicate_writes': duplicates,
            'duplicate_write_measurement': 'REPEATED_IDENTICAL_DISPATCH_INTENTS' if duplicates is not None else 'NOT_MEASURED',
            'false_completion': false_completion, 'elapsed_seconds': max(0, job.get('finished_at', service.store.now()) - job['created_at']),
            'tokens': tokens, 'measurement_complete': complete_records,
            'required_scenario_signal_observed': bool(signals[scenario]),
            'limitations': [] if passed else ['Required scenario evidence is missing or completion was not verified.']}


def run_manifest(client, manifest, *, execute=False, progress=lambda _: None):
    """Submit each fixed task once, then inspect durable snapshots on later runs.

    Model/route selection, test-repository choice, restart/disconnect injections
    and exact merge confirmations remain explicit setup/operator actions.
    """
    if not isinstance(manifest, dict) or manifest.get('schema') != 'gateway.production-qualification.v1':
        raise ValueError('Use a production qualification manifest')
    repository, cases = manifest.get('test_repository'), manifest.get('cases')
    if not isinstance(repository, str) or not isinstance(cases, list) or len(cases) != 10 or {row.get('scenario') for row in cases} != set(SCENARIOS):
        raise ValueError('Name one designated test repository and all ten distinct scenarios')
    results = []
    for row in cases:
        scenario = row['scenario']
        progress(SCENARIOS[scenario])
        job_id = row.get('job_id')
        if job_id is None and execute:
            request = row.get('request', {})
            if request.get('repository') != repository or not request.get('request_id') or request.get('task_scope') != 'implement':
                raise ValueError('Each submitted scenario needs a fixed authorized implementation request in the test repository')
            job = client.submit_engineering(request)
            job_id = row['job_id'] = job['id']
        if job_id is None:
            results.append({'scenario': scenario, 'status': 'NOT_EXECUTED', 'liveGitHub': 'NOT_EXECUTED'})
            continue
        job = client.engineering_job(job_id)
        if job.get('repository') != repository:
            raise ValueError('Qualification job belongs to another repository')
        result = client.request('GET', '/gateway/engineering/jobs/' + job_id + '/qualification?scenario=' + scenario)
        if result.get('job_id') != job_id or result.get('scenario') != scenario or result.get('repository') != repository:
            raise ValueError('Qualification result identity changed')
        results.append(result)
    return {'schema': manifest['schema'], 'test_repository': repository, 'cases': results,
            'passed': sum(row['status'] == 'PASSED' for row in results), 'total': 10,
            'qualified': all(row['status'] == 'PASSED' for row in results),
            'execution_scope': 'PRODUCTION_JOBS', 'fixture_results_included': False}
