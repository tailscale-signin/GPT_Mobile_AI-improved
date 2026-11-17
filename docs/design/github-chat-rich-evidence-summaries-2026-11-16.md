# GitHub Chat: rich, evidence-based engineering summaries

**Status:** feature design / implementation specification (not a claim of shipped behavior)  
**Target:** Android GitHub Chat + persistent Gateway engineering conversations  
**Integration:** existing draft PR #1349, `fix/post-1299-incident-diagnostics-2026-11-15`  
**Date:** 2026-11-16  
**Product goal:** make GitHub Chat's visible updates read like a useful technical assistant's work summary, not a repetitive log of tool calls.

## 1. Problem and source inspection

Today, `GitHubProgressNarrator` defaults to deterministic, receipt-derived prose. Its optional selected-model instruction explicitly forbids headings, and the fallback `factualSummary()` concatenates the last few observations and a generic "Next" statement. `GitHubChatProgress` uses a 45-second summary gate; no meaningful result may have changed by then. `GitHubActivityLines` coalesces exact summary text but does not determine semantic novelty. The compact activity path is distinct from the standard response renderer. Separately, `ChatMarkdown` already supports headings, bullets and Markdown tables, so reuse that parser for suitable content instead of adding a competing Markdown engine.

**Do not** simply prepend Markdown characters to plain `Text` components: this would display literal hash marks, pipes and asterisks. UI and production must be changed together.

## 2. User-facing experience

### A. Three presentation tiers

| Tier | When | Default display | Frequency |
| --- | --- | --- | --- |
| Live activity | A tool is running or a count advances | One short verb + target, existing animated header, duration and counters | Existing quick cadence; no long model summary |
| Insight update | New diagnostic finding, completed change, blocked action, changed CI evidence, meaningful decision | Short title, one-sentence takeaway and 1-3 evidence-based bullets | Material change only, debounced and capped; never filler |
| Rich comparison | At least 3 comparable verified observations or a genuine table of structured results | Above plus **one** compact table or graph | Occasional, only when clearer than prose |

After a run terminates, render a distinct **final outcome** with what was *actually verified*, what remains unverified and a concrete next action. A progress summary must **never** masquerade as final verification.

### B. Example layouts (synthetic sample values only)

Simple insight:

> **CI failure traced to Android build**  
> - **Found:** Kotlin compilation failed in the current run.  
> - **Changed:** The retry guard has a proposed fix; publication is not yet verified.  
> - **Next:** Run the affected test and compare the exact PR head.

Use title case sparingly; 3-7 words in a heading, not `# Status Update` repeated every time.

Table, when independent source rows exist:

| Check | Observed outcome | Evidence |
| --- | --- | --- |
| Android unit test | Passed | Run 251 / exact revision |
| Windows Gateway | Failed | Run 252 / exact revision |
| CodeQL | Pending | Current check read |

The table includes no inferred status. `Unknown` / `Not measured` are distinct from `Passed`, `Failed`, `Skipped` and `Pending`. Table cells link or expand into their source evidence. Provide horizontal scroll on narrow screens, copy as TSV/CSV and accessible row labels.

Chart, when a consistent numeric series is *actually observed*:

> **Check results across runs**  
> A line/bar chart of passed checks for three or more **actual run snapshots**, labelled by run/revision. Do not fabricate a trend from one snapshot, interpolate missing runs, mix denominators, or imply success from "tests discovered". Show the measure, unit, legend, time labels, source and a text equivalent.

### C. Layout and interaction

- Use Markdown typography for headings, short paragraphs, bullets and horizontal-rule-free tables. Prefer one compact title + at most 3 bullets, with a second detail level available on tap.
- A rich update has **at most one** data visualization. No graph just because the narrator wants decoration. Tables/charts are optional and should be rare in rapid updates.
- Mobile: full-width content in the existing chat flow, no giant nested cards; 8-12 dp internal spacing; labels and statuses in theme colours; dark/light/high-contrast readable; reduced-motion support. Avoid horizontal page drag; confine table horizontal scroll to the table.
- Keep the pinned Github activity header one line. Never render a table or chart into the timer/header. A summary update stays below it; history can collapse without destroying any unique finding.
- Offer **Details / Evidence**, **Copy summary**, **Copy table** and **Export table** when relevant. v1 export: RFC 4180 CSV with spreadsheet-formula-injection protection and UTF-8; later, real XLSX (typed cells, headers, no executable formulas) rather than merely calling Markdown an Excel file.
- Complete summaries are retained and render identically after app restart/reconnect, without making the same event appear as a fresh update.
- Accessibility: semantic heading levels, row/column headers and the same data in an alternative text table for charts. Do not put every graph animation into a live region.

## 3. Data contract (new: github.rich-summary.v1)

