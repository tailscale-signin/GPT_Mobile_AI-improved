# Local AI, New Chat insights, and research audit implementation

Base: `version-v0.9.50.0` (`3a078329c9ba498a62924a10b84eadd24293cd7d`).
Branch: `feat/local-ai-pricing-research-2026-10-12`.

This is an implementation branch, not a qualified Android release. The supplied plans and audit are preserved in `docs/design/`. No version bump, release publication, benchmark inference, model download, or device performance claim is part of this change.

## New Chat

- Profile cards retain selection, favorites, filters, and separate/combined modes. Nested score/price buttons open independently, and composition/selection identity uses profile UIDs.
- Personal cloud results use the existing current-suite evaluator and full-before-quick selection. Exact model and billing endpoint identity are required. Community results remain a separate opt-in. Opening the picker never starts a benchmark.
- Local profiles show recorded device results separately: native decode speed, first visible token, device/backend, and memory where measured. Recorded configurations are explicitly historical observations, not verification of current settings or cloud capability scores.
- Rates use decimal strings and `BigDecimal`, explicit currency, input/output per million tokens, cached-input and extra dimensions. Unknown prices never become zero. Direct endpoints, custom gateways, batch modes, and OpenRouter routes are distinguished.
- OpenRouter is the live public pricing adapter. Other providers remain unknown unless an exact reviewed entry or account override exists. The bundled reviewed catalog currently contains GPT-4.1 and its dated snapshot only; do not assume it covers every provider/model.
- Provider entries are fresh for one hour and expire after 72 hours. Reviewed/account entries use date-only provenance, seven-day freshness, 30-day expiry, and optional earlier expiry. Failed refreshes preserve original dates.
- Automatic refresh runs only while subscribed and enabled. Six-hour background refresh is separately opt-in. Manual refresh and exact account/model/route overrides are in Advanced settings; user settings participate in existing backups. Provider caches are disposable.

Reviewed source: https://platform.openai.com/docs/models/gpt-4.1 (reviewed 2026-10-12).
Live adapter contract: https://openrouter.ai/docs/api/api-reference/models/get-models . OpenRouter catalog rates are route references, not promises of the eventual routed/account bill.

## Local AI

Normal entry opens Models, followed by Assist and Settings tabs. Marketplace is a child page. The Models tab shows the Marketplace hero, Import action, then local profile cards. Legacy marketplace/helper routes enter their corresponding new destination. Profile actions include Use, Edit, Duplicate, Pin, Assign to Assist, and profile deletion that retains model bytes.

Marketplace recommendations are capped at four distinct compatible families, shown before the remaining families. Family representatives use eligible variants. A typed compatibility report separates packaged runtime, structural restrictions, total/available memory, and artifact evidence. Installed bytes never imply smoke testing or device verification. Unknown working memory stays unknown. Native initialization remains authoritative.

### Existing controls and ownership

| Existing storage/control | Destination | Consumer |
|---|---|---|
| Local runtime backend | Settings / Engine | `LocalRuntime`, backend admission |
| Per-profile accelerator/context/sampling | Profile Edit | Profile execution spec |
| `localExecutionPolicy` | Settings / Performance | Runtime plan selection |
| `localCpuThreads` | Settings / Performance | Runtime engine spec |
| `localModelCache` | Settings / Performance | Runtime compilation cache |
| `localSpeculativeDecoding` | Settings / Performance | Declared draft/MTP runtime support |
| `qnnAutomaticFallback` | Settings / Performance | Existing QNN fallback policy |
| `localIdleMinutes`, `localMemoryReserveMb` | Settings / Memory | Idle unload and memory admission |
| Hardware refresh / release idle engine | Settings / Memory | Existing runtime controls |
| `localNativeMetrics` | Settings / Diagnostics | Native counters |
| Runtime comparison / stop / clear evidence | Settings / Diagnostics | Existing benchmark coordinator/store |
| `local-model-downloads.wifiOnly` | Settings / Downloads | Existing download constraints |
| Transfer cancel/retry/delete and storage | Marketplace filters / Settings | Existing repository and WorkManager |
| Hugging Face credentials | Settings / Accounts; contextual catalog access | Existing token store and auth flow |
| `localAssist` | Assist | Existing opt-in master switch |
| `localAssistPolicy` | Assist | Worker binding, evidence selection, summary/query handlers |

Resets are scoped: the Memory timeout reset does not reset performance, diagnostics, profiles, accounts, or consent. Runtime settings still apply through their existing consumers. Search matches group names and control keywords.

