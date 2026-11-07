# Gateway capability decoding compatibility

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Parent PR: `811`. This work starts at `bf8e5f764bbbc8cac942e31ee0709344e0b02ac5` on `work/gateway-capability-compat-8597579`.

## Failure and evidence

The reported Android build uses `GatewayCapabilities.features: Map<String, Boolean>`, while the full gateway route includes `gateway_server_icon: "gateway_server"` and numeric `disconnect_grace` in that map. This mismatch can reject an HTTP 200 capability response before control negotiation permits inference. The supplied diagnostics record the HTTP success followed by a generic discovery failure; they do not include the response body or original decoder exception. The complete server response fixtures provide separate, reproducible evidence of the mixed field types. The separately observed stale-socket read/retry is not treated as the same failure.

The server owner captured both fixtures from the actual Uvicorn capability route, including middleware and startup. They are integrated in published commit `bce547900465e0f2a887424e704cb0384883844c` (original work-package commit `608f0d61a84e3984df111e7dd433548dde5f4afe`):

- `app/src/test/resources/gateway-v15-capabilities-legacy-mixed.json`: 26 top-level fields and 94 feature entries, including the two mixed values.
- `app/src/test/resources/gateway-v15-capabilities-full.json`: 27 top-level fields and 92 Boolean feature flags after the server metadata correction.

Only the runtime instance and source-commit identities were normalized. The Kotlin tests consume these complete captured responses; a reduced Boolean-only fixture is not used to establish the reported negotiation fix. No private diagnostic payload is committed.

## Android changes

`GatewayModels.kt` retains feature values as `JsonElement`. `supportsFeature` enables a flag only when its value equals `JsonPrimitive(true)`. Strings, numbers, false, null, arrays, objects, and absent keys cannot enable flags. Legacy metadata remains readable without being interpreted as authority.

`GatewayAPIImpl.kt` keeps the original `decodeFromString` acceptance path and JSON configuration. Other field types, required fields, gateway identity/version checks, authorization, and isolation negotiation remain enforced. A rejected decode triggers diagnostic inspection only. That inspection uses the serializer's known schema names, redacts all map keys to `[*]`, and stops at 256 nodes, depth 12, or a 160-character path. When the bounded inspection cannot locate a field, it reports `SCHEMA_MISMATCH` at the document root.

The resulting `GatewayCapabilityDiscoveryException` carries a stable `decodeReason` and `fieldPath`. Its message and the local diagnostic event contain only those schema-derived values. Neither the original decoder cause nor raw body excerpts, response values, credentials, selected hostname, or arbitrary map keys are attached to the decoding failure. Malformed JSON reports the document root. Cancellation still propagates, and decoding failures do not trigger transport retries or convert a known gateway into a direct inference route.

## Behavioral coverage

| Test source | Covered behavior |
| --- | --- |
| `GatewayV14CapabilitiesTest.kt` | Both complete route fixtures; actual-Boolean-only feature activation; legacy string/number values retained but disabled; unchanged required/typed capability shapes and key-free contract checks. |
| `GatewayCapabilityDiscoveryTest.kt` | Real production API with one metadata GET and zero inference calls; bounded reason/path for invalid Boolean/integer/required/map/header/JSON fields; raw decoder cause and private values absent; cancellation, 404/501, authorization/service failures, timeout, safe stale-socket retry, and no cancellation mutation replay. |
| `GatewayControlNegotiationTest.kt` | Both captured fixtures through production API and negotiation; exact selected URL/model/profile/account retained; isolation headers preserved; unsupported optional control removed; real decoder failure remains blocked before inference. |

## Validation and limits

The focused host suite runs the actual production capability models, API, network client, request configuration, metadata reader, control negotiation, route cache, and connection policy. It uses Ktor MockEngine and MockK for HTTP, with Android/log/unused DTO adapters explicitly confined to the host harness. It is not Android device qualification or proof of a successful model generation.

Final local validation passes 31 focused Kotlin/JUnit tests, 3 independent behavioral review probes, all 377 canonical fast tests, ktlint 1.3.1 on the five changed Kotlin files, and whitespace checks. Source hashes remained stable during execution; the task checkpoint records the exact source and fixture hashes. The three review probes also capture diagnostic logging and exercise nullable/default fields plus bounded-inspection exhaustion. Full Android compilation, complete unit/instrumentation gates, and approval of the integrated PR head remain root responsibilities. No remote publication, model request, merge, or dependency-lock modification is performed by this work package.
