# Six specification implementation status

Branch: `feat/research-crawler-marketplace-home-2026-10-18`
Base: `9fbc60b16171fa7e7cbcae41854e8ba6f6b35d5d`

Work in progress. This branch does not yet implement or qualify all six documents. Draft PR [#721](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/721) is open. No release bump or merge has occurred. `SOURCE_INDEX.json` retains the source documents and hashes; `CHECKLIST.md` is the source-linked review inventory. An unchecked item is not evidence that its existing code is absent, and code presence alone is not acceptance evidence.

## Implemented changes

### Research and Combined

- Accepted Research model/temperature overlays remain separate from registered dispatch authority; live destination, credential and permission checks still apply.
- Initial history excludes the current assistant slot. Earlier Combined turns project only their selected delivered prose; unavailable finals do not fabricate assistant notices or fall back to contributor drafts. Selected quality and provenance remain typed metadata.
- Same-turn Research corpus union preserves authority partitions and source versions. Packet fitting prioritizes readable excerpts without changing retained source bytes. Research uses its own citation policy.
- Explicit accepted Research settings create required work even without a numerical goal in the prompt. Ownership uses the assigned work item instead of verification escrow. Planning, extraction, gap analysis and review now have independently funded required stage IDs; dispatch selects the stage owner, unfunded or terminal work cannot borrow verification funding, and closure resolves pending work while retaining unknown physical usage. Stage outcomes retain failures beyond the bounded event history.
- Research admission distinguishes profile removal/disablement, identity ambiguity, destination/configuration changes, permission revocation, readiness timeout and local allowance exhaustion. Display-only profile renames do not revoke a pinned destination. Research has an explicit reviewer setting independent of Combined Delegation; live revocation stops that reviewer.
- Shared reviewed legacy reader classification is used by Research and Combined inspection accounting. Crawler is deliberately not admitted until its trusted grant/receipt bridge exists.
- Failed report writing retains an explicitly labelled incomplete draft. An unvalidated evidence fallback remains partial, not completed.
- Structured retry keeps the previous selected visible answer until a validated replacement is committed. Invalid framing, required-check failure and cancellation preserve the old revision.
- Combined contributor-to-writer orchestration now belongs to the application coordinator. The last contributor stays active while a durable writer is prepared and admitted under the chat gate. Foreground service start/stop decisions share the main thread and reuse an already promoted service.
- PASSAGES_V3 uses sequenced logical records, explicit end markers, strict reference validation, immutable committed records and generation/fence-checked Room checkpoints. Structured continuation restarts an unfinished record; committed text and attribution are restored together. Sealed snapshots persist dynamic timestamp defaults, and repair uses a successor snapshot.
- Final serialized provider messages are checked before admission for role shape, content shape and tool pairing. Initial implicit assistant prefill is rejected locally. Admission receipts identify the validator. Exact current-user identity validation and all provider-native paths still require completion.

### Main Menu and Home

- Conversation icon geometry, unread glass badge, extra-bold lifecycle-aware opacity pulse, profile-label alpha and theme-following/custom folder colors are implemented. The badge uses physical bottom-right positioning in RTL.
- Home preferences v2 migrate the full legacy list before the five-category cap. Profile and grouped tool selection support Apply/Cancel, search, stable identities and missing selections; compact categories open full selected lists.
- Shared tool status observes cached connection health and per-tool outcomes independently, respects revision/epoch expiry and disabled states, and includes installed native packages. Local runtime/memory readiness and device qualification remain open.
- A root-owned bottom gradient and full-screen scene wrapper avoid multiplying gradients in the adapted windows. The remaining window audit and visual/device matrix remain open.
- Provider settings save management access separately in the encrypted vault, validate its role without reading credits, and support removal and explicit reassociation after inference changes. Home has no management-key entry or refresh action.
- Wallet state is in a separate no-backup DataStore. A generation receipt with remote evidence admits one durable event; per-account FIFO actors claim before dispatch. Distinct generations remain distinct; duplicates, stale bindings, cooldown events and restart leftovers are not replayed. Batch cache hits produce no event. Explicit provider refresh shares account serialization.
- Credential rotation/removal and restore invalidate associations/cache. Portable complete backup excludes management secrets. Full lifecycle and all legacy restore paths remain under review.

### Universal Marketplace

- Optional native package, strict typed request/result contracts, source capabilities, local external-search adapters, live permission checks and stable fingerprints are implemented for eBay, Facebook Marketplace, Craigslist and Kijiji.
- External searches are explicitly link-only with zero verified listing counts; no scraping, AI call or paid request is implied. Prices use exact minor units, and unknown shipping remains unknown.
- Search/Saved/Settings workspace, saved queries, bookmarks and notes, typed chat result cards, profile/conversation dispatch, keep-data uninstall, explicit deletion, Room migration and backup payloads are wired.
- Room v43 Marketplace schema and v44 Combined checkpoint schema are exported. Full permission/revocation, backup/lifecycle, migration and UI acceptance still need qualification. Optional permitted API integration is not implemented.

### Crawler companion

- The optional Python companion implements the five MCP operations, signed trusted metadata, durable SQLite frontier/leases/generation fences, idempotency, linked resume, cancellation and complete-envelope pagination limits.
- One metered HTTP boundary enforces public DNS, pinned sockets, scope, robots, redirect limits, shared parent request/byte allowances, concurrency, dispatch pacing, durable cooldowns and cancellation. Unknown physical outcomes are retained without automatic replay.
- Captured-byte Trafilatura and optional Scrapling extraction, plain text and basic PDF extraction preserve source/revision hashes, acquisition times, coverage limitations and stable chunks.
- Schema 3 stores scoped content/artifacts once. Resume copies references and no acquisition charge. Deletion preserves shared child references. Content quotas, retention, archive pins, ephemeral expiration and verified pre-migration backups are implemented.
- Authenticated stateless Streamable HTTP at `/mcp` supports initialization, discovery, health and the five operations. Non-loopback listeners require TLS; requests/threads are bounded, grants remain separate from bearer authentication, and cancellation/request IDs are scoped to principal/root/grant. The CLI keeps the same single durable coordinator for either transport.
- Browser and OCR readiness remain false. Crawl4AI/Docling execution, Android/gateway grant bridge, catalog installer, Research/Examine/archive import, dependency hashes/notices and full AC matrix remain open.

### Research Study Cards

Main already contains study review records, deterministic component scoring, relationships, profile selection, live observation and Research migrations. This branch adds rubric-grounded score constraints, explicit missingness/reliability handling and saved review compatibility. Their full collection/examination/archive/restore audit, source-scope validation, scientific appraisal cases, expert review and device acceptance are not yet complete.

## Remaining critical release scope

- Research: complete the shared acquisition registry and canonical packet receipt audit; qualify the new stage funding, live authority and foreground lifetime across real providers and devices.
- Combined: qualify application-owned orchestration, the durable root barrier and PASSAGES_V3 across process/device failures; complete independent quality dimensions, contribution eligibility and adaptive repair/budget acceptance gates.
- Crawler: browser/document adapters, trusted bridge and app import/settings/job UI, deployment qualification and all acceptance cases.
- Home/Marketplace/Study Cards: remaining integration and device matrices described above.
- Reconcile all source-linked requirements and the existing unified reliability release gate before marking the implementation complete.

## Validation evidence

- Android SDK 37, JDK 21 and Gradle 9.8.0 are available. Earlier claims that Android compilation was unavailable are superseded.
- Android main Kotlin/Java and unit-test Kotlin compilation pass for the source tree at `3afde90c04eef5c540f751a7e06bcac051caf235`, using bounded separate compiler/test JVMs. Snapshot serializers remain public while their canonical JSON encoder is private.
- Full Android unit execution covered 3,617 tests: 3,601 initially passed, with 16 failures caused by stale instrumented runtime outputs. The compiled and compile-jar classes contained the corrected public serializer companion, while the instrumented/runtime-jar classes retained the old private companion. After regenerating those outputs, all 69 tests in the six affected classes passed, including legacy snapshot recovery, selected-revision preservation and the queued-prompt synthesis barrier. The combined latest XML results contain 3,617 tests and zero failures/errors/skips. This evidence consists of the full run plus that focused rerun, not a second full-suite execution.
- The first focused run exposed record-separator whitespace, timestamp-dependent snapshot hashing and required-work closure regressions; fixes and regression cases are in the branch. Empty-message transport fixtures and local Marketplace expectations were also corrected.
- Actual isolated Combined core: 30 JUnit tests passed. Actual isolated Marketplace contracts/coordinator: 7 tests passed; additional cases are in the Android run.
- Crawler: 39 Python tests pass with ResourceWarning promoted to an error, including actual loopback HTTP, cross-principal request-ID/cancellation isolation, persistence migration/backup, shared references, retention, quotas, parent grants, complete RPC limits, cooldowns and metered transport.
- Repository Python suite: 140 tests pass on the current changes. Resource XML (29 files) and Android regex preflights (640 literal patterns) pass.
- Kotlin formatting passes for all 150 changed Kotlin files. Exported Room schemas match the committed migration history.
- At source commit `3afde90`, GitHub CodeQL, Kotlin formatting, both contract jobs, preflight and the configured unified-reliability CI job passed. Android unit/lint, APK assembly, emulator and R8/JNI jobs were still running when this evidence was recorded. Their completion is not claimed here. Device visual QA and release acceptance remain open.
- The stricter local command `python scripts/check_unified_reliability_v2.py --require-done` still fails with 48 tasks not DONE and non-PASS acceptance cases. The configured CI check passing does not satisfy that release gate; the gate was not weakened.


## Remote delivery

The user explicitly requested a draft PR for this branch on 2026-10-19. The recovered implementation was pushed and PR #721 opened as a draft. The earlier delivery blocker is superseded by that request and the successful branch/PR creation. Follow-up commits use an expected-head check; do not overwrite concurrent branch changes.
