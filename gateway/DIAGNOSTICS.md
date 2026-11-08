# Diagnosing a reachable gateway with an unavailable model

The control interface, model catalogue, selected model, generation readiness and durable storage are separate observations. An HTTP 200 capability or dashboard response does not prove that inference can run. Readiness and model discovery never generate text, load another model or modify the selected profile.

## Check the configured backend on the gateway host

For a manual Python installation, use the same environment and configuration as the running gateway:

```sh
cd gateway
python gateway.py doctor --model EXACT_SELECTED_MODEL_ID
```

The doctor reports the normalized backend metadata base, model-list and health paths, structured readiness conditions, and the exact selected model's capacity/template observation. The metadata base accepts the configured URL with or without its final `/v1`; proxy path prefixes remain intact. The doctor is also available to an admitted local administrator at `/gateway/admin/doctor?model=...`. It is not a remote public surface.

From the configured client connection, inspect `/v1/models`, `/gateway/ready?model=...`, and `/gateway/capabilities?model=...`. URL-encode the selected model identifier. The successful catalogue must advertise that exact identifier. A successful readiness result must report `ready: true`, `generationReady: true`, and `modelAvailable: true` for the requested model. Capacity/template qualification is a separate selected-model observation; actual inference still requires a later generation test.

| Observation | Correction on the gateway host | Recovery evidence |
| --- | --- | --- |
| `BACKEND_CONNECTION_REFUSED` | Start the configured model server; verify its listening interface and port from the gateway process. | A model-list response and health response reach that same backend. |
| `BACKEND_CONNECT_TIMEOUT`, `BACKEND_DNS_FAILED` | Correct the configured address, DNS, route or firewall. A localhost address refers to the gateway host. | A fresh observation reaches the selected endpoint within its budget. |
| `BACKEND_READ_TIMEOUT`, `BACKEND_CONNECTION_CLOSED` | Inspect backend startup/load state and transport stability. Metadata reads may be retried; this receipt does not authorize replay of inference or a job submission. | A complete metadata response arrives; cached failure ages out or measured recovery invalidates it. |
| `BACKEND_TLS_FAILED` | Correct the backend TLS trust or certificate configuration. | The same configured HTTPS endpoint verifies successfully. |
| `BACKEND_METADATA_DENIED` | Authorize gateway-to-backend access to required metadata. Keep gateway remote `/health` and `/props` restrictions intact. | The gateway process reads the required model identity and allocation metadata. |
| `BACKEND_ROUTE_UNSUPPORTED` | Verify the backend API and configured path. Install a compatible backend or expose the required metadata route. | The expected route returns valid bounded metadata. |
| `BACKEND_HTTP_ERROR` | Inspect the retained `upstreamStatus` and the backend's local error report. | The failing upstream route returns success. |
| `BACKEND_INVALID_RESPONSE`, `BACKEND_RESPONSE_TOO_LARGE` | Correct the backend response format or excessive metadata. Do not infer model readiness from malformed data. | A valid catalogue and exact-model allocation pass validation. |
| `SELECTED_MODEL_NOT_AVAILABLE` | Load/configure the exact selected model or explicitly change the profile yourself. | That exact identifier is advertised. Automatic substitution remains disabled. |
| `SELECTED_MODEL_LOADING` | Allow or repair the existing load operation. | Health and selected-model readiness become ready. |
| `SELECTED_MODEL_UNVERIFIED`, `BACKEND_GENERATION_UNVERIFIED` | Expose the required metadata and repeat the check. | The missing observation becomes measured. |
| `GATEWAY_DRAINING` | Finish the intentional maintenance operation or restore admission through the existing manager. | The process no longer reports draining. |
| `DURABLE_STORAGE_UNAVAILABLE`, `DURABLE_STORAGE_WRITE_FAILED` | Inspect the local durable-store failure, path and free space; retain existing work and backups. | Journal readiness succeeds before new durable work is accepted. |

