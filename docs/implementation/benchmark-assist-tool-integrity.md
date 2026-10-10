# Benchmark, Local Assist and tool integrity implementation

This branch implements the software foundations from the October 10, 2026 Benchmark v2 and Local Assist/runtime specifications, plus the Gemma tool integration review. It is a draft implementation, not a device qualification or performance claim.

## Behavior and verification

| Area | Implemented behavior | Regression coverage |
| --- | --- | --- |
| Local results | Provider payload accounting is separate from structured model evidence. JSON records are compacted structurally. The allowance scales with effective context; subsequent dispatch is not blocked by a fixed shared 2 KB payload ceiling. Native evidence accounting stops and finalizes before continued context growth. | `LocalToolIntegrityTest`, `LiteRtLmAdapterTest` |
| Calls and errors | JSON object parsing and host schema validation precede dispatch. Native replies carry status, dispatch state and error category. An unknown write outcome prevents unchanged automatic retries. | `LocalToolIntegrityTest` |
| Native loops | Callback count, repeated failures and evidence/call limits terminate the native loop. One new tool-free conversation synthesizes retained observations without repeating actions. Dirty native sessions are still closed. | `LiteRtLmAdapterTest` |
| Capabilities | Required search/location/listing/page-reader schemas take priority; compaction retains tools or fails explicitly. Alias collisions fail clearly. MCP unknown-tool responses trigger one targeted catalog refresh. | `LocalContextPlannerTest` |
| Continuity | Verified persisted dispatcher results enter rebuilt sessions with tool/call identity, arguments, status and observation time. Enabled tools and current location permission constrain reuse. Cached and active fixes are identified. | Existing persistence checkpoints; location regressions |
| Nearby listings | App code calculates distances, rejects known out-of-radius listings, and labels unknown distances unverified. Indexed fallback and price qualifications remain in the structured packet. | `AirbnbGeographyTest` |
| Assist | Opt-in, read-only record selection uses a warm eligible on-device profile, two attempts and an eight-second deadline. The worker has no tools or recursive delegation; validated indices copy exact records. Invalid/cold/oversized attempts bypass to original evidence. | `AssistCoordinatorTest` |
| Runtime/package safety | Direct NPU selection uses the guarded loader. Fallback preserves the preferred backend. Digest identity survives renames and changes when bytes change. Trusted expected digests are verified before atomic install; absent publisher digests are identity-only. | `LocalRuntimeRouterTest`, `PackageDigestTest` |
| Progress | Activity labels are deterministic observations and do not open a native conversation. Follow-ups are at most one useful supported next step. | `LiteRtLmAdapterTest` |
| Measurement | Versioned reference lexical tokenizer excludes the first streamed chunk from decode rate. Native counters remain separately labeled. Standard text has 36 scheduled trials, Agent 48, and Delegation 24 seeded scenarios. | `DynamicScoreEngineTest`, benchmark runner/rating tests |
| Scores | Per-band normalization uses all verified, fresh profiles; five complete blocks maximum, separate execution cohorts, fixed weights, mandatory coverage and quality/reliability caps. Quick/incomplete runs do not establish anchors. | `DynamicScoreEngineTest` |
| Persistence | Additive Room database stores raw nested trials, immutable score snapshots and a transactional current pointer. Existing preferences remain a backup mirror with damage recovery. History is not silently discarded at 200 records. | `BenchmarkStoreTest` |
| Orchestration | Process-wide inference admission excludes chats/model mutation from benchmark preflight onward, allows nested benchmark work and releases on cancellation. Trials checkpoint before continuing. Foreground interruption cancels; paid requests never auto-resume. Paid profiles require explicit selection; batches rotate order. | `InferenceAdmissionTest`, existing runner cancellation tests |
| UI | Four benchmark views, cohort tables, explicit provisional coverage, (?) score explanations, Local Tool Health, and opt-in Assist in runtime settings. | Android compile/lint; physical-device UI review still required |

The reference unit is `reference-lexical4-v1`: each Unicode letter/digit run is divided into chunks of up to four code points; each punctuation/symbol counts once; whitespace counts zero. It is a reproducible comparison unit, not the provider's tokenizer or billing count. Complete native output text is counted after timing.

## Qualification gates that remain closed

The source specifications explicitly require staged release gates. The following are not claimed complete by this branch:

- LiteRT-LM 0.18.0 dependency promotion, ABI/dispatch and QAIRT qualification. The existing pinned 0.17.1 runtime remains in place; changing dependencies without driver/device evidence would defeat the specification's controlled comparison.
- Gemma E2B CPU/GPU/SM8750 qualification, immutable upstream/checksum pairing for a new catalog candidate, inspected compiled context, measured peak inference memory, thermal/energy measurements and speculative decoding on/off qualification.
- The matched six-task A/B/C Assist evaluation adapter and independent reviewer defect-detection/failover tracks. The current production Assist scope is bounded evidence record selection; autonomous tool planning is not enabled.
- A full typed stage/provenance journal and optional validated model-authored milestone narration. Existing dispatcher events remain authoritative; deterministic labels are used.
- Cold-load, concurrent stress, live paid-provider and multimodal suites; detailed confidence intervals; pagination and historical-snapshot export UI. They must not masquerade as the verified warm serial core track.
- Native process isolation and worker recovery IPC. Kotlin guards cannot intercept a native signal.

## Physical-device release protocol

Keep Gemma and the backend fixed while replaying large search → second tool, malformed/missing arguments, required tools under context pressure, unknown tool after one refresh, native rebuild, location permission revocation, distant listings, indexed fallback and unknown write timeout. Use synthetic identifiers for state-changing tests; never probe subscribe/unsubscribe/cache clearing through ordinary model chat.

Record APK commit, model digest, requested and actual backend, runtime/native build, device/OS, effective context, final manifest, call IDs, error categories, payload/evidence sizes and supported sources. Exclude credentials and precise location from exported diagnostics. Score selection, argument validity, dispatch, evidence continuity and answer correctness separately.

Publish or promote a runtime/package only after cancellation, teardown, guarded fallback, tools, lifecycle and device measurements pass. A SoC match or self-computed digest is not publisher authenticity or a successful NPU measurement.
