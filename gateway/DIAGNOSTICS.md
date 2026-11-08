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
| `BACKEND_HTTP_ERROR` | Inspect `upstreamStatus`, `upstreamCode`, `upstreamErrorType` and any recognized `upstreamMessageCode`. An unknown HTTP 400 does not prove that the model is unloaded; check the backend version, model ID and properties route. | The failing upstream route returns success. |
| `BACKEND_INVALID_RESPONSE`, `BACKEND_RESPONSE_TOO_LARGE` | Correct the backend response format or excessive metadata. Do not infer model readiness from malformed data. | A valid catalogue and exact-model allocation pass validation. |
| `SELECTED_MODEL_NOT_AVAILABLE` | Load/configure the exact selected model or explicitly change the profile yourself. | That exact identifier is advertised. Automatic substitution remains disabled. |
| `SELECTED_MODEL_NOT_LOADED` | Prepare the exact model explicitly on the gateway host, then recheck the same profile. | The model is observed loaded and its exact allocation and requested reasoning controls qualify. |
| `SELECTED_MODEL_LOADING` | Allow or repair the existing load operation. | Health and selected-model readiness become ready. |
| `SELECTED_MODEL_UNVERIFIED`, `BACKEND_GENERATION_UNVERIFIED` | Expose the required metadata and repeat the check. | The missing observation becomes measured. |
| `GATEWAY_DRAINING` | Finish the intentional maintenance operation or restore admission through the existing manager. | The process no longer reports draining. |
| `DURABLE_STORAGE_UNAVAILABLE`, `DURABLE_STORAGE_WRITE_FAILED` | Inspect the local durable-store failure, path and free space; retain existing work and backups. | Journal readiness succeeds before new durable work is accepted. |

Failure receipts contain fixed `reasonCode`, `failureCategory`, `dependency`, `upstreamStatus` when received, and `retryable` fields. `retryable` describes another metadata observation. Execution admission and mutation reconciliation retain their independent retry rules. Connection errors are categorized by exception types and socket error numbers; raw exception text, backend URLs and upstream response bodies are not exported.

Error envelopes are read under a 16 KiB bound. Only known upstream code/type values and canonical summaries of recognized complete messages are retained. `upstreamErrorBodyState` distinguishes a parsed, invalid, oversized, interrupted or deadline-limited body. A lost error body does not erase the received HTTP rejection. Unknown messages remain available only in the backend's own protected logs.

## Explicitly prepare an advertised router model

`modelAdvertised`, `modelLoaded` and `modelState` distinguish catalogue membership from an observed loaded allocation. A router can list an unloaded, loading or sleeping model while its process answers health requests. Readiness and qualification remain passive, with `autoload=false` on model and properties reads. The exact-model properties URL already includes `model`; removing that parameter or permitting metadata reads to autoload is not a repair.

When the configured backend is a llama.cpp router exposing `GET /models` with a known load state, an operator can run this separate command in the same protected service environment:

```sh
python gateway.py prepare-model --model 'EXACT_SELECTED_MODEL_ID'
python gateway.py doctor --model 'EXACT_SELECTED_MODEL_ID'
```

`prepare-model` verifies that exact identifier and router state, then sends at most one `POST /models/load` for an unloaded or sleeping model. It does not substitute a different model, change reasoning settings, run a generation, or send a load for an already loading/loaded model. The backend may allocate memory and apply its configured model eviction policy; this is an explicit host action and is never run by a readiness poll or an automatic retry. Backends without the supported router load contract require their own host controls.

The command reads the exact model state again after a load response, including a timeout or closed connection. Its receipt keeps `loadAccepted` separate from observed `modelState`; an unknown acceptance remains unknown even if a later read establishes that the model is loaded. `WAITING_FOR_MODEL` means wait and use `doctor`, not repeat the load. `METADATA_QUALIFIED` confirms the capacity observation only: inspect `reasoningControlState`, recheck the same profile in Android, and submit one explicit generation to qualify inference. The command is available in this source; it has not been run against the incident's PC by the repair tests.

