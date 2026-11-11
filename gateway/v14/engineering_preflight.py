"""Read-only, bounded admission checks for producible engineering acceptance.

Host templates may select an acceptance contract for a recognized objective. They
never confer completion: only fresh, revision-bound behavior receipts do that.
"""
import hashlib
import json
import os
import re
from pathlib import Path

from v14.engineering_scope import accepted_scope
from v14.engineering_validation import load_policy, run_bounded
from v14.execution_binding import ExecutionBindingError


class EngineeringPreflightError(ExecutionBindingError):
    def __init__(self, result):
        blocker = result['blockers'][0]
        super().__init__(blocker['reason_code'], blocker['message'], 412)
        self.result = result


def configuration_status():
    path = os.getenv('GATEWAY_ENGINEERING_VALIDATION_POLICY', '')
    return 'configured_not_verified' if path and Path(path).is_file() else 'missing'


def preflight(request, *, runner=None):
    from v14.engineering import validate_request
    runner = runner or run_bounded
    request = validate_request(request)
    result = {'schema': 'github.engineering-admission.v1', 'ready': False, 'blockers': [],
              'checks': {}, 'acceptance': request.get('acceptance', []),
              'objective_sha256': hashlib.sha256(request['objective'].encode()).hexdigest(),
              'source_sha': request.get('source_sha'), 'execution_owner': 'NOT_ADMITTED',
              'acceptance_source': 'REQUEST', 'coverage_scope': 'Only the listed requirements and mapped behavior tests; model prose and green CI alone are not acceptance evidence.'}

    def block(dependency, code, message, recovery):
        result['checks'][dependency] = 'blocked'
        result['blockers'].append({'dependency': dependency, 'reason_code': code, 'failed_stage': 'admission',
                                   'message': message, 'recovery_action': recovery, 'dispatch_state': 'not_dispatched'})

    if accepted_scope(request) != 'implement':
        result.update(ready=True, acceptance_source='NOT_REQUIRED_FOR_READ_OR_PLAN')
        return result
    try:
        policy, mirror, image, commands, coverage = load_policy(request['repository'])
    except (OSError, ValueError, KeyError, TypeError):
        block('validation_policy', 'VALIDATION_POLICY_REQUIRED',
              'Configure a valid host-approved validation policy for this repository before implementation.', 'CONFIGURE_VALIDATION_POLICY')
        return result
    result['checks']['validation_policy'] = 'ready'
    result['policy_sha256'] = hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if not result['acceptance']:
        templates = policy.get('acceptance_templates', [])
        matches = []
        valid = isinstance(templates, list) and len(templates) <= 32
        for template in templates if valid else []:
            if not isinstance(template, dict) or set(template) != {'objectives', 'requirements'}:
                valid = False
                break
            terms = template['objectives']
            if not isinstance(terms, list) or not 1 <= len(terms) <= 16 or any(not isinstance(term, str) or not 3 <= len(term.strip()) <= 16000 for term in terms):
                valid = False
                break
            objective = ' '.join(request['objective'].casefold().split())
            if objective in {' '.join(term.casefold().split()) for term in terms}:
                matches.append(template['requirements'])
        if valid and len(matches) == 1:
            try:
                result['acceptance'] = validate_request(dict(request, acceptance=matches[0])).get('acceptance', [])
                result['acceptance_source'] = 'HOST_OBJECTIVE_TEMPLATE'
            except (ValueError, TypeError, KeyError):
                valid = False
        if not valid or not result['acceptance'] or len(matches) > 1:
            block('acceptance', 'ACCEPTANCE_CONTRACT_REQUIRED',
                  'This objective has no unambiguous testable acceptance contract. Supply explicit requirements or configure one matching host objective template.', 'DEFINE_ACCEPTANCE_REQUIREMENTS')
    allowed_checks = policy.get('allowed_checks', [])
    if not isinstance(allowed_checks, list) or not 1 <= len(allowed_checks) <= 256 or any(not isinstance(name, str) or not 1 <= len(name) <= 400 for name in allowed_checks):
        allowed_checks = []
        block('ci_checks', 'VALIDATION_CHECKS_REQUIRED', 'Declare the exact CI check names supported by this repository validation policy.', 'CONFIGURE_CI_CHECK_MAPPING')
    normalized = []
    for requirement in result['acceptance']:
        if any(name not in allowed_checks for name in requirement['checks']):
            block('ci_checks', 'VALIDATION_CHECK_UNSUPPORTED', 'No approved CI check mapping covers requirement: ' + requirement['id'], 'CONFIGURE_CI_CHECK_MAPPING')
        producers = [item for item in coverage if item['requirement_id'] == requirement['id']]
        expected = requirement.get('evidence') or [{'kind': 'behavior_test', 'name': item['case_id']} for item in producers]
        if not expected or any(spec['kind'] != 'behavior_test' or not any(item['case_id'] == spec['name'] for item in producers) for spec in expected):
            block('acceptance', 'ACCEPTANCE_PRODUCER_REQUIRED',
                  'No approved behavior evidence producer covers requirement: ' + requirement['id'], 'CONFIGURE_ACCEPTANCE_MAPPING')
        normalized.append(dict(requirement, evidence=expected))
    result['acceptance'] = normalized
    if not any(item['dependency'] == 'acceptance' for item in result['blockers']):
        result['checks']['acceptance'] = 'ready'
    head = request.get('source_sha', '')
    if not isinstance(head, str) or not re.fullmatch('[a-f0-9]{40}', head):
        block('source_revision', 'SOURCE_REVISION_REQUIRED', 'Pin the exact source commit before implementation admission.', 'REFRESH_SOURCE_REVISION')
    else:
        try:
            bare = runner(['git', '--git-dir=' + str(mirror), 'rev-parse', '--is-bare-repository'], mirror, 10, maximum=4096)
            commit = runner(['git', '--git-dir=' + str(mirror), 'cat-file', '-e', head + '^{commit}'], mirror, 10, maximum=4096)
            if bare['exit_code'] != 0 or bare.get('output_tail', '').strip() != 'true' or commit['exit_code'] != 0:
                raise ValueError('Pinned mirror unavailable')
            result['checks']['source_revision'] = 'ready'
        except (OSError, ValueError, TimeoutError):
            block('source_revision', 'VALIDATION_REVISION_UNAVAILABLE', 'The approved bare mirror must contain the exact source commit.', 'PROVISION_PINNED_MIRROR')
    try:
        probe = runner(['docker', 'image', 'inspect', image, '--format={{.Id}}'], mirror, 15, maximum=4096)
        if probe['exit_code'] != 0 or not re.fullmatch(r'sha256:[a-f0-9]{64}', probe.get('output_tail', '').strip()):
            raise ValueError('Image unavailable')
        result['checks']['validation_runtime'] = 'ready'
    except (OSError, ValueError, TimeoutError):
        block('validation_runtime', 'VALIDATION_RUNTIME_UNAVAILABLE', 'Start Docker and pre-provision the approved digest-pinned validation image.', 'PROVISION_VALIDATION_RUNTIME')
    result['ready'] = not result['blockers']
    return result
