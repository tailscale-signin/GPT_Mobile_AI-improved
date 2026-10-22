# October 22 Research and Combined reliability follow-up

This implements the supplied October 21 diagnostic audit (reported revision
`8893b63823a43ff05a287e1a8df8d4059ed9d906`) and October 22 Combined delivery audit
(reviewed revision `5ca4cead455f61742fb223501063d6690f0a9c9a`) on
`fix/research-combined-delivery-2026-10-20`, starting at `0dd5b3c8`.
The supplied audit is the incident evidence; no new device trace is claimed.

## Visible behavior

- The Research card shows each accepted profile's discovered sources, sources
  found by no other selected profile, readable sources, examined studies,
  pending assessments, input/output tokens and model-request count. Attribution
  survives shared-corpus merges and saved snapshots. Missing attribution in old
  records is not reassigned retrospectively. Estimated usage is labeled.
- Home conversation cards have one thin progress bar for Combined or Research.
  The bar follows acquisition, contributor completion and final writing/validation
  milestones. It is not a time estimate. Completion requires all accepted
  contributors, the final Combined writer when applicable, and a valid delivery
  record for the current answer. Partial/interrupted work stays below 100%.
  Durable Combined milestones are restored without opening dispatch authority.
- Unresolved Research questions appear below partial Combined answers, including
  when the retained answer remains useful. Question coverage checks are
  structural checks, separate from factual and numerical verification.

## Examination and output admission

Scientific appraisal requests retain their 6,144-token contract. Independent
review (2,048 requested), study review (4,096), extraction and lightweight
verification use separate allocations. The serialized provider request and local
runtime reject an insufficient artifact allowance before dispatch.

An output-limited appraisal splits only the unfinished source interval into
smaller independently valid JSON artifacts. Exact UTF-16 boundaries, completed
appraisals, passage IDs and chunk positions are durable. Compact artifacts use a
1,024-token contract; an interval that cannot be split further becomes an
explicit capacity wait rather than an identical retry. Study review can be
committed as a summary plus ten domain judgments, each validated against the
same evidence packet. Restarts request only missing parts. Passage IDs support
large split inventories. Unconfirmed transport failures remain partial even if
the text resembles valid JSON.

Research planning, gap finding, extraction, review and appraisal have stage-specific
deadlines. Malformed/partial outputs are retained, existing targeted schema repair
remains bounded, and model fallback stays within the already authorized endpoint
set. Combined waits up to three minutes for accepted automatic Examination work
before recording unfinished stages as partial; it does not silently release
those stage budgets while the contributor is still collecting evidence.

## Storage and identity

Research task mutations use per-task coroutine mutexes on the I/O dispatcher.
Independent journal commits run concurrently; deletion and garbage collection
use an exclusive maintenance gate whose internal state mutex never holds disk
I/O. Atomic-file readers share a short per-file lock with writers, because
Android's reader may restore a backup. Generation fences are refreshed inside
I/O admission, including when another store instance resumed the turn. The
completed-history limit remains 48 manifests. Large source indexing is split
into its own service and guarded by the packaged DEX size check.

Conflicting DOI/PMID/URL aliases create a separate quarantined capture with
`IDENTITY_REVIEW_REQUIRED`. Existing works, bodies and alias ownership remain
unchanged. The article view presents the conflicting mappings and an explicit
“Keep studies separate” review action. Identical progress saves do not reopen a
reviewed conflict. Legacy conflict outbox entries are non-retryable until the
identity input changes; ordinary transient failures retain their backoff.

## Delivery and recovery

Combined retains the compact claim inventory and adds explicit question-to-source
obligations before writing. Research delivery budgets now reserve the evidence
packet and compact contribution input, not just the original prompt. Hard token,
cost, tool and destination limits remain admission gates.

Verification selects an enabled, accepted reader profile with compatible frozen
identity and transfer permission. It supports validated legacy URL-reader
schemas as well as declared reader capabilities. Missing configuration produces
an actionable diagnostic. No account, tool or evidence grant is added implicitly.

Research reports retain the authoritative source registry, deterministic
references, source-version checks and numerical grounding. Failed non-brief
repairs can continue one section at a time. Valid committed sections survive an
interruption and are included in a useful evidence-backed partial report when a
complete repair cannot be validated.

