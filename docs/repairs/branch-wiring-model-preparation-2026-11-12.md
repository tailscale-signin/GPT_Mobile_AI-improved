# PR 819 wiring review and selected Gateway model preparation

## Scope and branch identity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
PR: `819`, `fix/voice-chat-crash-and-diagnostics-2026-11-11`, targeting `main`
at `e62bded83b3861816c2c6e97116b30c9eb0920f1`.

The wiring review started at `642250b3acffeddb792d3ba1eb774708d52dd6b1`,
after the diagnostic remediation recorded in
[diagnostic-6412928121631152869-remediation.md](diagnostic-6412928121631152869-remediation.md).
The user subsequently requested that selecting an AI profile load its exact
Gateway model, specifically reporting an advertised but unloaded `qwen3.5-9b`.
Work was isolated from other contributors' checkouts. Later Gateway worker,
lease-recovery, release-scope, and inventory changes were fetched and reconciled
before publication; no other contributor's uncommitted files were changed.

The machine-readable checkpoint and validation identities are in
[branch-wiring-model-preparation-2026-11-12-task.json](branch-wiring-model-preparation-2026-11-12-task.json).
Publishing these changes does not authorize merging the PR.

## Automatic exact-model loading

The previous app could discover that a model was advertised but unloaded, but
its "Load model on gateway" control only revealed a command-line instruction.
The actual loading implementation was reachable only through the host CLI.

The app now connects selected profiles to an explicit preparation operation.
New Chat, the legacy Home picker, Voice Entry, and existing-chat membership,
model, and next-message selection use the same controller and repository.
Restored selections use their persisted chat model. Changing the endpoint,
credentials, selected model, enabled status, or reasoning preference invalidates
the screen's old selection. Conversation reasoning overrides take the same
precedence as the Send admission path.

For a Gateway advertising `features.model_preparation_v1`, the app:

1. Discovers the selected Gateway and reads the exact model's readiness.
2. Requests `POST /gateway/models/prepare` with only the selected model ID and
   an operation ID when loading is required. The `/v1` alias is supported.
3. Shows checking and loading state while bounded, read-only metadata checks
   reconcile the result. Already loading or loaded models do not cause another
   backend load.
4. Reports ready only after exact-model capacity and the original reasoning
   setting have been rechecked. A successful preparation acknowledgment alone
   does not qualify inference or a reasoning mode.

The app's observation window is bounded to two minutes and 30 polling intervals.
An explicit Recheck can reconcile the same operation ID if its original response
was lost or Gateway admission was temporarily busy. It cannot turn an unresolved
operation into a new load ID. The Gateway persists dispatch intent before the
backend POST and coalesces unresolved work by exact backend/model, including
across app or Gateway restart. A later access rejection does not erase an earlier
unknown dispatch outcome.

The Gateway endpoint uses the existing authentication, scope, browser-CSRF, and
optional installation/principal identity checks. It accepts bounded request
fields and uses bounded concurrency, receipt size, journal capacity, and a shared
backend-request deadline. It never generates text, substitutes a model, or alters
reasoning settings. Passive capability and readiness requests still use
`autoload=false`; they do not initiate loads. Verified fresh readback can settle
an existing load receipt. Stale cached or invalidated in-flight observations
cannot settle a newer preparation.

Selecting or rechecking a profile keeps the draft and does not submit it. A late
response for a previous selection cannot change the current profile's status.
Leaving a screen stops that screen's observation; it cannot undo a backend load
that was already dispatched.

Both the app and Gateway need this implementation. An older Gateway receives
read-only checks and shows an update/manual-preparation explanation. Backends
without the supported llama.cpp router load contract keep their host controls.
An absent model, denied metadata, unsupported reasoning setting, or unreachable
backend remains an explicit blocker; no alternative model is selected.

## Additional confirmed wiring repairs

