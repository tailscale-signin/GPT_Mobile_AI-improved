# Completion and search reliability update

## Runtime behavior

The v13 transport asks the backend for one tool call per model round (`parallel_tool_calls=false`). A composite `web_search` call can still execute several authorized search engines internally. If a backend ignores sequential calling, its unexecuted batch gets at most one repair request. No call in the rejected batch is executed, relabeled as another owner, or silently dropped after execution. Existing tool authorization and argument validation remain in place.

A response with neither visible answer text nor executable tool calls also gets at most one answer-only repair when the output allowance permits. Reasoning is never promoted into the answer. The repair preserves the original request and completed evidence, disables tools/thinking, and has an output cap of at most 1,024 tokens. With an explicit output cap, the transport subtracts the first attempt's reported output usage; unknown usage or an exhausted allowance terminates with a specific error instead of starting an unbudgeted retry. Both attempts share the original wall-clock deadline. Reported usage is combined only where both attempts provide the corresponding measurements.

An explicit `[DONE]` terminates reading without requiring a further blank line or socket EOF. A completed finish marker permits a short trailing-usage grace period (two seconds by default), after which the transport closes a lingering backend stream. A bare `[DONE]` can complete text, but never authorizes unfinished tool fragments. Premature EOF, malformed tool arguments, duplicate argument keys, unauthorized calls, and incomplete tools are still errors.

Android stops collecting a response at its first `Done` or `Error` event and flushes pending text. Late socket-close errors cannot overwrite an already completed answer. Cancellation still propagates and releases upstream resources.

## Multi-engine search

The Android search aggregator attempts every unique selected compatible engine, rather than silently selecting only two. Existing per-engine permissions, profile selections, and shared tool/byte budgets still apply. Parallel mode permits three concurrent engines; sequential mode still visits all selected engines. Each execution has an isolated 20-second timeout. A failed engine or optional crawler cannot erase successful search evidence. Duplicate URLs retain the names of all contributing engines. Only successful evidence is cached, and query lock storage is bounded.

The aggregate is used by primary chat and the delegated research tool set. Disabled, unauthorized, or schema-incompatible tools are not implicitly enabled. Budget exhaustion may prevent remaining engines from running and is returned in the result metadata.

## Deployment

Install the Android build and deploy the matching gateway code; updating an APK alone does not replace the Python server running on the PC. The relevant Python changes are in `gateway_v13_runtime.py` and `gateway_v13_transport.py`; they must stay with their matching `gateway_v13.py` and `gateway.py` launcher. Back up local configuration and update code without overwriting credentials, memory stores, or databases, then restart the gateway process.

The supplied October 5 diagnostics advertised gateway 13.0.2, while the repository source used for this branch identifies itself as 13.0.0. The exact mixed-owner error string was not present in that repository source. Confirm the actual deployed code path rather than assuming a version label proves these fixes are installed. Live validation against the deployed server is still necessary.

## Validation

Run focused gateway regressions from the repository root:

```bash
python -m unittest discover -s gateway/tests -p test_gateway_completion_recovery.py -v
python -m unittest discover -s gateway/tests -p test_gateway_v13.py -v
```

Android regression coverage includes `ModelTokenComparisonTest`, `ApiStateTerminalCollectionTest`, and `MultiEngineSearchToolTest`. Use the repository's Gradle test/build workflow for Android validation. A Python-only test pass is not an Android build or an end-to-end phone/gateway test.

The log also contains provider DNS failures. These transport/search changes do not repair the device's DNS/network configuration or guarantee third-party service availability.