Failure receipts contain fixed `reasonCode`, `failureCategory`, `dependency`, `upstreamStatus` when received, and `retryable` fields. `retryable` describes another metadata observation. Execution admission and mutation reconciliation retain their independent retry rules. Connection errors are categorized by exception types and socket error numbers; raw exception text, backend URLs and upstream response bodies are not exported.

## Selected-model observations and reasoning

The selected-model handshake keeps the existing `EXECUTION_*` failure codes and adds actionable reason fields. `observedAtMillis` identifies the measurement; `observationAgeMs` is monotonic cache age. Failed observations are cached for three seconds and concurrent observations for the same configuration/model share one probe. Configuration identity changes and measured backend recovery invalidate old failure evidence. Physical execution boundaries still force fresh validation.

`reasoningControlState: VERIFIED` requires the existing successful synthetic template proof for the exact loaded model/template revision. `UNSUPPORTED` requires completed negative template observations with unchanged exact-model metadata. Missing, denied, timed-out, malformed or changing metadata remains `UNVERIFIED`. Synthetic template checks do not run inference. Android preserves a strict Off request when qualification fails; it does not silently change the setting or model.

## Timing and request identity

Each HTTP call receives a unique `X-Gateway-HTTP-Request-ID`. The existing `X-Gateway-Request-ID` remains a logical receipt. Exported `httpRequestId` joins one physical request to its response; `operationDigest` joins a caller operation's checks; `retryAttempt` identifies an explicitly supplied bounded read retry. Trace and parent-span identities remain available separately.

`arrivalAtMs` is captured at middleware entry. The request's `timestampMs` is that arrival time even if the record is emitted after admission. `responseStartedAtMs` records accepted response headers, and `completedAtMs` records the final accepted body send. `durationMs` uses the monotonic interval through that completion. `recordedAtMs` records log emission. Interrupted delivery explicitly distinguishes sent headers from a completed body and never invents an HTTP 500 before headers were sent. Older records without arrival fields retain their original timing limitations.

Routes use fixed operation categories such as `backend_health`, `backend_properties`, `management_dashboard`, `maintenance`, `identity`, `job_catalog`, `conversations` and `messages`. Path identifiers and query strings are excluded. Expected `remote_surface_not_admitted` rejections remain identifiable separately from connectivity failures. Android metadata is accepted only after admission; missing metadata remains unknown, rather than being assigned to another client type.

## Export the full evidence

The desktop's default ZIP export obtains a live snapshot through its existing local management identity. It contains:

- `gateway-debug.jsonl`: at most 5,000 transport/lifecycle records, structured safe observations, physical HTTP IDs and timing.
- `diagnostic-events.jsonl`: at most 5,000 retained causal events across captures, including captured model/tool receipt transitions and their session/owner digests.
- `manifest.json`: build revision, gateway instance/boot identity, configuration/backend fingerprints, observed Android builds, file hashes, time and sequence coverage, source watermarks, losses, truncation and availability.

Only a local administrator can export across captures. Remote diagnostic exports remain principal/session scoped. The transport-only API remains `/gateway/admin/debug/export`; add `?format=zip` for the bundle. Debug mode and capture/privacy gates still control which events can be retained. Disabled capture, absent journals, expired or truncated evidence, and uncaptured work are explicitly incomplete. Missing events do not prove that inference never ran or that a job was lost.

Startup records retain build/configuration identity and a known startup reason. Controlled shutdown emits a stop event with a clean/failure indication. A missing stop record alone is not proof of a crash because retention and disabled capture can also remove it.

## Acceptance on the actual deployment

Install the complete matching gateway package and Android branch build. Capture one failed check and confirm its reason, dependency, selected profile/model and build identity are retained. Repair that specific backend condition on its host. Then verify a fresh catalogue containing the selected model, ready health, capacity/template qualification for the requested settings, and one explicitly submitted generation with terminal receipt evidence. If durable engineering is needed, separately verify its GitHub/validation prerequisites and follow an accepted job through completion/reconnect. Host regression tests do not substitute for these deployment observations.
