# Local tool recovery solution plan — 2026-10-18

Target branch: `feat/integrated-runtime-workspace-reliability-2026-10-18`.
Evidence: `app-diagnostics-1809040178851972411.jsonl`, captured app commit
`d21ebcc480ccb5dd063b427265ae95f8527e428a`. Implementation reviewed against branch
head `0bd33630`. No credentials, private addresses, arguments or result bodies
are copied into this plan.

## Diagnosis and repair status

| Priority | Finding | Solution and status |
| --- | --- | --- |
| P0 | Local adapter receives nine tools, but the 8192-token planner repeatedly registers only one with an 8192 output preference. | Implemented: allocate a usable catalog before expanding the reply allowance. Retain required capabilities, fitting history anchors, result headroom and a modest reply reserve. For contexts of at least 4096 tokens, add ranked optional schemas while preserving up to 512 reply tokens; allocate remaining reply space afterwards. Smaller contexts retain conservative admission. Required input still fails intact when it cannot fit. |
| P0 | Research can retain search but omit the reader required to inspect its results. | Implemented: protect search plus URL reading for research, browsing, source verification and citation requests. Protect file slicing for explicit file/document reading. Only already-authorized tools are considered. |
| P0 | Registration is mistaken for successful tool use; the log does not prove any completed tool round trip. | Implemented: explicit prompt-bridge completion diagnostics report registered count, prompt call count and native-tool-use status. Existing execution/result events remain authoritative. A normal text completion is not a successful tool-use test. |
| P1 | Parser rejects an exact bare name/arguments call object. This is a compatibility gap; this log does not contain enough model output to prove it caused these attempts. | Implemented: opt-in acceptance only in prompt-bridge mode. Strict parsing remains the default for native mode. Bare objects must contain only name and arguments, and the existing registered-name and JSON-object argument checks still apply. Add a concrete typed-call example to the bridge instruction. Existing tool budgets, authority checks, execution and continuation are reused. |
| P1 | GGUF load is interrupted inside native code, then the exact artifact/backend is quarantined. Memory admission passed. | Device investigation remains required. Capture the isolated worker failure/tombstone with the artifact identity and backend. Use the existing controlled Verify load flow; do not clear quarantine on ordinary retries or blindly switch the backend. This log does not identify a safe native-code patch. |
| P1 | MCP ports 8101/8102 time out; external MCP hosts fail DNS. | Verify phone-to-gateway routing, listeners and phone DNS separately. Re-test health and an authorized direct call before model inference. Client changes cannot make an unreachable service reachable. Never fabricate successful results or bypass authorization. |
| P1 | One request receives no tools, and another is terminated after configuration changes. Recovery attempts report BLOCKED_PERMISSION. | Verify the current profile bindings and request authority on-device. Prepare a fresh authorized request after profile changes. The diagnostic does not identify which permission blocked recovery, so do not remove the permission guard. |
| P2 | Conversation debug export rejects its schema. | Already repaired on the requested branch: DebugExportSchema is shared by the writer and bundle reader, with explicit v2/v3/v4 compatibility. Verify its existing regression suite rather than duplicating the repair. |

These runs use GPU. They establish neither NPU success nor NPU failure. Local
Assist is OFF in the captured requests; primary local chat tools must operate
independently of that optional feature.

## Acceptance checks

1. With a large output preference and the captured nine-tool style catalog,
   verify that fitting tools remain admitted and total estimated prompt, reply,
   template and result reservations stay within model context.
2. Verify research keeps both search and source reading under catalog pressure.
   Verify required tool schemas fail explicitly if they cannot fit.
3. For prompt-only runtimes, exercise typed JSON, existing tagged/native
   wrappers and opt-in bare JSON. Unknown tools, disabled tools, malformed
   arguments, extra fields and explicitly non-call JSON must not execute.
   Stream split tool objects without leaking them into the visible answer.
4. On the phone, execute a calculator request, then search → read URL → final
   answer. Confirm ToolCall and ToolResult events and real observations;
   `COMPLETED` text alone is insufficient. Revoke permission before dispatch
   and confirm execution stops. Check network failures remain explicit.
5. Repeat separately for LiteRT GPU, a verified NPU package, and GGUF only after
   controlled native verification succeeds. Record artifact/backend identity.
6. Export a conversation debug ZIP and verify the manifest, required records,
   supported schemas and digests.

## Validation performed

- Host Kotlin/JUnit: 38 planner and prompt-bridge tests passed, including five
  new regressions. Production planner, output allocator, bridge and turn type
  were compiled; lightweight AgentToolDefinition and MessageV2 substitutes
  isolated these pure tests from Android/Room. This is not a full app compile.
- Existing debug-export bundle suite: 3 tests passed using production schema,
  bundle code and the SHA-256 helper extracted unchanged from ChatDebugExport.
- ktlint 1.3.1 passed for all five changed Kotlin files; git diff --check passed.
- Full Android unit tests were attempted. The wrapper could not download
  Gradle in this restricted environment; a preinstalled Gradle 9.8 offline run
  then failed resolving the uncached Android legacy-kapt 9.4.1 plugin.
- Android device inference, GGUF worker diagnostics, NPU execution and live MCP
  connectivity still require the device/CI environment.
