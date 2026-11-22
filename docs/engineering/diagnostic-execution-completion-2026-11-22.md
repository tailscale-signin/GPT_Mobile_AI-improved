# November 22 diagnostic execution and UI remediation

## Scope and ownership

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base: `main`, commit `c023735c02368fef4c748cf7be97b9fcfe2cd513` (PR #2126).
Working branch: `fix/diagnostic-execution-completion-2026-11-22`.
Release candidate: Android **0.9.66.13**, version code **147**.

The user authorized all recommendations in the November 22 diagnostic/source audit,
one draft PR, validation, merge after green checks, and publication after main is
green. Concurrent remote commits must be reconciled; no force push or bypass of
required verification is authorized or needed.

The captured build made 46 tool calls and 11 model invocations without a repository
mutation. This change addresses the concrete local defects and improves recovery
and diagnosis of external provider and connectivity conditions. Upstream service
availability, actual account credits and the user's phone/PC network are not
claimed repaired by changing client code.

## Implementation and acceptance map

| Audit requirement | Implementation boundary | Meaningful acceptance evidence |
| --- | --- | --- |
| Immutable approval/classification conflict (#2243, #2248) | Separate immutable preflight, recheck and classification records; current classification projection; idempotent identical append | Real Room/vault approval, classification and restart; changed immutable payload rejected; concurrent identical append preserves original bytes |
| Accurate persistence failure | Typed `WorkspaceEvidenceConflict`, application-persistence phase, hashed collision correlation | Cause-chain classification remains local and nonretryable; no protected payload is logged |
| Provider retry checkpoint mismatch | Correlated checkpoint identity, owner and revision validation retained through wait/journal persistence | Known-rejected request restores settled receipts; changed/stale/unknown boundaries remain rejected with exact cause |
| Reasoning-only completion (#2250) | Separate no-output kinds and one durable bounded operational repair | Empty/reasoning-only, duplicate, output-limit, cancellation and restart cases; repair cannot loop indefinitely |
| Blocked execution versus partial findings | Explicit execution and delivery state propagation | Terminal run, message and diagnostic state retain the same blocking cause |
| Small saved-source pages | Producer-issued larger recovery and continuation alternatives | Exact issued cursor coverage; no omitted, duplicated or invented source bytes |
| Path-only search loses meaning | Bounded path, query, mode, filter and revision observations in summaries | Compacted replay retains useful paths and incomplete-coverage truth |
| Repeated exploration | Branch/blob reuse and implementation milestone guidance | Same-task two-feature fixture restores source, creates a branch, validates two exact staged edits, commits and reads the pinned result; accepted commits remain distinct from verified feature completion |
| Invalid fuzzy phrase (#2245) | Typed argument correction to supported search mode | Invalid fuzzy phrase produces actionable correction without remote dispatch |
| Mechanical high-reasoning requests | Conservative cursor-only stage policy | Coding, review, explicit reasoning choices, mixed/error/final rounds retain selected behavior |
| Streamed provider errors (#2249, #2251, #2252) | Shared HTTP/SSE semantic classifier and safe retained provider evidence | HTTP 200 plus billing becomes action-required; transient continuation preserves prior payload and forbids unsafe replay |
| Provider wait (#2246) | Provider-scoped durable waits plus explicit refusal, persistence and scheduler outcomes | Every terminal rate-limit path records an owned durable wait or a closed no-wait reason; semantic rate limits after HTTP 200 retain transport truth |
| Comparison coverage (#2247) | Exact issued comparison receipt-chain continuation | Cursor-only child pages contribute to the pinned base/head comparison; pending pages still block completion |
| Expired task windows (#2242, #2244) | Explicit renewal before restore plus retained timing/renewal evidence | Accepted renewed window preserves completed work; no execution under an expired window |
| Time accounting | Bounded run/task interval projection | Observed active work, provider wait and user wait are non-overlapping; unobserved elapsed and legacy unlocated wait totals remain explicit |
| Compact diagnostic/report correlation | Typed provider, persistence, no-output and journal observations with mirrored closed reporting contracts | Same-message restart evidence retains its cause; public aliases hash private digests; unsupported hosted reporters retain approved payloads until capability support is available |
| Gateway timeout (#2253) | Shared origin-level TCP cooldown, path-scoped protocol failures, explicit reconnect invalidation | Gateway aliases share unreachable-service state; another port remains independent; reconnect and successful handshake recover |
| Last-known Gateway display | Existing retained control snapshot and separate live phase | Stale settings remain displayable while live write controls remain gated |
| Repeated MCP discovery | Separate preparation and catalogue authority epochs; typed cache-miss/invalidation reasons | Recent-profile and inference changes preserve unchanged catalogues; credential/tool authority changes invalidate them |
| Compact decisions | One active composer decision, queue navigation and expandable detail | Keyboard/large-font layout remains bounded; queued decisions remain reachable |
| Notification answers | Non-exported receiver, durable worker and exact decision/chat/run/profile ownership | Duplicate and stale actions, restart and changed ownership do not produce a second or misdirected answer |
| Unread/Gateway indicators | Existing themed unread emphasis without overlapping red badge; themed cloud for Gateway chats | Clear icon state and accessibility descriptions |
| Rendering and memory | Bounded literal Markdown fast path, allocation-aware caches and capture interval evidence | Literal/markup fidelity tests; byte bounds; process-wide capture counters accurately attributed |

## Validation and publication contract

Run focused behavior regressions, the repository host validation command, and all
canonical PR checks on the actual candidate. `Required verification` must pass;
missing, skipped, cancelled or failed required jobs are not acceptance. Re-read
head and base before push and merge. The merge call must use the reviewed head SHA.

After merge, require the exact-main Android, acceptance, CodeQL, PR validation,
post-merge, Gateway stability, Windows installer and development-environment
workflows. The existing release publisher verifies version, signer, artifact
identity, hashes and provenance before converting its draft release to published.

Android publication is requested by the version change. This patch does not change
the Windows/Gateway server implementation, so it does not request a duplicate publication
of the existing Gateway 18.1.0 release. The separate hosted report contract advertises
`EXECUTION_COMPLETION_EVIDENCE_V1`; native authenticated GitHub reporting remains
independent, and older hosted services do not receive unsupported approved payloads.

## Qualification limits

Local source and regression coverage do not establish physical phone performance,
actual provider-account availability, or the user's Windows/network reachability.
These require the corresponding device or service observations. Unified Reliability
v2's broader milestone remains independently tracked; this release does not mark
unexecuted scenarios as passed.
