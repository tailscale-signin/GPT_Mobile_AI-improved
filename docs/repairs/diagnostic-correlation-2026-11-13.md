# Diagnostic follow-up for issues 943–969

The capture identifies debug build 141, version 0.9.66.7, at source revision `79c58260de528068ee07e03e9d2ec0c5ab9e74b8`. All 20 section digests and three reconstructed records verified across 8,513 JSONL rows. The selected chat retains one failed and five completed run rows. The critical log also contains events from other runs. The capture was bounded by retained history.

| Issues | Observed evidence | Disposition |
| --- | --- | --- |
| #943–#944 | GitHub returned HTTP 200 for source read and search. The app had already used the 24,576-byte result budget. | Preserve the completed execution and its retained result. Report separately that model delivery exceeded the budget, with its phase and byte counters. |
| #945–#946 | The same counts show 47 of 118 capabilities verified, 64 blocked, seven untested, 14 pending continuations, and 199 calls remaining. Several actions required an owned fixture or fixture-only approval. | Reports now include blocker counts and distinguish pending local fragments from upstream pages when the verdict provides them. Real fixture qualification still requires its authorized resources and cleanup proof. |
| #947 | The native GitHub watch was rejected by the local source or destination grant before dispatch. | Record the local authorization scope. The existing grant and approval checks still apply. |
| #948 and #950 | Catalog discovery was blocked by another operation in the same batch. The prior operation had failed. | Keep ordering and authorization. Independent catalog discovery can run once the earlier operation finishes. |
| #949 | Android location permission was missing. | Return a typed precheck rejection and capture the Android permission scope. The user must grant location access to obtain a fix. |
| #951 | Zero data retention was required, and the selected model had zero entries in the provider's ZDR catalog. | The existing report captures the exact policy blocker. A compatible endpoint or model is required. |
| #952 | The matching Anthropic HTTP 400 invocation was rejected for insufficient account credits. | Capture a bounded billing classification and retain the observed HTTP status. Restoring credits is an account action. |
| #953–#954 | The model sent the same empty PR call twice. Both failed before dispatch. | Use the repository PR list for a selector-free call. Explicit single-PR reads still require a positive selector. |
| #955 | The app recorded HTTP 200 with no visible or reasoning bytes in the terminal invocation. The archive does not establish the backend cause. | Link the owning invocation to the delivery failure and capture verified terminal job status and result quality where available. |
| #956 | The original code digest matches the app-owned `COMBINED_REQUIREMENTS_UNMET` code. | Retain the specific code and bounded requirement-gap category and issue count. The historical requirement that failed is unavailable. |
| #957 and #961 | The selected OpenRouter model had no ZDR endpoints. | The existing admission report identifies the model-catalog blocker. The selected privacy policy still applies. |
| #958 and #963 | Anthropic returned HTTP 400. These reports arrived after the archive and do not identify a rejected parameter or billing cause. | Reports distinguish insufficient credits from other 400s when the response explicitly confirms it. |
| #959 | The app caught a policy-change exception for the tools section. | Capture that a resume must revalidate settings and preserve the bounded changed-field list. |
| #960 | The local Llama route's reasoning-capability probe timed out. | Capture whether the probe supplied a verified gateway observation, including the backend dependency and status if observed. |
| #962 | The NVIDIA endpoint returned HTTP 410. | Keep the typed unavailable-source status and the original HTTP evidence. Restoring that route requires provider or profile configuration. |
| #964 | The original Groq error-code digest matches `tool_use_failed` after reasoning output. | Capture the provider tool rejection as a bounded code without the rejected arguments or output. |
| #965–#968 | The TLS exception chain includes a protocol failure. One request received HTTP 200 before interruption. | Capture TLS category and whether a response had started. The historical endpoint or network cause remains unverified. |
| #969 | The retained exception digest matches a local Combined budget exception. | Capture pre-dispatch budget denial separately from provider transport failure. |

The repeated workflow listings returned page one of a 55-workflow inventory. The saved result contained a page-two recipe with the repository and ref defaults filled in by the executor. Recovery now accepts those defaults only when the issuing receipt corroborates them. An already consumed page stays suppressed, and continuation uses the same authorized tool and ordinary budget. Reused page-one receipts do not establish coverage of a new page.

The capture also records a Groq rate limit and three MCP discovery conditions: a refused connection, an endpoint configuration error, and a discovery configuration error. The report preserves a typed provider rate-limit classification when the terminal event supplies the provider. MCP connection and configuration recovery requires the affected servers or their exact settings. App diagnostics already retain their distinct classifications.

The hosted report service advertises `EXECUTION_OUTCOME_DIAGNOSTICS_V1` for the new fields. The app checks that capability before submission, preserving approved bytes if the service needs an update. The raw archive and private arguments, prompts, location, and provider messages are excluded from GitHub updates.
