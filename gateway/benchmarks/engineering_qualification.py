"""Ten production-task scenarios, graded only from durable execution evidence.

This evaluator does not turn fixture tests or missing telemetry into live passes.
The caller supplies already-authorized jobs in a designated test repository.
"""
import json
import re
from benchmarks import workspace_qualification as workspace
from v14.batch import digest
from v14.execution_binding import fingerprint

PAGE_SIZE = 256
MAX_OBSERVATIONS = 50000
MAX_OBSERVATION_BYTES = 32 * 1024 * 1024


def read_observations(db, table, job_id):
    """Scan a frozen rowid boundary with a completeness receipt, not LIMIT 513."""
    if table not in {'engineering_model_attempts', 'engineering_operations'}:
        raise ValueError('Invalid qualification evidence table')
    upper, expected = db.execute('SELECT COALESCE(MAX(rowid),0),COUNT(*) FROM ' + table + ' WHERE job_id=?', (job_id,)).fetchone()
    result, after, size, pages = [], 0, 0, 0
    while after < upper and len(result) < MAX_OBSERVATIONS:
        rows = db.execute('SELECT rowid,payload FROM ' + table + ' WHERE job_id=? AND rowid>? AND rowid<=? ORDER BY rowid LIMIT ?',
                          (job_id, after, upper, PAGE_SIZE)).fetchall()
        pages += 1
        if not rows:
            break
        for row_id, payload in rows:
            size += len(payload.encode('utf-8'))
            if size > MAX_OBSERVATION_BYTES or len(result) >= MAX_OBSERVATIONS:
                return result, {'complete': False, 'expected': expected, 'observed': len(result),
                                'pages': pages, 'throughRowId': upper, 'reason': 'EVIDENCE_BOUND'}
            result.append(json.loads(payload))
            after = row_id
    return result, {'complete': len(result) == expected and (after == upper or expected == 0),
                    'expected': expected, 'observed': len(result), 'pages': pages, 'throughRowId': upper}


def selected_route(job, attempts):
    binding = job.get('execution_binding') or {}
    request = job['request']
    route = {'model': request['model'], 'profile_uid': binding.get('profile_uid'),
             'profile_revision': binding.get('profile_revision'), 'binding_id': request.get('execution_binding_id'),
             'endpoint_identity': binding.get('backend_endpoint_identity'), 'base_sha': request.get('source_sha'),
             'settings_digest': binding.get('generation_parameters_digest')}
    allowed = {binding.get('requested_model_id')}
    allowed.update((binding.get('approved_alias_mapping') or {}).values())
    verified = (all(isinstance(value, str) and value for value in route.values())
                and binding.get('binding_id') == route['binding_id']
                and binding.get('requested_model_id') == route['model']
                and request.get('profile_id') == route['profile_uid']
                and binding.get('generation_parameters_digest') == fingerprint(binding.get('generation_parameters'))
                and bool(attempts) and all(row.get('binding_revision') == fingerprint(binding)
                    and row.get('requested_model_id') == route['model']
                    and row.get('observed_model_id') in allowed
                    and row.get('identity_status') == 'VERIFIED'
                    and row.get('backend_endpoint_identity') == route['endpoint_identity'] for row in attempts))
    return route, bool(verified)


def validate_expected_route(route):
    required = {'model', 'profile_uid', 'profile_revision', 'binding_id', 'endpoint_identity', 'base_sha', 'settings_digest'}
    if not isinstance(route, dict) or set(route) != required or not all(isinstance(value, str) and 0 < len(value) <= 512 for value in route.values()):
        raise ValueError('Freeze the exact model, profile, settings, binding, route and base SHA')
    if not re.fullmatch(r'[a-f0-9]{40}', route['base_sha']) or not re.fullmatch(r'[a-f0-9]{64}', route['settings_digest']):
        raise ValueError('Use immutable base and settings identities')
    return route

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


PRODUCTION_SCHEMA = 'gateway.production-qualification.v1'
CONTINUOUS_SCHEMA = 'gateway.continuous-engineering-reliability.v1'
CONTINUOUS_SCENARIOS = {
    'repeated_rate_limits': 'Resume after repeated 30–120-second HTTP 429 responses',
    'provider_scope_isolation': 'Keep unrelated providers available during throttling',
    'restart_during_wait': 'Recover the same cooldown checkpoint after a Gateway restart',
    'windows_restart_and_disconnect': 'Resume after Windows restart with the phone disconnected',
    'continuous_lost_mutation': 'Reconcile a lost GitHub write without duplication',
    'one_thousand_steps': 'Complete 1,000 logical steps across durable sessions',
    'repeated_ci_repair': 'Retain CI repair evidence across repeated failures',
    'same_branch_concurrency': 'Serialize publication for jobs on the same branch',
    'revoked_credentials_or_credits': 'Retain work and request action when authorization or credits end',
    'exact_head_merge': 'Verify approved exact-head merge and post-merge checks',
}


