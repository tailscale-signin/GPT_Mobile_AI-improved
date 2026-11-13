"""Explicit model preparation; passive metadata requests never load a model."""
import copy
import os

from v14.backend_observation import exception_failure, failure, model_state
from v14.execution_binding import ExecutionBindingError, configured_route


def preparation_receipt(model):
    return {'schema': 'gptmobile.gateway.model-preparation.v1', 'requestedModel': model,
            'state': 'PRECHECK_REJECTED', 'dispatchState': 'NOT_DISPATCHED', 'loadAccepted': None,
            'loadRequests': 0, 'preparationAction': 'NONE', 'modelState': 'UNKNOWN', 'selectedModel': None,
            'inferenceTested': False, 'automaticSubstitution': False, 'reasoningSettingsChanged': False}


def prepare_model(capabilities, model, *, before_dispatch=None, prior=None, expected_route=None):
    """Load or wake one advertised llama.cpp router model once, then reconcile by reading.

    The CLI invokes this directly; the explicit HTTP action first records its
    operation and passes a durable before_dispatch fence. A prior dispatch is
    reconciled only by reads. Successful loading never establishes generation.
    """
    if not isinstance(model, str) or not 1 <= len(model) <= 512 or model != model.strip() or any(ord(c) < 32 for c in model):
        raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'Select a bounded exact model identifier.', 400)
    runtime = capabilities.runtime
    provider = str(getattr(runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
    route = configured_route(runtime, provider)
    if expected_route is not None and route['runtime_config_revision'] != expected_route['runtime_config_revision']:
        raise ExecutionBindingError('MODEL_PREPARATION_CONFIGURATION_CONFLICT',
            'The configured backend changed before model preparation. Recheck the original exact selection.', 409)
    route = expected_route or route
    receipt = copy.deepcopy(prior) if prior else preparation_receipt(model)
    if provider != 'LLAMA':
        return dict(receipt, reasonCode='MODEL_PREPARATION_UNSUPPORTED',
                    nextAction='Load this exact model using the configured backend host controls, then run doctor --model again.')
    root = route['metadata_base']

    def inspect_router():
        body = capabilities._json('GET', root + '/models', params={'autoload': 'false'})
        rows = body.get('data')
        if not isinstance(rows, list) or len(rows) > 1024 or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in rows):
            raise ExecutionBindingError('EXECUTION_CAPABILITY_INVALID', 'The router model catalogue is invalid.')
        row = next((row for row in rows if row['id'] == model), None)
        if row is None:
            error = ExecutionBindingError('EXECUTION_MODEL_UNAVAILABLE', 'The exact selected model is not advertised by this router.')
            error.observation = dict(failure('model_unavailable', 'selected_model'), modelState='UNAVAILABLE')
            raise error
        state = model_state(row)
        if state == 'UNKNOWN':
            raise ExecutionBindingError('EXECUTION_CAPABILITY_UNSUPPORTED', 'This backend did not expose a supported router load state. Use its host controls to load the exact model.')
        return state

    def rejected(error):
        return dict(getattr(error, 'observation', None) or exception_failure(error), failureCode=error.code)

    try:
        state = inspect_router()
    except ExecutionBindingError as error:
        receipt['modelState'] = (getattr(error, 'observation', None) or {}).get('modelState', 'UNKNOWN')
        if receipt['loadRequests']:
            return dict(receipt, state='LOAD_REJECTED' if receipt['loadAccepted'] is False else 'LOAD_OUTCOME_UNKNOWN', reconciliationFailure=rejected(error),
                        nextAction='Recheck the exact model state; the prior load outcome is unresolved. Do not repeat the load.')
        return dict(receipt, state='PRECHECK_REJECTED', failure=rejected(error), nextAction='Repair the exact backend configuration, then recheck; no model load was sent.')
    observed = None
    if state == 'LOADED':
        capabilities.invalidate_model(route, model)
        observed = capabilities.selected_model(model, force=True)
        # Older routers keep a sleeping child marked loaded in the catalogue.
        # The exact child's fresh properties are authoritative about sleep.
        if observed.get('modelState') == 'SLEEPING':
            state = 'SLEEPING'
    receipt['modelState'] = state
    if state in {'UNLOADED', 'SLEEPING'}:
        if prior is not None:
            return dict(receipt, state='LOAD_REJECTED' if receipt['loadAccepted'] is False else 'LOAD_OUTCOME_UNKNOWN' if receipt['loadRequests'] else 'PRECHECK_REJECTED',
                        nextAction='Recheck the exact model state before another explicit preparation; this operation cannot dispatch twice.')
        waking = state == 'SLEEPING'
        receipt.update(dispatchState='DISPATCHED', loadRequests=1, state='LOAD_OUTCOME_UNKNOWN',
                       preparationAction='WAKE' if waking else 'LOAD')
        if before_dispatch is not None:
            before_dispatch(copy.deepcopy(receipt))
        try:
            # Never wrap this POST in the idempotent metadata retry policy.
            if waking:
                # A sleeping llama.cpp child still counts as running, so
                # /models/load rejects it. Empty tokenization wakes that exact
                # child without inference, unloading, or sending user content.
                # Disable router autoload if the child disappears before POST.
                result = capabilities._json('POST', root + '/tokenize',
                    body={'model': model, 'content': '', 'add_special': False}, params={'autoload': 'false'})
                accepted = result.get('tokens') == []
            else:
                result = capabilities._json('POST', root + '/models/load', body={'model': model})
                accepted = result.get('success') is True
            if accepted:
                receipt.update(loadAccepted=True, state='LOAD_ACCEPTED')
            else:
                receipt['failure'] = dict(failure('invalid_response', 'selected_model'), failureCode='EXECUTION_CAPABILITY_INVALID')
        except ExecutionBindingError as error:
            receipt['failure'] = rejected(error)
            # Only a definite rejection establishes non-acceptance. A timeout,
            # closed stream or invalid response leaves acceptance unknown.
            status = receipt['failure'].get('upstreamStatus')
            if status in {400, 401, 403, 404, 405, 409, 422, 501}:
                receipt.update(loadAccepted=False, state='LOAD_REJECTED')
        try:
            state = inspect_router()
            receipt['modelState'] = state
        except ExecutionBindingError as error:
            receipt['reconciliationFailure'] = rejected(error)
            receipt['modelState'] = (getattr(error, 'observation', None) or {}).get('modelState', 'UNKNOWN')
            return dict(receipt, state='LOAD_REJECTED' if receipt['loadAccepted'] is False else 'LOAD_OUTCOME_UNKNOWN',
                        nextAction='Check the exact model state on the backend before another load request; acceptance and readiness are separate observations.')
    if state != 'LOADED':
        unresolved = receipt['loadRequests'] and receipt['loadAccepted'] is not False
        return dict(receipt, state='WAITING_FOR_MODEL' if state == 'LOADING' else 'LOAD_OUTCOME_UNKNOWN' if unresolved else receipt['state'],
                    nextAction='Wait for the selected model to finish loading and run doctor --model. Do not repeat a load while its outcome is unresolved.')
    if observed is None or receipt['loadRequests']:
        capabilities.invalidate_model(route, model)
        observed = capabilities.selected_model(model, force=True)
    receipt['selectedModel'] = observed
    receipt['modelState'] = observed.get('modelState', state)
    if observed.get('limitsVerified') is not True:
        # A fresh properties/readiness failure wins over a stale loaded catalogue.
        # Keep only the typed failure evidence so clients can retain this blocked
        # qualification without exposing backend addresses or arbitrary prose.
        receipt['reconciliationFailure'] = {
            key: observed[key] for key in ('reasonCode', 'failureCode', 'failureCategory', 'dependency',
                'upstreamStatus', 'upstreamMessageCode', 'upstreamErrorBodyState') if key in observed}
    qualified = observed.get('limitsVerified') is True
    unresolved = receipt['loadRequests'] and receipt['loadAccepted'] is not False
    # A stale router catalogue cannot retire a durable dispatch fence. A fresh
    # sleeping, loading or failed observation leaves the host action unresolved
    # until the exact loaded allocation is qualified, even after an accepted POST.
    result_state = ('METADATA_QUALIFIED' if qualified else 'LOAD_REJECTED' if receipt['loadAccepted'] is False else
                    'WAITING_FOR_MODEL' if observed.get('modelState') == 'LOADING' else
                    'LOAD_OUTCOME_UNKNOWN' if unresolved else 'QUALIFICATION_BLOCKED')
    return dict(receipt, state=result_state,
                nextAction='Recheck the same profile and its reasoning controls in the app before resuming the saved task.')
