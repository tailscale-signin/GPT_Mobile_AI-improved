"""Allowlisted reasons for state/input guards; private exception text stays on the host."""

_REASONS = {
    'Unsupported engineering request fields': ('ENGINEERING_REQUEST_FIELDS_INVALID', 'request', 'Remove unsupported request fields and retry the saved draft.'),
    'Unsupported engineering budget': ('ENGINEERING_BUDGET_INVALID', 'execution_budget', 'Review the supported execution budget fields.'),
    'Invalid engineering budget': ('ENGINEERING_BUDGET_INVALID', 'execution_budget', 'Review execution budget bounds before retrying.'),
    'Invalid branch': ('ENGINEERING_BRANCH_INVALID', 'branch', 'Select a valid branch and retry the saved draft.'),
    'Select a valid repository': ('ENGINEERING_REPOSITORY_INVALID', 'repository', 'Select the intended owner and repository.'),
    'Invalid conversation revision': ('ENGINEERING_REVISION_INVALID', 'expected_revision', 'Refresh the original conversation revision before resuming.'),
    'Missing exact job revision': ('ENGINEERING_REVISION_REQUIRED', 'revision', 'Refresh the exact saved job before confirming this action.'),
    'Unsupported task scope': ('ENGINEERING_TASK_SCOPE_INVALID', 'task_scope', 'Select a supported task scope.'),
    'Base-branch implementation requires explicit task scope': ('ENGINEERING_TASK_SCOPE_REQUIRED', 'task_scope', 'Update the app to send explicit implementation authority before starting work from the base branch.'),
    'Base-branch implementation requires a pinned source revision': ('ENGINEERING_SOURCE_REVISION_REQUIRED', 'source_sha', 'Refresh the selected branch to an exact source revision before starting implementation.'),
    'Source revision must be an exact commit SHA': ('ENGINEERING_SOURCE_REVISION_INVALID', 'source_sha', 'Refresh the selected source revision; a full commit SHA is required.'),
    'Invalid engineering profile scope': ('ENGINEERING_PROFILE_INVALID', 'profile_id', 'Recheck the selected profile and its saved identity.'),
    'Invalid engineering memory mode': ('ENGINEERING_MEMORY_MODE_INVALID', 'memory_mode', 'Select a supported memory mode.'),
    'Unsupported Gateway execution mode': ('ENGINEERING_MODE_INVALID', 'execution_mode', 'Select a supported execution mode.'),
    'Unsupported batch policy protocol': ('ENGINEERING_BATCH_POLICY_INVALID', 'batch_policy_version', 'Update the app and gateway to compatible batch policy versions.'),
    'Invalid batch preferences': ('ENGINEERING_BATCH_POLICY_INVALID', 'batch_preferences', 'Review the supported batch preferences.'),
    'Expected a JSON object': ('ENGINEERING_REQUEST_FORMAT_INVALID', 'request', 'Send a structured engineering request.'),
    'Engineering request exceeds 96 KiB': ('ENGINEERING_REQUEST_TOO_LARGE', 'request', 'Reduce the request and retain large evidence separately.'),
}


def state_or_input_rejection(error, stage=None):
    reason, field, message = _REASONS.get(str(error), (
        'ENGINEERING_INPUT_OR_STATE_REJECTED', None,
        'The gateway rejected a request or state prerequisite. Retain the draft and inspect the exact request and saved job; a conversation change has not been established.'
    ))
    allowed_stages = {'engineering_guard', 'request_validation', 'provider_admission', 'binding_admission',
                      'message_admission', 'conversation_admission', 'execution_admission'}
    known_stage = stage if stage in allowed_stages else ('request_validation' if field else 'engineering_guard')
    return {'code': 'engineering_state_or_input_changed', 'reasonCode': reason,
            'invalid_fields': [field] if field else [],
            'failed_stage': known_stage, 'retryable': False, 'message': message}
