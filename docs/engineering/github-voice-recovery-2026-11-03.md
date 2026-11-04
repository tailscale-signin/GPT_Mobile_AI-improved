# GitHub, gateway and voice recovery

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Base: `main` at `847d55a9ae29a1d9b2f2c5233830a2b7016421f1`

Working branch: `feat/github-voice-complete-recovery-2026-11-03`

This change implements the requested chat/voice features and repairs supported by the two Android diagnostic reviews. It does not certify a phone, installed speech engine, or desktop model/template combination. No merge, release, deployment, or external repository mutation is part of validation.

The application task store is an Android Room database. This checkout has no running app database; this versioned development checkpoint does not fabricate application task receipts.

## Requested behavior

| Request | Implementation | Acceptance still needed |
| --- | --- | --- |
| Preview and summary pacing | One monotonic scheduler; globally deduplicated previews at least 3 seconds apart; immediate summary then 15-second summaries, each at least 20 words with completed work and next steps | Readability and accessibility on phone |
| Response placement and history | Progress occupies the answer position below the assistant icon; the icon toggles history; history closes when the final answer arrives and reopens above it | TalkBack and long-history layout |
| Workspace loading | Loading indicator and text below tab buttons | Small display and landscape |
| Workflows | Five immediate-send presets, including integration testing; empty composer shows themed workflow menu; typing restores Send; per-profile add/edit/remove settings | Keyboard/menu transitions |
| Composer and glass | Shared composer grows to 20 lines; live backdrop remains active during generation; remaining standard dialogs/dropdowns use app glass wrappers | Frame traces, IME lifecycle, pressure fallback |
| Status | PR cards grouped by exact head SHA, colored result tiles, branch section, inspectable runs; PR checks refreshed independently of selected branch | Large repositories and stale/fork PR states |
| Voice entry/settings | Immediate entry, listening after permission/access checks; wrench settings; current profile switching; no device footer | Permission grant, rotation, background/foreground and audio routes |
| Voice controls | Large central microphone toggle with theme fill when listening is enabled; text chat at bottom; silence and transient failures restart through one bounded session owner; no extra Resume button | Physical recognizer behavior and echo/interruption |
| Voices | Enumerates actual installed voices, prioritizes quality and locale, previews selections, supports voice-data installation, preserves network-voice consent | At least three high-quality voices must be installed by the selected TTS engine; labels cannot manufacture unavailable voices or establish audible quality |

## Incident repair mapping

Numbers in this table correspond to the 38 findings in the second review. “Existing” identifies protections already present at the base revision and retained here, not newly discovered proof of their operation on the reported device.

