"""Bounded per-backend admission with explicit interactive priority and aging."""
from __future__ import annotations

import contextvars
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass

from v14.execution_binding import ExecutionBindingError


admission_context = contextvars.ContextVar('gateway_inference_admission', default=None)
bound_invocation = contextvars.ContextVar('gateway_bound_invocation', default=None)


@dataclass
class Ticket:
    id: str
    lane: str
    created: float
    byte_length: int
    tokens: int
    memory_bytes: int
    state: str = 'QUEUED'


class FairInferenceScheduler:
    def __init__(self, slots=1, *, max_queue=64, max_queue_bytes=32 * 1024 * 1024,
                 max_queue_tokens=4 * 1024 * 1024, interactive_burst=4,
                 background_age_seconds=30, clock=time.monotonic, memory=None):
        if type(slots) is not int or not 1 <= slots <= 8:
            raise ValueError('Invalid qualified slot count')
        self.slots, self.max_queue = slots, max_queue
        self.max_queue_bytes, self.max_queue_tokens = max_queue_bytes, max_queue_tokens
        self.interactive_burst, self.background_age_seconds = interactive_burst, background_age_seconds
        self.clock, self.memory = clock, memory
        self.condition = threading.Condition(threading.RLock())
        self.queues = {'interactive': deque(), 'background': deque()}
        self.active = {}
        self.burst = 0
        self.completed = self.cancelled = self.rejected = 0

    def _next(self):
        interactive, background = self.queues['interactive'], self.queues['background']
        if not interactive:
            return background[0] if background else None
        if not background:
            return interactive[0]
        oldest = background[0]
        # A newly submitted background stage never jumps ahead of chat. An older
        # queued stage receives bounded progress after a burst/aging boundary;
        # active generation is never claimed to be preempted.
        aged = self.clock() - oldest.created >= self.background_age_seconds
        if oldest.created <= interactive[0].created and (aged or self.burst >= self.interactive_burst):
            return oldest
        return interactive[0]

    def _memory_available(self, ticket):
        if self.memory is None:
            return True
        observed = self.memory()
        if not isinstance(observed, dict) or observed.get('origin') != 'MEASURED' or self.clock() - observed.get('observed_monotonic', 0) > 5:
            return ticket.memory_bytes == 0 and not self.active
        available, reserve = observed.get('available_bytes'), observed.get('reserve_bytes', 0)
        if type(available) is not int or type(reserve) is not int or min(available, reserve) < 0:
            return False
        active = sum(value.memory_bytes for value in self.active.values())
        return ticket.memory_bytes + active + reserve <= available

    def acquire(self, mode, *, byte_length=0, tokens=0, memory_bytes=0, timeout=20,
                cancelled=lambda: False, progress=lambda value: None, validate=lambda: None):
        lane = 'background' if str(mode).lower() in {'background', 'long', 'review'} else 'interactive'
        if any(type(value) is not int or value < 0 for value in (byte_length, tokens, memory_bytes)):
            raise ValueError('Invalid inference reservation')
        ticket = Ticket(uuid.uuid4().hex, lane, self.clock(), byte_length, tokens, memory_bytes)
        deadline = ticket.created + timeout
        with self.condition:
            queued = list(self.queues['interactive']) + list(self.queues['background'])
            if len(queued) >= self.max_queue or sum(value.byte_length for value in queued) + byte_length > self.max_queue_bytes or sum(value.tokens for value in queued) + tokens > self.max_queue_tokens:
                self.rejected += 1
                raise ExecutionBindingError('INFERENCE_QUEUE_FULL', 'The selected backend queue reached its bounded capacity. Completed work is retained.', 429)
            self.queues[lane].append(ticket)
            last_progress = -float('inf')
            try:
                while True:
                    if cancelled():
                        self.cancelled += 1
                        ticket.state = 'CANCELLED'
                        raise InterruptedError('Inference cancelled while queued; no model request was sent')
                    if self.clock() >= deadline:
                        raise TimeoutError('The selected backend inference queue deadline expired')
                    if len(self.active) < self.slots and self._next() is ticket and self._memory_available(ticket):
                        self.queues[lane].popleft()
                        ticket.state = 'PREFLIGHT'
                        self.active[ticket.id] = ticket
                        self.burst = self.burst + 1 if lane == 'interactive' else 0
                        break
                    if self.clock() - last_progress >= 1:
                        progress({'phase': 'model_queue', 'state': 'QUEUED', 'lane': lane,
                                  'queue_ms': int((self.clock() - ticket.created) * 1000), 'active_slots': len(self.active)})
                        last_progress = self.clock()
                    self.condition.wait(.05)
            except BaseException:
                if ticket in self.queues[lane]:
                    self.queues[lane].remove(ticket)
                self.condition.notify_all()
                raise
        try:
            validate()
            if cancelled():
                raise InterruptedError('Inference cancelled before dispatch')
        except BaseException:
            self.release(ticket)
            raise
        self.transition(ticket, 'PREFILL')
        return ticket

    def transition(self, ticket, state):
        if state not in {'PREFLIGHT', 'LOADING', 'PREFILL', 'DECODING', 'CANCELLING'}:
            raise ValueError('Invalid inference stage')
        with self.condition:
            if ticket.id in self.active:
                ticket.state = state

    def release(self, ticket):
        with self.condition:
            if self.active.pop(ticket.id, None) is not None:
                ticket.state = 'FINISHED'
                self.completed += 1
            self.condition.notify_all()

    def snapshot(self):
        with self.condition:
            queued = list(self.queues['interactive']) + list(self.queues['background'])
            return {'schema_version': 1, 'qualified_slots': self.slots, 'active': len(self.active),
                    'queued': len(queued), 'queued_bytes': sum(value.byte_length for value in queued),
                    'queued_tokens': sum(value.tokens for value in queued),
                    'active_states': [value.state for value in self.active.values()],
                    'completed': self.completed, 'cancelled_before_dispatch': self.cancelled,
                    'rejected': self.rejected, 'memory_observation': 'AVAILABLE' if self.memory else 'NOT_MEASURED',
                    'preemption_supported': False}


