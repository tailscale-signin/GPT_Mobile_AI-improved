# Combined delivery repair

This change builds on the [Free-provider contract/authentication repair](free-provider-contract-auth-2026-10-22.md) on the same branch. It addresses the final-writing failures reported for France and Portugal while preserving accepted-task identity, frozen inputs, ownership fences, durable passages, and explicit contributor-fallback provenance.

## Delivery and recovery

`ProviderRouteCapacity` resolves the profile preference, known catalog/output ceiling, and enforced route limit before planning and dispatch. AI Horde's anonymous 256-token ceiling remains enforced. `CombinedWriterPolicy` separates contributor usefulness from final-writing suitability: it accounts for visible answer length, record framing, observed reasoning needs, known context capacity, current availability, malformed records, empty output, queue delays, and completed passages. Preference and reliability precede alphabetical tie-breaking. Initial selection, automatic successors, and manual recovery use this policy. Unknown structured capability remains an observation to establish, not a certification.

Requested allowance, provider ceiling, serialized allowance, and reported usage have separate diagnostic fields. The serialized request remains authoritative. Visible word constraints still apply independently of generation tokens. Combined contributor prompts state the provider-adjusted allowance even on the lightweight tool-free Free path.

Failure receipts select an explicit action: rotate on stalled reasoning, repeated malformed records, empty output, queue delay, or transient/provider unavailability; resume a valid unfinished record; stop on authorization, ownership, integrity, and hard-budget failures. Recovery does not infer permission from arbitrary error-like model prose. Existing bounded reconstruction and HTTP retry limits remain in place.

A successor is a new sealed snapshot. It reuses the exact retained contributions and checks the predecessor's saved record binding and rendered passages before carrying those records forward. It cannot mutate the original snapshot, widen source transfer authority, or accept a late write from the previous owner. Manual alternative selection uses the same policy and remaining root budget.

Failed finalization restores a readable saved revision when selection was lost, or leaves an explicit recovery panel with retained replies. Only a validated answer or an explicitly labeled contributor fallback replaces the selected result. Blank output is never reported as delivered. The panel exposes retry, another eligible writer, retained drafts, explicit **Use reply**, and **Check queued job** where applicable. Answer-slot identity, contributor identities, and current writer identity are retained separately.

Pending remote jobs retain their receipt independently of local failure and answer delivery. Inspection performs a status GET against the saved job ID, never a generation POST, and displays a separate saved provider result. It cannot publish over a successor's answer. Newly saved receipts bind the originating run/profile; legacy receipts without that binding are not guessed into an unrelated turn.

## Contributor quality and small requests

Transport status, content completeness, contribution disposition, factual support, and native finish information are separate. A native length stop or corroborated unfinished prose marks useful material partial. Punctuation alone does not reject short acknowledgments, lists, or code. Structural usability is not factual confidence. The compact writer packet includes these limitations.

Historical selection first reserves substantive material for supported periods, then adds further coverage. Event dates take precedence over broad lexical cues within each sentence. Overlapping descriptions of the same dated action consolidate provenance. Different actions, such as taking power and becoming emperor, do not independently establish a date contradiction. The packet identifies unsupported outline periods. These are conservative deterministic editorial checks; existing authorized verification handles material evidence questions, and the app does not claim factual verification from agreement or chronology alone.

A bare greeting or `test` selects one available provider and assembles its current-request acknowledgment locally through the same durable record and publication checks. Explicit phrases such as `test all providers` retain the selected participant test and summarize observed acknowledgments locally without another model invocation. Exact application-generated status text is validated against its frozen input rather than being treated as a set of competing factual quantities. Prior history is not imported into these acknowledgments.

Tool-free finalization no longer emits a 0/0 tool-quota notice. Delivery failure is recorded separately from the earliest contributor failure. Export path resolution and file preparation run on IO.

## Reasoning capabilities

Observations are bounded and expire after 24 hours, scoped to endpoint, requested alias, reported served model, and backend. Incoming answer bytes do not count as completed structured passages. Recorded data distinguishes the transmitted control, unverified disable support, observed reasoning despite a control, useful-output allowances, and stalled allowances.

Kilo retains its documented reasoning control, while actual served-model observations account for dynamic aliases. The explicit BlockRun Nemotron 3.5 Lightning route transmits `chat_template_kwargs.enable_thinking`; other models do not inherit this setting. BridgeNode disable support remains unverified rather than inventing a provider parameter or claiming its UI preference was honored.

Primary contracts checked for the model-specific control:

- https://blockrun.ai/docs/api-reference/chat-completions
- https://docs.nvidia.com/nim/large-language-models/2.0.10/get-started/advanced/get-started-nemotron-3.5-lightning.html
- https://kilo.ai/docs/gateway/models-and-providers

## Validation

The full debug Kotlin and unit-test source builds passed. The combined regression task passed **658 tests with zero failures or skips**, covering all `*Combined*`, `*Free*`, provider-adapter, chat-repository, agent-runner, stream-reducer, credential-migration, provider-selection, and prompt-queue tests. Coverage includes actual Horde capacity, initial/preferred/successor selection, malformed-record handoff across restart, stale-owner rejection, queued-job GET-only inspection, empty-final failure state, Free contributor prompt assembly, clipped history contributions, chronological coverage, saved revision selection, short acknowledgments, and explicit provider-test summaries. The existing Combined ownership, cancellation, accepted-task, accounting, publication, and provider tests also passed. A final focused run after tightening the repeated reasoning-stall eligibility rule passed **33 tests with zero failures or skips**. Changed Kotlin files pass ktlint 1.3.1, and `git diff --check` is clean. Recovery actions use a vertical layout so narrow screens retain access to every action.

The final debug Kotlin compile also passed after arranging the recovery actions vertically and making the current writer identity visible throughout finalization.

These tests do not establish live third-party service availability, universal reasoning-disable support, or factual accuracy of arbitrary provider output. The hosted Qwen backend remains an external service failure; the preceding provider repair retains its details and controls readiness.