| Findings | Repair or retained protection | Verification boundary |
| --- | --- | --- |
| 1 | Explicit engineering gateway profile selection, separate from inference; service identity and protocol handshake; graph and saved jobs use selected connection | Requires matching updated desktop gateway. Old saved jobs retain their exact original endpoint/account binding |
| 2 | Gateway-specific request routing; harmless durable diagnostic job; SSE delivery, close/reconnect, cursor resume, completion, idempotent resubmission and account isolation | This tests transport/storage, not model execution or GitHub writes; report states this explicitly |
| 3 | Final transport boundary negotiates accepted header names; unadvertised gateway controls are stripped; requested/accepted names logged; effective settings remain Not measured | A capability advertisement is not proof that a setting took effect |
| 4–5 | Calls assembled by choice/tool index, late IDs retained, changed/duplicate identities rejected, names/arguments bounded; overflow captures bounded sanitized shape; one smaller-schema recovery and then tool-free finalization | Tests reproduce fragmentation and overflow shapes, not the unavailable original stream or server cause |
| 6–7 | Existing route fingerprint includes model/build/template; metadata retry is idempotent, one fresh-connection retry, endpoint-scoped expiry. Runtime fingerprint added to diagnostics | Exact installed llama build/model/Jinja combination still needs live compatibility tests |
| 8–12 | Existing serialized admission, template/tokenization probes, prompt-budget receipts and final usage capture retained; intermediate llama tool rounds capped at 4,096 serialized output tokens; runtime/reasoning/reserve state logged; final answer reserve retained | Estimated measurements stay labeled; server timings, effective reasoning and accelerator remain unknown unless supplied |
| 13 | Graph registration requires configured gateway and persistent memory; uses native GitHub authorization/revalidation path; graph reads have explicit replay classification; graph writes retain authorization | Replay classification never creates a grant |
| 14–15 | Preflight failures retained in reporting; attempted/dispatched/completed/rejected counts; graph receipts included; verification catalog filters model-hidden aggregate aliases to match discovery | Current authorization revision and contract checks remain authoritative |
| 16 | Repetitive local-only omissions coalesced with counts; original policy snapshots retained | No local-only context or cloud-memory permission is relaxed |
| 17 | Dedicated gateway acceptance sequence; GitHub checklist retains component/action dependencies and exact receipts; informational analysis does not require implementation evidence | Generic endpoint coverage is never application health certification |
| 18–20 | Local result fragments and filtered-empty issue dependencies bypass unrelated inventory sampling cap; exact continuation recipes persist; small recovery fragments fit retained views | Exhaustive unrelated history remains opt-in; search coverage remains explicit |
| 21–22 | Literal code queries, path globs and pattern syntax explained; unsupported regex/qualifiers rejected with corrections; partial lexical/graph index coverage remains visible | Indexing is not compiler-resolved understanding |
| 23–25 | Self-test CI dependencies select requested SHA; PR checks grouped by exact head; decision/watch blockers distinguished from write fixtures; gateway report leads with component result | Historical lint, skipped jobs and unavailable fixtures never establish full success |
| 26–28 | One recognition owner with serialized starts, shorter bounded cooldown, at most three automatic transient retries; silence resumes when enabled; progress is immediate and paced | Actual Android service limits and voice quality require phone testing |
| 29–31 | Live blur wrappers retain existing resource-pressure fallback; parsing/verification use computation dispatchers; streaming activity coalesced; installed voice enumeration runs off main | No claim that GC, blur, or IME warnings were proven root causes; frame/heap/focus profiling remains required |
| 32 | Existing asynchronous persistence/coalescing/terminal journal protection retained; optional derived-memory failure no longer blocks primary saved chat receipt | Process death and saturated device-storage tests remain required |
| 33 | Repeated feedback-signature warning grouped with counts when received as non-error diagnostic noise | Model compatibility is not changed speculatively |
| 34–38 | Bounded sanitized stream-shape diagnostics; gateway/app/runtime identity; causal failure and dispatch/deadline preservation; independent error context retention; repeated noise aggregation; canonical policy index now reads embedded workspace snapshots | Desktop console logs and the original failing stream were not provided; no server-side cause is claimed |

The first diagnostic review is also covered by conversational smoke-test classification, informational completion contracts, terse follow-up resolution within the bound task, offline derived-memory handling, preservation of original failure through verifier fallback, circuit cooldown propagation, bounded evidence recovery, query corrections, partial-checklist reporting and removal of the unsolicited 350-word default. Explicit profile tool-call ceilings are now respected; status, diagnostic, informational and integration tasks have separate caps. Existing stopped-run checkpoints, approved-write reconciliation, privacy protection and skipped-check semantics are preserved.

## Connection migration

1. Add or retain a private Llama/Ollama connection whose URL points to the desktop engineering gateway, rather than the llama inference port.
2. In GitHub Settings, select that connection under **Engineering gateway**. An inference profile may be selected explicitly when it really addresses the gateway; no inference URL is assumed automatically.
3. Update the desktop using the complete validated package. The engineering capability response now identifies `engineering-gateway`, protocol 1; supported transport-control headers are advertised separately.
4. Ask to test gateway integration. The response names the diagnostic job and reports which acceptance steps completed. Missing service/configuration is a blocker, not an invitation to substitute GitHub API inventory.

Diagnostic jobs are owner-scoped, retain seven days, have a global 10,000-row limit and never invoke a model or mutate a repository. Reusing the same request identity returns the same job. The normal engineering job state machine remains independent.

## Validation record

- Focused host Kotlin suite: **83 tests passed**, using production portable sources with minimal Android/domain DTO adapters, including bounded silence recovery and exact-run CI log selection. This is not an Android APK build.
- Gateway suite: **392 tests, 391 passed and 1 skipped** on Python 3.12, including HTTP/SSE, durable job, account isolation and reconnect tests. Fixtures do not connect to the user's desktop or GitHub account.
- Repository fast suite: **301 Python tests passed**; regex, resources, icon and dependency/policy checks run. Final formatting and diff checks are repeated before publication.
- Kotlin formatting: ktlint 1.3.1 on changed Kotlin files.
- Android compilation/unit suite: requires CI or a host with JDK 21, SDK 37 and downloadable Gradle dependencies. Local host has JDK 17 and no SDK. CI results must be bound to the PR head and recorded separately.
- Physical acceptance: microphone routing and permission/lifecycle matrix; three installed natural voices and audible quality; 20-line IME behavior; live blur frame traces; actual llama/model/template compatibility; running desktop job disconnect/reconnect and process-death recovery.

Do not convert any unexecuted device/server acceptance item into a pass or mark the draft release-ready solely from host tests.
