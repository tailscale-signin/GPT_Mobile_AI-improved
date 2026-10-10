# Gateway v14 implementation and upgrade

This implementation branch targets **14.2.0** and retains the 14.1 foundations. See [V14_1_ARCHITECTURE.md](V14_1_ARCHITECTURE.md) for the researched architecture, implemented stability changes, configuration and failure tests. The branch keeps modern MCP disabled and advertises only the additive contracts currently implemented and qualified by its test suite.

This is a reviewable development implementation, not a certified Windows/phone release. It retains the v13.1 inference, delegation, recovery, output-budget and MCP runtime. Do not replace a PC installation with only gateway.py: every file in manifest-v14.json is required.

The archive also includes `android/Gateway_v14_Android_Compatibility.patch`, based on reviewed main commit a66155df. Apply it on an isolated app review branch with `git apply`, then build and test using the repository's JDK/SDK before installing on the phone.

## Start locally

Use Python 3.12+ and a dedicated virtual environment. Install the hash-pinned requirements.txt. Run `python gateway.py validate-config`, then `python gateway.py doctor`, then `python gateway.py`. Default bind is 127.0.0.1:8090. The model backend remains separately configured (normally 127.0.0.1:8080). Configure an Android Custom or LLAMA profile with the gateway base URL ending in /v1 and leave the key empty. Capability-aware key validation accepts only v14's explicit key-free advertisement; ordinary provider credentials remain required.

Loopback is a single-user local-machine boundary. Local processes are trusted. Job ownership and cache keys use `local:single-user`, never an Android installation header. No automatic personal-memory retrieval/storage or tool-result learning runs in v14. The existing PC memory files/configuration remain intact. Known PC memory servers are excluded until a separately scoped workspace adapter is implemented.

## Phone via Tailscale

Set `GATEWAY_AUTH_MODE=trusted_proxy` on a dedicated loopback listener behind Tailscale Serve. Set `GATEWAY_ALLOWED_HOSTS` to a JSON array of exact HTTP authorities (including port when non-default), and `GATEWAY_ALLOWED_ORIGINS` to a JSON array of exact origins. Set `GATEWAY_TRUSTED_USERS` to a JSON mapping, for example `{"approved@example.com":{"principalId":"emanuel","scopes":["chat","jobs","tools"]}}`. Serve must strip incoming identity headers and inject its verified `Tailscale-User-Login`. Tagged devices without user identity fail closed. Do not use public Funnel or unrestricted LAN exposure. Uvicorn proxy header rewriting is disabled so admission checks the actual socket peer.

One approved user has one principal; two phones for that user share its state. This is not verified per-device isolation. The retained runtime automatically selects tools, so chat requires both chat and tools grants; a tool-free limited-user mode is not advertised. Remote access is limited to models, chat, capabilities, readiness, approved inventory and owned jobs. Administrative/configuration/debug/backend proxy surfaces are local-only. Direct browser mutations require an approved Origin and the `X-Gateway-CSRF` token obtained from GET /gateway/session. The loopback llama.cpp UI now receives a bootstrap script before its app bundles; its same-origin fetch mutations obtain and send this token automatically. Other browser clients must send the token themselves. Cross-origin writes remain rejected, and the trusted-proxy browser/admin surface stays disabled.

## State and rollback

The installer stages a complete sibling release and installs locked dependencies; it never activates a service, adjusts firewall rules, launches downloaded MCP servers, or copies live SQLite files. Record the old working directory, launch command, environment, configuration and database locations. Stop writes and use SQLite's backup API for a consistent state snapshot (including WAL); retain the previous release and coherent database snapshot.

Select explicit existing `GATEWAY_AUTH_DB`, `GATEWAY_JOB_DB`, and `MCP_CONFIG_PATH` locations before changing the service launch directory; do not overwrite custom configuration. Unmapped v13 jobs remain private. An operator may explicitly reassign a legacy device's ownership using `python gateway.py migrate-owner --legacy-device DEVICE_ID --principal local:single-user` (or `tailnet:APPROVED_ID`). This modifies ownership records only; it does not replay jobs or delete old tokens. Keep the snapshot to undo that migration. Optional `GATEWAY_LEGACY_BEARER=true` admits existing device credentials during migration; new installations do not need a bearer key.

Stop the old service, select the staged gateway.py and its virtual environment, and validate local models/chat/recovery plus the approved phone route. If validation fails, stop v14 and restore the previous launch target and coherent state snapshot. Service activation and state changes are separate steps, not one atomic transaction.

## Delivered changes

