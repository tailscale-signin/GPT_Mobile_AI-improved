"""One-task executable grading for an authenticated caller's selected model.

The caller owns generation. Receipts remain explicitly client observations; this
API never substitutes the Gateway model or treats requested settings as provider
acknowledgements. Model proposals remain in the private journal, not readbacks.
"""
import re

from benchmarks import fixtures
from benchmarks.contracts import content_digest, digest
from benchmarks.grader_manifest import PROPOSAL_SCHEMA

GENERATION_FIELDS = frozenset({'model', 'profile_uid', 'profile_revision', 'settings_digest', 'provider_kind',
                               'route_identity', 'request_ids', 'observed_model', 'identity_verified',
                               'input_tokens', 'output_tokens', 'duration_ms', 'app_commit',
                               'settings_confirmed', 'acknowledged_fields', 'reasoning_tokens', 'usage_complete'})


def generation_receipt(value):
    if not isinstance(value, dict) or set(value) - GENERATION_FIELDS:
        raise ValueError('Unsupported generation receipt')
    result = dict(value)
    for key in ('model', 'profile_uid', 'profile_revision', 'settings_digest', 'provider_kind', 'route_identity'):
        text = value.get(key)
        if not isinstance(text, str) or not 1 <= len(text) <= 512 or text != text.strip() or any(ord(c) < 32 for c in text):
            raise ValueError('Complete selected-profile generation identity is required')
    for key in ('input_tokens', 'output_tokens', 'reasoning_tokens', 'duration_ms'):
        number = value.get(key)
        if number is not None and (type(number) is not int or number < 0):
            raise ValueError('Invalid generation measurements')
    for key in ('identity_verified', 'settings_confirmed', 'usage_complete'):
        if key in value and type(value[key]) is not bool:
            raise ValueError('Invalid generation authority')
    for key in ('request_ids', 'acknowledged_fields'):
        items = value.get(key, [])
        if not isinstance(items, list) or len(items) > 32 or any(not isinstance(item, str) or not 1 <= len(item) <= 512 for item in items):
            raise ValueError('Bounded generation receipts are required')
    for key in ('observed_model', 'app_commit'):
        text = value.get(key)
        if text is not None and (not isinstance(text, str) or len(text) > 512):
            raise ValueError('Invalid observed identity')
    return result


def public_task(task, suite_seed=17):
    from benchmarks.context_fixture import project_public_context
    return {
        **project_public_context(task),
        'schema': 'gateway.coding-task-public.v2', 'id': task['id'], 'task_id': task['id'],
        'problemId': task['problemId'], 'family': task['family'], 'title': task['title'],
        'taskDigest': task['taskDigest'], 'fixture_digest': task['taskDigest'], 'manifestDigest': task['manifestDigest'],
        'graderDigest': task['graderDigest'], 'hiddenTestsDigest': task['hiddenTestsDigest'],
        'oracleDigest': task['oracleDigest'], 'proposalSchemaDigest': task['proposalSchemaDigest'],
        'seed': suite_seed, 'fixtureSeed': task.get('seed', suite_seed), 'language': task.get('language', 'BOUNDED_PYTHON_SUBSET'),
        'executionScope': task.get('executionScope', 'ISOLATED_PYTHON_CONTRACT'), 'prompt': task['prompt'] if 'prompt' in task else fixtures.prompt(task),
        'outputSchema': task.get('outputSchema', PROPOSAL_SCHEMA), 'taskPack': task.get('taskPack', 'python'),
    }


def select_task(task_id, seed=17, task_pack='python'):
    if task_pack == 'python':
        return fixtures.find_task(task_id, seed)
    if task_pack == 'repository':
        from benchmarks.repository_tasks import task_spec
        return task_spec(task_id, seed)
    raise ValueError('Unknown benchmark task pack')


def environment_receipt(settings, task_pack, grader_digest):
    native = task_pack == 'repository'
    environment = dict(image=settings.get('repository_image', '') if native else settings['sandbox_image'],
                       seconds=min(180, max(60, settings['sandbox_seconds'])) if native else settings['sandbox_seconds'],
                       taskPack=task_pack, grader=grader_digest, network='none', cpus=2,
                       memoryMiB=1024 if native else 512, pids=96 if native else 64,
                       temporaryMiB=256 if native else 64, readOnly=True, uid=65534,
                       languages=['kotlin', 'sqlite', 'actions'] if native else ['BOUNDED_PYTHON_SUBSET'])
    return {'environmentDigest': digest(environment), 'environment': environment}


