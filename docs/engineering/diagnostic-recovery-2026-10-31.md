# Diagnostic recovery and GitHub integration

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Working branch: `feat/github-operation-resilience-2026-10-31`
- Integrated dependency: PR #767, merged into `main` at `be5d55930df8be6caad2380eea71b2f9a6bb0419`
- Additional integrated dependency: PR #766 at `65d4fca0d9a5093b4dcc55319308c058abb304e0`
- Main integration baseline: `be5d55930df8be6caad2380eea71b2f9a6bb0419`

The supplied investigation covers 7,597 exported records from app commit `acca703a9f88e0391b753daa2052c95bc02305c6`. Its counts (36 errors, 76 warnings, 24 informational transport failures, 93 history compactions) describe that captured run. They are not measurements of this revision. Raw diagnostics, source prompts and private endpoint addresses are not checked into this repository.

## Changed behavior

The reported budget admitted a 32,768-token output reservation against a 32,768-token replay ceiling. The new allocation reduces the **actual transmitted primary output cap** while retaining useful tool evidence and a safety reserve. Tool-dependent work cannot start with zero replay allowance. Configuring a larger context does not erase the conservative 32,768-token replay ceiling or prove that the server serves that larger context.

Recovery classifies native GitHub actions separately. Current CI, PR header and repository reads can reconcile saved writes without a recovery effect ID. Immutable reads may reuse authorized receipts. Unknown mutations remain blocked; local denials and reused receipts report `dispatched=false`. Saved-evidence reader validation also returns typed unsent outcomes.

Llama review measures the JSON-escaped task, draft and evidence inside a complete system/user/schema envelope. This remains a conservative UTF-8 estimate, not a serving-tokenizer measurement. Actual final request admission still validates the serialized provider payload. A review uses the observed route capacity, or a conservative fallback, with separate output headroom and a safety margin. Receipt fragments, batches and final consistency summaries all have bounded admission. A draft or task that cannot fit by itself blocks publication rather than silently losing content.

The reviewer uses a bounded JSON schema only after an observed synthetic compatibility probe succeeds on that account/route/model/build/template fingerprint. Thinking is disabled only when a template probe has verified the control. Otherwise strict local JSON and evidence-coverage validation still apply. A length-limited, reasoning-only or capacity-rejected review splits its failed evidence batch; it cannot approve an answer without a complete visible verdict.

Private drafts, original receipts and validated batch verdicts are saved as protected, content-addressed records. A retry resumes review from those records before primary planning. It does not append another recovery narrative or repeat completed GitHub work. Permission and source checks run when records are read or written and again before publication. Corrupt or inaccessible checkpoints block the retry. Successful state is persisted before emitting the answer. This checkpoint covers the review stage; it does not establish that a subsequent independent claim-extraction or delivery stage completed.

## Investigation ledger

“Retained” means the pending parent branches already implement the relevant mechanism; this branch preserves it and records its limits. “Deployment/device” means code alone cannot qualify the diagnosed environment.

