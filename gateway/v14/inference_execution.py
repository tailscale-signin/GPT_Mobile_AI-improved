"""Selected-only model admission and receipts at every physical POST boundary.

The context is deliberately inherited by bounded transport repairs. A logical
call may contain two physical requests; each receives its own fenced receipt.
"""
from __future__ import annotations

import copy
import os
import time
import uuid

from v14.execution_binding import ExecutionBindingError, canonical, fingerprint
from v14.inference_budget import fit_engineering_payload, plan_tokens, stage_reserves
from v14.inference_scheduler import admission_context, bound_invocation
from v14.debug import diagnostic_execution_event
from gateway_v13_runtime import GatewayContractError, normalize_backend_messages


def before_physical_dispatch(url, payload):
    invocation = bound_invocation.get()
    if invocation is not None:
        invocation.before_dispatch(url, payload)


def observe_physical_response(data, response):
    invocation = bound_invocation.get()
    if invocation is not None:
        invocation.observe_response(data, response)


def selected_dispatch_headers():
    invocation = bound_invocation.get()
    return {} if invocation is None else {
        'X-Gateway-Execution-Binding': invocation.binding['binding_id'],
        'X-Gateway-Execution-Owner': fingerprint([invocation.job['principal'], invocation.job['_owner_epoch']]),
        'X-Gateway-Execution-Attempt': invocation.current['attempt_id'],
    }