def evaluate_request(value):
    keys = {'request_id', 'task_id', 'seed', 'fixture_digest', 'grader_digest', 'proposal_digest', 'environment_digest', 'preset', 'proposal', 'generation_receipt',
            'comparison_context', 'evidence_lane', 'repository', 'base_sha', 'cohort', 'task_pack'}
    if not isinstance(value, dict) or set(value) - keys:
        raise ValueError('Unsupported executable evaluation request')
    seed = value.get('seed', 17)
    if type(seed) is not int or not 0 <= seed <= 2 ** 31 - 1:
        raise ValueError('Invalid fixture seed')
    task_pack = value.get('task_pack', 'python')
    task = select_task(value.get('task_id'), seed, task_pack)
    if value.get('fixture_digest') != task['taskDigest']:
        raise ValueError('TASK_REVISION_CHANGED')
    if 'grader_digest' in value and value['grader_digest'] != task['graderDigest']:
        raise ValueError('GRADER_REVISION_CHANGED')
    if value.get('preset', 'quick') not in {'quick', 'standard', 'deep'}:
        raise ValueError('Unknown coding preset')
    proposal = value.get('proposal')
    if not isinstance(proposal, str) or len(proposal.encode()) > 65536:
        raise ValueError('A bounded candidate proposal is required')
    if 'proposal_digest' in value and value['proposal_digest'] != content_digest(proposal):
        raise ValueError('PROPOSAL_IDENTITY_CHANGED')
    receipt = generation_receipt(value.get('generation_receipt'))
    request = {key: value[key] for key in ('request_id', 'comparison_context', 'repository', 'base_sha', 'cohort', 'environment_digest') if key in value}
    request.update(suite='coding_submission', preset='quick', seed=seed, model=receipt['model'],
                   task_id=task['id'], fixture_digest=task['taskDigest'], grader_digest=task['graderDigest'], proposal_digest=content_digest(proposal),
                   generation_receipt=receipt, evidence_lane=value.get('evidence_lane', 'EXECUTABLE'), task_pack=task_pack)
    if request['evidence_lane'] not in {'EXECUTABLE', 'LIVE_GITHUB'}:
        raise ValueError('Unsupported evidence lane')
    if request['evidence_lane'] == 'LIVE_GITHUB':
        if not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', str(request.get('repository', ''))):
            raise ValueError('A designated disposable repository is required')
        if not re.fullmatch(r'[a-f0-9]{40}', str(request.get('base_sha', ''))):
            raise ValueError('An immutable disposable base revision is required')
        if not re.fullmatch(r'[A-Za-z0-9_-]{8,96}', str(request.get('cohort', ''))):
            raise ValueError('A bounded comparison cohort identity is required')
    return request, proposal


def task_summary(case):
    qualified = case.get('graded') is True and case.get('qualified') is True
    outcome_known = qualified or case.get('outcomeKnown') is True
    score_known = qualified and case.get('score') is not None
    resolved = case.get('resolved') is True
    return {'version': 'coding-evidence-v2', 'contractVersion': 'coding-evidence-v2',
            'maximum': 1000, 'score': case.get('score') if score_known else None, 'qualified': qualified,
            'outcomeComplete': outcome_known, 'knownOutcomes': int(outcome_known),
            'resolved': int(resolved), 'planned': 1, 'trialCount': 1, 'uniqueProblemCount': 1,
            'initialResolved': int(resolved), 'finalResolved': int(resolved), 'bestResolved': int(resolved),
            'firstAttemptRate': float(resolved) if outcome_known else None,
            'finalAttemptRate': float(resolved) if outcome_known else None,
            'bestWithinBudgetRate': float(resolved) if outcome_known else None,
            'repairBudget': 0,
            'executionScope': case.get('executionScope', 'ISOLATED_PYTHON_CONTRACT'),
            'liveGitHub': case.get('liveGitHub', 'NOT_EXECUTED'),
            'graderDigest': case.get('graderDigest'), 'taskDigest': case.get('taskDigest'),
            'modelIdentityScope': 'AUTHENTICATED_CLIENT_OBSERVATION',
            'scope': 'one_immutable_task',
            'scoreQualification': 'VERIFIED' if score_known else 'OUTCOME_ONLY' if outcome_known else 'INCOMPLETE',
            'label': 'One verified executable task' if qualified else 'Observed model failure' if outcome_known else 'Incomplete executable task evidence'}