| Finding | Implementation or retained behavior | Verification and remaining boundary |
| --- | --- | --- |
| F01: zero replay allowance | `ReplayAllowance` jointly allocates actual output and replay; `AgentRunner` rejects zero allowance before dispatch. | Reported input/output budgets and several context sizes have behavior regressions. |
| F02: reconciliation reads blocked as writes | `ResponseRecoveryContext` uses action-level GitHub authorization and immutable-read policy. | Fresh repository/CI/PR header reads and blocked uncertain writes have regressions. |
| F03: local rejection marked sent | Typed local guards and evidence-reader errors use `dispatched=false`; reused receipts preserve reuse provenance. | Recovery tests cover unsent rejection; transport/journal tests distinguish rejected, accepted and unknown work. |
| F04: oversized reviewer requests | Complete escaped envelope estimates, dynamic receipt fragmentation, at most 16 records per batch, bounded claim reduction and current capacity rechecks. | Unicode/escaping, long evidence and provider capacity rejection regressions. Actual tokenizer/template overhead remains a server measurement. |
| F05: reasoning exhausts verdict output | Separate bounded review output, capability-tested schema and verified thinking control; incomplete verdicts fail closed and smaller batches retry. | Reasoning-only `finishReason=length`, malformed verdict and schema probe tests. Capability observations cannot guarantee arbitrary future model output. |
| F06: retry restarts planning | Protected review checkpoint and prompt-addressed successful verdict cache. | Restart test forbids any primary planning and confirms successful earlier batches are reused. |
| F07: partial repository arguments | Normalize partial names and GitHub repository URLs against the selected repository before facade validation. Conflicts and non-string values are rejected locally. | Public facade regression exercises partial owner, partial repo, URL and conflicting repo. |
| F08: dependencies admitted without prerequisites | Admission closes over the complete batch dependency graph. Excluded dependents remain in `ToolBatchCheckpoint` with typed `DEPENDENCY_DEFERRED` receipts and can reopen on explicit budget retry. | Dependency admission, graph barriers and interrupted checkpoint tests. It does not silently grant more calls. |
| F09: local controls poison circuits | Unsent typed budget/dependency/argument/reconciliation outcomes do not count as provider/tool failures. | Existing failure policy and runner regressions retained; real dispatched failures still count. |
| F10: Llama metadata/template EOF | Retain route-scoped connection recovery, safe probe retry and capability caches from #767. | Mock transport/capacity tests. Deployment/network availability requires actual server evidence; generation is not replayed after output starts. |
| F11: MCP connection failures | Retain per-route connectivity gating, bounded reconnect and protocol negotiation independent of native GitHub. | Existing MCP contract tests remain in Android CI. Host/DNS/service configuration cannot be repaired from a captured log. |
| F12: engineering endpoint returns 404 | Negative-cache unsupported capabilities for 60 seconds per connection identity; require `enabled=true` and a principal before establishing engineering readiness. | HTTP 404/disabled/account-separation tests. Deploy the engineering service at the configured gateway endpoint and verify it separately from inference. |
| F13: UI stalls | Review parsing, hashing and evidence preparation run on `Dispatchers.Default`; streaming buffer reuses its content snapshot. Retain adaptive coalescing already present. | Code change removes repeated string construction. Release-device frame/layout profiling is still required; no latency improvement is claimed. |
| F14: oversized resolver | Retain the smaller resolver stages from #766's latest head. | Resolver tests and packaged ART method-size gate remain canonical; a host source compile does not qualify DEX. |
| F15: deferred enrichment | Retain persisted deferred jobs and runtime-idle/model-available wake subscriptions. Add explicit persistence/wake-trigger diagnostics. | Existing queue/restart tests remain in Android CI; do not treat `workerScheduled=false` as lost work by itself. |
| F16: embedding lifecycle | Preserve idle and memory-pressure release. Log actual idle age, required idle interval and Android trim signal; separate process counters from engine footprint. | No speculative residency change. Physical-device memory/reload hysteresis requires measurement. |
| F17: incomplete diagnostic accounting/privacy | Classify informational EOF/DNS/connection failures as actionable transport warnings; numeric `promptTokens` fields survive prompt redaction; causal attribution is explicitly unverified. | Export/privacy regression checks numeric metrics and a private prompt canary. |
| F18: broad schema overhead | Defer authorized write/commit/stage/release/watch tools from the initial catalog until discovery. Keep consequential user-decision tools available. | Existing progressive catalog tests; authorization remains distinct from advertisement. |
| F19: unverified gateway settings | Retain explicit requested/effective/observed provenance; use current serving-capacity observations for review admission. | Mock admission and probe tests are not confirmation of the deployed server's settings. |
| F20: benign platform diagnostics | Retain separate optional-platform/JNI/tensor classifications; transport warnings do not reclassify unrelated informational hints. | Existing structured diagnostic classification tests. Categories are observations, not proof of root cause. |

## Other requested integration changes

[GitHub operation resilience](github-operation-resilience.md) describes verified lost-write reconciliation, structured file receipts, strict issued pagination cursors, actual minimal GraphQL header queries, bounded API admission, packaging process deadlines and asynchronous native check watching. Packaging runs in a CI JVM, independently of the Android GitHub API dispatcher. Native check watching uses persisted background polling with backoff; it does not claim a GitHub webhook subscription.

## Validation and qualification

Focused host tests compile production runner, tool facade, workspace, journal, scheduler, review and native HTTP classification code with inert Android boundary fixtures. Three unchanged resolver/provider integration cases require the Android bundle; they are not counted as host passes. Separate context/network/diagnostic tests exercise real Ktor mock HTTP and diagnostic exports. The canonical host command is `bash scripts/validate-local.sh fast`; the full Android command is `bash scripts/validate-local.sh full` with JDK 21 and Android SDK 37.

