# Combined collection and long-response continuation

## Incident and scope

This follow-up extends `fix/combined-finalization-attribution-recovery-2026-10-24` without replacing its atomic final-answer, attribution, or renderer-lifecycle changes.

The supplied `export_Combined Chat_1792932003558.zip` identifies version 0.9.60.0, build 128, commit `1050ca2812a2234c7f9454193ae075332d8ae09d`. Its request was a 5,000-word history of Portugal. It retains 21 runs, 25 model invocations, and 1,305 journal events. No final synthesis run was dispatched. These counts describe recorded attempts, not independent user requests.

| Observed failure | Repair |
| --- | --- |
| Kilo stopped at 2,048 output tokens; Pollinations stopped at 1,500. No continuation invocation was recorded. | The single-final-writer policy no longer disables contributor output completion. Requested length and actual serialized output allowance drive successive text-only requests. |
| Pollinations returned the identical 776-word partial on its initial request and four retries. | Text-only routes now load authorized recovery data. Recovery sends the original task, saved progress, and a bounded exact tail. A successful empty or duplicate continuation gets one bounded regeneration attempt, within the existing request allowance. Repeated output is never appended twice. |
| Kilo recovered Vireonix's answer and replaced its own 1,040-word draft with a shorter reply. | Candidate recovery runs are scoped to the selected profile. Same-message retries retain the matching nonblank saved answer before extending it. Empty later attempts cannot erase that draft. |
| `read_recovery_context` rejected decimal strings for offset/length, then hit repeat protection. | This read-only reader accepts validated integers or decimal strings, with explicit range checks. General tool validation is unchanged. |
| AI Horde returned clipped 256-token generations with queue completion. | Fresh, resumed, and cached queue results report their actual request allowance. Queue completion is not represented as a semantic completeness guarantee. Requested length and conservative unfinished-packet detection can continue the answer. |
| Short replies were recorded as completed for the 5,000-word task. | Length checks run across the assembled answer. Existing short contributions remain usable partial evidence. Failure text reports the actual number of continuation requests. |
| LLM7's approximately 86-minute wait blocked all final writing and was absent from the failure dialog. | Paused profiles appear with their deadline. Continue can exclude them for the current turn and combine available material. Chat membership is retained; excluded local wait attempts cannot wake later and dispatch. |
| Five waits were invalidated when sibling responses changed. | The saved input excludes the current response row, which is not part of the dispatched context. Previous-turn history is still validated. |
| Longer responses can meet a provider quota after saving earlier packets. | A typed text-continuation wait binds the exact saved prefix and original task, revalidates them on wake, and resumes text-only delivery without repeating completed tools or research. |
| Small-output writers were rejected before their append setting was considered. | Writer preflight uses the accepted append policy. Append capacity is based on a short complete record, with observed reasoning requirements preserved. |
| Writer instructions explicitly prohibited continuation. | New versioned append plans use consistent append-aware instructions at initial dispatch, recovery, and explicit retry. Archived plan digests retain their original meaning. |
| A writer could declare its answer finished before meeting the requested length. | New append plans withhold premature END records and request more substantive passages using the same next sequence. Exact, minimum, approximate, and maximum-only contracts keep their respective meanings; repeated no-progress responses still stop. |
| Every final-writer continuation replayed all previous generated records. | Full records remain stored. A validated cursor binds sequence, IDs, snapshot/revision, record digest, and progress; only a bounded generated-text view is replayed. Frozen source input is preserved. |
| Long completed contributions and archived retries were re-assessed repeatedly while siblings streamed. | Failure and response-profile caches reuse assessments for unchanged inputs. The profile projection tracks only its own current and archived run statuses; unrelated activity does not repeat prose scans in composition. |

## Delivered behavior

Single and Multi-chat use the same continuation path. Combined contributors also use that path. Research final writing uses it around its report-validation pipeline. The structured Combined writer retains its durable record protocol and atomic final publication.

Each physical request observes the provider's actual output and context allowance. A 1,024-token **output** allowance can produce a 4,096-token assembled answer through successive requests. A true 1,024-token **context window** still bounds input plus output for every request; the app retains the complete answer outside that window and supplies bounded continuation context. It does not claim to increase the model's native attention window.

The continuation count is derived from the requested length and observed productive output, without the previous hard sixteen-request ceiling. Unknown-length output continues only while the provider reports truncation or a conservative queued-packet check indicates unfinished output. A duplicate or empty continuation may use one remaining request to regenerate new material; a second no-progress result remains terminal. Actual provider admission, cancellation, deadlines, user token/spending limits, and response-retention limits remain effective. Completed external tool actions are not replayed by text-only continuations.

New Combined conversations and existing conversations without saved Combined preferences enable long-answer continuation by default. The supplied chat has no saved Combined preferences, so its next request receives that default. Existing persisted settings and frozen strict request policies retain their meaning. In a conversation with saved continuation disabled, enable **Continue long answers** before submitting a new long request; this does not rewrite previously accepted turns. A finished answer remains one displayed answer; partial intermediate requests do not become separate contributor tabs or separate final revisions.

## Regression coverage

