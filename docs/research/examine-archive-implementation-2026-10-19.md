# Research examination and archive implementation

This branch implements the 19 October Research Examine and Archive design against main `4a0109754506c28b4fa15da879a389e2eae7dcbc`, together with the diagnostic repair list supplied in the implementation request. It changes data retention, examination admission, acquisition and writing contracts, and Combined evidence handling. It does not merge or publish a release.

## Archive and examination invariants

- Eligible discoveries save independently of report writing and the legacy archiveEnabled preference. Each source has a database save receipt and an independent durable retry record. Cards observe committed membership and save receipts to show Saved, Saving, Save failed or explicit removal, and select the accepted assessment pointer. Observers receive the snapshot after indexing dispositions have been committed.
- The limit applies to eligible unexamined works. Accepted evidence-checked assessments, legacy assessments awaiting integrity reconciliation, and processing holds are additional to that allowance. Scores and retraction warnings cannot remove protection.
- An accepted review, selected review/version pointers, assessment body roots, archive protection, and terminal job state commit together. A failed or empty new assessment preserves the selected earlier assessment.
- Manual cleanup writes deletion suppression and fences jobs. Automatic eviction does not suppress explicit later examination. A user re-add creates a fresh attempt; old leases cannot promote results.
- Restore stages protection before retention. It preserves stronger existing selections, respects deletion suppression, and accepts more than 1,000 protected works. Schema 4 migrates older reviews into legacy holds without declaring them newly verified.
- Card Examine submits a job on the first tap. Duplicate taps join within the same work/version, chat, origin, authority, examiner, and semantic-policy scope. Details and progress remain separate actions. Setup failures are returned as admission results.
- Automatic examination is driven by the discovery outbox and WorkManager, independently of cards. Settings/profile repair reconsiders admission. Background dispatch observes the foreground preference; persisted interrupted automatic work can recover while user-paused jobs require user action.

## Requirement map

| Design finding | Implementation |
| --- | --- |
| R01 protected retention | Membership protection, selected review/version, assessment roots, transactional trimming and legacy migration |
| R02 required acquisition funding | Turn-wide accepted acquisition assignments can use their sealed funding across participants; unassigned required calls are rejected before dispatch |
| R03 access integrity | Typed challenge/authentication/preview classification before readable credit; consistent partial-text quality; Combined ledger also rejects challenge content |
| R04 extraction/review admission | Acquisition exhaustion is separate from processing exhaustion; retained evidence retains its review opportunity |
| R05 first-tap Examine | Card submits the action and opens the relevant details/progress surface |
| R06 empty completion | No committed review means no successful examination; oversized packets produce an explicit recovery reason |
| R07 display independence | Compose no longer owns automatic scheduling; durable per-discovery outbox drives it |
| R08 ownership/progress | Matching includes chat and origin; the ViewModel retains the admitted job ID |
| R09 reviewer policy | Shared effective examiner/reviewer resolution; configured independent reviewer must be available and required reviews must pass |
| R10 source authority | Native retrieval receipts identify transferable public captures; restricted captures remain partitioned and can save locally |
| R11 save acknowledgement | Per-source save receipts, isolated failures, retained ingestion payloads and retry processing |
| R12 stronger history | Selected accepted assessment is separate from later weaker captures; richer coverage is retained |
| R13 writer contracts | Brief contributor packets; distinct initial, repair and chapter request roles; validated section checkpoints keyed to the exact evidence packet |
| R14 unread frontier | Pending/deferred leads and document-extraction handoff survive; stop text distinguishes acquisition/recovery constraints |
| R15 portable growth | Snapshot/schema 4, protected growth, protection-first activation and historical score treatment |
| R16 source identity labels | Citation cards label references, retain unknown publication types and show committed archive membership counts |
| R17 reader capability | Permitted bounded native document extraction can supplement unavailable academic transport; unavailable capability remains explicit |
| R18 cosmetic changes | Presentation, organization and recall preferences do not enter the examination semantic policy hash |
| R19 compaction | Questions and limitations remain in compact delivery packets; section checkpoints retain an exact packet digest |
| R20 recovery tools | Combined verifiers are restricted to authorized evidence readers/search; report repair/chapter requests are tool-free |
| R21 diagnostics | Selected-chat lifecycle/save receipts, job linkage, protection pointers, transfer manifests, verification outcomes and repair rejection reasons |

