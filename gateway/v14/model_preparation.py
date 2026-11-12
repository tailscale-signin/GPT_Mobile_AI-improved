"""Explicit model preparation; passive metadata requests never load a model."""
import copy
import os

from v14.backend_observation import exception_failure, failure, model_state
from v14.execution_binding import ExecutionBindingError, configured_route


def preparation_receipt(model):
    return {'schema': 'gptmobile.gateway.model-preparation.v1', 'requestedModel': model,
            'state': 'PRECHECK_REJECTED', 'dispatchState': 'NOT_DISPATCHED', 'loadAccepted': None,
            'loadRequests': 0, 'modelState': 'UNKNOWN', 'selectedModel': None,
            'inferenceTested': False, 'automaticSubstitution': False, 'reasoningSettingsChanged': False}


def prepare_model(capabilities, model, *, before_dispatch=None, prior=None, expected_route=None):
    """Load one advertised llama.cpp router model once, then reconcile by reading.

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
    receipt['modelState'] = state
    if state in {'UNLOADED', 'SLEEPING'}:
        if prior is not None:
            return dict(receipt, state='LOAD_REJECTED' if receipt['loadAccepted'] is False else 'LOAD_OUTCOME_UNKNOWN' if receipt['loadRequests'] else 'PRECHECK_REJECTED',
                        nextAction='Recheck the exact model state before another explicit preparation; this operation cannot dispatch twice.')
        receipt.update(dispatchState='DISPATCHED', loadRequests=1, state='LOAD_OUTCOME_UNKNOWN')
        if before_dispatch is not None:
            before_dispatch(copy.deepcopy(receipt))
        try:
            # Never wrap this POST in the idempotent metadata retry policy.
            result = capabilities._json('POST', root + '/models/load', body={'model': model})
            if result.get('success') is True:
                receipt.update(loadAccepted=True, state='LOAD_ACCEPTED')
            else:
                receipt['failure'] = {'failureCode': 'EXECUTION_CAPABILITY_INVALID', 'dependency': 'selected_model'}
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
    capabilities.invalidate_model(route, model)
    observed = capabilities.selected_model(model, force=True)
    receipt['selectedModel'] = observed
    receipt['modelState'] = observed.get('modelState', state)
    return dict(receipt, state='METADATA_QUALIFIED' if observed.get('limitsVerified') is True else 'QUALIFICATION_BLOCKED',
                nextAction='Recheck the same profile and its reasoning controls in the app before resuming the saved task.')
