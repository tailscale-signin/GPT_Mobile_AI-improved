"""Descriptive scoring of the ten production engineering qualification jobs."""
import math
import re

from benchmarks.contracts import digest
from v14.batch import digest as workspace_digest
from v14.engineering_defaults import effective_execution_budget

VERSION = 'gateway.github-intelligence.v1'
CASE_CONTRACT_SCHEMA = 'gateway.github-intelligence-case.v4'
SUPPORTED_CASE_SCHEMAS = {CASE_CONTRACT_SCHEMA, 'gateway.github-intelligence-case.v3'}
CONVERSATION_CONTEXT_SCHEMA = 'gateway.engineering-conversation-context.v1'
USER_INSTRUCTIONS_SCHEMA = 'gateway.engineering-user-instructions.v1'
# Each dimension identifies the real jobs on which its observation is meaningful.
RUBRIC = {
    'task_completion': (25, ('unfamiliar_bug', 'source_paging', 'ci_repair', 'existing_pr',
                              'context_rotation', 'lost_mutation', 'gateway_restart', 'phone_disconnect')),
    'code_correctness': (20, ('unfamiliar_bug', 'ci_repair')),
    'repository_understanding': (15, ('source_paging', 'unfamiliar_bug')),
    'error_recovery': (15, ('lost_mutation', 'gateway_restart', 'ci_repair')),
    'context_retention': (10, ('context_rotation',)),
    'tool_efficiency': (5, ('source_paging', 'unfamiliar_bug')),
    'evidence_accuracy': (5, ('terminal_ci_blocker', 'post_merge_failure')),
    'response_quality': (5, ('terminal_ci_blocker', 'post_merge_failure')),
}


def public_failure_matches(scenario, data, report):
    """Grade the displayed claim independently of the text producer."""
    text = report.get('summary')
    if (not isinstance(text, str) or len(text) > 1000 or report.get('benchmark_status') != 'NOT_MEASURED'
            or report.get('device_performance') != 'NOT_MEASURED'):
        return False
    post_merge = scenario == 'post_merge_failure'
    head = data.get('merge_sha' if post_merge else 'head_sha')
    if not isinstance(head, str) or not re.fullmatch(r'[a-f0-9]{40}', head):
        return False
    claim = text.lower().strip()
    revisions = re.findall(r'\b[0-9a-f]{7,64}\b', claim)
    if not revisions or any(len(value) < 12 or not head.startswith(value) for value in revisions):
        return False
    # Only two evidence-bearing clauses are supported. Additional assertions do
    # not silently qualify the answer, even when the first sentence is correct.
    parts = re.split(r'[.;!\n]+\s*', claim.rstrip('.!'))
    if len(parts) != 2 or not re.match(r'(?:please\s+)?(?:review|inspect|check|examine|open|read|see|look at)\b', parts[1]):
        return False
    if not re.search(r'\b(?:checks?|results?|logs?|evidence|workflow)\b', parts[1]):
        return False
    if re.search(r"\b(not|never|no|passed|passing|green|succeeded|successful|published|deployed|released|built|probably|possibly|might|may|if|unless|despite|but|although)\b|n['’]t\b", claim):
        return False
    failed = bool(re.search(r'\b(failed|failing|failure|unsuccessful)\b', parts[0]))
    blocked = bool(re.search(r'\b(blocked|blocking)\b', parts[0]))
    if failed == blocked or not re.search(r'\b(?:ci|checks?|verification|workflow|build)\b', parts[0]):
        return False
    prs = re.findall(r'(?:\bpr\s*#?|\bpull.request\s*#?|#)(\d+)\b', claim)
    if post_merge:
        return failed and not prs and bool(re.search(r'\bpost[- ]merge\b|\bafter (?:the )?merge\b', parts[0]))
    ci = data.get('ci') or {}
    return bool(scenario == 'terminal_ci_blocker' and ci.get('head_sha') == head and type(data.get('pr')) is int
                and prs and all(pr == str(data['pr']) for pr in prs) and ci.get('pr') == data['pr']
                and ci.get('ci_verdict') == ('FAILED' if failed else 'BLOCKED')
                and ci.get('checks_failed' if failed else 'checks_blocked') is True and ci.get('checks'))