## Additional diagnostic corrections

Free-provider limit notices are transport/UI metadata rather than assistant text, and the original limit finish reason remains incomplete. Legacy notice-only answers are rejected by delegated-result and Combined contributor eligibility. Research additionally rejects incomplete delegated output and validates its stage schema. Repeated structured failures open shared model health across stages as well as stage health. Reasoning preferences survive Kilo request preparation; their effect on a dynamically selected underlying provider remains unverified. Retry changes the output allowance within the profile cap and reduces optional schema work, rather than repeating the same allocation silently.

The Research reader follows the typed bounded document-extraction handoff with the same authorized reader, URL and domain filters. Partial sources remain partial in the live run and restored data. Explicit empty acquisitions receive an evidence packet or deterministic incomplete outcome. Report validation records initial and repair failures separately, and it does not mislabel a rejected report as a missing writer.

Combined uses per-capture public provenance rather than requiring equal unused tool catalogs. Restricted evidence is not widened. Required URL verification performs a traced, budgeted read. Verifier prose without a readable receipt does not complete evidence acquisition; existing permitted support can be reused without a new network request. Explicit requested topics produce stable actionable obligations, separately from lexical contribution coverage. Rejected replacements retain the prior draft and expose their reason.

Frozen Combined input is encoded once per record-store revision. Unfinished token fragments are checkpointed in bounded batches; complete records still commit before publication. Terminal and cancellation paths force a checkpoint. Database indexing is separated from the larger snapshot-save coroutine.

Diagnostic classification normalizes event prefixes, recognizes structured-stage and rejected-delivery outcomes, and avoids counting connection timeout configuration as a connection failure. Executable-tool omission counts cannot become negative due to synthetic tools. Token decomposition is explicitly marked unreliable when provider reasoning exceeds reported output. Related model/delegation failures carry a causal incident identifier.

## Validation boundaries

The private MCP listener/VPN route, external HTTP 403 access decisions, Kilo's server-selected model behavior, device allocations, ART release-method sizing, and Android process/foreground scheduling require real service/device checks. A successful compatibility fallback is not treated as an outage. The existing memory enrichment queue already watches eligible runtime availability and persists/deduplicates waiting work; this export does not establish memory loss.

Local validation passed:

- Full app `testDebugUnitTest`: 3,640 tests across 597 classes, zero failures, errors or skipped tests. This includes Research, Room migration/schema, Combined, Free transport, delegated results, diagnostics and the Research benchmark handoffs.
- Kotlin and Java compilation, generated-class processing and Android `lintDebug`. Lint reports zero errors, 2,916 warnings and 23 hints; the report is not warning-free.
- All 145 repository Python tests, including five production-SQL retention/migration checks.
- Changed-file ktlint 1.3.1, Android regex compatibility (644 literal patterns; zero errors), resource validation (29 XML files), Kotlin syntax comparison and git whitespace checks.
- Room generated schema 4: 29 entities, identity hash `22d1250ef705ae7547b4f9654413f26c`.

The complete suite caught and verified repairs for the default 512-token contributor handoff: exact evidence survives metadata/claim packing, and unreviewed passages remain explicitly unverified. Detailed writer contracts stay separate from contributor briefs. An isolated Research store uses its injected application context without an unnecessary framework lookup.

MockK used a JVM startup instrumentation agent because this container cannot self-attach. A larger local Gradle heap was used for final validation; repository build settings were not changed for these environment restrictions.

Device tests remain required for first-tap interaction, rotation/process death, queued cancellation, foreground policy, disk exhaustion, and end-to-end provider/reader behavior. The responsiveness figures in the design are measurement targets, not claims established by these changes. Repository CI and device/service acceptance remain draft-PR review gates.