The load/state contract follows the upstream [llama.cpp router API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md#using-multiple-models), including its documented `POST /models/load` and sleeping-state metadata. Server-version compatibility is verified through the actual responses, not inferred from the gateway's version label.

## Selected-model observations and reasoning

The selected-model handshake keeps the existing `EXECUTION_*` failure codes and adds actionable reason fields. `observedAtMillis` identifies the measurement; `observationAgeMs` is monotonic cache age. Failed observations are cached for three seconds and concurrent observations for the same configuration/model share one probe. Configuration identity changes and measured backend recovery invalidate old failure evidence. Physical execution boundaries still force fresh validation.

Readiness, model qualification and template qualification are separate probe types. The backend caches include configuration, exact model and transport/recovery identity; template gates additionally bind the measured model/template capability revision. A slow template check releases the shared cache lock so another model's readiness can proceed. Android observations retain authenticated route, selected model, profile revision, network generation and observed gateway instance. Do not aggregate 200/503 results as one global availability state without matching those identities and the probe type.

`reasoningControlState: VERIFIED` requires the existing successful synthetic template proof for the exact loaded model/template revision. `UNSUPPORTED` requires completed negative template observations with unchanged exact-model metadata. Missing, denied, timed-out, malformed or changing metadata remains `UNVERIFIED`. Synthetic template checks do not run inference. Android preserves a strict Off request when qualification fails; it does not silently change the setting or model.

## Engineering host prerequisites and safe reads

`GATEWAY_ENGINEERING_GITHUB_REQUIRED` means the gateway host has no usable configured GitHub connection. The native phone connection does not satisfy that prerequisite. Select an existing enabled host MCP connection with `GATEWAY_ENGINEERING_GITHUB_SERVER`; its protected `env` supplies `GITHUB_PERSONAL_ACCESS_TOKEN` or `GITHUB_TOKEN`. Alternatively set `GATEWAY_ENGINEERING_GITHUB_TOKEN` in the host's protected service environment. Keep credentials out of diagnostics, chat and repository files. Apply configuration using the installation's normal restart procedure after active work permits it. See [the engineering host setup](INTELLIGENT_ENGINEERING.md#host-setup).

Then read `/gateway/engineering/capabilities` through the original admitted connection. Check journal readiness, `host_github_connection: configured`, the running worker and `draining: false`. These local prerequisites do not establish GitHub network reachability or repository authorization; those require a subsequent authorized operation. A prerequisite rejection remains `PRECHECK`/`NOT_DISPATCHED`, with the saved draft intact. An acknowledged job is reconciled using its existing ID.

Android dashboard, readiness, job catalogue/history and engineering GETs use the bounded metadata-read policy. It permits at most one retry for a recognized transient connection/EOF failure, evicts stale idle sockets where applicable, and adds jitter. Confirmed HTTP rejections, invalid documents, TLS failures and cancellations do not enter this transport retry loop. Inference, job submission, approval, cancellation and model-load POSTs have separate ownership/reconciliation rules and are never replayed by this policy. Repeated catalogue pages must retain their full cursor and authenticated scope; matching route labels alone do not prove duplicate requests.

## Timing and request identity

Each HTTP call receives a unique `X-Gateway-HTTP-Request-ID`. The existing `X-Gateway-Request-ID` remains a logical receipt. Exported `httpRequestId` joins one physical request to its response; `operationDigest` joins a caller operation's checks; `retryAttempt` identifies an explicitly supplied bounded read retry. Trace and parent-span identities remain available separately.

`arrivalAtMs` is captured at middleware entry. The request's `timestampMs` is that arrival time even if the record is emitted after admission. `responseStartedAtMs` records accepted response headers, and `completedAtMs` records the final accepted body send. `durationMs` uses the monotonic interval through that completion. `recordedAtMs` records log emission. Interrupted delivery explicitly distinguishes sent headers from a completed body and never invents an HTTP 500 before headers were sent. Older records without arrival fields retain their original timing limitations.

Routes use fixed operation categories such as `backend_health`, `backend_properties`, `management_dashboard`, `maintenance`, `identity`, `job_catalog`, `conversations` and `messages`. Path identifiers and query strings are excluded. Expected `remote_surface_not_admitted` rejections remain identifiable separately from connectivity failures. Android metadata is accepted only after admission; missing metadata remains unknown, rather than being assigned to another client type.

`X-Gateway-Client-Diagnostics-Status` explicitly distinguishes accepted metadata, capture disabled, invalid metadata, no request and rejection before processing. Android retains `CAPTURE_DISABLED` and `INVALID_METADATA` only when reported by that header. Older servers without an explicit outcome still produce `NOT_CONFIRMED`; an absent acknowledgment is not relabelled as success. Disabled capture is a known evidence limitation and does not by itself indicate a transport failure.

## Export the full evidence

The desktop's default ZIP export obtains a live snapshot through its existing local management identity. It contains:

- `gateway-debug.jsonl`: at most 5,000 transport/lifecycle records, structured safe observations, physical HTTP IDs and timing.
- `diagnostic-events.jsonl`: at most 5,000 retained causal events across captures, including captured model/tool receipt transitions and their session/owner digests.
- `manifest.json`: build revision, gateway instance/boot identity, configuration/backend fingerprints, observed Android builds, file hashes, time and sequence coverage, source watermarks, losses, truncation and availability.

Only a local administrator can export across captures. Remote diagnostic exports remain principal/session scoped. The transport-only API remains `/gateway/admin/debug/export`; add `?format=zip` for the bundle. Debug mode and capture/privacy gates still control which events can be retained. Disabled capture, absent journals, expired or truncated evidence, and uncaptured work are explicitly incomplete. Missing events do not prove that inference never ran or that a job was lost.

Startup records retain build/configuration identity and a known startup reason. Controlled shutdown emits a stop event with a clean/failure indication. A missing stop record alone is not proof of a crash because retention and disabled capture can also remove it.

## Acceptance on the actual deployment

Install the complete matching gateway package and Android branch build. Capture one failed check and confirm its reason, dependency, selected profile/model and build identity are retained. Repair that specific backend condition on its host. Then verify a fresh catalogue containing the selected model, ready health, capacity/template qualification for the requested settings, and one explicitly submitted generation with terminal receipt evidence. If durable engineering is needed, separately verify its GitHub/validation prerequisites and follow an accepted job through completion/reconnect. Host regression tests do not substitute for these deployment observations.