def retry_pairs(job):
    request, data = job['request'], job['data']
    expected_binding = request.get('execution_binding_id')
    if not expected_binding:
        return []
    binding_hash, job_hash = digest(expected_binding), digest(job['id'])
    scheduled, pairs = {}, []
    for entry in data.get('retry_history', []):
        if (not isinstance(entry, dict) or entry.get('binding_hash') != binding_hash or entry.get('job_hash') != job_hash
                or type(entry.get('checkpoint_revision')) is not int
                or type(entry.get('observed_at')) not in (float, int)
                or type(entry.get('retry_at')) not in (float, int)
                or not entry.get('boot_hash')):
            continue
        identity = (entry['checkpoint_revision'], entry.get('stage'), entry['retry_at'])
        if entry.get('outcome') == 'SCHEDULED':
            scheduled[identity] = entry
        elif entry.get('outcome') == 'RESUMED' and entry['observed_at'] >= entry['retry_at']:
            prior = scheduled.pop(identity, None)
            if prior:
                pairs.append((prior, entry))
    return pairs


def continuous_signals(job, accepted, completed, lost_mutation_proven, duplicates):
    data = job['data']
    pairs = retry_pairs(job)
    resumptions = [(before, after) for before, after in pairs if before.get('http_status') == 429]
    observed_cooldowns = [before['retry_at'] - before['observed_at'] for before, _ in resumptions]
    restarts = any(before['boot_hash'] != after['boot_hash'] for before, after in resumptions)
    approval = job.get('approval') or {}
    post_merge = data.get('post_merge') or {}
    blocked = data.get('blocked_reason') or {}
    return {
        'repeated_rate_limits': completed and sum(29 <= wait <= 121 for wait in observed_cooldowns) >= 3,
        'provider_scope_isolation': completed and 'provider_scope_isolation' in accepted and bool(resumptions),
        'restart_during_wait': completed and restarts,
        'windows_restart_and_disconnect': completed and restarts and 'windows_restart_and_disconnect' in accepted,
        'continuous_lost_mutation': completed and duplicates == 0 and lost_mutation_proven and 'continuous_lost_mutation' in accepted,
        'one_thousand_steps': completed and job.get('steps', 0) >= 1000 and (job.get('execution_session') or {}).get('number', 0) > 1,
        'repeated_ci_repair': completed and sum(job.get('repairs', {}).values()) >= 2 and 'repeated_ci_repair' in accepted,
        'same_branch_concurrency': completed and 'same_branch_concurrency' in accepted,
        'revoked_credentials_or_credits': job['state'] == 'NEEDS_INPUT' and blocked.get('retryable') is False
            and (blocked.get('http_status') in (401, 402) or blocked.get('code') == 'ENGINEERING_ACCESS_DENIED'),
        'exact_head_merge': completed and bool(data.get('merge_sha')) and post_merge.get('status') == 'PASSED'
            and bool(approval.get('head_sha')) and approval.get('head_sha') == data.get('head_sha')
            and post_merge.get('qualified_revision') == data.get('merge_sha'),
    }