Local results: 388 runner/GitHub JVM tests, 65 context/network/diagnostic JVM tests, and 19 recovery JVM tests passed. The recovery harness compiles the production recovery wrapper with inert Room/research/platform boundary records. The canonical fast command passed 278 host policy/script tests and its resource, dependency, secret, architecture and regex checks. Changed Kotlin sources passed ktlint 1.3.1; whitespace checks passed. These are host checks, not instrumented-device executions.

This environment has JDK 17 and no Android SDK 37. Android compilation, Robolectric, heap stress, lint, packaged ART gates, real GenieX packaging, emulator checks and actual server/device performance must pass on this PR's current head before qualification. The draft PR is not merge approval, an APK release, or a verified gateway deployment. No merge, release or deployment is performed by this change.

## Local autonomous engineering and GitHub presentation

Autonomous engineering is derived from the current profile and enabled by default for local GitHub tasks. Local Llama and Ollama connections must point to a loopback, private-network or Tailscale address; on-device LiteRT also uses the native autonomous instructions. Cloud provider types and public Llama/Ollama destinations do not expose the feature. Disabled or different profiles cannot inherit it. The same policy is enforced before saved-job dispatch and reconnection, so hiding the panel is not the authorization boundary.

The native agent receives the local autonomous task instructions on both primary request paths: plan, inspect, implement, verify, independently review, prepare a draft PR for requested coding work, watch CI and repair within the accepted budgets. Existing tool permissions, stop controls and consequential decisions remain authoritative. The local panel shows the mode as on without an opt-in switch. Saved host jobs remain a separate explicit setup for duration, branch and acceptance criteria and require verified engineering capabilities; a plain inference server is not presented as a durable job service. Remote profiles retain ordinary GitHub chat tools without autonomous-job availability.

The pinned header uses one line for activity, with ellipsis for long labels and a separate theme-primary total timer aligned on its baseline. Presentation removes the persistent `Status` prefix and embedded elapsed clock from the activity. A shared 1,800 ms linear phase drives the themed text highlight and loading-bar gradient. Frame updates are read in drawing, and timer updates are isolated from answer layout. The same header component renders saved-job progress without another timing implementation.

“Working in your repository” is plain aligned text without a card, border or background. Its title, summary and milestones use 50% opacity. A small animated three-dot cursor appears after the GitHub response while generation is active and disappears when generation stops. It is a presentation element and cannot enter saved, copied or exported answer text.

For this addition, seven JVM behavior tests for profile eligibility/defaults/scope and activity presentation passed, and the canonical fast command passed 278 host tests. New Robolectric cases cover stable activity announcements during panning, the separate timer, progress removal on completion, and the generating cursor lifecycle. These Compose tests require Android CI and have not been executed locally. Physical-device visual/frame performance remains unmeasured. The current parent #767 is integrated through `cff38f846fbcea3efad887ae5f9215383306f8fd`, including its local adapter repair and main at `548521a3419021e95af8c3ac9e6c7f4656a527d9`.

## Integration regression repairs

Canonical and GenieX validation of `7a95f71fe55910fba74b8be9b94e8dcfa6c03a0c`
exposed integration failures that the earlier host subsets did not cover. A decision
could inherit a dependency on a deliberately deferred mutation in the same batch.
Decision-stage dependency graphs now contain only the authorized decision calls;
mutations remain undispatched until a later plan consumes the answer. The existing
mixed decision/write behavior regression is retained.

Bounded GitHub error bodies now use the shared Kotlin JSON parser, so permission
and secondary-rate-limit classification is consistent in Android and JVM execution.
Specific MCP, native, recovery and fatal diagnostic categories take priority over
the informational transport fallback. New regression cases retain both component
attribution and transport-warning coverage.

Discovery regressions now verify the intended narrow initial inventory followed by
explicit admission of the exact write/commit schemas, including long retained tasks
and continuation. They still check conversation isolation and live revocation. The
budget boundary test checks the structured reserve field name. The completed-job
UI test checks the stable accessible activity label and its separate elapsed timer.
The queued-authentication regression uses real coroutine time with bounded waiting
instead of advancing a virtual admission timeout while HTTP completes on real IO.

The repaired runner/GitHub host bundle passed 393 tests, including decision/write
ordering, bounded HTTP error classification and queued authentication. The context,
network and diagnostic host bundle passed 75 tests. The canonical fast command
passed 278 host tests, and changed Kotlin passes ktlint 1.3.1. These host fixtures
do not execute the Android resolver or Compose UI; the repaired head must pass the
complete canonical, GenieX, packaged-runtime and emulator workflows before merge.
