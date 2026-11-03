# GitHub graph intelligence implementation

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base branch: feat/voice-github-status-redesign-2026-11-02
Base commit: 5e8d29e9c66713295162e0f04a1d9b799b9d5a4b
Working branch: feat/github-graph-intelligence-2026-11-03

Parent work: PR #788, merged into main. Before publication, fast-forwarded the working branch to current main e76ff28bcb220965c145f6ab8461e15a75a6f964, retaining its release/APK-packaging changes. The graph PR targets main.

Intent: implement the 22 graph intelligence features from the approved Graphify comparison. Keep GitHub authorization, source evidence, and required CI authoritative. Graph observations never authorize changes or establish runtime correctness.

## Feature map

All 22 proposals have an implementation entry point. These are bounded, source-level capabilities; inferred relationships and historical knowledge are never execution evidence.

| # | Feature | Implementation and available behavior |
| --- | --- | --- |
| 1 | Structural code graph | Python AST and tree-sitter Kotlin, Java, JavaScript, TypeScript/TSX; JSON schema declarations and Markdown headings. Files, classes, functions, containment, imports and unique static call candidates. |
| 2 | Relationship-aware search | `query` ranks matching symbols and expands related neighbors within the result budget. |
| 3 | Dependency tracing | `callers`, `callees` and directed `path`; ambiguous selectors return choices, unresolved dispatch stays unresolved. |
| 4 | Change impact | `impact` walks reverse dependencies from paths or symbols and reports reachable affected files. |
| 5 | Provenance and confidence | Immutable GitHub links, commit and blob SHA, parser, line ranges, and structural/inferred confidence. |
| 6 | Incremental branch indexes | Reuse identical blobs; delete removed fragments; resolve all relationships again after changes. Separate principal, GitHub actor, repository and branch namespaces. |
| 7 | Completeness reporting | `status` exposes pending, excluded, unsupported, failed and partially parsed files plus unresolved references. |
| 8 | Bounded context | Byte-bounded valid JSON, explicit truncation, neighborhood limits and no dangling displayed edges. |
| 9 | Requirement traceability | `note`/`requirements` join requirements to source blobs, affected test candidates and explicitly linked engineering receipts. Workflow success requires an exact repository and executed SHA receipt. |
| 10 | Diagnostic mapping | `diagnose` maps bounded log locations to containing source declarations. It does not establish root cause. |
| 11 | Repair memory | Historical CI/repair records captured by engineering jobs and explicitly through `note`; source changes require review. |
| 12 | Android/gateway contracts | `contracts` compares literal producer/consumer field names and reports mismatches for inspection. It is not whole-program type inference or protocol verification. |
| 13 | PR overlap | `prs` compares changed files and impacted neighborhoods for up to 20 open PRs; gives review-order hints without merge authorization. |
| 14 | Affected tests | `impact` ranks related test paths, labels them NOT_RUN, and preserves required CI. |
| 15 | Code-linked decisions | `note`/`notes` support source-linked decisions, explicit supersession and invalidation when supporting blobs change. |
| 16 | Architecture diff | `diff` compares two explicitly indexed immutable revisions for symbols and relationships. |
| 17 | Architecture health | `architecture` reports connected file communities, dependency cycles, hubs and review candidates. These are static heuristics, not calibrated risk scores. |
| 18 | Mobile repository map | GitHub chat's Repository map dialog has resumable indexing, search, tappable nodes, source navigation, tracing, impact/tests, PR overlap, notes and requirements. |
| 19 | Source-linked architecture docs | `wiki` generates Markdown and bounded Mermaid diagrams from the selected index; mobile can copy the document. Regenerate after indexing a new revision. |
| 20 | Optional Graphify provider | `provider` calls an explicitly configured existing Graphify MCP server. Validates local checkout identity, clean state and SHA before/after. External index freshness remains unverified. |
| 21 | Cross-repository mapping | `federated` searches up to five explicitly selected and separately authorized indexes, reports ambiguous shared names and qualified import candidates. |
| 22 | Measured retrieval dashboard | `metrics` and the mobile Metrics view show actual request counts, latency, output bytes and index reuse statistics. Accuracy and token savings say Not measured. |

## Entry points