class BoundModelInvocation:
    def __init__(self, service, job, role):
        self.service, self.job, self.role = service, job, role
        self.runtime, self.store = service.store.runtime, service.store
        self.binding, self.route, self.capability = service.validate_execution(job)
        self.current, self.ticket, self.cancellation = None, None, None
        self.physical_count, self.observed_count = 0, 0
        self.initial_budget = self.compaction = None
        self.started = time.monotonic()

    def stopped(self):
        return self.cancellation is not None and self.cancellation.is_set()

    def validate_queue(self):
        if self.stopped():
            raise InterruptedError('Engineering inference cancelled before admission')
        if self.role not in {'final summary', 'final report'} and self.store.now() >= self.job['deadline'] - self.job['request'].get('execution_budget', {}).get('final_seconds', 30):
            raise ExecutionBindingError('EXECUTION_FINAL_TIME_RESERVED', 'The remaining execution time is reserved for durable verification and reporting.')
        self.binding, self.route, self.capability = self.service.validate_execution(self.job, force=True)

    def queue_receipt(self, ticket, scheduler):
        self.ticket = ticket
        self.job['data']['inference_queue'] = {'ticket_id': ticket.id, 'lane': ticket.lane,
            'wait_ms': max(0, int((time.monotonic() - ticket.created) * 1000)),
            'state': ticket.state, 'memory': scheduler.snapshot()['memory_observation']}

    def new_receipt(self):
        rejected_override = os.getenv('GATEWAY_ENGINEERING_REVIEW_MODEL')
        return {'schema_version': 1, 'attempt_id': 'attempt_' + uuid.uuid4().hex,
                'job_id': self.job['id'], 'binding_id': self.binding['binding_id'],
                'binding_revision': fingerprint(self.binding), 'role': self.role,
                'phase': self.job['state'], 'job_revision': self.job['revision'],
                'boot_id': self.service.bindings.boot_id, 'owner_epoch': self.job['_owner_epoch'],
                'lease_id': self.job['_execution_lease'], 'state': 'PRECHECK', 'dispatched': False,
                'requested_model_id': self.binding['requested_model_id'], 'observed_model_id': None,
                'provider_kind': self.binding['provider_kind'],
                'backend_endpoint_identity': self.binding['backend_endpoint_identity'],
                'identity_status': 'UNAVAILABLE', 'observed_at_ms': int(time.time() * 1000),
                'capability_revision': self.capability['capability_revision'],
                'hardware': self.capability.get('hardware') or {'status': 'NOT_MEASURED'},
                'backend_build': self.capability.get('backend_build'),
                'model_revision': self.capability.get('model_revision'),
                'template_digest': self.capability.get('template_digest'),
                'cache': {'enabled': False, 'reason': 'PRIVATE_NAMESPACE_NOT_QUALIFIED',
                          'identity': fingerprint([self.binding, self.job['principal'], self.capability.get('template_digest')])},
                'rejected_configuration': 'SELECTED_PROFILE_OVERRIDE_REJECTED' if rejected_override and rejected_override != self.binding['requested_model_id'] else None}

    def persist(self):
        self.store.record_model_attempt(self.job, self.current)
        self.job['data']['latest_execution_receipt'] = copy.deepcopy(self.current)
        diagnostic_execution_event(self.runtime, self.job['principal'], 'MODEL_EXECUTION',
            binding_id=self.binding['binding_id'], job_id=self.job['id'], state=self.current['state'],
            error_code=self.current.get('error_code'), dispatched=self.current['dispatched'],
            local_duration_ms=max(0, int((time.monotonic() - self.started) * 1000)),
            bounded_attributes={'model_digest': fingerprint(self.binding['requested_model_id']),
                                'endpoint_digest': self.binding['backend_endpoint_identity'],
                                'capability_digest': self.capability['capability_revision']})

    def before_dispatch(self, url, payload):
        self.current = self.new_receipt()
        try:
            self.validate_queue()
            if url != self.route['completion_url'] or payload.get('model') != self.binding['requested_model_id']:
                raise ExecutionBindingError('EXECUTION_DESTINATION_MISMATCH', 'The physical inference destination differs from the accepted selection.')
            if payload.get('tools') or payload.get('cache_prompt') is not False:
                raise ExecutionBindingError('EXECUTION_OWNER_MISMATCH', 'Bounded engineering inference cannot dispatch delegated tools or unscoped prompt caches.')
            selected = self.binding['generation_parameters']
            for key in ('temperature', 'top_p', 'top_k', 'repeat_penalty', 'seed'):
                if key in selected and payload.get(key) != selected[key]:
                    raise ExecutionBindingError('EXECUTION_PARAMETERS_CHANGED', 'A transport attempted to replace a selected generation parameter.')
            if 'reasoning' in selected and payload.get('chat_template_kwargs', {}).get('enable_thinking') != selected['reasoning']:
                raise ExecutionBindingError('EXECUTION_PARAMETERS_CHANGED', 'A transport attempted to change the selected reasoning policy.')
            budget = plan_tokens(payload, self.capability, lambda request: self.service.capabilities.count(request, self.capability), **stage_reserves(self.capability))
            self.current.update(budget=budget, capability_revision=self.capability['capability_revision'],
                                state='DISPATCHED', dispatched=True,
                                reserved_tokens=budget['input_tokens'] + budget['output_tokens'])
            # The store checks the same lease and total run budget in the write
            # transaction. Persist intent before the socket can send bytes.
            self.persist()
            self.physical_count += 1
            if self.ticket is not None:
                self.service.scheduler.transition(self.ticket, 'PREFILL')
        except Exception as error:
            self.current.update(state='PRECHECK_REJECTED', dispatched=False,
                                error_code=getattr(error, 'code', type(error).__name__))
            self.persist()
            raise

    def observe_response(self, data, response):
        if self.current is None or not self.current['dispatched']:
            raise ExecutionBindingError('EXECUTION_RECEIPT_MISSING', 'No fenced physical dispatch receipt exists for this response.')
        observed = data.get('model') if isinstance(data, dict) else None
        self.current.update(observed_model_id=observed if isinstance(observed, str) and len(observed) <= 256 else None,
                            http_status=getattr(response, 'status_code', None),
                            state='COMPLETED', completed_at_ms=int(time.time() * 1000))
        allowed = {self.binding['requested_model_id']}
        aliases = self.binding.get('approved_alias_mapping') or {}
        if aliases.get(self.binding['requested_model_id']) and self.binding['requested_model_id'] in (self.capability.get('model_aliases') or []):
            allowed.add(aliases[self.binding['requested_model_id']])
        self.current['identity_status'] = 'VERIFIED' if observed in allowed else 'UNAVAILABLE' if not observed else 'CONTRADICTED'
        usage = data.get('usage') if isinstance(data, dict) else None
        if isinstance(usage, dict):
            self.current['usage'] = {key: value for key, value in usage.items() if key in {'prompt_tokens', 'completion_tokens', 'total_tokens'} and type(value) is int and 0 <= value <= 16777216}
            self.current['usage_origin'] = 'PROVIDER_REPORTED'
        self.observed_count += 1
        if self.current['identity_status'] != 'VERIFIED':
            self.job['data']['execution_integrity'] = self.current['identity_status']
            self.current.update(state='FAILED', error_code='EXECUTION_RESPONSE_IDENTITY_' + self.current['identity_status'])
            self.persist()
            error = ExecutionBindingError(self.current['error_code'], 'The backend response does not prove the accepted model identity. Dependent writes are paused.')
            error.dispatched = True
            raise error
        try:
            response_reference = self.service.artifacts.put_json(self.job, data, 'model-response')
        except Exception as error:
            self.current.update(state='FAILED', error_code=getattr(error, 'code', 'EXECUTION_RESPONSE_PERSISTENCE_FAILED'))
            self.persist()
            raise
        self.current['response_ref'] = response_reference
        self.job['data']['execution_integrity'] = 'VERIFIED'
        self.persist()
        if self.ticket is not None:
            self.service.scheduler.transition(self.ticket, 'DECODING')

    def execute(self, payload, cancellation):
        self.cancellation = cancellation
        if getattr(self.runtime, 'SELECTED_ONLY_PHYSICAL_GUARDS', False) is not True:
            raise ExecutionBindingError('SELECTED_ONLY_TRANSPORT_UNSUPPORTED', 'This backend transport cannot enforce a binding at each physical request.')
        request = copy.deepcopy(payload)
        request['messages'] = normalize_backend_messages(request.get('messages', []))
        request.update(model=self.binding['requested_model_id'], cache_prompt=False)
        selected = self.binding['generation_parameters']
        for key in ('temperature', 'top_p', 'top_k', 'repeat_penalty', 'max_tokens', 'seed'):
            if key in selected:
                request[key] = selected[key]
        if 'context_tokens' in selected and selected['context_tokens'] != self.capability['context_tokens']:
            raise ExecutionBindingError('EXECUTION_CONTEXT_CONFIGURATION_CHANGED', 'The selected context allocation differs from the running model allocation.')
        if 'reasoning' in selected:
            request['chat_template_kwargs'] = {'enable_thinking': selected['reasoning']}
        source_reference = self.service.artifacts.put_json(self.job, request, 'model-input')
        self.job['data']['latest_model_input'] = source_reference
        request, self.initial_budget, self.compaction = fit_engineering_payload(request, self.capability, lambda value: self.service.capabilities.count(value, self.capability))
        request['max_tokens'] = self.initial_budget['output_tokens']
        if self.compaction:
            self.compaction['source_reference'] = source_reference
            self.job['data']['context_compaction'] = self.compaction
        marker = bound_invocation.set(self)
        admission = admission_context.set({'byte_length': len(canonical(request).encode()),
            'tokens': self.initial_budget['input_tokens'] + self.initial_budget['output_tokens'], 'memory_bytes': 0})
        response = None
        try:
            response = self.runtime.http_post(self.route['completion_url'], json=request,
                _gateway_job_mode='background', _gateway_cancel_event=cancellation)
            response.raise_for_status()
            data = response.json()
            if self.physical_count == 0 or self.observed_count != self.physical_count:
                raise ExecutionBindingError('EXECUTION_RECEIPT_MISSING', 'The transport returned without a verified receipt for every physical model request.')
            return data
        except Exception as error:
            if self.current is None:
                self.current = self.new_receipt()
            if self.current['state'] in {'PRECHECK', 'DISPATCHED'}:
                rejected = isinstance(error, GatewayContractError) and error.details.get('dispatch_state') == 'backend_rejected'
                self.current.update(state='FAILED' if rejected else 'UNKNOWN_OUTCOME' if self.current['dispatched'] else 'PRECHECK_REJECTED',
                                    error_code=getattr(error, 'code', type(error).__name__))
                if rejected:
                    self.current.update(failure_details=copy.deepcopy(error.details), completed_at_ms=int(time.time() * 1000))
                self.persist()
            if isinstance(error, ExecutionBindingError):
                error.dispatched = self.current['dispatched']
            raise
        finally:
            if response is not None:
                response.close()
            admission_context.reset(admission)
            bound_invocation.reset(marker)