A changed output revision may reuse an identical sealed input corpus. Direct
verification reads can prove execution through settled tool reservations even
without a model-invocation row. Recovery still checks destination, accepted turn,
current permission, completion, public-source scope and compatible evidence.
Exclusion diagnostics explain the specific failed condition.

## October 22 Combined delivery corrections

Response links have separate purposes: API endpoint examples, documentation,
evidence citations and other links. Only evidence citations must resolve in the
ledger; explicit unknown source IDs and numbered citation links still fail.
Unregistered endpoint/documentation links remain advisory and do not establish
factual support or authorize any network request.

DeepSeek DSML control tokens are withheld across every streaming boundary and
rejected before contributor admission, including a completed message with a
normal preamble followed by tool markup. Provider failures and control-only
messages cannot inflate contributor usability or enter the claim inventory.
Remaining usable contributors can proceed, with missing profiles disclosed.

The editor receives a conservative deduplicated inventory. Ranked, comparative
and word-limited requests select relevant claims while preserving contributor
ownership, qualifiers and applicable minority cautions. The frozen snapshot
retains the complete original claims. Compact evidence excerpts and supporting
source IDs replace repeated full evidence bodies in synthesis and repair
prompts. Lexical coverage remains a diagnostic, not an obligation to reproduce
every contributor sentence. Claim decisions are only requested for material
disputes or omissions. `MERGED` requires a real matching final span.

Repairs target material factual failures, required user scope and concrete
verification needs. Advisory attribution or coverage issues retain a useful
answer as completed with limitations, explicitly unverified. Material unresolved
failures remain partial. A rejected replacement keeps the previous revision and
receipts; replacements cannot add new verification needs or new failed checks.

Malformed decision fields can reconstruct only the rejected logical record.
Committed records stay immutable, rejected bytes remain in the journal, and
continuation counts survive restarts. Unknown evidence references, conflicting
replays, ownership failures and authorization failures do not enter this repair
path. Repeated malformed output stops at a bounded allowance with committed
passages still available.

## Tools and connections

A provider round can buffer up to 128 calls, with independent argument, duplicate,
same-operation, run-total and concurrency limits. Dispatch uses sequential groups
of at most 32. Queued call IDs, exact arguments and bounded results are checkpointed
for persistent conversations and held only in memory for temporary ones.
Completed calls are reused on resume; undispatched calls continue through current
admission. A crash after dispatch produces an unknown-outcome receipt and never
silently replays that call. This is durable local accounting, not a claim of
exactly-once side effects on an external server. Conversation deletion and
conversion to temporary remove durable journals and fence old writers.

The earlier compact GitHub schemas, repository intent synonyms, adaptive
explicit discovery, provider-schema telemetry and omission reasons remain in
place. MCP discovery now has a separate 30-second producer deadline while the
interactive catalog wait stays short and keeps last-known metadata. Explicit
connection tests use a lightweight local reachability probe before discovery.
OAuth refresh serialization and transient backoff are per connection; refresh
and authorization cannot overwrite a concurrently changed connection. Missing
or invalid credentials request reauthorization. Search challenge/cooldown
outcomes open the affected engine's circuit while other selected, authorized
engines continue.

For an explicitly enabled OpenRouter zero-data-retention policy, a cached
preflight checks the official ZDR endpoint catalog against the requested model
and selected providers. Known incompatibility produces a policy diagnostic
before inference. Missing/malformed metadata is reported as unknown and the
unchanged privacy policy remains server enforced. Other routing constraints
remain server enforced as well; availability is not a certification. Existing
credential-scoped rate-limit cooldowns continue to honor `Retry-After`.
The catalog format follows OpenRouter's [ZDR endpoint API](https://openrouter.ai/docs/api/api-reference/endpoints/preview-the-impact-of-zdr-on-the-available-endpoints).

## Validation

Validation results are recorded after the final source tree is tested. Regression
coverage includes 33-call interruption/resume, changed call identity and grants,
chunked review restart, identity quarantine, concurrent saves/readers, stale
recovery fences, five-profile source attribution, five-question coverage,
progress completion gates, independent OAuth refresh, search challenge failover
and settled verification receipts.

Device frame timing, Perfetto/Compose traces, live provider credentials and the
user's exact five-model configuration require an Android device and those
services. Unit/integration, heap and packaged-code checks do not establish those
runtime measurements. Native GGUF/NPU behavior is outside this follow-up's code
changes and is not inferred from the supplied audit.