- Gateway: `POST /gateway/engineering/graph` (also `/v1/gateway/engineering/graph`) under existing engineering principal authorization. Required fields: `action`, `repository`, `branch`, observed full `commit_sha`.
- Model tools: one `repository_graph__<connection alias>` tool for eligible Android GitHub chats, and `pc_repository_graph` for a local gateway model. Normal GitHub evidence and approval flows remain authoritative.
- Mobile: choose a private Llama/Ollama gateway profile, enable GitHub tools and persistent engineering memory, then open Repository map in a non-temporary GitHub chat. Choose Index to start/resume. Closing or Stop cancels the client loop and preserves completed batches.
- Optional Graphify: host environment `GATEWAY_GRAPHIFY_SERVER` names an already configured MCP server; `GATEWAY_GRAPHIFY_PROJECTS` is a JSON object mapping `owner/repo` to its local checkout. Ignore generated Graphify outputs so the checkout remains clean. No server is installed or enabled automatically.

Example index request (replace the SHA with a currently observed revision):

```json
{"action":"index","repository":"owner/repo","branch":"feature","commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","budget_bytes":16000}
```

Repeat with the returned `index_cursor` while `has_more` is true. `status` also returns a cursor after interruption. Use `retry_failed:true` on the first index request to retry failed/unsupported fragments after fixing parser availability.

## Storage, authorization and bounds

Encrypted snapshots and notes use the existing engineering database and AES-GCM key lifecycle. Losing the encryption key fails closed. Graph recall into an engineering model is limited to loopback model destinations and persistent-memory jobs. Temporary chats do not expose the explorer or its graph tool. The Android client rechecks profile, connection, workspace feature permissions and gateway principal after every response.

Every direct graph request checks the existing gateway GitHub account, repository access, commit identity and branch head before loading cached source. Stale snapshots stay explicitly marked. Indexing accepts regular tracked supported files only, with 24 files per resumable batch, four read workers, 256 KiB per file, 8,000 eligible files per repository, four revision snapshots per branch namespace, and 128 MiB uncompressed per snapshot. Native parser dependencies are version- and hash-pinned and included in Windows PyInstaller collection.

Notes are capped at 4,096 per namespace; retrieval scans the newest 512 and returns up to 32 ranked records. Federation compares explicit qualified declaration imports with a bounded comparison count; other package/module/runtime dependencies need source investigation. Source text is untrusted data and is never executed by indexing.

## Validation ledger

- 25 focused graph tests passed, covering structural parsing, ambiguity, import resolution, invalidation, contracts, exact diagnostic line mapping, bounded context, encrypted restart, account/branch/principal isolation, revoked permissions, key loss, resume cursors, requirement receipts, federation authorization, capture/resume and private-model recall.
- Two graph HTTP tests cover existing engineering-principal authorization and bounded-capacity responses.
- Four Android regressions added for immutable request binding, scope-override rejection, operation routing/uncertainty and endpoint response handling. Android execution is tracked by the PR's normal validation workflow; this host lacks JDK 21 and Android SDK 37.
- Final `python gateway/check_gateway.py` passed: 367 Python tests (one existing platform-specific skip) and seven browser bridge tests, including the 25 graph regressions. An intermediate run rejected the edited package's stale hash manifest; regenerating the manifest restored the passing full run.
- Desktop `validate-engine` now extracts a function with all five native grammar variants before admitting a candidate executable. Source-mode preflight passed locally; the Windows installer smoke executes this same check inside the frozen executable.
- `bash scripts/validate-local.sh fast` passed: 297 script tests plus repository, resources, regex, icon, dependency and provenance checks. Changed Kotlin sources passed ktlint 1.3.1; final generated indexes and package manifest are regenerated before publication.
- Measured local working-tree smoke: 3,879 files parsed in 9,312 ms; 31,521 nodes and 67,743 edges; graph construction 2,217 ms; a sample query 64 ms. 37 files parsed partially and 244,842 references remained unresolved. This was a development working tree, not an immutable release checkout, phone benchmark or network-indexing measurement.

## Remaining validation boundaries

The feature surface is implemented, but Android compilation, device interaction and Windows packaged parser loading require their respective CI/device environments. An installed external Graphify server has not been exercised here. Dynamic dispatch, reflection, generated code, parser errors and omitted files can hide dependencies; an empty graph result is never proof of absence. No feature bypasses required CI, GitHub rules or existing mutation approvals.
