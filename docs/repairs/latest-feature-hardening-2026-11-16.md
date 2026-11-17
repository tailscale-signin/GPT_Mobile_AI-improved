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
