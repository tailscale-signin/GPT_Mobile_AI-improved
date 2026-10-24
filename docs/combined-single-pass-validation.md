# Combined single-pass delivery and attribution validation

The regression fixture `app/src/test/resources/combined/portugal-2026-10-23.json` contains only the request, four supplied contribution bodies, and two retained synthesis texts from the diagnostic export. It excludes credentials, account configuration, and journal metadata. `completeTestCandidate` is a deterministic test candidate assembled from retained text; it is not a new provider run or a factual certification.

## Acceptance checks

- The 387-word revision stays selected when a later 198-word candidate fails. The later text remains inspectable as a separate revision.
- A complete candidate near 500 words covers the retained historical periods. Local validation must not issue another model request, verification call, or continuation.
- Treaty of Tordesillas and transatlantic slave-trade wording link to BridgeNode and Vireonix respectively. Merged 1488/1498 clauses retain event/date associations; swapped dates, negation, and lost qualifications cannot receive source colour.
- Shared text is neutral with a shared-source indicator. A passage opens source excerpts; profile markers provide a non-colour identifier.
- Persisted profile hues determine all foreground colours. Reordering/removing profiles does not change assignments; foreground contrast is at least 4.5:1 against the actual surface.
- Terminal failed contributors do not block usable contributions. Pending contributors remain behind the terminal/deadline barrier. A persisted synthesis owns its frozen inputs, so late arrivals do not recompute the answer.
- ISO retry timestamps and incompatible demo routes survive serialization. User retries obey cooldowns; a shared failure dialog exposes combine, eligible retry, and removal actions.
- Reasoning usage is recorded per served route. Passage arrival alone cannot calibrate successful delivery; only a terminal, locally complete answer can do so.
- Length, requested-topic coverage, textual source links, structural usability, and evidence verification remain distinct. Lexical uncertainty is not reported as proven omission.

## Device validation still required

Use the affected routes and a restored copy of the conversation to request the Portugal answer in 500 words. Check normal stop and output-limit receipts, visible word count, all historical periods, source inspectors, and revision retention. Restart the process during collection and during synthesis, then confirm exactly one accepted synthesis dispatch for the turn. Exercise multiple failed profiles, a future ISO cooldown, a demo exclusion, and a late result after the source set is frozen.

Record the answer screen on the affected device in light, dark, and custom themes. Verify the actual bubble surface, resolved-colour diagnostics, profile markers, and source-link hit targets. Measure frame timing while streaming, opening source excerpts, switching revisions, and scrolling a long answer. Compare cold preparation with repeated committed passages; source fingerprints and line mappings have bounded caches, and matching runs off the UI thread. The original frame stalls do not identify attribution processing as their cause.

Inspect `WRITER_USAGE`, `DISPLAY_COLOUR`, `RETRY_REQUESTED`, `RECOVERY_INSPECTION`, and `REVISION_PROMOTION` diagnostics, along with the export's existing dropped-event count. An incomplete journal is not proof that an unrecorded interaction did not occur.

## Initialization, delivery and responsiveness follow-up

GGUF load receipts now start before the JNI class can load its library. The durable journal advances through library loading, template validation, selected-backend initialization, device probing, weights and context allocation. Library/template stages have a 15-second deadline; backend/probe stages have a 20-second deadline; context allocation has a separate 60-second bound. The existing general load watchdog still bounds weight loading. An initialization deadline terminates the isolated worker. A CPU recovery load can start once, before any generation, only after the prior worker's binder is dead. The pinned registry is patched at build time to leave GPU registration to the selected JNI backend, so CPU initialization does not enumerate the compiled Vulkan/OpenCL backends. Auto reads saved qualification without native cache-key probes and skips quarantined GPU tuples.

Worker termination intent is retained with PID, request, process generation, native operation and stage. A matching Android SIGKILL exit in the receipt's time window can establish controlled termination; an intent receipt alone explicitly does not establish the exit cause. Timeouts remain quarantined for the affected artifact/backend. They do not quarantine the CPU recovery tuple.

Claim extraction and source offsets share a bounded immutable content index. Generic synthesis instructions are shorter; complete contribution bodies remain unchanged. Passage arrival is progress only: writer completion scores and useful-output calibration require final complete delivery plus locally satisfied length and scope. `CONTENT_INDEX_PREPARED`, `PREPARATION_TIMING`, `SYNTHESIS_CHECK_DETAILS`, `DISPLAY_SPAN_MAPPING` and synthesis trigger fields separate preparation, failed checks, shared links and actual display mappings without logging answer bodies.

Provider diagnostics distinguish attempted dispatch from transmission demonstrated by a response. Connection/transmission remain unknown when unobserved. Reasoning-only output-limit responses are `REASONING_BUDGET_EXHAUSTED`, other empty answers are `NO_VISIBLE_ANSWER`, and repeated generation failures receive a temporary route cooldown. DNS failures share bounded network episodes and retain the network-change identity and validation state; a network change invalidates unreachable status without clearing provider cooldowns. Terminal hosted jobs remain terminal.

Tool omission diagnostics distinguish irrelevant tools from actual context rejection. Thinking extraction and answer projection prepare on a background dispatcher. Status text no longer rebuilds a gradient each frame; its periodic updates pause with the activity lifecycle. Bottom following observes content size, with one cancellable scroll, rather than observing scrolling offsets. Privacy preferences preload asynchronously and keep the screen protected until available. A failed native document link retains its URI grant for explicit Import copy recovery, showing storage estimates and existing worker progress.

### Additional device acceptance

1. Inject a blocked Vulkan/OpenCL initialization in a disposable test worker. Confirm the stage and operation are visible before the block, termination occurs at the stage deadline, and CPU recovery reports a different process generation. With no saved GPU qualification, Auto must reach CPU without invoking GPU registration. Test both CPU-only and GPU-enabled APKs.
2. Replay the queued-provider turn while typing, manually scrolling and switching profiles. Capture a system frame trace and compare main-thread work and frame durations. Host tests and moving work off-main do not establish an on-device jank improvement.
3. Exercise reasoning-only responses at 2,048 and 4,096 output tokens, a seven-host DNS burst, a network switch, a 429, and a terminal hosted queue error. Confirm distinct categories, eligible retry times and no repeated polling of a terminal job.
4. Deny native path reopening after a successful preview. Confirm the failed job offers Import copy for the same document, performs no copy until confirmed, checks storage, reports progress, survives restart, and releases an owned grant after completion or replacement by another source.
5. Cold-start with app lock enabled. Before preferences load, content must remain hidden and secure. Resume should not open SharedPreferences on the UI thread.

No physical-device frame trace or live affected-provider replay has been captured for this follow-up.
