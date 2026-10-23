# Repository audit repairs — 0.9.56.0

This release combines the free-provider and Combined-delivery repairs documented in the adjacent implementation notes with the October 23 repository audit. It adds horizontal response navigation in Multi-chat and Combined conversations. Swipe left for the next profile (right in RTL), or use the accessible next/previous actions. Boundaries do not wrap, and vertical scrolling does not change profiles.

## Audit coverage

| Audit | Implementation |
|---|---|
| B01 | Free model pickers retain the selected profile UID; reordering profiles cannot redirect a model change. The picker names its target. |
| B02 | Both local-model entry points offer file and folder linking through one typed source and review flow. URI permissions and worker inputs retain the source kind. Archives are rejected with instructions to extract and link a folder; linked files are not copied. |
| B03 | Manage connection opens the exact connection UID. |
| B04 | Local Models, Marketplace and Settings are real tabs. Toolbar and system Back use the same rule; embedded Marketplace has no duplicate toolbar. |
| B05 | Discovery filters and the NPU query share ViewModel state. Reset and Show full catalog clear search, processor, runtime, capability and status consistently. |
| B06 | Incompatible installed models retain Remove download or Unlink. Confirmation explains profile effects and original-file preservation. |
| B07 | Notification navigation uses saved pending and consumed event IDs. Rotation/resume cannot replay an already consumed event; pending events survive recreation. |
| B08 | Recovery and profile reads share a visible error boundary with Retry and redacted exportable details. Completed startup phases are skipped on retry. Theme/repository initialization waits for recovery. |
| B09–B10 | Research status is placed with its owning turn and reads the displayed profile/revision. Accepted delivery suppresses stale failures; contributor progress names its owner. |
| B11 | Provider queue identity includes the persisted run ID. Checking a saved job does not submit again; Generate again uses a new identity. Late job inspection cannot publish over a successor answer. |
| B12 | Combined recovery uses typed failure categories and the same capacity-aware writer policy for selection, retry and fallback. |
| B13 | Terminal errors are timeline metadata, separate from answer text and partial output. Recovery actions and diagnostic details remain available. |
| U01–U02 | New-chat setup uses compact mode and searchable profile selection; secondary choices live in Options. Research remains under Plugins. |
| U03 | Home subtitles use tested contrast; reorder handles are visible and accessible. |
| U04–U05 | Research shows a short phase/measure and sources; accounting and assessment metadata move into Details. Examine/Resume/Examine again reflects the actual action state. |
| U06 | Combined failure copy explains retained replies and recovery. Current writer diagnostics are separate from the answer identity. |
| Shared surfaces | Compact provider/plugin rows; effective globally-paused MCP status; local technical details, backup recovery explanation and Settings Overview. Important limits, permissions and destructive consequences remain visible. |
| R01 / C01 | Explicit version-bound release scope replaces the version allowlist. Full qualification still requires the full milestone gate. Main pushes execute emulator acceptance, and publication waits for required successful checks on the exact commit. |
| C02 | The physical-device workflow records measured candidates and invokes the existing latency comparison against a reviewed baseline with matching device, build, workload and animation conditions. Missing baseline is explicitly NOT_QUALIFIED. |
| C03 | Release artifacts include compressed/uncompressed payload inventory. Optional-runtime extraction is deliberately a later packaging track, not a claimed size reduction. |

## Validation

Focused regressions exercise hidden Gradio state, terminal queue errors, authenticated free routes and cooldowns; Combined writer capacity, malformed delivery and retained drafts; queue generation identity; separate run-error metadata; startup retry and notification consumption; local source rejection/filter reset; and exact release checks. Android acceptance adds profile-target and LTR/RTL swipe tests to the existing compact-width, large-font and navigation suite.

CI evidence must be evaluated on the actual candidate commit. Compilation alone is not emulator evidence. A workflow skip is not a passing release check. Live provider readiness requires completed generation, not catalog reachability or queue acceptance.

## Qualification limits

The release scope and release notes disclose unfinished Unified Reliability v2 acceptance, absent physical-device baseline/profile measurements, unestablished live Qwen/LLM7 generation, and deferred optional-runtime extraction. Deterministic tests do not establish those external conditions.

## Performance and packaging follow-up

Run Android device validation on a dedicated reference device. Review its benchmark JSON and validation conditions, then commit the accepted baseline under `docs/implementation/performance-baselines/`. Use its repository path as the workflow input for subsequent candidates. Generate and review a production baseline profile only from measured representative runs; never substitute a synthetic profile or another device's measurements.

Use `optional-payload-inventory.json` from signed releases to prioritize extraction. Shared DEX is explicitly unattributed: package-name guesses are not savings measurements. Extract one optional runtime/data payload at a time, preserve separate downloaded/enabled/connected state, test offline recovery and install/uninstall behavior, and compare signed artifacts plus startup/streaming/navigation traces before accepting each stage. No dynamic-feature conversion is included in this release.
