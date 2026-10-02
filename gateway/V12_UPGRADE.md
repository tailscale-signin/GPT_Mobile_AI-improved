# Gateway v12

This standalone script upgrades the supplied October 1 v11 source, preserving its MCP sessions, durable jobs, reconnect/single-flight behavior, domain-aware client-first routing, curated GitHub surfaces, default repository grounding, cache/context recovery, model discovery SSE and Ollama compatibility endpoints. The existing repo v10 remains available.

## Changes

- Fix the context guard's 512-token minimum overriding smaller requested output limits.
- Normalize output-cap aliases at request entry and the model HTTP boundary. Use the smallest explicit cap, including `requestedOutputCap`, `requested_output_cap`, `delegation_output_cap`, `max_completion_tokens` and `n_predict`. Strip internal aliases before sending requests to llama.cpp. Bound explicit reasoning budgets below the total cap.
- Report empty, reasoning-only, tool-call and length-limited partial results distinctly. A completion object alone no longer implies success.
- Enrich existing progress events with descriptive UI text, tool names and short filenames. Preserve actual server messages. Include elapsed time where available without fabricated percentages.
- Emit waiting updates on the existing stream heartbeat interval, describing the last observed activity and total elapsed time. Keep the OpenAI empty-delta heartbeat for existing clients. Waiting updates do not reset MCP inactivity timers.
- Expose `/gateway/v12` and `/v1/gateway/v12` with result-quality and progress-field contracts. Progress protocol remains `/2` for existing Android clients.
- Add a Windows installer that validates syntax/version, backs up the installed script and stages its replacement.

## Install

Place `gateway_v12.py` and `install_gateway_v12.ps1` together. Stop the current gateway, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install_gateway_v12.ps1
```

Use `-Python D:\path\to\venv\Scripts\python.exe` if the gateway uses a virtual environment. Restart the gateway using the existing configuration and MCP config. No runtime configuration migration is required.

## Validation

```sh
python -m py_compile gateway/gateway_v12.py
python -m unittest discover -s gateway/tests -v
```

Contract tests run without starting models or MCP processes. Live Windows installation, llama.cpp token enforcement, provider-specific reasoning behavior and Android rendering require testing against the user's running services. Separate reasoning/final allocation is constrained by backend support; this gateway bounds explicit budget fields but cannot guarantee separate token pools on every model. The script retains v11's existing input-context guard and worker recovery; it does not replace the Android delegation planner or benchmark ranking UI.

## 12.1.1 delegation repair

Delegated child requests preserve the app's tool schemas and bypass domain filtering and local-first tool routing. Buffered SSE retains backend decode timing. Child dispatch is bounded by a 10-second queue wait, a 45-second upstream read timeout, and a 60-second stream ceiling; configured smaller Android progress deadlines still apply. Child timeouts are not replayed inside the gateway. See [the diagnostics repair notes](../docs/delegation-diagnostics-repair.md) for client changes and regression coverage.

## 12.1.2 GitHub delegation tools

Install/restart the updated `gateway_v12.py` or identical `gateway_v12.1.py`, and use an Android build with the GitHub delegation repair. Previously every delegated-worker request bypassed gateway-local MCP discovery, including repository tasks that needed the configured GitHub server.

Tool-capable GitHub workers now send `X-Gateway-Allow-Local-Tools: true` alongside `X-Gateway-Delegated-Worker: true`. The gateway uses its existing local MCP routing and configured credentials for these requests, while still skipping automatic memory injection. Text transforms and benchmark fixtures remain isolated; `tool_choice=none` suppresses all tools. The isolated 60-second stream ceiling applies only to isolated workers; tool-capable workers retain the normal gateway cancellation and timeout controls.

The app preserves the primary profile's enabled GitHub tools in local-first mode so the primary can complete writes or recover from missing worker capabilities. Repository tasks skip public-web planning, and worker schemas are ordered with GitHub first before applying the input-token budget. Profile disables, per-chat exclusions and native-tool approvals remain in force. Gateway-local opt-in is withheld when chat tool selection is restricted because the app cannot enforce individual exclusions on server-managed tools. Gateway tool permissions are managed by the server's existing configuration.
