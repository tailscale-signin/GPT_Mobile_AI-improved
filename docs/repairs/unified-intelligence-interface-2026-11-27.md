# Unified intelligence and interface upgrade

## Scope and source identity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Target: draft PR #2651,
`feature/windows-native-benchmark-kit`, observed at
`b54df163105a38570e7d42b06e9d7bdc7a6c2d15` before implementation. The base observed
for this task was main `6435462ac6f306d63b720205a9f2aafc33de1e4d`.

This implements the supplied unified intelligence guide by extending the durable
agent run, choice, repository state, Gateway graph, research session, progressive
tool catalog and benchmark systems already on that branch. It does not introduce
a second conversation database or a parallel remote execution controller.
Existing release qualification and uncertain-write safeguards remain in force.

## Implementation map

| Requested behavior | Implementation and boundaries |
| --- | --- |
| Durable choices | The encrypted decision store retains the original task, source message, profile, workspace, options and branch circumstances. A pending decision parks the saved tool invocation and releases the model worker. Answer submission is serialized and restores that invocation. A saved waiting trace uses a pause indicator, avoids false failure reports, and can transition to the answered result only against its exact original receipt. Relative active-time allowances exclude persisted user-wait intervals; absolute deadlines remain absolute. |
| Arrow-only header | One saved `GitHubHeaderState` controls the whole header. Collapsed title, navigation, menus, indicators and progress leave composition and accessibility traversal. A 48 dp arrow remains. A delayed preference read cannot override a new tap. |
| Report dialog dismissal | Report surfaces use standard Compose `AlertDialog` and `ModalBottomSheet` ownership. Closing clears UI state independently of any already accepted durable outbox operation. Modal ownership generations fence delayed saves, retries and removals after dismissal or another selection. Newly delivered submissions return to the conversation; explicitly inspected historical receipts remain available. Incident dismissal is idempotent, and the independent alpha-fade overlay is removed. |
| Gateway index maintenance | Registered branch subscriptions are durable and account-scoped. The existing index worker reconciles branch heads between conversations, with bounded active/inactive polling and retry delays. Android connection and background workers reconcile the selected manifest automatically. |
| Themed startup | Predeclared light/dark startup resources match the application defaults, followed immediately by a Surface using the selected runtime theme. No repository work is required to reveal the application. Android cannot read arbitrary Compose colors before process startup. |
| Resume interrupted generation | An interrupted persisted run now exposes the existing recovery path even when no terminal error string was written. Resume keeps the original run/job binding; the UI distinguishes Gateway reattachment from checkpoint continuation. Discard is available. Provider-native recovery is used only by existing supported adapters. |
| Automatic scroll anchor | Manual bottom arrival engages following within 24 dp. Deliberate upward scrolling uses a separate 48 dp threshold. Existing arrow animation, stable list state and anchor ownership remain in use. |
| Standard benchmark | One selected profile and one Run Benchmark action launch the existing durable runner. Standard combines original reasoning/research/tool fixtures with offline GitHub engineering. Nine weighted categories disclose coverage. Results, history and export are visible; configurable campaigns remain under Advanced. |
| Progressive phone mapping | Tree identity and immutable blob caches are reused. Essential documentation, build files and entry points rank first. Source reads remain bounded and parallel. Truncated recursive trees gain a persisted, bounded subtree frontier; an unfinished or capacity-limited frontier is explicitly partial. |
| Research in GitHub chat | Request intent controls repository admission. General questions and research do not inherit unnecessary repository work. A follow-up can reuse a bounded same-conversation research artifact with captured source/quote/version checks. Historical findings remain untrusted data and do not authorize actions. |
| Persistent tool intelligence | `UniversalToolRegistry` records the freshly authorized definitions in the existing encrypted store and warms exact schema-cost estimates during local readiness. The full local inventory is separate from admitted model schemas. Live resolvers and executors retain permission, profile and provider checks. |