| Area | Tests |
| --- | --- |
| Four 1,024-token packets, more than sixteen small packets, early-stop responses, duplicate/no-progress handling, saved-prefix failures | `OutputLimitRecoverySessionTest`, `LongResponsePolicyTest` |
| True small-context planning, complete original-task preservation, strict-writer vs contributor policy | `ResponseContinuationPlanTest` |
| Actual repository dispatch across ordinary chat and Combined contribution | `ChatRepositoryImplTest.longAnswersContinueThroughTheSharedChatAndCombinedContributorDispatch` |
| Profile isolation, empty retries, authorization revalidation, typed reader arguments | `ResearchRecoveryLoaderTest`, `ResponseRecoveryContextTest`, `RecoveryContinuationDraftTest` |
| Serialized output metadata, queued-result identity, old-history isolation | `ProviderAdaptersTest`, `FreeAiTransportTest`, `FreeQueuedTransportTest`, `PollinationsTransportTest` |
| Writer packet capacity, preserved source input, cursor integrity, archived plan compatibility | `CombinedWriterPolicyTest`, `CombinedRecordContinuationInputTest`, `CombinedWriterRequestPlanTest`, `CombinedRecordProtocolTest`, `CombinedAppendPolicyTest`, `CombinedAppendRecoverySessionTest` |
| Available-result collection, paused profiles, safe all-wait dismissal, honest contribution status, stable waits | `CombinedSynthesisInputsTest`, `CombinedSinglePassInputsTest`, `CombinedResponseStateTest`, `CombinedCleanPresentationTest`, `ProviderWaitRecoveryTest` |
| Main-thread repeated-assessment reduction and invalidation | `CombinedProfileFailureCacheTest`, `CombinedResponseProfileCacheTest` |

The branch's existing full unit, Android lint, APK, DEX-size, Room-schema, and instrumentation gates also apply. Exact results are recorded in the commit/PR checks; fixture responses validate application behavior and do not constitute live-provider service qualification.

## Integration boundaries

Final integration review identified additional cases that require explicit regression coverage:

| Boundary | Intended behavior and regression |
| --- | --- |
| A due wait is claimed just before its profile is excluded | Exclusion is checked again under the chat gate before resume and preflight. A claimed queued attempt is canceled without dispatch. A stale alert resolves only the same answer slot, profile and accepted root; an already-running successor retains its saved text and explicit exclusion reason, without canceling the whole turn. `ProviderWaitRecoveryTest` pauses between claim and resume and separately exercises queued and running successors. A previously accepted remote request cannot be undone locally. |
| An initial preflight wait has no error or timeline metadata | A typed Room projection carries the saved retry deadline for the current message/run/fence. The repository observes it on IO and suppresses unchanged emissions. The ViewModel and failure cache update when the deadline arrives or changes; stale owners cannot supply a countdown. Retry remains disabled while an initial waiting deadline is unavailable. `ProviderWaitRecoveryTest`, `ChatViewModelStartupTest`, `CombinedProfileFailureCacheTest` and `CombinedCleanPresentationTest` cover the actual query and UI projection. |
| A separate continuation message has only empty failed attempts | Recovery may restore older revisions of that same answer slot. Earlier slots remain context and separate word credit; they cannot become the new slot's visible prefix. A typed quota continuation fails closed if its prefix belongs to another slot. `RecoveryContinuationDraftTest` covers both an empty separate slot and a valid older revision of the current slot. |
| A completed queued writer result contains a premature END | Live and recovered structured results use the same frozen versioned answer policy. Valid passages remain committed and atomically assessed; the premature END remains uncommitted and resumable. Importing a saved result performs no generation. A restart after checkpoint persistence replays the same partial bundle without feeding the failed parser twice. `QueuedAnswerRecoveryTest` also preserves strict and archived version 1 behavior. |
| Repeated premature END reaches the no-progress bound | The outcome is classified as incomplete delivery with `STOP`, not an unknown error or a completed answer. No automatic retry or writer rotation is authorized by this classification. The existing explicit new-revision path remains available. `CombinedAppendRecoverySessionTest` verifies the terminal category, decision and uncommitted END. |

## Device and service acceptance

Replay the 5,000-word request with the same profiles. Verify that output-limited contributors have continuation-kind invocations, that prior text survives a failed extension, and that the final writer starts after the available-contribution decision. Reopen the chat and confirm one final answer with its matching attribution report. Test a queued continuation whose polling is interrupted and a provider quota wait that outlasts the app process. Cancel during a continuation and confirm that no later request is sent.

The export contains severe UI stalls but no retained fatal crash or ANR stack. Deduplicating frame events across the diagnostic array and app-log snapshot yields 25 windows, 9,315 sampled frames, 764 jank frames, 590 stalls over 100 ms, and a maximum frame duration of approximately 1.317 seconds. Frame timings and active graphics effects do not prove that blur alone caused the stalls. The assessment cache removes a concrete repeated-work path; physical-device frame timing must establish any performance improvement. External quota policies, hosted 502 errors, and a provider's inability to deliver useful text remain external conditions. Their partial results and statuses must remain explicit.