def install_scheduler(runtime):
    existing = getattr(runtime, 'selected_model_scheduler', None)
    if existing is not None:
        return existing
    requested = max(1, int(getattr(runtime, 'LLAMA_SLOT_COUNT', 1)))
    qualified = max(1, min(8, int(getattr(runtime, 'GATEWAY_QUALIFIED_LOCAL_SLOTS', 1))))
    scheduler = FairInferenceScheduler(min(requested, qualified), memory=getattr(runtime, 'inference_memory_snapshot', None))
    runtime.selected_model_scheduler = scheduler
    if not callable(getattr(runtime, '_acquire_llama_model_gate', None)):
        return scheduler
    local = threading.local()
    def acquire(mode, cancel_event=None, hard_cancel_event=None, progress_callback=None, round_number=None):
        metadata = admission_context.get() or {}
        invocation = bound_invocation.get()
        timeout = getattr(runtime, 'LLAMA_LONG_MODEL_QUEUE_TIMEOUT_SECONDS', 45) if mode == 'long' else getattr(runtime, 'LLAMA_MODEL_QUEUE_TIMEOUT_SECONDS', 20)
        from v14.actions import current_gateway_job_id
        job_id = current_gateway_job_id.get()
        with getattr(runtime, 'job_registry_lock', threading.RLock()):
            job = getattr(runtime, 'job_registry', {}).get(job_id) or {}
            if job.get('_durable_background_admitted') and job.get('run_deadline'):
                timeout = max(.001, job['run_deadline'] - time.time())
        cancelled = lambda: any(signal is not None and signal.is_set() for signal in (cancel_event, hard_cancel_event))
        def progress(event):
            if progress_callback:
                progress_callback(dict(event, event='model_queue_wait', status='running', scheduling_state='queued', queue_reason='model_capacity', round=round_number))
        ticket = scheduler.acquire(mode, byte_length=metadata.get('byte_length', 0), tokens=metadata.get('tokens', 0),
                                   memory_bytes=metadata.get('memory_bytes', 0), timeout=timeout, cancelled=cancelled,
                                   progress=progress, validate=(invocation.validate_queue if invocation else lambda: None))
        local.ticket = ticket
        if progress_callback:
            progress_callback({'event': 'model_queue_admitted', 'phase': 'model_execution', 'status': 'running', 'scheduling_state': 'running', 'queue_reason': '', 'round': round_number})
        if invocation:
            try:
                invocation.queue_receipt(ticket, scheduler)
            except BaseException:
                scheduler.release(ticket)
                local.ticket = None
                raise
        return max(0, int((time.monotonic() - ticket.created) * 1000))
    def release(request_started):
        ticket = getattr(local, 'ticket', None)
        if ticket is not None:
            scheduler.release(ticket)
            local.ticket = None
    runtime._acquire_llama_model_gate, runtime._release_llama_model_gate = acquire, release
    return scheduler