## Repository synchronization contract

The implementation extends the existing authenticated graph route rather than
adding a competing repository endpoint family. A manifest binds repository,
branch, access scope, commit, parser format and index generation. Delta records
carry immutable blob identities, a digest of their uncompressed content and a
bounded compressed payload. Deletions are explicit. Transfers retain their base,
target generation and cursor.

Android stages a transfer independently of the active snapshot. It verifies
individual records, bounds decompression and verifies the complete generation
before publishing the replacement. An interrupted or corrupt transfer therefore
cannot replace the last verified map. Reconnection continues a matching transfer
or safely restarts from the verified base when generation or authority changed.

Transport completion does not imply complete repository coverage. Symbol and
dependency records are bounded lexical candidates, with explicit projection and
source coverage. They do not certify compiler resolution, runtime data flow or
executed tests. Offline observations retain their capture age. Freshness is based
on an observed branch head, not the mere existence of a cached index.

## Benchmark integrity

Standard is fixture suite version 14, with its own original profile evaluation
scope. It is not an official SWE-bench evaluation, a live production repository
test, or a replacement for the existing native-product comparison campaigns.
It uses disposable in-memory tool fixtures and does not publish code or operate
on a production branch. Provider usage is disclosed in the launch panel.

| Category | Weight |
| --- | ---: |
| General reasoning | 15% |
| Instruction following | 10% |
| Tool selection and execution | 10% |
| Research and factual accuracy | 10% |
| GitHub repository understanding | 10% |
| GitHub coding and repair | 15% |
| GitHub autonomous execution | 10% |
| Verification and recovery | 15% |
| Speed and efficiency | 5% |

Each score uses the matching test ID, fixture content hash, validator, category,
workload and evidence scope from one run. Duplicate, foreign, unsupported or
missing observations remain unevaluated. A partial category can show the available
observations, but no overall comparable score is emitted until all expected
observations are present and the run has completed normally. Older suites are
not reinterpreted. A fast incorrect answer cannot earn efficiency points.

Export derives these scores from complete retained runs, not lightweight UI/export
projections that omit the fixture manifest. Comparison requires matching suite,
rotation, profile configuration and execution policy. Existing signed evidence,
independent graders, native campaigns and detailed exports remain available.

## Validation and release qualification

The task checkpoint next to this record contains the actual validation state.
New regressions cover persisted choices, duplicate answers, original invocation
rehydration, pause clocks, interrupted-run presentation, intent admission,
schema changes, source-verified research handoff, benchmark coverage and index
transfer/maintenance failures. Tests use retained identities and behavior, not
invented success receipts.

Host checks and deterministic tests are distinct from Android runtime acceptance.
The implementation environment has JDK 21 but cannot download the Gradle wrapper
or Android SDK, so Kotlin/Compose compilation and Android tests require the hosted
workflows for the final commit. Existing branch CI also exposed a missing closed
deadline-provenance field; that projection is repaired and regression-tested.

Physical Android 12–16 startup, predictive back, rotation, process death, 24-hour
choice waits, and Snapdragon 8 Elite performance targets require device runs.
Windows installation and service reconnect behavior require the final Windows
workflow and installed-product qualification. No claimed latency, token savings,
download-avoidance percentage or comparative model score is inferred from source
changes. Unsupported provider stream resumption remains unsupported.

All required checks must be attached to the exact final source commit before
release qualification. This record itself grants no merge, publication, grading,
external messaging or production write authority.

## Platform references

- [Android saved state guidance](https://developer.android.com/develop/ui/compose/state-saving): persistent application data remains separate from transient UI restoration.
- [Android splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen/): supported startup theme attributes and the system-managed launch window.
- [GitHub Git Trees API](https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28): recursive tree limits and explicit truncation handling.

The implementation reuses existing documented provider and Gateway adapters; no
provider feature is enabled solely because another provider supports it.