def case_observations(job, scenario, signal, completed):
    request, data = job['request'], job['data']
    admission = data.get('admission') or {}
    environment = (admission.get('development_environment') or {}).get('image')
    policy = admission.get('policy_sha256')
    criteria = request.get('acceptance') or []
    binding = job.get('execution_binding') or {}
    tool_policy = binding.get('required_capability_policy')
    permissions = data.get('initial_permission_revision')
    actor = data.get('actor_id')
    initial_base = data.get('initial_base_sha')
    expected = next((c for c in criteria if c.get('id') == scenario), None)
    # Bind actual admitted tasks, host policy, resources and tools, omitting only
    # per-arm identities. Hash the objective and requirements locally for export.
    # Persistent history is profile/binding scoped and is not frozen in this
    # contract. Its observations remain available, but cannot qualify a comparison.
    comparable = (expected and request.get('objective') and request.get('source_sha') and
                  request.get('memory_mode') == 'SESSION_ONLY' and
                  type(actor) is int and actor > 0 and re.fullmatch(r'[0-9a-f]{64}', str(permissions)) and
                  data.get('qualification_permissions_changed') is not True and
                  re.fullmatch(r'[0-9a-f]{40}', str(initial_base)) and binding.get('tool_owner') == 'GATEWAY' and binding.get('privacy_scope')
                  and isinstance(tool_policy, list) and all(isinstance(s, str) for s in tool_policy)
                  and re.fullmatch(r'[0-9a-f]{64}', str(policy)) and
                  re.search(r'(?:@|^)sha256:[0-9a-f]{64}$', str(environment)))
    settings = ('task_scope', 'context_variant', 'memory_mode', 'sampling_policy', 'execution_mode',
                'batch_policy_version', 'batch_preferences', 'specialist_roles', 'repair_attempts', 'duration_hours')
    contract = digest({'schema': CASE_CONTRACT_SCHEMA, 'repository': request['repository'], 'source': request['source_sha'],
                       'scenario': scenario, 'objective': request['objective'], 'criteria': criteria,
                       'policy': policy, 'environment': environment, 'initial_base_sha': initial_base,
                       'github_actor': actor, 'permissions': permissions, 'privacy_scope': binding['privacy_scope'],
                       'tool_owner': binding['tool_owner'], 'tool_policy': sorted(tool_policy), 'budget': effective_execution_budget(request),
                       # Match the normal engineering producer's get semantics: absent and null
                       # both send None. Preserve ordered messages and every present field, but
                       # keep their private contents inside a local digest, never the report.
                       'conversation_context': {'schema': CONVERSATION_CONTEXT_SCHEMA,
                                                'sha256': digest(job.get('conversation_context'))},
                       # The producer uses [] only when this field is absent;
                       # explicit null and all sent command identities stay distinct.
                       'user_instructions': {'schema': USER_INSTRUCTIONS_SCHEMA,
                                             'sha256': digest(data.get('user_instructions', []))},
                       'settings': {k: request.get(k) for k in settings}}) if comparable else None
    measures = {}
    terminal = job['state'] in {'COMPLETED', 'FAILED', 'CANCELLED'}
    blocked = job['state'] == 'NEEDS_INPUT' and scenario in RUBRIC['evidence_accuracy'][1] and signal
    if not (terminal or blocked):
        return {'caseContractSchema': CASE_CONTRACT_SCHEMA, 'caseContract': contract, 'measures': measures}

    head = data.get('head_sha')
    verified = data.get('completion_verification') or {}
    matched = next((c for c in verified.get('requirements', []) if c.get('id') == scenario
                    and head and c.get('head_sha') == head and c.get('receipt_ids')), {})
    if scenario in RUBRIC['task_completion'][1]:
        measures['task_completion'] = int(completed and bool(signal))
    if scenario in RUBRIC['code_correctness'][1] and expected:
        specs = expected.get('evidence') or [{'kind': 'behavior_test', 'name': scenario}]
        if any(s.get('kind') == 'behavior_test' for s in specs) and matched.get('status') in {'BEHAVIOR_VERIFIED', 'CONTRADICTED'}:
            measures['code_correctness'] = int(matched['status'] == 'BEHAVIOR_VERIFIED')
    if scenario in RUBRIC['repository_understanding'][1] and expected:
        # Count acquired, blob-bound source ranges on the selected revision.
        # Instrumentation predating this map or an incomplete history is unknown.
        reads = data.get('workspace_source_reads')
        if reads is None and data.get('initial_source_acquisitions') and data.get('workspace_iterations', 0) == 0:
            reads = {'complete': True, 'paths': [], 'ranges': [], 'total': 0}
        if isinstance(reads, dict) and reads.get('complete') is True:
            required_paths = {workspace_digest([request.get('source_sha'), path]) for path in expected.get('paths', [])}
            initial_paths = set(data.get('initial_source_paths', []))
            if required_paths:
                acquired = initial_paths.union(reads['paths'])
                measures['repository_understanding'] = len(required_paths.intersection(acquired)) / len(required_paths)
            total = data.get('initial_source_acquisitions', 0) + reads['total']
            later = set(reads['ranges'])
            initial_ranges = data.get('initial_source_ranges')
            if total > 0 and isinstance(initial_ranges, list):
                measures['tool_efficiency'] = len(set(initial_ranges).union(later)) / total
            elif total > 0 and initial_paths.isdisjoint(reads['paths']):
                # Legacy path-only receipts prove distinctness when initial and subsequent files do not overlap.
                measures['tool_efficiency'] = (len(initial_paths) + len(later)) / total
    if scenario in RUBRIC['error_recovery'][1]:
        measures['error_recovery'] = int(completed and bool(signal))
    if scenario == 'context_rotation' and data.get('context_compaction'):
        if matched.get('status') in {'BEHAVIOR_VERIFIED', 'CONTRADICTED'}:
            measures['context_retention'] = int(completed and matched['status'] == 'BEHAVIOR_VERIFIED')
    if scenario in RUBRIC['evidence_accuracy'][1]:
        report = data.get('final_report') or {}
        receipt = report.get('terminal_receipt') or {}
        if (receipt.get('schema') == 'gateway.terminal-receipt.v1' and receipt.get('job_id') == job['id']
                and report.get('job_id') == job['id'] and head):
            if scenario == 'terminal_ci_blocker':
                verdict = (data.get('ci') or {}).get('ci_verdict')
                matches_failure = verdict in {'FAILED', 'BLOCKED'} and report.get('ci_verdict') == verdict
            else:
                matches_failure = (bool(data.get('merge_sha')) and report.get('merge_sha') == data['merge_sha']
                                   and (report.get('post_merge') or {}) == data.get('post_merge')
                                   and (data.get('post_merge') or {}).get('status') == 'FAILED'
                                   and data['post_merge'].get('merge_sha') == data['merge_sha'])
            accurate = bool(signal and matches_failure and report.get('head_sha') == head and receipt.get('acceptance_verified') is False
                            and receipt.get('status') in {'FAILED', 'BLOCKED'} and report.get('status') == 'PARTIAL_UNVERIFIED')
            # A correct structured status cannot rescue a fabricated or stale public sentence.
            accurate = accurate and receipt.get('checkpoint_revision') == job.get('revision') and public_failure_matches(scenario, data, report)
            measures['evidence_accuracy'] = int(accurate)
            actionable = receipt.get('next_action') not in (None, '', 'none')
            specific = bool(report.get('blocked_steps') or (report.get('post_merge') or {}).get('status') == 'FAILED')
            measures['response_quality'] = int(accurate and actionable and specific)
    return {'caseContractSchema': CASE_CONTRACT_SCHEMA, 'caseContract': contract, 'measures': measures}


