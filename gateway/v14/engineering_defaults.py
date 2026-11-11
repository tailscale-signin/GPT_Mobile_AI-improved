"""Frozen new-admission defaults; retained jobs keep their accepted limits."""

DEFAULT_DURATION_HOURS = 24
DEFAULT_EXECUTION_BUDGET = {'model_calls': 512, 'model_tokens': 2097152,
                            'verification_calls': 2, 'final_calls': 1, 'final_seconds': 30}
EXECUTION_BUDGET_LIMITS = {'model_calls': 512, 'model_tokens': 8388608,
                         'verification_calls': 256, 'final_calls': 256, 'final_seconds': 900}
LEGACY_EXECUTION_BUDGET = {'model_calls': 128, 'model_tokens': 4194304,
                         'verification_calls': 2, 'final_calls': 1, 'final_seconds': 30}


def new_request_defaults(request):
    return dict(request, duration_hours=request.get('duration_hours', DEFAULT_DURATION_HOURS),
                execution_budget=dict(DEFAULT_EXECUTION_BUDGET, **request.get('execution_budget', {})))


def retained_request_defaults(request, accepted):
    """An omitted value on a retry means the original allowance, never today's default."""
    result = dict(request)
    if 'duration_hours' not in result:
        result['duration_hours'] = accepted.get('duration_hours', 3)
    if 'execution_budget' in accepted:
        result['execution_budget'] = dict(accepted['execution_budget'], **result.get('execution_budget', {}))
    return result


def effective_execution_budget(request):
    return dict(LEGACY_EXECUTION_BUDGET, **request.get('execution_budget', {}))