def snapshot(service, principal, job_id, scenario):
    continuous = scenario in CONTINUOUS_SCENARIOS
    scenarios = workspace.ALL_CASES if scenario in workspace.ALL_CASES else CONTINUOUS_SCENARIOS if continuous else SCENARIOS
    if scenario not in scenarios:
        raise ValueError('Unknown engineering qualification scenario')
    job = service.evidence_access(principal, job_id)
    data = job['data']
    scan = {}
    def records(db):
        from v14.engineering_operations import EngineeringOperations
        EngineeringOperations.schema(db)
        attempts, scan['attempts'] = read_observations(db, 'engineering_model_attempts', job_id)
        operations, scan['operations'] = read_observations(db, 'engineering_operations', job_id)
        return attempts, operations
    attempts, operations = service.store.tx(records)
    # Legacy injected snapshots have no completeness receipt and retain the old
    # conservative ceiling. Production DB scans prove all rows through a bound.
    complete_records = all(value['complete'] for value in scan.values()) if scan else len(attempts) <= 512 and len(operations) <= 512
    dispatched = [row for row in attempts if row.get('dispatched')]
    completed = job['state'] == 'COMPLETED' and data.get('completion_verification', {}).get('passed') is True
    false_completion = job['state'] == 'COMPLETED' and not completed
    usage = [row.get('usage', {}).get('total_tokens') for row in dispatched]
    tokens = sum(usage) if usage and all(type(n) is int and n >= 0 for n in usage) and complete_records else None
    boot_ids = {row.get('boot_id') for row in dispatched if row.get('boot_id')}
    writes = [row for row in operations if row.get('dispatched')]
    # Distinct operation IDs do not prove distinct intended mutations. Until
    # reconciliation attests intent identities, duplicate writes remain unknown.
    intents = [row.get('request_ref', {}).get('sha256') for row in writes]
    duplicates = len(intents) - len(set(intents)) if intents and all(intents) and complete_records else None
    # A successful job with zero duplicate intents does not prove any mutation
    # outcome was lost. Require host-produced reconciliation of an observed write.
    dispatched_ids = {row.get('operation_id') for row in writes if row.get('job_id') == job_id}
    observed = data.get('mutation_reconciliations')
    observed = observed if isinstance(observed, list) else []
    lost_mutation_proven = any(
        isinstance(row, dict) and row.get('schema') == 'gateway.mutation-reconciliation.v1'
        and row.get('job_id') == job_id and row.get('operation_id') in dispatched_ids
        and row.get('phase') in {'COMMIT', 'OPEN_PR', 'PROMOTE', 'MERGE'}
        and row.get('original_dispatch_observed') is True
        and row.get('remote_state_verified') is True and row.get('replay_performed') is False
        and row.get('original_state') in {'DISPATCHED', 'UNKNOWN_OUTCOME'}
        and isinstance(row.get('remote_result_sha256'), str)
        and len(row['remote_result_sha256']) == 64
        for row in observed
    ) if complete_records else False
    verdicts = data.get('completion_verification', {}).get('requirements', [])
    accepted = {row['id'] for row in verdicts if row.get('status') == 'BEHAVIOR_VERIFIED'}
    signals = {
        'unfamiliar_bug': completed and bool(data.get('changed_paths')) and 'unfamiliar_bug' in accepted,
        'source_paging': completed and any(row.get('action') == 'read' and row.get('start_line', 1) > 200 for row in data.get('workspace_receipts', [])),
        'ci_repair': completed and bool(job.get('repairs')) and 'ci_repair' in accepted,
        'existing_pr': completed and bool(job.get('continuation_pr')) and job['continuation_pr'] == data.get('pr'),
        'context_rotation': completed and bool(data.get('context_compaction')),
        'lost_mutation': completed and duplicates == 0 and lost_mutation_proven and 'lost_mutation' in accepted,
        'gateway_restart': completed and len(boot_ids) > 1,
        'phone_disconnect': completed and 'phone_disconnect' in accepted,
        'terminal_ci_blocker': job['state'] in {'NEEDS_INPUT', 'FAILED'} and bool(data.get('ci')) and not completed and 'terminal_ci_blocker' in accepted,
        'post_merge_failure': bool(data.get('merge_sha')) and data.get('post_merge', {}).get('status') == 'FAILED' and not completed,
    }
    if continuous:
        signals = continuous_signals(job, accepted, completed, lost_mutation_proven, duplicates)
    # Completion metadata alone does not measure a production model attempt.
    attempts_measured = complete_records and bool(dispatched)
    passed = signals.get(scenario, False) and attempts_measured and not false_completion
    workspace_details = {}
    if scenario in workspace.ALL_CASES:
        workspace_details = workspace.measurements(job, scenario, dispatched, completed, duplicates, lost_mutation_proven)
        signals[scenario] = workspace_details['passed']
        passed = signals.get(scenario, False) and attempts_measured and not false_completion
    latest = dispatched[-1] if dispatched else {}
    comparison_route, route_verified = selected_route(job, dispatched)
    return {**workspace_details, 'schema': workspace.SCHEMA if scenario in workspace.ALL_CASES else CONTINUOUS_SCHEMA if continuous else PRODUCTION_SCHEMA, 'scenario': scenario, 'title': scenarios[scenario],
            'job_id': job_id, 'repository': job['request']['repository'], 'source_sha': job['request'].get('source_sha'),
            'head_sha': data.get('head_sha'), 'model': job['request']['model'],
            'model_revision': latest.get('model_revision'), 'quantization': latest.get('hardware', {}).get('quantization'),
            'binding_id': job['request'].get('execution_binding_id'), 'route_identity': latest.get('backend_endpoint_identity'),
            'execution_mode': job['request'].get('execution_mode', 'standard'),
            'status': 'PASSED' if passed else 'FAILED' if false_completion else 'NOT_VERIFIED',
            'liveGitHub': 'OBSERVED' if writes else 'NOT_EXECUTED', 'completion': completed,
            'first_attempt_success': (completed and not bool(job.get('repairs')) and
                                      all(row.get('state') == 'COMPLETED' for row in dispatched)) if attempts_measured else None,
            'repair_attempts': sum(job.get('repairs', {}).values()), 'duplicate_writes': duplicates,
            'lost_mutation_reconciliation_observed': lost_mutation_proven,
            'duplicate_write_measurement': 'REPEATED_IDENTICAL_DISPATCH_INTENTS' if duplicates is not None else 'NOT_MEASURED',
            'false_completion': false_completion, 'elapsed_seconds': max(0, job.get('finished_at', service.store.now()) - job['created_at']),
            'tokens': tokens, 'measurement_complete': complete_records,
            'evidence_scan': scan, 'comparison_route': comparison_route,
            'selected_route_verified': route_verified and complete_records,
            'required_scenario_signal_observed': bool(signals[scenario]),
            'limitations': [] if passed else ['Required scenario evidence is missing or completion was not verified.']}