The engine produces a **typed, versioned structure**, not arbitrary model-authored UI. Persist it alongside existing public summary/notice metadata, maintaining backward-compatible plain-text projections for existing conversations, notifications, exports and older clients.

```json
{
  "schema": "github.rich-summary.v1",
  "job_id": "job-redacted",
  "revision": 42,
  "head_sha": "observed-exact-source-sha",
  "kind": "MILESTONE",
  "title": "CI validation uncovered a failure",
  "takeaway": "The current Android compile check failed; no merge is verified.",
  "bullets": [
    {"label": "Observed", "text": "Android compilation failed.", "evidence_ids": ["check:251"]},
    {"label": "Next", "text": "Inspect the exact failed step.", "evidence_ids": ["check:251"]}
  ],
  "blocks": [
    {
      "type": "table",
      "columns": ["Check", "State", "Evidence"],
      "rows": [["Android", "Failed", "check:251"], ["Windows", "Pending", "check:252"]],
      "evidence_ids": ["check:251", "check:252"]
    }
  ],
  "source_event_ids": ["event-8", "event-9"],
  "created_at": "source-event-time",
  "verification": "OBSERVED_NOT_FINAL"
}
```

Actual IDs and SHA are supplied by the source pipeline; values above are illustrative. No unverified label, unknown check or unsupported number is to be materialized as a fact. Do not return the synthetic sample in production.

Recommended model / persistence types:

- `SummaryKind`: `FINDING`, `MILESTONE`, `BLOCKER`, `COMPARISON`, `FINAL`.
- `SummaryBlock`: `Bullets`, `EvidenceTable`, `EvidenceChart` and `PlainText`. For `EvidenceChart`, choose a trusted renderer with `BAR` or `LINE`, a **single named metric**, unit, series values, ordered labels, evidence references and text equivalent.
- `EvidenceRef`: job ID, call/event ID, repository, observed head SHA, provider source, timestamp, status, visibility/privacy classification, read authority.
- `NoveltyKey`: stable fingerprint of canonical source event IDs + factual outcome + revision (not wording or model choice). For text-only historic updates, use existing line dedupe without declaring semantic equivalence.

The model may propose a title and explanatory prose from a pre-redacted, bounded evidence set. Only the app/Gateway builds tables and chart datasets from verified structured receipts; the model **cannot introduce numeric cells**, choose an unchecked status, or supply a direct arbitrary UI payload. Serialize with bounds (e.g. title 80 chars, takeaway 240, 3 default bullets, 12 rows and 6 columns per table, 12 chart observations, one visualization/update) and deterministic overflow to "View details".

## 4. Event-to-summary policy (how repetition stops)

1. Ingest bounded, privacy-safe observations from the existing Gateway progress, operation result and acceptance/CI evidence streams. Bind to the correct job/branch/PR/head and deduplicate replayed operation IDs before synthesis.
2. Maintain a source-backed `FindingLedger` recording the last **meaningful** facts emitted per job, not simply the last two strings. When a fact is superseded, explicitly state the changed result. Keep unresolved blockers pinned until resolved or qualified.
3. Classify a **material delta**: first verified finding; changed failure/blocker; a new committed/published mutation receipt; changed CI outcome; different missing requirement; newly verified task criterion; explicit user decision. Merely reading another file, unchanged retries, ticks, elapsed time, duplicate logs and unchanged next steps are *not* summary triggers.
4. On a material delta, prepare content and debounce to avoid bursty cards (design default: coalesce ~15 s, maximum one routine update per ~45 s). Urgent user-action blockers can bypass the routine debounce, but still dedupe by event key. Preserve current 3-second lightweight activity/counter updates.
5. Choose the content shape algorithmically:
   - Default: title + takeaway + 1-3 bullets.
   - Table: 3+ independent, comparable structured rows or a genuine multi-column inventory whose relationships benefit from scanning.
   - Chart: 3+ verified numeric observations on the same metric with consistent scope/denominator and time or ordered run labels; different labels with categories may use a bar chart. Never chart qualitative statuses as an invented number.
6. Validate all public claims against known observations. Display `Not available` or `Not measured` when evidence is absent. Retain `blocked/pending/skipped` distinctions. Apply the existing redaction and provenance rules to every title, bullet, table cell, export and chart label.
7. Persist the accepted summary with its source IDs and revision, then mark only those events as published. If selected-profile prose fails validation or times out, produce the deterministic structured fallback without failing the engineering job or consuming more tool work.
8. Do not regenerate a "new" summary just because a user reopens a chat or the Gateway reconnects. Do not replace final answers with progress cards.

**Anti-boilerplate rule:** Never repeatedly emit "I am investigating", "I reviewed X", "Next I will continue", "I have completed N operations", or another generic introduction when the *observed facts have not changed*. An unchanging result produces **zero** new summaries.