### Import support and limits

| Input | Standard build | GenieX build | Qualification |
|---|---|---|---|
| `.litertlm` | Copy, signature/size validation, content identity, registration | Same LiteRT path | Native device execution still required |
| Manifest `.localmodel` / manifest-first ZIP | Clear build-required error | Existing secure bundle validator; declared data assets/hashes | Exact runtime/backend/device must pass preflight and generation |
| Standalone `.gguf` | Clear build-required error | Bounded metadata inspection; self-contained text model, embedded chat template, explicit context; CPU/GPU manifest | No inferred NPU/projector/shard support; device execution not asserted |
| Model folder | Not offered | SAF folder containing `manifest.json` and exact declared assets, normalized to validated bundle | Same manifest rules; staging requires additional free storage |
| Generic `.tflite`, `.task`, arbitrary folder/ZIP | Rejected | Rejected | Never accepted by renaming |

Imports run on IO in persisted WorkManager jobs, with durable SAF grants, foreground notification, cancel action, coalesced progress, and a review step. A restarted job clears only its own staging directory and restarts the copy; this is safe restart, not byte-range resume from a document provider. Completed artifact identities are immutable/content-addressed. Registration is serialized with model deletion and checks that final bytes still exist. Existing filename-based legacy records are not rekeyed. Removing a profile retains weights. Installed model deletion checks active local work and releases an idle matching engine before deleting bytes.

### Assist behavior

Legacy opted-in evidence selection can still use an already warm eligible worker. A pinned worker restricts selection. New summarization and query-proposal handlers are opt-in and operate only on already-authorized, read-only source results. Exact original records remain retained. There is one native helper at a time, shared attempt limits per turn, and bounded derived-result reuse scoped to turn, tool authority, worker configuration, policy, goal, and exact input. Permission/policy revocation discards stale derived work.

These handlers do not create new document, memory, tool, network, or cloud-sharing permissions. Query proposals are not automatically executed. Additional speculative modes without implemented permission-aware handlers are not exposed as working switches.

## Research audit coverage

| Findings | Implementation / preserved base behavior |
|---|---|
| L01 | Correlated public DNS recovery gate shared by native academic clients, URL reader, and public gateway health; single-site failures do not open it |
| L02, L09 | Existing endpoint admission/backoff and modern/legacy negotiation cache retained; network epoch invalidation retained; protocol probes remain separate from tool outcomes |
| L03, C03 | Provider completion publishes discoveries into a bounded reading queue before aggregate search finishes; page discovery proceeds while reader slots work; resumed/web source pools refill on completion; completed evidence checkpoints immediately |
| L04 | Warm/eligible worker admission retained; missing profiles/artifacts remain repairable, with explicit installed/missing state |
| L05, L13 | Partial transfer files retained; Android stop reason recorded, including background restriction; foreground entry separated from notification progress; updates coalesced to one second |
| L06, C06 | Deadline errors are typed; generic cancellation is no longer asserted to be parent cancellation; source failure reason survives checkpoints/export |
| L07, C11 | Byte/time reservations persisted ahead in chunks; exact in-memory counters; crash restore cannot refund a reservation; synchronized monotonic shared active clock; cached source references and incremental retention |
| L08, C08 | Authentication, local daily policy, provider rate limit, and network recovery have separate states |
| L10, L11 | Existing provider-route context/output metadata, learned limits, and progressive advertised-tool accounting retained |
| L12, C04 | Target-site denial remains terminal; eligible OA URLs can use already-authorized readers when native extraction is limited, respecting remote-reader and domain settings; paper identity retained |
| L14 | Assist binding extracted from the large chat coroutine; research reading and queue coordination separated; checkpoint/progress/telemetry churn reduced. Release-device allocation/jank/compiler profiling remains a qualification gate |
| L15 | Existing model-ready/idle wake-up and persisted deferred-memory queue retained; no assertion of an embedding handle leak |
| C01 | Unimplemented integrations labeled unavailable/not queued; no new untested adapter declared ready |
| C02 | Authority-scoped turn corpus, stable source IDs, short source leases, sibling stop propagation; unrelated subquestions no longer share a whole-turn lock; identical tasks remain coalesced |
| C05 | Stagnation uses readable/support yield; useful below-goal evidence is `PARTIAL_USEFUL`, remains deliverable/reviewable, and does not assert goal completion |
| C07 | Fair provider rotation, normalized query keys, recheck inside provider admission; readers rotate within eligible authorized choices |
| C09, C10 | Indexed in-process event/rollup cache, incremental retention, fail-soft writes; HTTP/redirect, empty, canceled, useful search/read outcomes separated; native event export added |
| C12 | Deep-research local worker entry uses the common Android orchestrator and shared ledger; explicit legacy-disabled mode remains backwards compatible |

