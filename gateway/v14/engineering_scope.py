"""One interpretation of the accepted scope, including retained v1 requests."""
from v14.execution_binding import ExecutionBindingError


def accepted_scope(request):
    value = request.get('task_scope', request.get('scope', 'implement'))
    if value not in {'read', 'plan', 'implement'} or ('scope' in request and request['scope'] != value):
        raise ExecutionBindingError('TASK_SCOPE_INVALID', 'The accepted task scope is missing or contradictory; no operation was dispatched.', 409)
    return value