def run_manifest(client, manifest, *, execute=False, progress=lambda _: None, checkpoint=lambda _: None):
    """Submit each fixed task once, then inspect durable snapshots on later runs.

    Model/route selection, test-repository choice, restart/disconnect injections
    and exact merge confirmations remain explicit setup/operator actions.
    """
    schema = manifest.get('schema') if isinstance(manifest, dict) else None
    if schema not in (PRODUCTION_SCHEMA, CONTINUOUS_SCHEMA, workspace.SCHEMA):
        raise ValueError('Use a production or continuous engineering qualification manifest')
    scenarios = workspace.manifest_cases(manifest) if schema == workspace.SCHEMA else CONTINUOUS_SCENARIOS if schema == CONTINUOUS_SCHEMA else SCENARIOS
    if schema == workspace.SCHEMA:
        workspace.validate_route(manifest.get('route'))
    repository, cases = manifest.get('test_repository'), manifest.get('cases')
    expected_route = manifest.get('expected_route')
    if expected_route is not None:
        validate_expected_route(expected_route)
    if (not isinstance(repository, str) or not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', repository)
            or not isinstance(cases, list) or len(cases) != len(scenarios)
            or not all(isinstance(row, dict) and isinstance(row.get('scenario'), str) for row in cases)
            or {row['scenario'] for row in cases} != set(scenarios)):
        raise ValueError('Name one designated test repository and all distinct scenarios from the chosen qualification suite')
    results = []
    for row in cases:
        scenario = row['scenario']
        progress(scenarios[scenario])
        job_id = row.get('job_id')
        if job_id is None and execute:
            request = row.get('request', {})
            if request.get('repository') != repository or not request.get('request_id') or request.get('task_scope') != 'implement':
                raise ValueError('Each submitted scenario needs a fixed authorized implementation request in the test repository')
            if expected_route and any(request.get(key) != expected_route[field] for key, field in (
                    ('model', 'model'), ('profile_id', 'profile_uid'), ('execution_binding_id', 'binding_id'), ('source_sha', 'base_sha'))):
                raise ValueError('A submitted scenario changed the frozen model/profile/base binding')
            job = client.submit_engineering(request)
            job_id = row['job_id'] = job['id']
            checkpoint(manifest)
        if job_id is None:
            results.append({'scenario': scenario, 'status': 'NOT_EXECUTED', 'liveGitHub': 'NOT_EXECUTED'})
            continue
        job = client.engineering_job(job_id)
        if job.get('repository') != repository:
            raise ValueError('Qualification job belongs to another repository')
        result = client.request('GET', '/gateway/engineering/jobs/' + job_id + '/qualification?scenario=' + scenario)
        if (schema == CONTINUOUS_SCHEMA and result.get('schema') != schema) or result.get('job_id') != job_id or result.get('scenario') != scenario or result.get('repository') != repository:
            raise ValueError('Qualification result identity changed')
        if schema == workspace.SCHEMA and (result.get('schema') != schema or result.get('route') != manifest['route']):
            raise ValueError('The observed qualification route differs from the selected profile, executor or environment')
        if expected_route and (result.get('comparison_route') != expected_route or result.get('selected_route_verified') is not True):
            result = dict(result, status='NOT_VERIFIED', comparison_binding='MISMATCH_OR_UNVERIFIED',
                          limitations=[*result.get('limitations', []), 'The exact frozen comparison route was not verified.'])
        results.append(result)
    return {'schema': manifest['schema'], 'test_repository': repository, 'cases': results,
            'passed': sum(row['status'] == 'PASSED' for row in results), 'total': len(scenarios),
            'qualified': all(row['status'] == 'PASSED' for row in results),
            **({'route': manifest['route'], 'completion_rate': sum(row['status'] == 'PASSED' for row in results) / len(scenarios)} if schema == workspace.SCHEMA else {}),
            'execution_scope': 'PRODUCTION_JOBS', 'fixture_results_included': False,
            'comparisonEligible': expected_route is not None and all(row.get('selected_route_verified') is True and row.get('comparison_route') == expected_route for row in results),
            'expected_route': expected_route}
