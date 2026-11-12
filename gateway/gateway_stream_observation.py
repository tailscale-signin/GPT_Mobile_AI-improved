"""Observe ASGI handoff without claiming socket flush or changing checkpoint persistence."""
from __future__ import annotations

import json
import time

from starlette.responses import StreamingResponse


class ObservedCompletionStream(StreamingResponse):
    """Each yielded chunk is sent immediately; the terminal receipt describes this delivery only.

    ASGI send completion means the server accepted the body with backpressure applied.
    It does not establish a socket flush, proxy receipt, client parsing or UI paint.
    """

    def __init__(self, *args, clock=time.monotonic, **kwargs):
        super().__init__(*args, **kwargs)
        self._clock = clock

    async def stream_response(self, send):
        started = self._clock()
        first_submit = first_complete = first_content_submit = first_content_complete = None
        events = 0
        delivery_mode = 'completion_buffered'

        def elapsed():
            return round(max(0.0, self._clock() - started) * 1000, 3)

        async def observed_send(message):
            nonlocal first_submit, first_complete, first_content_submit, first_content_complete, events
            nonlocal delivery_mode
            body = message.get("body", b"") if message.get("type") == "http.response.body" else b""
            if not body:
                return await send(message)
            if body.strip() == b"data: [DONE]":
                receipt = {"measurement_scope": "gateway_asgi_stream", "delivery_mode": delivery_mode,
                           "first_event_submit_ms": first_submit, "first_event_send_completed_ms": first_complete,
                           "first_content_submit_ms": first_content_submit, "first_content_send_completed_ms": first_content_complete,
                           "observed_elapsed_ms": elapsed(), "forwarded_events": events,
                           "socket_flush_confirmed": None}
                packet = b"data: " + json.dumps({"choices": [], "gateway_delivery": receipt}, separators=(",", ":")).encode("utf-8") + b"\n\n"
                await send({"type": "http.response.body", "body": packet, "more_body": True})
                return await send(message)
            submitted = elapsed()
            if first_submit is None:
                first_submit = submitted
            content = False
            if body.startswith(b"data: "):
                try:
                    item = json.loads(body[6:])
                    if isinstance(item, dict) and isinstance(item.get('gateway_text_stream'), dict) and item['gateway_text_stream'].get('delivery_mode') == 'incremental_final_answer':
                        delivery_mode = 'incremental_final_answer'
                    content = any(isinstance(choice, dict) and isinstance(choice.get("delta"), dict)
                                  and isinstance(choice["delta"].get("content"), str) and bool(choice["delta"]["content"])
                                  for choice in item.get("choices", []) if isinstance(item, dict))
                except (ValueError, TypeError, AttributeError):
                    pass
            if content and first_content_submit is None:
                first_content_submit = submitted
            await send(message)
            completed = elapsed()
            events += 1
            if first_complete is None:
                first_complete = completed
            if content and first_content_complete is None:
                first_content_complete = completed

        await super().stream_response(observed_send)
