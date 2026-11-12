"""Incremental delivery for a final text-only round, separate from diagnostic events.

The shared worker retains a bounded prefix so backpressure or a reconnect cannot
drop individual deltas. The durable job still commits the complete final result.
Tool-bearing and structured internal model rounds remain completion-validated.
"""
import json
import threading
from contextlib import contextmanager
from contextvars import ContextVar

from gateway_v13_runtime import GatewayContractError, visible_stream_text


current_delivery = ContextVar('gateway_text_delivery', default=None)
visible_output = ContextVar('gateway_visible_output', default=None)
VISIBLE_ROLES = {'primary', 'github-primary', 'synthesis', 'follow_up_synthesis'}


class TextDelivery:
    def __init__(self, maximum=1024 * 1024):
        self.maximum = maximum
        self.lock = threading.Lock()
        self.text = ''

    def offer(self, raw):
        text = visible_stream_text(raw, preserve_whitespace=True)
        with self.lock:
            # An empty attempt may still be repaired. Once a visible answer
            # exists, publish its exact layout including leading indentation.
            if not self.text and not text.strip():
                return
            if not text.startswith(self.text):
                raise GatewayContractError('The streamed answer changed its previously published prefix.',
                    'streamed_answer_changed', 502, details={'partial_output_published': bool(self.text), 'retryable': False})
            # Final delivery supplies any remainder; this prefix never grows
            # without bound and never drops already published text.
            self.text = text[:self.maximum]

    def snapshot(self):
        with self.lock:
            return self.text

    def chunks(self, prefix, model, identifier, created, chunk_chars=2048):
        text = self.snapshot()
        if not text.startswith(prefix):
            raise GatewayContractError('The shared stream cursor no longer matches the admitted answer.',
                                       'streamed_answer_changed', 502)
        for start in range(len(prefix), len(text), chunk_chars):
            delta = text[start:start + chunk_chars]
            yield delta, 'data: ' + json.dumps({
                'id': identifier, 'model': model, 'created': created, 'object': 'chat.completion.chunk',
                'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': delta}, 'finish_reason': None}],
                'gateway_text_stream': {'schema_version': 1, 'delivery_mode': 'incremental_final_answer'},
            }, ensure_ascii=False) + '\n\n'


@contextmanager
def final_answer(payload, role):
    delivery = current_delivery.get()
    allowed = (delivery is not None and role in VISIBLE_ROLES and not payload.get('tools')
               and payload.get('tool_choice') in (None, 'none')
               and (payload.get('response_format') or {}).get('type', 'text') == 'text')
    marker = visible_output.set(delivery.offer if allowed else None)
    try:
        yield
    finally:
        visible_output.reset(marker)


def output_started():
    delivery = current_delivery.get()
    return bool(delivery and delivery.snapshot())
