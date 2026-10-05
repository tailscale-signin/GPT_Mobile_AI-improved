# Gateway 13.1.0

V13 builds on v12.2 and the Android delegation/debug fixes. `gateway_v13.py` is the new implementation; `gateway.py` is the stable launcher. Keep `gateway_v13_runtime.py`, `gateway_v13_transport.py`, `gateway_multisearch.py`, and `gateway_security.py` alongside it. V12 files remain available for rollback; they do not automatically load v13.

## Install

Use Python 3.12+, stop the gateway service, and run from this directory:

```powershell
python -m pip install -r requirements.txt
.\install_gateway_v13.ps1 -Destination D:\MCP\gateway\gateway.py
```

Restart the existing service using `python gateway.py` (or `uvicorn gateway:app` with your existing host/port). The installer syntax-checks every module and backs up all replaced files. It preserves pairing/job databases and MCP configuration. Keep the same working directory and database environment variables used by the existing service. The installer does not stop/restart the service or install dependencies automatically. Restore the timestamped backups while stopped to roll back.

For a fresh installation, follow README pairing instructions. All diagnostics and chat endpoints require the paired-device bearer token. `GET /gateway/v13` reports capabilities and `GET /gateway/ready` returns 200 when the backend health probe succeeds, otherwise 503.

## Changes in 13.1.0

- Completed backend streams get a one-second usage drain, rather than waiting indefinitely for a missing `[DONE]` marker.
- Reasoning-only replies receive one tool-free finalization attempt. Repeated empty output returns `empty_completion`, never a false success or a claim of verified research.
- Mixed-owner tool plans are partitioned: gateway calls run locally; unexecuted client intents are replanned against a client-only catalog. Unknown tools are never relayed to the phone.
- Compatible enabled gateway web-search tools fan the same query out across engines with bounded concurrency and a shared 20-second deadline. Partial failures preserve other results and source URLs are deduplicated. Unsupported required parameters/filters are not guessed. Native llama.cpp `/tools` search endpoints are not wrapped by this MCP executor; expose those search services as gateway MCP or app MCP connections for aggregation.
- Android also needs the matching app update for model-grouped Token Comparison, client-side multi-search, empty-answer recovery, and bounded completion draining.
- Install all companion modules, including the new `gateway_multisearch.py`. Updating only `gateway.py` is insufficient.

## Prior v13 changes

- Context preflight now covers isolated delegates. Compaction preserves the original goal, recent user turns, complete tool exchanges, and instruction messages. It removes older hidden reasoning and omits old turns only when needed. An omission notice tells the model that evidence is missing.
- Explicit output budgets are retained, including budgets above 16,384 tokens. Incompatible context/output budgets return `context_budget_exceeded` (422), rather than silently shrinking the answer budget. Physical model context and caller-specified limits still apply. Final synthesis retains unique collected observations without an extra fixed 6,144-token output cap.
- Upstream chat uses SSE with cancellation and wall-clock deadlines. The gateway assembles and validates complete tool calls before execution. Interrupted streams produce errors, rather than executing partial arguments or replaying a generation after response bytes arrived.
- Final delivery uses bounded SSE chunks without trimming generated content; usage and timings are delivered once. Backend output progress is observable while generation runs. Final content remains buffered until the gateway has finished its tool/validation phase.
- Client-supplied tools take precedence over local MCP name prefixes. Phone Location calls stay owned by the Android client. Local MCP routing remains available when the worker explicitly opts in.
- Reviewer roles force a tool-free request even when contradictory worker flags are present. Read-cache identities include device identity and case-sensitive tool names; prefetch threads inherit request context.
- Progress events use `gateway_progress`, without inserting status text into generated reasoning. Singleflight client detach happens once on completion/disconnection, and orphan cancellation respects reconnect grace.
- Request validation rejects malformed catalogs, duplicate names, invalid caps and unsupported multiple completions before job creation. Tool responses must name an active tool, contain complete JSON-object arguments and include required fields.

The Android changes in the same draft PR provide yellow reviewer text, a score at three times the text size, and pink recalled-memory spans in debug mode. These colors are rendered by Android; upgrading only the Python server cannot change the app UI. Gateway progress labels are metadata, not model-generated reasoning or memory citations.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `GATEWAY_BACKEND_STREAMING` | `true` | Use upstream chat SSE; set false for backends without SSE support. |
| `GATEWAY_DELEGATE_DEADLINE_SECONDS` | `120` | Wall-clock deadline for an isolated model round after slot acquisition. |
| `GATEWAY_MODEL_DEADLINE_SECONDS` | `300` | Wall-clock deadline for other streamed model rounds. |
| `GATEWAY_LEGACY_PROGRESS_TEXT` | `false` | Compatibility escape hatch for old clients that require progress in reasoning text. |

Backend socket idle/connect timeouts and queue limits still apply. Nonstream compatibility mode uses the existing bounded HTTP timeout. Defaults are not claims about maximum model context: that capacity is discovered from the backend, with existing fallback configuration.

## Validation

```sh
python -m py_compile gateway*.py
python -m unittest discover -s tests
```

Tests cover the authenticated HTTP boundary, reviewer isolation, Location ownership, device cache isolation, context overflow/output preservation, lossless large final delivery, fragmented tools, cancellation, interrupted streams and model-slot release. Live Windows installation, phone GPS, MCP servers and llama.cpp inference still require testing on the target system.
