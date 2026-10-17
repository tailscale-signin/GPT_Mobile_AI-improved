# Local tool context repair — October 17, 2026

## Evidence

Source: app-diagnostics-405691826416440321.jsonl, 5,926 records; app 0.9.53.0 (122), commit 6bc2df4d.

Seven requests resolve ten tools and expose nine after GitHub task routing. The export reports prompt-bridge mode because the installed artifact declares native function calling unavailable. Six context plans register zero tools; one registers one. No tool execution events are present. The requests complete with text, so this export demonstrates lost tool availability rather than a confirmed inference crash. Local Assist is intentionally skipped while the primary local runtime owns inference.

The adapter embedded every prompt-bridge schema in the system prompt, then charged schemas again in the planner. It also sent that full catalog after registering only the selected subset. A large output preference could consume the remaining optional schema budget even without duplication.

## Changes

- Plan against the actual rendered bridge catalog, charging each selected catalog once. Construct the runtime system prompt from the final selected tools only.
- Reserve required tools, or one feasible relevant tool when none is explicitly required, before allocating output. Context, result and template bounds still apply; the full catalog is not guaranteed to fit every model.
- Preserve the native OpenAPI callback and constrained decoding route for capable artifacts. Artifacts without a native decoder use app-managed execution; these changes do not convert model weights or add an unsupported decoder to LiteRT.
- Recognize explicit Qwen/Hermes tool tags, single Mistral call envelopes, and single OpenAI function envelopes, including JSON-encoded argument objects. Keep unknown tools, malformed calls and ordinary JSON non-executable. Multi-call batches are not executed by the single-call bridge.
- Keep supported call envelopes hidden across streaming chunk boundaries, with a bounded buffer. Ordinary prose streams immediately.
- Recheck request authorization before tool dispatch and after execution, before returning observations or starting another inference step.
- Run adapter preparation and generation flow collection on Dispatchers.Default instead of inheriting the UI dispatcher. This removes local planning/serialization work from the UI thread; hardware GPU contention still requires device profiling.
- Correct per-tool registration diagnostics so omitted tools are identified as omitted, rather than reported as prompt-bridge registrations.
- Classify Android codec discovery warnings as informational and Adreno shader/pipeline failures as actionable graphics observations. Preserve the raw evidence.

## Other observations and limits

The export includes repeated rendering windows with roughly 750 ms maximum stalls and Adreno WebView shader/pipeline failure messages. It does not establish a causal stack linking those failures to local inference. The dispatcher change addresses avoidable UI-thread work, but resolution of GPU/driver stalls requires another device run. Android process exit reason 10 is not evidence of an application crash. Unsupported audio codec enumeration does not establish that chat or tool execution failed.

## Validation

Added regression cases for high output preferences, once-only rendered schema accounting, native-format streaming, denied and malformed envelopes, prompt-bridge execution and permission revocation, and diagnostic classification. Existing native callback coverage remains intact.

Passed: 130 Python repository script tests, Android regex compatibility (562 literal patterns, no errors), tool statistics coverage (31 producers), and git diff whitespace checks. The Python suite includes synthetic artifact/device-output fixtures; it is not on-device validation.

Blocked locally: focused Android/Kotlin unit tests could not start because the Gradle distribution is not cached and the environment cannot download services.gradle.org. Full app compilation, Kotlin unit tests, Android lint and on-device tool/model testing remain required. No APK or release was built here.
