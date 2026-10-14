# Research workspace and runtime reliability

Sources: the user-supplied October 13 runtime design, log issue audit, and engines/archive/memory design. This change builds on the existing research journal, archive, appraisal, permission gate, and modern MCP transport. It does not replace the existing Unified Reliability release gate.

## Changes

- Expected saved-evidence authorization denial becomes a typed outcome and clean repository exit. Reason code, root/generation, evidence and destination revisions, allowed actions and repeated-generation counts persist in the existing assistant timeline. Cosmetic profile edits do not change destination authorization; endpoint, credentials, model and other policy changes still do. Evidence is checked again before outbound requests.
- Partial text stays partial; source bodies and deferred handoff packets are retained separately from the fitting inline packet.
- PubMed uses NCBI ESearch followed by bounded EFetch, preserves biomedical metadata and abstracts, has a shared rate budget, and exposes the 10,000-result query limitation. Europe PMC requests core metadata with query/owner-scoped cursors. Neither provider grants peer review merely by indexing an article.
- Scholar is an explicit browser handoff with bounded DOI/PMID/PMCID/URL, BibTeX and RIS import previews. No Scholar automation or background waiting is claimed.
- Discover, Archive, Learned outcomes and Activity are separate workspace tabs; Settings is a separate action. Native discovery admits entries incrementally and Room Flow refreshes the archive.
- Standalone imports have independent owners. Existing FIFO retention, exact identity conflict checks, immutable source versions and deletion fences remain in force. Metadata refresh does not replace retained readable text. Provider discovery provenance is recorded independently.
- Organization uses source metadata and stable rules. It does not call a model. Empty topic groups are omitted; user-created categories and topic changes remain available.
- Learned outcomes require exact supporting passage references and separate research-memory consent. They never enter personal-fact storage. Current-version, body-hash, retraction, exclusion, deletion and consent checks run again before recall dispatch. Historical outcomes do not answer a request for current evidence automatically.
- Explicit study/article citation references bind the version supplied to the writer even after a refresh; a URL in the same paragraph inherits that immutable reference. Article details offer Open source and Use in chat through the existing reviewed share flow.
- Hub metadata has a bounded, credential-scoped cache at pinned revisions; ordinary Hub discovery renders partial results incrementally.
- Research database 1→2 adds discovery, outcome, recall exclusion and browser handoff records. Backup schema 2 reads schema 1 and restores imported outcomes as NEEDS_REVIEW.

## Validation and release status

Test evidence is recorded in `validation.md`. Passing unit tests is not target-device acceptance. The supplied runtime design explicitly holds CR-01 open until repeated denial/recovery on the actual ASUS runtime is observed without an ART abort. Navigation and SQLite performance require release-like traces on ASUS and a comparison device. The raw 8,588-row capture was not supplied as a log fixture with these three design documents.

The existing `scripts/check_unified_reliability_v2.py --require-done` release gate rejects the current completion inventory. Its 48 task records and unpassed acceptance cases cannot be marked done merely because CI succeeds. No version bump or signed release is justified until that gate and the device acceptance hold are satisfied.

## Runtime finding register

All thirty original IDs remain visible. “Existing” identifies code to validate, not a declaration of acceptance.

| Findings | Components / evidence | Remaining acceptance |
| --- | --- | --- |
| CR-01, CR-02 | ResponseRecoveryContext; ChatRepository dispatch authorization; denial tests | T01–08, physical runtime denial/restart and zero unauthorized sends |
| CR-03 | Existing root work identity and AgentRun compare-and-set; persisted typed denial/fingerprint | T03/T05/T06 durable repeated-action admission |
| RS-01, RS-06 | Existing ResearchSessionStore frontier and budget fences; retained chunks; partial labels | T04/T09/T12 windows, restart and coverage |
| RS-02 | Existing canonical review identity and currentReviewRecord | T13–14 source-bound independent review |
| RS-03 | Existing extraction validation; conservative outcome builder | T10–11 yield and repair fixtures |
| RS-04, RS-05 | Existing local assist readiness, explicit parent tools, runtime scheduling | T17–20 on-device contribution and scoped children |
| RS-07 | Academic readers; explicit abstract/partial/full coverage | T15 permitted access ladder |
| MC-01, MC-02, MC-07 | Existing McpClientManager, ModernMcpTransport and LocalServiceHealth | T29–32 actual server auth, compatibility and readiness |
| MC-03, MC-04, MC-05, MC-06 | Existing request deadlines, safe replay rules and health retry state | T33–36 gateway ports, stream loss, DNS, cancellation |
| CT-01 | Existing role-specific synthesis tools; packet selection reports deferred evidence | T20–21 serialized schema reduction and slice access |
| CT-02, CT-03 | Existing model catalog provenance and invocation ledger | T22–25 route caps, provider usage vs estimates |
| BM-01 | Existing benchmark cohorts and invocation outcomes | T25 complete/partial/failed/canceled denominators |
| UI-01, UI-02, UI-03 | Background discovery and reactive paginated archive; existing stream method extraction | T40–42 release-like traces, generated method inspection |
| LC-01 | Shared bounded Hugging Face requests, pinned metadata cache and incremental results | T39 partial refresh and navigation/download isolation |
| MM-01, MM-02 | Existing FTS fallback and durable model-ready enrichment queue | T26–28/T37–38 driver matrix, wake-up and deletion |
| DV-01 | Structured diagnostic classification/export | T44–46 raw capture incident accounting |
| PL-01 | OEM classification preserved independently | T43 isolate recurring protected-setting observation |
| CV-01 | Live native engine status and explicit Scholar browser handoff | T16 actual requests and skip reasons |

## Engines/archive acceptance register

RE-01 through RE-08 and AT-01 through AT-24 are the source requirements; this document does not silently reduce their scope. Fixture coverage includes native XML/citation parsing, migration, archive identity/provenance, reactive insertion, clear fences, exact outcomes and recall revocation. Live provider contracts, large-font/two-pane UI, detached-job/process-death scenarios and device latency require additional acceptance before declaring the entire design complete.
