"""Versioned, content-free accounting shared by journal, clients and dispatch."""
from v14.execution_binding import fingerprint, ExecutionBindingError


def receipt(payload, budget, binding, capability, *, parent=None, stage='UNKNOWN'):
    allocated = sum(budget[name] for name in ('input_tokens', 'output_tokens', 'safety_tokens',
                                             'verification_tokens', 'final_tokens', 'recovery_tokens'))
    if allocated + budget['remaining_tokens'] != budget['context_tokens'] or allocated > capability['context_tokens']:
        raise ExecutionBindingError('EXECUTION_BUDGET_INCONSISTENT', 'The serialized request allocation is inconsistent.')
    value = dict(budget, schema='gateway.execution-budget.v1',
                 binding_id=binding['binding_id'], model_id=binding['requested_model_id'],
                 capability_revision=capability['capability_revision'], stage=stage,
                 prompt_sha256=fingerprint(payload), tool_schema_sha256=fingerprint(payload.get('tools', [])),
                 response_schema_sha256=fingerprint(payload.get('response_format')),
                 task_reservation_tokens=budget['verification_tokens'] + budget['final_tokens'] + budget['recovery_tokens'],
                 parent_receipt_id=(parent or {}).get('receipt_id'),
                 allocation_version=(parent or {}).get('allocation_version', 0) + 1,
                 limits_verified=True)
    value['receipt_id'] = fingerprint(value)
    return value