| Boundary | Defect and correction |
|---|---|
| Edited GitHub approval → saved chat | An edited replacement patch could lose its original chat/message/model/profile binding and hardware-verification requirement. Replacement now verifies both patches remain unapproved, durably transfers source identity under the repository lock, then cancels only the old pending review. Unchanged edits cannot cancel their own deduplicated task. Binding failure preserves the original review. |
| GitHub staging → existing approval binding | Identical active patches can deduplicate. Staging must preserve the persisted source and binding digest and reject an attempt to silently adopt another chat's approval. Legacy upgrades derive their identity from the saved source. |
| Native approval decisions → continuation | Fixture approval and rejected-fixture cleanup continuations must retain the original source message and saved evidence even when an unrelated turn intervenes. Durable recovery metadata carries that identity through the queue and persistence boundary. The original `WAITING_FOR_APPROVAL` attempt remains a terminal no-replay boundary. |
| Execution failure → ledger and acceptance | A saved pending/rejected approval could replace an independent provider failure such as HTTP 429 in the task verdict. Ledger and acceptance now retain the primary execution failure while recording approval facts separately. Unknown remote outcomes and accepted deadlines keep their stronger reconciliation/stop conditions. |
| Explicit task limits → continuation | Inline labeled budgets, including `Budget: 10 minutes and 17 tool calls`, now retain both limits and select the stricter applicable value. Historical durations and counts do not become new permissions. |
| Saved approvals → progress header | Historical approval pauses must not label later continuation work as still waiting. Header projection uses scoped native task/receipt evidence while retaining genuinely unresolved approval or commit work. It does not mutate saved run state or authorize replay. |
| Diagnostic archive → next archive part | A section marked both resumable and refresh-on-resume could inherit an old source reference or row cursor. A new part now regenerates current state from its beginning, while non-refreshable history keeps its continuation cursor. |
| Failed issue report → browser fallback | A failed browser handoff used to acknowledge the failed receipt and remove its recovery action. The same fallback draft now remains available for retry or explicit dismissal. Lifecycle cancellation preserves the unacknowledged receipt. |

## Connected paths checked

The review traced dependency injection and producers/consumers for Gateway
capabilities, saved results, queue admission, external-work blocking, recovery
ownership, streaming/display projection, reasoning checks, send preparation,
voice navigation and lifecycle, reporter delivery, GitHub approval and mutation
reconciliation, CI-watch identity, staged diagnostic export, incident recovery,
memory constraints, and UI frame-context recording.

The existing exact model/profile/job/credential fences, unresolved mutation
guards, paused approval retry boundary, and draft ownership remain necessary.
No speculative relaxation of those guards was made to hide an error state.

## Validation and practical limits

The first integrated candidate, `ffd6ab548cc2e824053a747bfce69d743ff58515`,
compiled 165 current production source files and passed 331 tests across 29
selected JVM/Robolectric suites. Those suites included the real entry ViewModels,
chat startup and model changes, model-preparation API/repository/controller,
approval replacement, export continuation, reporter retry, and earlier diagnostic
remediation regressions. Final-source validation and publication identities are
recorded in the adjacent task checkpoint.

Final integrated validation compiled **180 current production source files** and
passed **423 tests across 39 JVM/Robolectric suites**. The full Gateway check ran
**851 tests across 65 suites: 849 passed and two environment-specific checks were
skipped** (Windows Credential Manager and an unavailable bindable LAN IPv4
interface). Its **seven browser tests** also passed. The Python total includes the
50,000-event diagnostic stress case. The real capability capture exposed
32 top-level fields and 99 feature entries with zero inference requests.

The canonical fast command passed **462 host tests**, resource validation,
secret/dependency/policy checks, and Android regex checks. ktlint 1.3.1 passed on
all **56 changed Kotlin files**. The host check first caught an outdated Android
copy of the captured capability fixture; synchronizing it corrected that failure.
One test initially linked to an obsolete cached Kotlin overload; filtering that
stale facade from a derived dependency copy and recompiling the two current tests
corrected the harness binding. Bytecode inspection confirmed the current helper,
and all 39 suites were rerun successfully without changing a behavior assertion.

Host compilation uses real cached dependencies and generated Android artifacts;
it does not replace full KSP/Hilt generation, Android packaging, lint, device
execution, or current-head CI. The earlier canonical Android attempt stopped
when the Gradle daemon disappeared at `:app:kspDebugKotlin`; its incomplete result
is retained in the earlier remediation record. No checksum or Gradle memory
policy was relaxed.

Tests simulate the HTTP boundary and backend state transitions. This workspace
does not establish that the user's physical Gateway has been updated, that its
`qwen3.5-9b` has actually loaded, or that Android native voice/performance behavior
has been qualified on the user's device. Those execution claims require the
updated app/Gateway and actual device or host observations.
