"""Evidence gates for one exact coding profile, workspace executor and environment."""
import re

SCHEMA = 'gateway.development-workspace-qualification.v1'
CASES = {
    'workspace_kotlin_repair': 'Reproduce a Kotlin test failure and pass the same check after a repair',
    'workspace_multi_file': 'Verify callers after a coordinated multi-file change',
    'workspace_phone_reconnect': 'Close and reopen Android and reconnect to the original job',
    'workspace_host_restart': 'Resume the job on a new host boot',
    'workspace_branch_advance': 'Observe and reconcile an external branch advance',
    'workspace_lost_write': 'Reconcile an uncertain write without repetition',
    'workspace_remote_profile': 'Use the selected remote profile with authorized Gateway source',
    'workspace_restore': 'Restore a retained candidate and revalidate it',
}


def measurements(job, scenario, attempts, completed, duplicates, lost_write):
    data = job['data']
    binding = job.get('execution_binding') or {}
    admission = data.get('admission') or {}
    environment = admission.get('development_environment') or {}
    source, head = job['request'].get('source_sha'), data.get('head_sha')
    validation = data.get('local_validation') or {}
    checks = list(data.get('workspace_checks', {}).values())
    verdict = next((v for v in data.get('completion_verification', {}).get('requirements', [])
                    if v.get('id') == scenario and v.get('status') == 'BEHAVIOR_VERIFIED' and
                    v.get('head_sha') == head and v.get('receipt_ids')), {})
    def tested(row):
        return bool(row.get('output_sha256') and row.get('candidate_ref') and row.get('policy_sha256') == admission.get('policy_sha256'))
    # A failed and a passing test must share the check, source and policy but exercise different patches.
    repair = any(a.get('status') == 'FAILED' and type(a.get('exit_code')) is int and a['exit_code'] != 0 and tested(a)
                 and b.get('status') == 'PASSED' and b.get('exit_code') == 0 and tested(b)
                 and a.get('source_sha') == b.get('source_sha') and a.get('check_index') == b.get('check_index')
                 and a.get('patch_identity') != b.get('patch_identity') and b.get('patch_identity') == validation.get('patch_identity')
                 for a in checks for b in checks)
    restore_ids = {r['check_id'] for r in data.get('checkpoint_history', []) if r.get('action') == 'restore_candidate' and r.get('check_id')}
    boots = {a.get('boot_id') for a in attempts if a.get('boot_id')}
    graph = [a.get('graph_retrieval') or {} for a in attempts]
    kind = str(binding.get('provider_kind', '')).upper()
    evidence = {
        'workspace_kotlin_repair': environment.get('profile') == 'Android / Kotlin' and repair,
        'workspace_multi_file': len(set(data.get('changed_paths', []))) >= 2 and validation.get('status') == 'PASSED',
        'workspace_phone_reconnect': bool(verdict),  # require a typed device trace below
        'workspace_host_restart': len(boots) >= 2,
        'workspace_branch_advance': bool(verdict),  # requires the named observed-head reconciliation behavior
        'workspace_lost_write': duplicates == 0 and lost_write,
        'workspace_remote_profile': kind not in {'', 'LLAMA', 'OLLAMA'} and any(g.get('dispatched') and g.get('included_nodes', 0) > 0 for g in graph),
        'workspace_restore': validation.get('status') == 'PASSED' and any(c.get('check_id') in restore_ids and c.get('patch_identity') == validation.get('patch_identity') for c in checks),
    }
    if scenario == 'workspace_phone_reconnect':
        evidence[scenario] = any(item.get('kind') == 'device_trace' for item in verdict.get('evidence', []))
    route = dict(executor='gateway', model=job['request']['model'], profile_uid=binding.get('profile_uid'),
                 endpoint_identity=binding.get('backend_endpoint_identity'), environment=environment.get('image'))
    exact = bool(route['profile_uid'] and route['endpoint_identity'] and route['environment'] and attempts and source and head
                 and all(a.get('binding_revision') and a.get('backend_endpoint_identity') == route['endpoint_identity'] and
                         a.get('requested_model_id') == route['model'] and
                         a.get('observed_model_id') in {route['model'], (binding.get('approved_alias_mapping') or {}).get(route['model'])}
                         and a.get('observed_model_id') for a in attempts))
    counts = data.get('workspace_action_counts')
    covered = isinstance(counts, dict) and sum(counts.values()) == data.get('workspace_iterations', 0)
    return dict(repeated_workspace_actions=sum(n - 1 for n in counts.values()) if covered else None,
                operator_controls=data.get('operator_controls', 0) if environment else None,
                recovery_succeeded=bool(evidence[scenario]) if scenario in {'workspace_host_restart','workspace_lost_write','workspace_restore'} and exact and completed else None,
                passed=bool(completed and verdict and exact and evidence[scenario]), route=route,
                behavior_receipt_ids=verdict.get('receipt_ids', []), environment_profile=environment.get('profile'),
                observed_boots=len(boots), measured_attempts=len(attempts))


def validate_route(route):
    if (not isinstance(route, dict) or set(route) != {'executor', 'model', 'profile_uid', 'endpoint_identity', 'environment'}
            or route['executor'] != 'gateway' or not all(isinstance(v, str) and 0 < len(v) <= 512 for v in route.values())
            or not re.search(r'(?:@|^)sha256:[0-9a-f]{64}$', route['environment'])):
        raise ValueError('Specify one exact profile, model, endpoint identity and digest-pinned Gateway environment')
