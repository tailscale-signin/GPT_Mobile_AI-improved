# Latest feature design and wiring review

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Base: `main`, `4a1d790939ae1ab50bb582bbf8ce7c34000e1d1c`
- PR: #1349
- Working branch: `fix/post-1299-incident-diagnostics-2026-11-15`
- Starting head: `3a67dc5399ed6ba7b43570deca351ff9a8fecbd6`
- Intent: improve the new audit controls and retained-result navigation, make Gateway recovery states truthful, and harden voice focus transitions without changing selected-profile authority.

## Review and acceptance

1. Review the PR's audit, Gateway readiness/recovery, diagnostics, provider cooldown and voice changes against their call sites and tests.
2. Audit controls identify the selected saved model, preserve explicit selection across list changes, explain disabled states, and make coverage and optional deterministic policy clear.
3. Opening a saved GitHub audit displays that batch's retained attempts, including blocked attempts without scores. A later run or renamed/deleted profile cannot replace its evidence.
4. Gateway retry presentation distinguishes scheduled, resumed, needs-input and interrupted outcomes. Cached or invalid timing cannot claim a live countdown.
5. Voice focus loss/gain cannot start an unprimed track, leak paused output levels or reset the bounded interruption deadline repeatedly.
6. Run focused behavior regressions and canonical host validation; record Android/emulator limitations explicitly. Regenerate source-owned inventory after edits. Reconcile the remote branch before publishing; preserve concurrent work.

## Implemented changes

- Reworked GitHub AI audit controls into explicit profile and coverage steps, with model/provider labels, search, accessible selection rows, optional deterministic settings and actionable readiness messages. Selection remains bound to a saved UID; disabling/removing that profile cannot select another one implicitly.
- Fixed saved-audit navigation: opening an older batch previously navigated to the latest aggregate results. The new view uses only the chosen batch's profile snapshots and run IDs, and retains blocked/no-score evidence even if its model profile was removed.
- Added distinct scheduled, resumed, needs-input, interrupted and recorded retry presentations. Countdown calculation rejects stale, non-finite and inconsistent monotonic observations. Protocol state parsing uses a stable locale.
- Hardened voice focus transitions under the audio-track lock. Focus gain resumes only a primed paused track; draining/teardown clears readiness. Repeated focus-loss events retain the first pause deadline. Paused/ducked audio levels follow actual output, and focus timeout cannot trigger a second voice output while another app owns the speaker.
- Wired new Compose interaction tests into the release acceptance script, including checks that both named tests actually ran.

## Validation checkpoint

- `bash scripts/validate-local.sh fast`: passed, including 525 host tests, resource/regex/metadata checks and regenerated source inventory.
- Authentication suite: 160 tests passed.
- Kotlin host regression subset: 8 tests passed using current production focus policy, Gateway job models and presentation sources. The host supplies only Android constant, annotation and unrelated DTO/exception boundaries; this does not exercise AudioTrack, Android UI or serialization.
- Kotlin formatting and `git diff --check`: passed.
- Gateway regression suite: 101 Python suites completed, with 1,371 passed and 28 platform-dependent tests skipped; all 7 browser bridge tests passed. Native Windows qualification is unavailable on this Linux host.
- Android Gradle unit/Compose execution: not run locally because `services.gradle.org` is unreachable while obtaining Gradle 9.8.0. Added saved-result unit regressions and Compose interaction tests require Android CI. Physical audio playback, visual rendering and live provider/GitHub qualification remain unverified.

No current-build device qualification or repository-wide completion is claimed. The same PR remains the review surface; concurrent branch edits are reconciled before publication.

## Follow-up feature review — November 17

- Repository and working branch unchanged; base `main` remains `4a1d790939ae1ab50bb582bbf8ce7c34000e1d1c`.
- Starting reviewed head: `701fd73d2`; intent: repair contradictory recovery grouping, retain check-export context, and adapt chart layout and audit actions for small screens and accessibility.
- Review covers the new rich-summary pipeline and history rendering, selected-profile audit dispatch/results, provider cooldown scope, Gateway retry/checkpoint projection, diagnostic allowlists, and voice focus transitions. This is source review, not live device/provider qualification.
- Validation plan: regressions for terminal/retry/input precedence and retained CSV evidence; Compose coverage for large-font charts and source attribution; canonical host checks. Preserve concurrent PR edits before publishing.

### Follow-up changes and evidence

- Recovery grouping now prioritizes approval/device/input/cancellation blockers even when a job is still scheduled as queued or running, includes `DEVICE_VALIDATION`, normalizes protocol whitespace, and never treats a retained resumed retry as a new waiting retry. Terminal jobs stay in recent history and show retained retries as recorded history.
- Check-table CSV now includes source, complete/incomplete captured-list coverage and the recorded-result limitation on every row; source labels are quoted and protected against spreadsheet formula interpretation. Partial tables missing their disclosure are rejected. Markdown escaping is bounded before composing the final table.
- Redesigned check bars with a full-width track below flexible labels/counts, eliminating fixed count widths. Each chart row has one accessibility announcement, and rich progress cards regain polite live-region semantics. Added Compose regressions for narrow/large-font charts and model-authored tables that must not acquire source-owned chart/export actions.
- Audit queue actions wrap on narrow screens, and the export action explicitly says it exports all benchmark data, matching its existing implementation.
- Reconciled concurrent head `900c01558`, including factual-summary fallback and waiting-for-input precedence. Regenerated source inventory from the combined sources rather than retaining either side's generated hashes.
- Canonical host checks: 526 tests passed, along with metadata, regex, resource, icon and provenance checks. No physical runtime qualification is implied.
- Focused Gateway checks: 156 cases run, 154 passed, 2 platform-dependent skips (desktop, engineering durability/recovery, acceptance evidence, GitHub governor and benchmark execution). Initial runners lacked dependencies; these results use the existing provisioned Gateway environment.
- Focused Kotlin host checks: 20 tests passed against production recovery models/presentation and rich-summary formatting/export logic. Host boundaries replace serialization annotations, unused Gateway types, JSON primitive/redaction integration and the unused task classifiers; no Compose/Android/provider execution is claimed.
- Local Android/Compose test invocation could not start: the Gradle 9.8.0 download failed with `Network is unreachable`. These added UI tests require CI; physical Android rendering and speech remain unverified.

## Gateway coding benchmark follow-up

Reviewed the benchmark audit for `gateway-benchmark-96ddf1c8.json` against branch commit `780d41932`. The audit lists three invocation failures and three graded failures from an active run. It does not contain the raw provider error or candidate patches. The observed Gateway path can reject a 4,096-token allowance before generation when the context cannot also fit the prompt. The repaired runner retains the error category and job identity, and lowers the allowance on a typed context rejection after confirming any admitted job has settled.

Coding runs now checkpoint active cases and retain a digest-linked terminal receipt. Strict patch parsing, parser locations, uncovered defect modules, and comparisons with earlier measured candidates improve repair feedback. The evaluator checks the host-owned fixture boundary separately from same-process candidate input observations. Independent candidate input preservation still blocks formal qualification. Model artifact, native batching and GPU evidence require an observed run on the installed Gateway. Use the existing opt-in `gateway/qualify_engineering.py` workflow with an authorized test repository for live engineering qualification.