def score(report):
    if not isinstance(report, dict):
        raise ValueError('Expected a production qualification report object')
    rows = report.get('cases', [])
    if (not isinstance(rows, list) or any(not isinstance(row, dict) or not isinstance(row.get('scenario'), str)
            or not isinstance(row.get('intelligence', {}), dict)
            or not isinstance(row.get('intelligence', {}).get('measures', {}), dict) for row in rows)):
        raise ValueError('Invalid qualification cases or measurements')
    route = report.get('expected_route')
    route_valid = (isinstance(route, dict) and all(isinstance(route.get(k), str) and route[k].strip()
                   for k in ('model', 'profile_uid', 'profile_revision', 'binding_id', 'endpoint_identity'))
                   and re.fullmatch(r'[0-9a-f]{40}', str(route.get('base_sha')))
                   and re.fullmatch(r'[0-9a-f]{64}', str(route.get('settings_digest'))))
    expected = {scenario for _, scenarios in RUBRIC.values() for scenario in scenarios}
    by_case = {r['scenario']: r for r in rows}
    jobs = [row.get('job_id') for row in rows]
    schemas = {r.get('intelligence', {}).get('caseContractSchema') for r in rows}
    case_schema = next(iter(schemas)) if len(schemas) == 1 else None
    bound = (case_schema in SUPPORTED_CASE_SCHEMAS and report.get('schema') == 'gateway.production-qualification.v1' and
             report.get('execution_scope') == 'PRODUCTION_JOBS' and report.get('fixture_results_included') is False and
             report.get('comparisonEligible') is True and route_valid and len(rows) == len(expected) and set(by_case) == expected and
             all(jobs) and len(set(jobs)) == len(jobs) and all(
                 r.get('selected_route_verified') is True and r.get('comparison_route') == route and
                 r.get('measurement_complete') is True and r.get('repository') == report.get('test_repository') and
                 r.get('source_sha') == route.get('base_sha') and
                 r.get('intelligence', {}).get('caseContractSchema') == case_schema and
                 re.fullmatch(r'[0-9a-f]{64}', str(r.get('intelligence', {}).get('caseContract')))
                 for r in rows))
    dims = {}
    for name, (weight, scenarios) in RUBRIC.items():
        values = [by_case.get(s, {}).get('intelligence', {}).get('measures', {}).get(name) for s in scenarios]
        valid = [n for n in values if type(n) in (int, float) and math.isfinite(n) and 0 <= n <= 1]
        dims[name] = {'weight': weight, 'observed': len(valid), 'planned': len(values),
                      'points': weight * sum(valid) / len(values) if bound and len(valid) == len(values) else None}
    total = sum(d['points'] for d in dims.values()) if all(d['points'] is not None for d in dims.values()) else None
    identity = digest([case_schema, {s: by_case[s]['intelligence']['caseContract'] for s in sorted(expected)}]) if bound else None
    return {'schema': VERSION, 'caseContractSchema': case_schema, 'dimensions': dims, 'pointsOutOf100': total, 'taskSetDigest': identity}


def compare(left, right):
    scores = [score(report) for report in (left, right)]
    if (any(s['pointsOutOf100'] is None for s in scores) or scores[0]['taskSetDigest'] != scores[1]['taskSetDigest']
            or left['expected_route']['settings_digest'] != right['expected_route']['settings_digest']
            or left['expected_route'] == right['expected_route']
            or {r['job_id'] for r in left['cases']} & {r['job_id'] for r in right['cases']}):
        raise ValueError('Comparison needs distinct measured runs with identical tasks, source, environment, policy, settings and budgets; use session-only memory and identical admitted conversation and follow-up inputs')
    return {'schema': VERSION, 'taskSetDigest': scores[0]['taskSetDigest'],
            'arms': [{'route': report['expected_route'], **result} for report, result in zip((left, right), scores)],
            'rightMinusLeftPoints': scores[1]['pointsOutOf100'] - scores[0]['pointsOutOf100']}