The supplied audit also recommends device/network/service investigation. Code cannot validate gateway reachability, repair a user's credential, demonstrate that every upstream provider is healthy, or prove phone DNS/VPN stability.

## Validation and release gates

Completed locally:

- Kotlin syntax/style checks with the repository's ktlint version/style on all 85 changed Kotlin files.
- Resource preflight (27 XML files), icon reference check, and QNN provenance check.
- 75 isolated JVM tests passed against production money/endpoint/freshness logic, ledger reservations and concurrent clock, DNS gate, completion pools, source serialization, content-addressed LiteRT import/cancellation/corruption, Assist retention/sharing/revocation, the Android research orchestrator, runtime deletion exclusion, bounded import inspection, manifest transactions, checksum cancellation, cache expiry, and telemetry accounting/export. The orchestrator checks include reading fast provider results before slow discovery completes and keeping sibling task planning separate from shared sources.
- `git diff --check` and base-branch ancestry inspection.

The isolated JVM harness uses minimal Android Room/Parcelable annotation, delegation-serializer, logging, and marketplace-catalog placeholders, plus extracted tool contracts/capability types, a reduced settings fixture with matching defaults, and runtime diagnostic/qualification placeholders. The second pass uses the Kotlin serialization compiler plugin for actual manifest and telemetry serialization and compiles runtime admission/engine-holder logic against the Android SDK API jar; native inference and Android lifecycle behavior are still excluded. It does not exercise those integrations and is not a substitute for the Android build. Full application compilation, Hilt/Room generation, Compose integration, Robolectric journal persistence tests, and instrumented tests are not certified by this run.

Blocked here: Java networking reports `Network is unreachable` while Gradle resolves `com.android.legacy-kapt:9.4.1`, before app compilation. Repository dependency pins were not changed to disguise this. Run `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug assembleDebug` in the normal CI/build environment, and the separate GenieX build workflow.

Before release, complete the supplied physical-device matrix: standard and GenieX generation for each advertised import route, exact NPU tuples, interrupted transfer/import and process recreation, restored-backup migration, 200% text/TalkBack/landscape/theme review, active-response deletion races, and release-device scrolling/memory/thermal measurements. No screenshots, performance target attainment, or native runtime qualification is claimed without those checks.

## Second review — 2026-10-12

- Fixed nested-lock deletion failure. File deletion now acquires the engine reservation without waiting, unloads only the matching installation, and leaves active generation/benchmarks running. Regression checks cover active work and model-ID prefix collisions.
- Added bounded header/manifest inspection before import confirmation, declared size/runtime/processor details, and a post-import Create profile action. Import cancellation reaches checksum verification and GGUF metadata traversal; bundle progress is reported. Restored the download worker's explicit cancellation callback after the importer API changed.
- Imported LiteRT files no longer inherit invented CPU/GPU declarations. Profile creation consults native metadata and preserves exact NPU package checks. Missing/disabled Assist workers, loaded model state, last new-chat selection, and initial installation loading now have distinct presentation.
- Import enqueue failures are surfaced, duplicate taps are suppressed, and newly acquired SAF grants are released when the observed job becomes terminal. Existing shared grants are retained; interruption keeps the grant for restart.
- Fixed research cache renewal-on-hit and cache-hit attribution after semaphore admission. Separated transport failures/cancellation from semantic outcomes, preserved legacy event provenance, and moved slow export writes outside the acquisition telemetry lock.
- Assist rechecks the selected worker's existence, consent, enabled state, and configuration before/after derived work. Oversized record packets no longer consume an inference attempt.
- Pricing cache reuse no longer rewrites unchanged snapshots every minute; obsolete entries are bounded. Blank connection IDs stay profile-scoped, invalid extra rates are rejected, expired manual entries cannot be saved as current, and the popup displays refresh progress/outcomes.
- Storage totals use filesystem measurements when the installed set changes, rather than counting only the manifest bytes for a multi-file bundle. Progress updates do not trigger repeated scans.

The normal Android compilation retry still fails before Kotlin compilation while resolving `com.android.legacy-kapt:9.4.1`. Full CI and the physical-device acceptance matrix above remain required.