## 5. Implementation touchpoints

| Layer | Current integration | Change |
| --- | --- | --- |
| Source observation | `data/github/GitHubChatProgress.kt`, Gateway progress + result receipts | Normalized finding ledger and material-delta event keys; no raw tool-body parsing in timer path |
| Authored/factual prose | `data/github/GitHubProgressNarrator.kt`, `GitHubProgressPresentation.kt` | Remove "no headings" restriction for rich output path; content-shape planner, no repeated-next fallback, verified-data-only block creation |
| Accepted public notices | `data/github/GitHubSemanticSummary.kt`, `ApiState.ActivitySummary` | Versioned optional rich payload + public plain-text fallback; selected-profile provenance, content-size budget and privacy boundary |
| Timeline | `presentation/ui/chat/GitHubActivityLines.kt`, `GitHubRenderedLines.kt` | Stable ID, semantic dedupe and ordering across replays; line-count pacing must account for actual laid-out content |
| UI | `GitHubLiveProgressCard` call in `ChatBubble.kt`, existing `ChatMarkdown.kt` | A rich-summary composable with Markdown, bounded data table and evidence chart; maintain old single-paragraph renderer for historical/unsupported entries |
| Export | Existing chat export and debug export | Human-readable Markdown/CSV, optional XLSX, data/evidence references with safe spreadsheet escaping |
| Gateway persistent jobs | `gateway/v14/activity.py`, durable job/evidence storage | Forward canonical observed facts, not model-only interpretations; persist across disconnect and support source-bound replay |

Do not change core tool permissions, automatic merge criteria, rate-limit/retry semantics or the selected AI profile just to implement the presentation. Any new schema migration must be backward compatible and tested.

## 6. Acceptance tests / required evidence

1. **No repetition:** 100 identical heartbeat ticks + repeated tool result replay generate no additional insight card. A changed real failure generates exactly one.
2. **Substantive content:** Each non-introduction update names a concrete file/check/PR/finding or displays a useful scoped count, with an evidence ID; empty facts show no filler.
3. **Safety:** An optional model cannot claim an unobserved pass, merge, fix or statistic; unknown check status remains `Not measured`; pending, skipped and failed remain distinguishable.
4. **Conditional visuals:** Single failure => brief bullets; 3+ comparable real check rows => table; 3+ consistent numeric run snapshots => labelled chart; no data => no chart. Different denominators => no fake trend.
5. **Renderer:** Light/dark and 360 dp / tablet widths; headings, bullet wrapping, table scroll, chart alternative text, reflow without flicker or runaway measurement; reduced motion.
6. **History:** Kill/reopen/reconnect with durable progress: same summary count, content, order, source references and last outcome; no duplicate notification.
7. **Export:** CSV/TSV quote escaping, spreadsheet formula injection prevention (`=`, `+`, `-`, `@`, tabs and leading whitespace), PII redaction, correct evidence labels. XLSX only if generated as an actual workbook.
8. **Performance:** Timer and active preview stay inexpensive; decode/format in background, memoize by summary ID and width; summary/chart rendering must not reparse on every token or timer tick. Track render latency, frame drops and summary density without logging private content.
9. **Current-head test evidence:** Code/unit/Robolectric screenshots + Android integration + Gateway event fixtures + CI on the exact PR head. Design acceptance is not a merged or deployed feature.
10. **Completion semantics:** Never mark summary as verification. Final outcome must be based on latest acceptance receipts and cannot say "all green" or "merged" without current-head authorization and check evidence.

## 7. Build order

**Phase 1 — data and text:** typed summary schema, fact ledger, novelty gate, safe deterministic headings/bullets, migration, Kotlin/Gateway tests. Keep original single-line live activity.

**Phase 2 — structured UI:** rich summary renderer, source-linked tables, compact evidence disclosure, copy/export, accessibility and responsive layout tests.

**Phase 3 — graphs:** native small line/bar chart from validated source series, text alternative and tests against misleading denominator/time comparisons.

**Phase 4 — selected-profile enrichment and measured rollout:** evidence-constrained optional prose, compare against deterministic fallback, profile isolation; use tests to tune summary cadence, repetition and visual density. Enable by default only after the exact-head gates are green.

**Non-goal:** Turn every status line into a chart, synthesize numerical values from prose, export private logs unredacted, or add another background model whose failures stop engineering work.

## 8. Measurable success criteria

- At least 95% of rich summaries state a *new* source-supported result versus the last summary in the same job (manual + automated review).
- Zero invented CI passes, merged states, commit IDs or numeric chart values in test fixtures.
- Zero repeat cards on event replay/reconnection.
- A normal short GitHub run has **zero** gratuitous data visuals; a table/chart is shown only when its content earns the space.
- Users can scan "what changed / evidence / next" without opening the long technical activity history.