- Complete-package hash validation before startup and a staged Windows installer.
- Key-free loopback and explicitly configured trusted-proxy admission, Host/Origin checks, browser CSRF, principal-scoped legacy job/cache compatibility and local ownership migration.
- Additive capability DTOs and blank-key discovery in the existing Android validation path; blank Authorization headers are omitted on recovery calls.
- Deterministic provider-order round-robin search results with URL tracking-only identity normalization, duplicate-source attribution and valid structured projections instead of sliced JSON. Bounded shared concurrency and a 12-second phase deadline.
- Durable commits fail explicitly instead of acknowledging unsaved completions; terminal-state guards prevent late failure/progress/cancel updates from overwriting completed results. Existing durable journal/recovery and bounded delegation behavior are retained.
- Client-authoritative memory defaults, plus optional `gateway_memory` recall envelopes checked for conversation scope, explicit local destination and evidence budgets before model dispatch. No PC replica or automatic contradiction approvals.
- Exact catalog-bound, case-sensitive MCP dispatch with duplicate/malformed alias quarantine; owner identity comes from the selected connection metadata rather than prefix guessing.
- Sanitized connection IDs and health states, readiness probing off the ASGI event loop, doctor/config-validation commands, and explicit disabled MCP façade response.

## Remaining plan gates

Modern MCP adapter certification, the optional native MCP façade, exact cross-language search golden parity (including fetch/refill deadlines and content shadow comparison), general typed product/listing/news/Combined evidence forwarding, expanded profile-aware gateway consent, portable protected state export/restore, encrypted durable-content persistence and comprehensive model/resource tuning remain unfinished. They are not advertised as implemented. Android package backup/restore and rich-card rendering continue to use existing app behavior.

Windows installer/rollback, Tailscale identity, live model/provider behavior, Android JDK 21/SDK build, and physical-phone acceptance require target-environment testing. No speedup, universal source availability or complete acceptance-matrix pass is claimed.

## Validation

Run the existing v13 regression suites in a separate process, then the new v14 suite. The v14 composition root intentionally configures the retained runtime's globals for its process.

```
python -m unittest discover -s gateway/tests -p test_gateway_v13.py -v
python -m unittest discover -s gateway/tests -p test_gateway_completion_recovery.py -v
python -m unittest discover -s gateway/tests -p 'test_gateway_v14*.py' -v
```

Use `build_v14_package.py` only from the reviewed source checkout; rebuilding the manifest trusts the current source. Distribution hashes detect incomplete/mixed/modified packages, not publisher identity. Review the source commit and provenance separately. Do not regenerate a manifest merely to bypass a failed downloaded-package check.

## Browser 403 fix (October 9)

Earlier v14 packages served the llama.cpp UI but did not attach its required CSRF header. This blocked POST /tools, POST /v1/streams/lookup and POST /v1/chat/completions before backend dispatch. The browser bootstrap corrects that integration, preserving the original request body, headers, signal and streaming response. Tokens stay in page memory and are stripped before forwarding to llama.cpp. One refresh/retry is allowed only after an explicit gateway admission rejection; upstream/network failures never trigger write replay.

Update the complete gateway package, restart it, then reload the browser page so it loads the bridge. No gateway API key is needed. Existing MCP processes and personal memory files do not require changes. The MCP notification warning in the supplied log did not prevent discovery; all 102 tools were discovered.

## Existing journal fix (October 10)

Earlier journal schemas can lack `gateway_jobs.created_at`. The previous initializer used `CREATE TABLE IF NOT EXISTS`, which left an existing table unchanged and allowed startup to succeed before the first chat failed. v14 now adds missing job/event timestamp columns in a transaction before cleanup or recovery. Saved JSON, results, event sequences and ownership remain intact; known historical timestamps are copied from saved JSON. Repeated startup is safe. Unsupported core schemas stop startup with a journal upgrade error and leave the migration rolled back.

Keep the existing `gateway_jobs.sqlite3`; no database deletion or reset is required. Stop the gateway, update the complete package (including its manifest), preserve configuration, databases and `.venv`, then restart. Existing retention rules still apply. Failed initial job writes now return HTTP 503 before the stream starts or the model runs. Failed final commits produce an SSE error instead of delivering an unsaved successful answer.

Real SQLite regressions cover fresh and legacy databases, empty journals, populated results/events, repeated startup, interrupted-job recovery without replay, migration rollback, and registration/final-commit failures through the actual chat route. Only model inference is stubbed in those chat tests. A POST /tools 404 after successful browser admission is passed through from llama.cpp; it is separate from the SQLite crash and is not converted into a fabricated success.
