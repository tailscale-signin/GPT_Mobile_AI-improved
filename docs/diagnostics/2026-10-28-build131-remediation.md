# Build 131 diagnostic remediation

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Working branch: `feature/github-engineering-modernization`
Starting head: `7b826ba6a4d795ed1ae24eef067fd4b252d15ea4`
PR: #759, stacked on #758 (`feature/improve-in-chat-workspace-ui`).
Evidence: `app-diagnostics-9174620254644383624.jsonl`, 7,003 records,
app 0.9.63.0/build 131, app commit `4d26a8d0b851f1274c87d92b44858fff8546ca68`.
The capture timestamps are October 28 in America/Toronto (October 29 UTC).

Intent: repair GitHub tool-name rejection, final-response tool protocol handling,
and avoid unused full-window glass capture. Preserve authorization, mutation
receipts, single-pass Combined behavior, and bounded retries. Raw private logs
are not committed. This document is the repository checkpoint; the Android
engineering task database is not available to this editor.

## Evidence and disposition

- 13 failed GitHub calls: five real repeat suppression envelopes and eight plain
  rejection messages. `ToolBatchPlan` incorrectly labels unknown/unavailable
  names as repeats. A bare `github_search` is rejected even when the advertised
  name is `github_search__github_api`. Unknown legacy names such as
  `github_read_file` must receive capability guidance, never fabricated results.
- Five tool markup failures: two DeepSeek responses with profile tools disabled,
  and three llama.cpp responses after tool schemas were removed. No raw control
  payload is available in this privacy-redacted export. Keep the markup guard;
  make tool-free requests explicit and allow at most one protocol repair before
  any answer has been published, outside single-pass Combined runs.
- 68 performance incidents (44 frame windows and 24 skipped-frame messages).
  Frame windows include glass and streaming effects but do not prove a GPU
  driver cause. Avoid recording the full-window haze layer when only the inline
  chat backdrop is consumed. Device profiling is required to verify improvement.
- 13 MCP reachability failures for the configured private endpoint on port 8101.
  Existing per-connection backoff progresses to 240 seconds. The app cannot start
  the external service or repair routing. Restore that configured service and
  use Check again; do not switch endpoints or credentials automatically.
- Two unexpected HTTP stream endings on port 8080 entered bounded provider
  retry. The log does not establish that retry failed; retain partial-output and
  mutation replay safeguards. Verify the desktop server/gateway independently.
- One SAF native-path permission denial explicitly offers copying. This is the
  scoped-storage fallback, not evidence of a corrupt model or permission bypass.
- ASUS `asus_leak_detect_proj` SecurityExceptions originate in framework
  `BinderInternal$GcWatcher.finalize`, not application frames. Do not suppress
  arbitrary finalizer failures or request hidden-system permissions.
- `ro.hardware.chipname` access denial, optional feedback tensors, job foreground
  hints, optional jank monitoring, IME cleanup and ordinary GC messages are
  platform observations. Preserve evidence; the specific inaccessible optional
  hardware property is not a model-load failure.

## Implemented changes

- Separate `TOOL_NOT_AVAILABLE` from genuine repeat suppression. Give the model
  one bounded opportunity to use an advertised capability instead of immediately
  disabling the entire catalogue. Bare read/search/context aliases resolve only
  to one already-authorized namespaced tool; mutations, foreign namespaces and
  ambiguous providers never auto-resolve.
- Explicit tool-free system instructions and `tool_choice=none` on compatible
  requests prevent historical tool calls from defining the final response.
  Retain the separately authorized prompt-tool bridge and initial gateway-only
  execution; once client tools are advertised or replayed, gateway injection is
  disabled, including the delegated-request header override.
- Permit one protocol recovery for an empty tool-free answer. A partial visible
  answer, single-pass Combined run, or internally managed tool session cannot
  trigger this retry. Completed mutations are retained and are never replayed.
- Allocate the full-window haze source only while a window consumer needs it.
  Inline backdrops retain their own source; previews do not register against the
  parent window. Popup disposal releases the consumer registration.
- Classify only the exact Android libc denial of the optional SoC label as
  informational. Generic permission errors and finalizer failures remain visible.

## Validation

- `bash scripts/validate-local.sh fast`: PASS, 212 Python tests and repository
  policy, secret, regex, resource, icon, provenance and whitespace checks.
- 14 focused Kotlin/JUnit cases: PASS in an isolated JDK 17/Kotlin 2.2.20 harness
  compiling the production runner, batch planner, markup guard, generation safety,
  replay compactor, retention, usage and failure policies. Platform/Android,
  catalogue selection and unused persistence dependencies were stubbed; this is
  not a full Android integration build.
- Added Android-suite regression coverage for provider wire requests, separately
  authorized gateway tools, prompt-tool bridge preservation, and single-pass
  Combined behavior. These require the repository's Android test environment.
- Changed Kotlin files pass ktlint 1.3.1.
- Attempted targeted `:app:testDebugUnitTest`: BLOCKED before compilation by
  `Network is unreachable` downloading Gradle 9.8.0. This host also has only JDK
  17 and no Android SDK; the repository requires JDK 21 and SDK 37.
- Physical ASUS rendering, external MCP reachability, desktop stream stability
  and local inference qualification remain unverified. No all-green or device
  recovery claim is made. CI must validate the published head.

