"""Explicit retention and optional client preconditions; never authentication."""
from v14.execution_binding import ExecutionBindingError

MUTATION_IDENTITY_HEADERS = ['X-Gateway-Installation-ID', 'X-Gateway-Principal-ID']
ORDINARY_RETENTION_POLICY = {'header': 'X-Gateway-Conversation-Retention',
                             'values': ['full', 'none'], 'default': 'none'}


def verify_mutation_identity(headers, principal, installation):
    expected_gateway = headers.get('x-gateway-installation-id')
    expected_principal = headers.get('x-gateway-principal-id')
    if expected_gateway is None and expected_principal is None:
        return
    if any(not isinstance(value, str) or not 1 <= len(value) <= 512 or
           any(ord(character) < 33 for character in value)
           for value in (expected_gateway, expected_principal)):
        raise ExecutionBindingError('CONTINUITY_IDENTITY_INVALID', 'Supply both bounded gateway and account preconditions.', 400)
    if expected_principal != principal or expected_gateway != installation():
        raise ExecutionBindingError('CONTINUITY_IDENTITY_MISMATCH',
            'The gateway installation or account changed. Refresh its identity before submitting this command.', 409)


def conversation_retention(payload, headers, profile):
    metadata = payload.get('gateway') or {}
    requested = headers.get('x-gateway-conversation-retention')
    if requested is not None and requested not in {'full', 'none'}:
        raise ValueError('Unsupported conversation retention policy')
    explicit = metadata.get('store_conversation')
    if explicit is not None:
        policy = 'full' if explicit is True else 'none'
        if requested is not None and requested != policy:
            raise ExecutionBindingError('CONVERSATION_RETENTION_CONFLICT',
                'The retention header must match the canonical conversation privacy policy.', 400)
    else:
        policy = requested or 'none'
    if policy == 'full' and not (metadata.get('profile_id') or profile.get('profile_id')):
        raise ExecutionBindingError('CONVERSATION_PROFILE_REQUIRED', 'Full conversation retention requires an original profile identity.', 400)
    return policy


def retained_policy(job):
    # Canonical jobs from the preceding release already have real conversations.
    # Older ordinary jobs without a conversation never acquire invented history.
    return job.get('conversation_retention', 'full' if job.get('conversation_id') else 'none')
