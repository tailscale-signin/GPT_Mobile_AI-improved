"""Explicit adoption of an authorized, exact existing pull request."""
from v14.execution_binding import ExecutionBindingError


def verify_pull(request, pull, selection, *, head=None, base=None):
    repository = request['repository'].lower()
    head, base = head or selection['head_sha'], base or selection['base_sha']
    if (pull.get('number') != selection['number'] or pull.get('state') != 'open' or pull.get('merged') is True
            or pull.get('head', {}).get('sha') != head or pull.get('base', {}).get('sha') != base
            or pull.get('head', {}).get('ref') != request['branch'] or pull.get('base', {}).get('ref') != request['base']
            or any((pull.get(side, {}).get('repo') or {}).get('full_name', '').lower() != repository for side in ('head', 'base'))):
        raise ExecutionBindingError('EXISTING_PR_CHANGED', 'The selected PR, repository, head or base changed. Review its current identity before adoption.')
    return {'pr': pull['number'], 'pr_url': pull['html_url']}
