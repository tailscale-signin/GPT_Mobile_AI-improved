# GitHub mode reliability and intelligence

## Change identity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main` at `0e625e250117475446b8f216df2501ddbef168f2`.
Working branch: `fix/unified-github-gateway` (draft PR #2646).
The original isolated branch was reconciled with concurrent work at
`d47f17815e43a70353d39c576378d7726bbefabd` and its subsequent admitted-tool
replay correction at `f421d95d192d733f6cf2498d45fa09093be47212` before publication. The combined change
preserves unified task routing, prepared request budgets, selected-model wakeup,
provider quota handling, independent batch reads and verified-owner Gateway defaults.
Intent: implement the November 26 diagnostic investigation, qualify the exact
revision through a draft PR, merge after the required checks, and publish the
versioned Android and Gateway releases after main-branch verification. Concurrent branch
advances must be reconciled; publication never uses an unconditional ref update.

## Behavioral contract

The accepted task, selected revision, retained evidence, effective capacity,
remaining budget and saved answer continue to belong to the existing task and
evidence owners. Replay admission follows the selected model-visible schemas.
A plain answer can use an exactly full context without reserving hidden backend
receipts; queued or unsolicited tool calls still require evidence space before
execution. A receipt can complete a deterministic lookup before provider replay
admission or another provider call. Broader investigation still requires its
accepted criteria and normal verification/publication gate.

| Investigation finding | Required implementation and regression |
| --- | --- |
| Adaptive planning and replay used different capacity ceilings | Carry the effective context ceiling and provenance from planning through execution, continuation and final serialization; exercise the recorded prompt sizes and minimum usable replay envelope. |
| Exact OpenRouter latest alias metadata was not reliably refreshed/restored | Refresh the public exact ID, persist its observation age and advertised capabilities, reject stale metadata, and keep custom routing unknown unless observed. |
| An empty successful PR list could fail replay admission | Finish a complete, trusted, bounded direct lookup from its receipt without requiring a provider round or a second tool call. |
| Current clean CI drifted into an older failed run | Freeze the accepted source SHA, complete current-revision observations through the real session gate, and reject accidental historical substitution. |
| Repeated runs and overlapping log reads looked like progress | Bind progress/reuse to repository, source, run, attempt, job and covered ranges; permit a new attempt or real evidence delta. |
| Provider waits and normal tool handoffs consumed the transport retry allowance | Retain separate durable recovery counters; reuse the existing provider wait deadline owner and reconcile uncertain writes. Preserve original structured failure evidence. |
| A useful saved report was replaced by a generic terminal error | Preserve the useful report and remaining criteria on interruption; never label an unfinished diagnosis verified. |
| AUTO final synthesis spent its entire cap on reasoning | Use the lowest advertised compatible reasoning effort for the supported AUTO final-delivery path; preserve explicit fixed settings and never invent support for a numerical reasoning cap. |
| Sparse indexing and broad path matches wasted focused context | Reuse immutable source/symbols across tasks, retain bounded lexical references, add relevant caller/configuration neighbors, and report the excerpts actually prepared. |
| Qualification prerequisites were difficult to distinguish from completion | Report read-only, supported-fixture and full-live scope separately, with required passed/blocked/untested/failed counts and exact prerequisite groups. |

## Focused repository context

Task context bundle version 8 retains existing authorization and source identity
checks. Its symbol catalog can read the earlier compact definitions and upgrades
only observed immutable blobs to include bounded lexical references. Catalog shards
retain recent rows under a 1 MiB encoded UTF-8 ceiling, including JSON framing. Reusing an
unchanged blob does not require fetching it again when the selected commit or
chat changes. A packet is still pinned to its exact repository/ref/commit/query
and parser/bundle generation.

Ranking excludes generic directory scaffolding such as `app/src/main` while
retaining exact path matches, meaningful filenames, symbols and verified feature
references. A selected implementation and related test retain first access to
the shared deadline. At most two retained caller candidates and one nearest
configuration file extend the existing bounded import/test neighborhood. The
same 20-source ceiling and preparation deadline apply; no full repository scan
is required before an ordinary lookup.

Coverage records selected source bytes and lines after packet truncation, plus
caller/configuration counts. `source_complete` explicitly describes the attempted
task selection only. The existing context card displays these prepared counts
and still explains that final request admission may trim or reject context.
Lexical relationships remain candidates, and test source is not execution proof.

## Qualification and evaluation

The observed 132-row qualification shape has 126 required and six excluded
capabilities. A synthetic regression preserves its 46 passed, 73 blocked and
seven untested required rows: 80 capability gaps. Independent task criteria are
counted separately, so an additional unmet criterion does not become a fabricated
81st API capability. Cached/mocked/untested evidence cannot be promoted to a live
pass by the summary. Scope selection does not change permissions or create
fixture authority.

Regression evaluation uses task completion, revision correctness, novel evidence
coverage, duplicate reads, preserved recovery state and delivered answer behavior.
Confirmed provider usage remains separate from estimates and failed reservations.
No live model quality, phone latency, token-saving percentage or superiority over
another coding product is inferred from a deterministic regression.

## Validation record

Local focused JVM execution covers 32 production-context,
qualification-summary and context-presentation tests, 25 merged capacity/catalog tests,
44 durable recovery tests, 14 lookup/CI evidence tests, seven interruption-export
tests and 16 terminal-delivery tests. The repeated-provider-wait regression fails
against the original base and completes with the recovery change. The interruption
export suite also fails against the earlier implementation and passes after cause
retention is repaired. Two adapter-specific terminal tests require the full Android
test graph and were excluded only from the standalone host harness. Android session, provider,
runner and recovery regressions are included in the ordinary test sources and
must pass the repository's exact-revision PR and main-branch gates. The canonical
host gate is `bash scripts/validate-local.sh fast`; generated source maps and
inventories must match the final combined revision. Release signing, artifact
identity, packaging size, attestations and published-asset verification remain
mandatory and are performed by the existing release workflow.

The coordinated Gateway 18.1.5 host gate collected 2,123 Python tests across
154 modules: 2,091 passed and 32 were skipped for unavailable environment
capabilities. Seven browser-bridge tests and the 50,000-event diagnostic stress
check passed. Native Windows behavior, installer packaging and publication
attestations remain the required hosted gates.
