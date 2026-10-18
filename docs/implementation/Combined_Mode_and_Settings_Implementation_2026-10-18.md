# Combined Mode and Settings: implementation design

*Product contract, implementation map, and evaluation plan — 18 October 2026*

**Decision.** Give Combined Mode two independent chat settings: **Merge style**, from Evidence-based to Collection, and **Response effort**, from Quick to Full scale. Remove the Delegation mode control from Combined chat settings and suppress its inherited automatic routing in Combined execution. Keep the selected contributors as the explicit participant roster.

These controls will improve the result only if they govern the runtime as well as the synthesis prompt. This specification therefore includes shared resource accounting, grounded claim selection, honest uncertainty, healthy-contributor fallback, reliable revisions, portable settings, and measurable release gates. The intent is a more accurate and complete combined answer with controllable work and response length.

| Decision | Proposed default | Meaning |
| --- | --- | --- |
| Merge style | Evidence-led at 25 | Favor support while retaining useful differences and qualifications |
| Response effort | Balanced at 50 | Allow normal research and detail with adaptive stopping |
| Settings interaction | Save Changes | Stage Combined edits and apply them to new requests after a successful save |
| Full scale execution | Renewable planning | Continue useful work within the requested task without an arbitrary application cutoff |

**Scope and status.** Sections 1–14 preserve the accepted design contract and its audit rationale, originally established against repository revision `ab2123debe5e3929c9128989eeed7f912eea8ba6`. Sections 15–17 record the additional Settings requirements, implementation on `fix/conversation-glass-rendering-2026-10-18`, and validation. The existing branch started this implementation at `a361beb11ddeb6ede92cc6b11fdfd9517fb2565d`; its earlier glass fixes are retained. Planning values are initial configuration, not measured performance guarantees. The original manual quality scores are not post-change benchmark results. [A1] [R01]

**Reading guide.** Sections 1–3 explain the evidence and product experience. Sections 4–6 define enforceable policy, accounting, and persistence. Sections 7–10 specify orchestration, claim quality, delivery, and diagnostics. Sections 11–13 map the work to source files, tests, and a release sequence. Section 14 records the final design review. The source register distinguishes the observed implementation from proposed interfaces.

## 1 Why Combined Mode needs this change

### Findings from the supplied chat

The earlier manual review rated the complete experience **58 out of 100**, with the final conversation recap at **68 out of 100**. Those numbers are evaluative judgments about this single log, not percentages of true claims, calibrated benchmark scores, or values produced by the app. The recap covered all seven preceding request categories, but repeated unsupported material and blurred a failed Combined reply with peer answers that had never become a successful combined final. [A1]

| Observed measure | Result | Interpretation |
| --- | --- | --- |
| Combined delivery | 7 successful finals from 8 requests | The joke request failed despite usable peer drafts |
| Contributor attempts | 40 runs with 2 failures | Participant availability and final delivery must be measured separately |
| Final quality status | All 7 completed with issues | Digest integrity did not establish semantic or factual correctness |
| Passage verification | 32 CITED_UNVERIFIED and 22 UNVERIFIED | Resolving a citation ID was not proof that it supported the sentence |
| Tool events | 48 with 15 failures | About 31 percent failed in this log including policy and provider failures |
| Successful completion latency | Median about 52 seconds | A single export cannot establish a general latency guarantee |

The successful answers sometimes improved on individual drafts. The France answer integrated useful chronology and corrected a contributor’s erroneous date. Choosing Lisbon for a request for one Portuguese event was legitimate scope selection; fairness did not require presenting every alternative. These strengths should survive the redesign. [A1]

The most serious defects were changes in meaning. A Parkinson trial citation was used to suggest a benefit although the inspected paper reported no significant benefit in the studied small sample. A minority contributor’s qualification about uncertain mechanisms disappeared while the final used stronger language. An unsupported allegation about a person’s death propagated from one candidate into later answers and the recap. Shared conversation history then made repetition resemble corroboration. These are merge and grounding failures, not a shortage of final-answer words. [A1] [E01] [E02]

The inspected runtime explains two delivery failures directly. Synthesis ownership is filtered to the pinned primary, so failure of that contributor can leave no admitted owner even when peers are usable. Replacement repair is built through a continuation helper, while progressive delivery requires a new owner to revise committed content. The new design must repair both paths. [R07] [R14]

### Product goals and success criteria

The user should be able to request a focused evidence-led result or a broad collection of useful contributions without learning orchestration terminology. They should independently choose economical work or extensive research and response development. Every position must preserve the user’s topic, requested format and length, evidence limitations, selected participants, and authorized tools.

Success means better grounded claims, preservation of consequential differences, clear resolution of contradictions, dependable delivery, and lower avoidable work. Longer answers, more searches, greater lexical overlap, and equal amounts of text from each model are not success metrics by themselves. The original eight turns become a regression set; a broader held-out set is required before claiming general improvement.

## 2 The two slider contract

### Merge style

Use five selectable stops with stored values **0, 25, 50, 75, and 100**. These are stable policy anchors, not evidence-confidence percentages. The field name is `collectionBias`; increasing it broadens coverage of distinct, relevant material. Default to **25 Evidence-led** when a chat has no valid saved value.

| Stop | Label | Selection behavior |
| --- | --- | --- |
| 0 | Evidence-based | Prioritize the strongest supported conclusions and material uncertainty |
| 25 | Evidence-led | Prioritize evidence and retain useful differences and qualifications |
| 50 | Balanced | Combine a clear answer with meaningful alternative contributions |
| 75 | Broad | Preserve more relevant ideas and competing interpretations |
| 100 | Collection | Organize distinct contributions comprehensively within the requested scope |

The policy first applies invariant admissibility rules, then ranks eligible material for inclusion. Evidence-based may omit weak optional hypotheses. Collection may preserve useful hypotheses, attributed views, and unresolved differences when they are clearly qualified. Both preserve negative results and important minority evidence. Known errors are corrected or excluded; sensitive unsupported allegations are not repeated merely to achieve coverage.

A qualification belongs to the claim itself. Population, treatment route, date, outcome, study design, confidence, and causal limits must remain attached. “A small animal experiment suggests” cannot become “this is highly effective in people.” A closing disclaimer does not repair a confident unsupported assertion earlier in the answer.

For implementation, begin with configurable evidence-strength and unique-coverage ranking weights of **5 to 1** at Evidence-based, **4 to 2** at Evidence-led, **3 to 3** at Balanced, **2 to 4** at Broad, and **1 to 5** at Collection. Normalize the scored features before combining them. These are tie-breaking priorities among admissible, in-scope candidates; they cannot waive grounding, required qualifications, or the user’s requested coverage. Calibrate them with the evaluation set rather than presenting them as probabilities.

### Response effort

Use the same five stored positions for `effort`. Default to **50 Balanced**. This control changes discretionary research, draft and reasoning allowances, checking, refinement, and default final length. It is a work preference, not a latency guarantee, a permission grant, or an instruction to exhaust a quota.

| Stop | Label | Work behavior |
| --- | --- | --- |
| 0 | Quick | Compact drafts and a concise final with minimal necessary checking |
| 25 | Efficient | Targeted verification and moderate detail with little redundant work |
| 50 | Balanced | Normal research and explanation with adaptive stopping |
| 75 | Thorough | More independent checking and room for substantial detail |
| 100 | Full scale | Extensive useful research and response development with automatic renewal |

The selected roster remains selected at Quick. Give every available participant a fair initial opportunity, using smaller drafts and shared evidence before considering optional additional passes. Do not silently convert Combined into a primary-only request. If five slow providers are selected, the system cannot promise one-model latency; accurate progress and status must reflect those dependencies.

Full scale may still answer a simple question in one sentence. For a difficult open-ended task, it can use much larger token and tool resources, multiple context windows, and output continuations. It stops when the requested work is complete or cannot usefully progress. The slider does not expand the subject indefinitely.

### Behavior at the four corners

| Combination | Expected result |
| --- | --- |
| Evidence-based and Quick | A focused answer using the strongest available support with targeted checks and explicit limits |
| Evidence-based and Full scale | Deep verification and careful conflict resolution even when the final must remain short |
| Collection and Quick | A compact organized view of distinct available ideas with local uncertainty and deduplication |
| Collection and Full scale | Broad researched coverage of useful findings and alternatives with support and disagreement preserved |

**Explicit request examples.** “Write 500 words” remains a 500-word contract at every position. “Tell me one joke” produces one joke. “Do not search” disables search at Evidence-based as well as Collection. “Compare all five proposals” preserves the comparison even at Quick. Fiction and brainstorming do not acquire unnecessary factual research merely because the evidence endpoint is selected.

## 3 Chat settings experience

### Placement and interaction

In Combined Mode, rename the existing first dialog tab from **Models** to **Combined**. Keep the current **Tools** and **Privacy** tabs. Place the two slider sections at the top of the first tab, above the existing participant and model controls. Remove `ConversationDelegationCard` and its associated divider only for Combined. The current dialog does not receive conversation mode, so the mode and Combined settings must be added to its input contract. [R02]

Each slider shows its title, selected anchor label, five tick positions, and the two endpoint labels. Show only the selected anchor’s short explanatory sentence. Do not print all five names across a narrow phone or present a percentage that looks like confidence. Preserve the existing creativity control and other relevant settings; the new feature introduces exactly two additional Combined tuning controls.

| Interface element | Required copy |
| --- | --- |
| First tab | Combined |
| First slider | Merge style |
| First endpoints | Evidence-based and Collection |
| Default first helper | Prioritize evidence and retain useful differences. |
| Collection helper | Keep distinct contributions and clearly mark uncertainty. |
| Second slider | Response effort |
| Second endpoints | Quick and Full scale |
| Effort helper | Use the selected anchor copy below followed by Your requested limits still apply. |
| Active or queued work notice | After saving, changes on this tab apply to new requests. Running and queued requests keep their original Combined setup. |

| Effort anchor | Selected helper copy |
| --- | --- |
| Quick | Use compact drafts and only the checking needed for your request. |
| Efficient | Use targeted checks and useful detail with little extra work. |
| Balanced | Balance research, response detail, and resource use. |
| Thorough | Allow deeper checks, broader research, and more explanation. |
| Full scale | Continue useful research and detail until the task is complete or an actual limit is reached. |

### Save semantics

The current footer has **Save Changes** and **Cancel**. Models and creativity are staged today, while participant membership, tool, research, and privacy callbacks include immediate writes. The new design must not imply that Cancel reverses every action in the dialog. [R02]

For Combined, maintain one saved-state draft across tabs for the two sliders, participant membership, model choices, and creativity on the first tab. Save validates that draft once. Before Save, Cancel, Back, and outside dismissal discard the draft. During commit, prevent duplicate saves and dismissal until the operation has a known outcome. A reported failure leaves the dialog open with the draft intact and the previous revision authoritative. Cancelling after that failure marks the pending save aborted so recovery cannot later publish the discarded values. Process-death recovery may complete a previously accepted Save that has not been explicitly aborted.

Rotation, recomposition, and tab switching preserve the draft. Opening a different chat loads a different draft keyed by stable conversation identity. Tools and Privacy operate on committed participant identities, with callbacks bound to stable IDs rather than list positions. Newly added or changed participants become configurable there after Save. Immediate changes have their own revisions and must not be overwritten by recovery of the first-tab save.

Keep existing tool and privacy actions in their established immediate flow, including the current Tools-tab reasoning switch, operating-system permissions, and explicit memory actions. Add the plain helper **“Changes on this tab save immediately.”** where applicable. A separately global Local Assist action must identify its immediate scope. Persistence does not retroactively change a dispatched request; revocation blocks subsequent tool starts and resumed work. These actions must not be included in a false rollback promise. [R02]

Update the Combined Tools-tab search helper to **“Enabled connections are available for research. Response effort controls how broadly they are used.”** The current helper promises that web search queries every enabled connection. The new scheduler must honor an explicit request to use all engines, while otherwise selecting useful authorized engines within the work plan. An enabled connection is eligible; it is not a requirement to repeat every optional query everywhere. [R02]

Persist both slider fields together through one DataStore update. Saving participant and model changes may also touch Room; implement a durable save-operation journal and recovery before presenting the combined operation as all-or-nothing. Use explicit draft, validating, committing, committed, failed, and aborted states. All affected readers use a committed-revision facade, and submission is blocked only while that save is committing. Publish the new revision after every required write succeeds. Retry or recover an interrupted save without overwriting unrelated concurrent changes; raw Room or DataStore reads must not expose a half-committed revision.

### Accessibility and visual treatment

Use a discrete Material slider with `valueRange = 0f..100f` and `steps = 3`; three intermediate stops plus two endpoints make five positions. Snap to the supported values and keep direction consistent in storage, visible labels, keyboard actions, and accessibility. Announce, for example, **“Merge style, Evidence-led, 2 of 5”** and **“Response effort, Balanced, 3 of 5.”** Support one-stop adjustments and at least 48 dp interaction targets.

Allow long endpoint labels to wrap at large font sizes. Verify small screens, landscape, 200 percent font scaling, RTL, TalkBack, keyboard focus, light and dark themes. These are proposed acceptance requirements, not claims about current accessibility conformance.

Retain `FadingAlertDialog`, already imported as `AlertDialog` by `ChatModelDialog`, and its shared `glassBackdrop` and `appOverlayTokens` implementation. The existing material uses a **70 percent theme-tinted fill** and **40 dp backdrop blur** when available. Keep text and controls readable and respect the existing solid-material and blur-Off preferences. Do not add another background wrapper, blur the dialog’s text, or apply a permanent 70 percent alpha to its contents. Preserve popup source filtering and lifecycle cleanup. [R19]

When the backdrop host is absent or inactive, use the existing themed fallback. Verify the blur effect and contrast on supported devices instead of promising identical rendering on every backend. The visual treatment remains independent of the two persisted policy values.

## 4 Policy resolution and precedence

### Separate the policy objects

Implement a central `CombinedPolicyResolver` as a proposed component. It produces an explicit work plan from typed inputs. Do not scatter slider thresholds across provider adapters or merely inject an extra sentence into the synthesis prompt.

| Proposed input | Responsibility |
| --- | --- |
| UserGoalConstraints | Requested topics, language, sources, length, format, deadlines, and explicit no-search instructions |
| CombinedRequestedPolicy | Saved slider values, selected participant identities, model choices, and settings version |
| CapabilityPolicy | Effective tool grants, profile eligibility, data boundaries, and required action authorization |
| ProviderDeviceCaps | Context and generation capacity, rate limits, availability, memory, and actual configured profile limits |
| UserHardLimits | Explicit token, spending, time, or tool ceilings where configured |
| CombinedWorkPlan | Protected baseline, optional allocations, scheduling, renewal, and finalization reserve |
| EvidencePolicy | Fixed grounding rules plus the selected breadth priority |

Apply authorization and actual hard constraints first. Within those bounds, the user’s explicit task and answer contract take precedence over soft effort defaults. Preserve selected-participant opportunities and finalization capacity. Use the sliders to allocate optional work only after those requirements are represented. If the task is impossible within a hard constraint, keep a concrete partial result and identify the exact unresolved part and limiting condition.

Merge style may redistribute attention between adjudication and broad coverage inside a work plan. It cannot independently enlarge the resource allowance, change tools, add profiles, or lower evidence requirements. Response effort can enlarge optional work but cannot transform uncertain information into verified facts.

### Requested settings and effective execution

At submission or enqueue, freeze the user’s intent, both slider values, selected participant identities and requested models, explicit task constraints, and settings-policy version. Capture an initial capability view and budget intent for audit. Store a `requestedPolicyDigest` separately from the eventual `effectivePlanDigest`.

At dispatch, revalidate availability, permissions, provider capacity, and explicit limits. Persist the effective plan before work starts. Queue delay may reduce what is available; a frozen intent does not authorize the use of a revoked tool. Dispatch must not silently substitute an unselected remote model or broaden the original grants. Record each material reduction and its reason.

All contributors, research workers, validators, synthesis, repair, continuation, and recovery consume the same accepted intent and shared root budget. Later slider edits affect later requests. Automatic retries reuse the remaining budget. A deliberate new regeneration is a new run with current settings and a link to the prior run; it must be distinct from an automatic retry in diagnostics and user behavior.

### Delegation removal must reach execution

Current Combined settings inherit delegation when the conversation override is null. Deleting a card or clearing the stored override can therefore restore global defaults. Add a mode-and-run-role rule at the runtime resolution boundary: **Combined roles do not execute automatic delegation from either global inheritance or legacy per-chat overrides.** Apply it in both ViewModel dispatch and repository resolution, with one authoritative resolver to prevent divergence. Explicit Combined participants and separately authorized research or Local Assist retain their own paths. [R04] [R07]

Explicit selected-model fan-out remains the Combined orchestration mechanism. Direct permitted tools, separately authorized Local Assist, and explicitly configured research preparation retain their own policy paths. Neither slider enables them. Research settings currently reuse parts of the delegation model, so preserve their depth and readiness behavior while separating automatic model delegation from research configuration. The final synthesis editor remains tool-free; factual retrieval belongs in the preceding verification phase. [R04] [R08]

Keep legacy per-chat delegation values dormant for Combined rather than destructively deleting unrelated configuration. Mode changes may restore the appropriate behavior elsewhere. Old backups must not reactivate hidden Combined routing. New Combined dialogs expose no Delegation mode, including creation, restored-chat, and retry entry points.

## 5 Resource planning and token optimization

### Reserve the required baseline first

Before optional research, reserve one fair initial opportunity for every selected available contributor, essential user and history context, necessary evidence work, a coherent final, and appropriate validation or repair. The baseline is task-derived and fully counted in usage; it is not free. Limit each initial draft to the claim detail needed for its assigned scope and capability, preserving material qualifications.

The controller seals this baseline from the user’s requested work before optional execution. A worker cannot bypass Quick by repeatedly labelling extra searches essential. Expansion needs an auditable reason such as an explicit requested source count or a newly discovered material contradiction; unrelated exploratory work remains optional. Reserve concrete next batches within the plan rather than reserving the entire Full scale envelope as one provider request.

If an explicit hard ceiling cannot fund the baseline, never overrun it merely to provide every participant an opportunity. Protect the feasible finalization reserve and mark unstarted participants as budget-blocked, distinct from failed or considered contributors. Report the resulting limited scope accurately. A tool or monetary hard cap also takes precedence over the slider’s preferred work level.

Quick controls optional work. It cannot guarantee that a large explicit task with five participants uses few total tokens. If a user asks for many required source reads or a long output, show a concise planning notice when helpful, such as **“Your requested source review needs more work than the Quick default.”** Continue the authorized task without treating a soft preference as an additional permission gate.

The following are **proposed initial configuration values for discretionary work beyond that baseline**. They are shared across the entire Combined turn, not multiplied by the number of models. Ranges describe planning allowances for a substantive open-ended task; they are neither minimum consumption targets nor universal answer requirements. Calibrate them against replay data before release.

| Effort | Optional tool operations | Additional input tokens | Additional generated tokens |
| --- | --- | --- | --- |
| Quick | Up to 2 | 8 thousand | 2 thousand |
| Efficient | Up to 6 | 32 thousand | 8 thousand |
| Balanced | Up to 20 | 128 thousand | 32 thousand |
| Thorough | Up to 80 | 512 thousand | 128 thousand |
| Full scale | Up to 300 in first tranche | 2 million in first tranche | 512 thousand in first tranche |

The allocations at positions 0–75 bound optional work. Required user scope can require a documented expansion. At Full scale, the initial tranche renews automatically while useful in-scope work remains. Do not turn 300 calls or a token value in this table into a hidden maximum for the complete task. Conversely, reaching the next anchor is not a reason for Quick to keep doing merely optional work.

| Effort | Default visible answer guidance | Checking and refinement behavior |
| --- | --- | --- |
| Quick | Up to about 700 visible tokens | One synthesis and necessary correction |
| Efficient | Up to about 1400 visible tokens | One targeted optional check when useful |
| Balanced | Up to about 2500 visible tokens | Selective conflict checks and revision |
| Thorough | Up to about 5000 visible tokens | Wider checking and evidence-directed revision |
| Full scale | Up to about 12000 visible tokens initially | Further sections or revisions as required by the task |

These visible-token values guide ordinary open-ended answers. They are overridden by explicit length and format, actual usefulness, and provider capabilities. A simple answer can be much shorter at any setting. A requested large report can exceed the Full scale starting value through coherent continuations. Do not map provider reasoning budgets directly to visible prose length.

Final visible output is part of the protected finalization baseline. Additional generated tokens in the first table cover optional reasoning, intermediate outputs, and metadata across all attempts; they are not a second allowance for the same final text. One provider’s generation limit must never be set to the aggregate multi-invocation figure.

### Shared accounting and capacity

Create one atomic root budget per Combined turn. Child reservations belong to contributor, research, verifier, synthesis, repair, and continuation roles. Reserve estimated or requested usage before dispatch, settle against reported usage, release unused reservations, and retain uncertainty for attempts whose completion or billing is unknown. Retry counters and tranche renewal must not reset cumulative consumption.

Track total input, cached input, generated output, visible output, and reasoning output separately when the provider exposes them. Count every physical attempt once using an immutable attempt ID. Include failed calls and retries according to actual reported or estimated usage. Keep measured and estimated totals distinguishable. Unknown monetary cost remains unknown; do not render it as zero.

When a hard monetary ceiling is configured, admit a paid call only with a reliable conservative upper cost bound for its requested maximum work or an already authorized route with a known bounded cost. Otherwise state the constraint instead of claiming the cap is guaranteed. An unknown-completion attempt retains its outstanding reservation until reconciliation or a conservative settlement rule covers it; uncertainty must not release capacity for another concurrent child to spend.

Per-invocation context admission is a different calculation from whole-turn cost. For invocation i, require **compiled prompt tokens plus provider generation reserve plus accounting headroom to fit that invocation’s actual context capacity**. The compiled prompt includes user and system instructions, relevant history, evidence excerpts, tool schemas, attachments, and serialization. Five prompts must not be summed against one model’s context window, and one final editor’s usage must not be displayed as the whole Combined bill.

An exposed tool schema is not an executed operation. Separate `maxTools` from `maxToolCalls`. A batch of ten source reads consumes ten work units even if it uses one RPC, and query batches count their component queries. Cached content reduces network work but still has replay-token cost. Acquire shared evidence once where permissions and freshness permit; do not make every profile repeat the same searches.

### Full scale renewal and stopping

At planning, establish a bounded topic inventory and completion criteria derived from the request. For open wording such as “all benefits” or “maximum detail,” organize finite in-scope questions and evidence needs. New material may refine an item or reveal a consequential omission; novelty alone must not perpetually enlarge the task.

At a tranche boundary, persist progress and renew automatically if that inventory still has unresolved work, there is useful progress or a necessary stage still awaiting its opportunity, and the next bounded work queue is concrete. Maintain the original permissions, task scope, cumulative accounting, and explicit hard limits. No routine Continue or approval prompt is required to perform work already requested.

Useful progress includes inspecting a required source, resolving a contradiction, finding a null result, completing a requested subtopic, or adding an independent relevant evidence cluster. More prose, duplicated URLs, and repeated empty queries are not progress. Start with a configurable no-progress checkpoint after three diverse completed acquisition batches; it triggers replanning and stopping when there is no productive next step, not abandonment of pending required reads or finalization.

Stop at completed scope, cancellation, an actual blocking hard constraint, or persistent lack of a useful next action. Preserve and deliver the best supported result with unresolved work accurately stated. Respect provider rate limits and bounded backoff. A circuit breaker for an unhealthy search service is distinct from an arbitrary cap on the user’s whole task.

### Replace hidden application limits

The current repository clamps tools to 24 or 64 in request preparation and then clamps again to 64 when constructing the execution budget. It also contains runner defaults, context-retention bounds, output reserves, and a continuation-count clamp that can limit a high-effort plan. Replace application-derived Combined limits with the central resolved policy, and distinguish them from explicit profile, provider, device, and user constraints. [R05] [R06]

Audit observed-call limits, per-round limits, retained evidence, replay tokens, timeouts, no-progress rules, and maximum continuations. Each effective reduction needs a reason and origin in diagnostics. Support a legitimate zero-call plan without coercing it to one executable call. Preserve physical capacity and security checks; removing an application clamp does not increase a provider’s context window or authorize disabled tools.

Record cap origin explicitly: user-set, explicitly configured profile, provider or device capacity, application default, or legacy unknown. Application defaults belong to the effort resolver and must not masquerade as user hard ceilings. Where an older stored value has ambiguous origin, retain and expose that ambiguity through a documented migration policy rather than silently declaring it unlimited. Give each cap a scope, such as invocation, worker run, or whole Combined turn, so renewal cannot accidentally evade an explicit limit.

## 6 Data model and conversation lifecycle

### Proposed persisted preferences

Add a dedicated serializable model under `data/model` and a per-chat map in `AppFeatureSettings`. Do not reuse `combinedComprehensive`, which the current source explicitly labels legacy. The existing JSON DataStore preference supports an atomic mutation pattern suitable for the two values. This preference map alone requires no Room column. Save journaling, stable identity, or portable run records may require their own database migration. [R03]

```kotlin
@Serializable
data class ConversationCombinedSettings(
    val schemaVersion: Int = 1,
    val collectionBias: Int = 25,
    val effort: Int = 50
)

// Proposed addition to AppFeatureSettings
val conversationCombined:
    Map<Int, ConversationCombinedSettings> = emptyMap()
```

Normalize supported values to the nearest stop after clamping to 0–100, with ties going to the lower stop. Missing fields use documented defaults. Run normalization idempotently on load, save, and supported legacy import. Unknown future schema versions must remain distinguishable from corruption; preserve their raw payload, disable incompatible edits, and avoid silently overwriting them with defaults.

The current settings implementation decodes and re-encodes the whole typed JSON blob with unknown keys ignored. An unrelated feature mutation can therefore erase future nested fields. Add an envelope-preserving `JsonElement` merge or equivalent preservation path across every mutation and import. If preservation cannot be guaranteed, prevent destructive writes to the affected payload. Disabling only the Combined sliders is insufficient. [R03]

Use field-level updates inside `mutateFeatureSettings` rather than replacing a stale whole settings object. Compose the new normalizer with the existing Local Assist normalization. Store a settings revision for concurrency and the save journal. A temporary new-chat ID must migrate its settings to the real chat identity once, without copying another unsaved draft’s values. [R03] [R07]

### Snapshot and accounting records

Extend the current synthesis snapshot write path from version 4 to a proposed version 5. Include the requested policy, accepted settings revision, selected participant identities, effective plan, explicit answer contract, root budget ID, evidence and claim inventory versions, and requested/effective digests. The snapshot also identifies any continuation prefix and its owning answer revision. [R07] [R08]

Carry the same context into earlier contributor and verifier dispatch; recording it only when synthesis starts is too late. Existing version 4 snapshots remain replayable under an explicit legacy policy version and captured inputs. An automatic retry of an older run must not silently use today’s slider settings or claim that the new checks were performed.

### Lifecycle requirements

| Event | Required behavior |
| --- | --- |
| New Combined chat | Use Evidence-led and Balanced unless a valid explicit creation preset supplies values |
| Open or rotate dialog | Reconstruct the committed settings and preserve any active chat-specific draft |
| Save during a run | Commit for new requests while preserving accepted running and queued intent |
| Branch or duplicate | Copy valid Combined preferences to the new identity and retain appropriate history lineage |
| Switch chat mode | Preserve dormant Combined preferences and apply the selected mode’s routing rules |
| Delete conversation | Remove its preference entry and associated portable settings or journal records |
| Automatic retry or resume | Reuse accepted intent and remaining root budget with live revocation checks |
| New regeneration | Create a new run and policy snapshot linked to the prior answer |

### Backup and restore

The current complete SETTINGS backup serializes DataStore preferences, but CONVERSATIONS-only backup does not include arbitrary feature preferences. Adding a map therefore does not by itself make the sliders travel with a conversation. Legacy configuration export also excludes this per-chat feature state. [R15]

Add a versioned conversation-owned portable settings payload with stable conversation identity and import ID remapping. Restore chat data and its Combined preferences through an idempotent journal so interrupted import cannot attach half a policy. Duplicate or branch operations generate a new identity while copying the appropriate values. Preserve original run snapshots as historical records rather than rewriting them with imported defaults.

For SETTINGS-only restore, do not apply `chatId = 4` to an unrelated local conversation that happens to have the same integer. Match stable identities or retain unmatched policies as deferred import data. A full backup, a conversations-only backup, and a settings-only restore must have separately tested behavior. Plain text and Markdown exports remain readable conversation exports; do not advertise them as restorable settings packages.

## 7 Orchestration and healthy contributor fallback

### Turn lifecycle

Use explicit persisted states: **Accepted**, **Planning**, **Collecting**, **Verifying**, **Synthesizing**, **Checking**, **Revising**, and a terminal delivery state. Record contributor states independently. Failed, cancelled, interrupted, excluded, and usable partial responses must remain distinguishable. An honest partial final differs from a fully checked completion.

At acceptance, freeze task and participant intent. At planning, resolve effective capabilities and reserve the root budget. During collection, share compatible evidence and track all participant terminal statuses. During verification, resolve consequential factual conflicts against inspected sources. During synthesis, render the selected claim plan with provenance. During checking, enforce the answer contract and claim dispositions. Repair creates a new revision, and terminal delivery commits that revision and its linked records together.

State transitions must be idempotent across process death and duplicate callbacks. Full cancellation stops all new provider and tool dispatch, including verification, synthesis, repair, continuation, and finalization. Attempt cooperative cancellation of in-flight work, settle known usage, and preserve completed evidence and the last coherent text through local state updates. Do not treat permission revocation as a retriable model failure. A separate stop-research-and-finish action, if later provided, would explicitly permit finalization; it is not implied by Cancel.

### Synthesis owner selection

The current candidate selection requires the admitted contributor UID to match the pinned primary. Replace that restriction with a deterministic eligibility resolver. Prefer the configured primary if healthy and compatible. Otherwise choose a healthy, already selected, authorized participant capable of the synthesis contract, using a stable tie-break recorded in the run. Preserve local-only constraints, excluded-memory rules, and model availability. [R07]

If one healthy candidate remains, deliver a properly labelled single-contributor fallback with its actual provenance and necessary checks. If several remain, synthesize them. If none remain, deliver a precise failure state and preserve any recoverable partial material separately. Do not introduce a new remote model or enable delegation to hide the failure.

For the joke regression, four usable peer drafts must not be lost because the primary spends 131 seconds and terminates at a length limit. Apply existing configured worker deadlines and task-sensitive no-progress detection. Once pending workers reach a legitimate terminal state, select a healthy owner and complete. Quick must not claim that a still-pending participant was considered. Optional progressive display can show usable material earlier with an accurate pending status. [A1]

### Canonical history

Commit fallback results through the same canonical Combined history path as normal finals. Preserve each user turn, whether a combined final was delivered, which candidates were available, and the supporting evidence lineage. A recap must distinguish “a combined joke failed, although peer drafts were available” from “we shared a joke.” [A1] [R16]

Update `CanonicalCombinedHistory` to use the new delivered-scope status separately from verification. It currently marks the prior answer unfinished unless `quality.complete` is true. A successfully delivered answer with disclosed evidentiary limits must not become unfinished merely because semantic or factual verification is incomplete. Preserve genuine partial states and all owner and digest checks. [R16]

Carry corrections and support ancestry through summaries. A statement inherited by five models from one earlier answer remains one ancestry chain. Its repetition is not independent evidence. If an earlier statement is corrected, later summaries use the correction or explicitly preserve the historical distinction when the user is asking what was said at the time.

## 8 Claims evidence and fair merging

### Capture the original contributions

Retain the original primary draft before the visible primary message is replaced by the merged final. Capture immutable contributor text, model identity, status, attempt ID, creation time, and source digest. Use these captured contributions as the input inventory; never treat the evolving synthesis or continuation as a new contributor. The supplied export retains the original material in its combined sources, which is essential for this audit. [A1] [R08]

Rename the current length-derived “quality” measure to **structural usability** or equivalent internal terminology. Its present 100, 85, 70, and 45 scores come from length thresholds after structural checks. They are not evidence of factual quality and must not influence credibility as if they were. Short correct answers to short questions must remain eligible. [R09]

### Structured claim and support model

Create one claim record per distinct proposition with its subject, predicate, object or outcome, time, units, population, intervention route, study type, polarity, causal strength, and uncertainty. Preserve the contributing profile set and precise source spans. Separate semantic identity from raw text similarity so paraphrases can share a record without losing qualifications.

| Proposed record | Required fields or relations |
| --- | --- |
| ClaimRecord | ID, proposition, material qualifiers, scope, candidate spans, originating profiles, ancestry |
| EvidenceRecord | Source identity, title and URL, retrieval depth, excerpt and digest, dates, tool event, permissions |
| WorkIdentity | DOI or equivalent publication identity, canonical URL, mirrors, version and independence group |
| SupportEdge | Claim, evidence excerpt, support or contradiction, applicability, verification method and limitations |
| ClaimDisposition | Included, merged, qualified, disputed, corrected, omitted or unresolved with a truthful reason |
| VerificationReceipt | Claim and final span, evidence references, check method, result, confidence limits, revision digest |

Use retrieval levels such as **TITLE_ONLY**, **SEARCH_SNIPPET**, **READABLE_PASSAGE**, and **FULL_TEXT_AVAILABLE**. Store what was actually received. A title-only discovery cannot establish a study result. A readable passage can support only what it says; full text availability is not automatic verification. Source links and citation IDs must remain separate from support judgments.

The current ledger accepts readable passages and merges URL records, but the inspected path does not treat a snippet as full text and does not establish publication identity across mirrors. Improve snippet preservation without upgrading it. Normalize DOI, PMID, publisher, preprint, and mirror relationships carefully; preserve version differences and do not collapse genuinely independent studies just because titles resemble one another. [R11]

### Grounding and contradiction checks

Verify high-consequence or disputed claims against the exact available passages. Record whether the source supports, contradicts, partially supports, or does not address the proposition. An entailment model can assist, but its judgment is fallible; deterministic entity, numeric, date, route, and outcome checks and retained evidence make the result reviewable. Do not label the whole answer factually verified because a model returned a boolean.

For the hydrogen regression, preserve the studied intervention, population, small sample, and null outcome. Do not combine hydrogen-rich water with inhalation as if they were the same intervention, or treat biomarker changes as proof of clinical benefit. Do not transform mechanistic plausibility into established human efficacy. PubMed, PMC, a publisher page, and a mirrored PDF of the same study form one evidence work, not four independent confirmations. [A1] [E01]

Replace generic number and negation matching with comparisons between aligned claims about the same subject, outcome, time, and units. IL-6 and IL-8 are different entities rather than automatically conflicting numeric values. “Leave beneficial ROS intact” and “without disrupting beneficial ROS” can agree despite different surface polarity. Check within a contributor as well as across contributors; the current detector skips same-profile comparisons. [R10]

Conflicts are resolved by support and applicability, not profile count or primary status. Preserve a useful minority warning when it changes the conclusion. If reliable sources remain inconsistent, state the exact unresolved difference. Avoid composing incompatible statements such as a lasting relationship rupture and an unsupported reconciliation into one apparently coherent narrative.

### Claim selection and fair attribution

Fairness means every usable relevant contribution is considered under the same criteria, followed by defensible selection. It does not require equal words, colors, citations, or included claims from each model. A sole strong counterexample may deserve more attention than four redundant assertions. A short explicit request can justify excluding good but out-of-scope alternatives.

Record all contributing profiles for a shared claim and separately identify the external evidence that supports it. Credit important qualifications as contributions even when the main proposition came from another model. Profile colors remain presentation aids. They must not be used as an accuracy measure or as a quota to satisfy.

For every `MERGED` decision, require the final text to contain an identified span preserving the proposition and its material qualifiers. “Mentioned in the closing caveat” is insufficient if that caveat is absent. Use **OMITTED_SCOPE**, **OMITTED_DUPLICATE**, **OMITTED_UNSUPPORTED**, or another truthful reason where appropriate. The current decoder validates identifiers and a nonblank reason but does not establish this semantic relation. [R12]

Build a request-to-claims coverage matrix before synthesis and validate it afterward. Coverage concerns required topics and useful admissible claims, not every sentence written by every model. Save unresolved or rejected decisions in the debug record. Keep sensitive rejected allegations out of the user-facing collection unless the task specifically requires a carefully attributed analysis of the allegation itself.

## 9 Final answer validation and revision ownership

### Enforce the answer contract

Compile the current user’s required count, format, language, topic set, and singular or comparative intent into an explicit answer contract. Distinguish “exactly 500 words” from “about 500 words.” Use one versioned word-count method for planning, validation, diagnostics, and display. State how contractions, hyphenated words, headings, citations, and code blocks are counted; avoid unexplained 564 versus 565 results across different reports.

For an exact word request, validate the visible prose using the documented counting rule before final commit. For approximate wording, use a documented tolerance appropriate to the request. Keep citation markers and hidden metadata out of the visible prose count, while counting visible substantive headings consistently. Bullets must be actual list items. A request for one event must not turn into a collection of unrelated events.

Validate source resolution, claim support, material qualifiers, unresolved contradictions, and truthful dispositions separately from formatting. A matching answer digest establishes that the report belongs to the text; it does not establish that the text is correct. Preserve check status and limitations rather than collapsing all checks into a misleading quality number.

### Repair is replacement when replacement is intended

Introduce a dedicated `REPLACE_DRAFT` request builder. Give it the complete candidate draft, frozen answer contract, failed checks, relevant evidence, and explicit replacement instructions. Do not use `appendFollowUpContext`, whose continuation framing conflicts with replacing an answer. Keep legitimate continuation of truncated output as a separate operation. [R14]

Assign every revised answer a new revision owner and digest. Validate the revised text and its linked citations, claim decisions, attribution, and reports against that revision. Commit them atomically with a compare-and-set guard on the previous owner. A stale repair must not overwrite a newer completed answer. If rejected, retain the earlier text for history and recovery and record the exact failing check and reason.

When checking establishes a material error, immediately invalidate the affected current assessment and mark the visible answer as requiring correction or partial with a concise explanation. A failed replacement must not restore an old completed or checked presentation for a known-wrong answer. A purely stylistic repair can retain a previously valid result without that degradation.

If a checker needs new evidence, use an explicit loop from checking to targeted authorized verification, an updated claim plan, replacement synthesis, and checking again. Submit a bounded evidence request to the verifier, charge the same root budget, and preserve the tool-free editor. If the evidence remains unavailable, qualify or omit the claim and state any material remaining limitation; never invent a supporting citation.

Progressive delivery can remain responsive, but changing already committed content requires a new revision owner. Material late evidence invalidates affected support checks and triggers a new revision when within the task and budget. The current progressive revision restriction must become a supported transition rather than a permanent reason to skip repair. [R13]

Reserve correction and finalization capacity before optional acquisition. At lower effort, use the reserve for required format or material factual repairs and stop discretionary rewrites. At Full scale, additional useful revisions can use a renewed tranche; repeated rewrites that make no progress trigger the same stopping logic as research. Avoid fixed tiny repair limits that defeat the explicit task, and avoid endless revision loops.

### Delivery statuses

Use distinct terminal outcomes for **completed**, **completed with stated limits**, **partial**, **failed**, and **cancelled**. Preserve verification detail separately. An answer can be successfully delivered while some claims remain unverified, and a failed primary can coexist with a useful fallback. User-facing status should explain the actual shortfall without exposing a dense internal report in ordinary chat.

## 10 Efficiency observability and scoring

### Reduce duplicated work without removing important evidence

The audit recorded large repeated prompt inputs and synthesis envelopes several times larger than the raw source bodies. It did not establish context truncation as the cause of the observed semantic losses. Optimize duplicated instructions, repeated source drafts, schemas, and historical payloads before reducing actual evidence excerpts or important qualifiers. [A1] [R08]

Use one shared claim inventory and evidence store referenced by stable IDs. Give the editor the necessary excerpts and decisions, with expansion available in the verification phase. Do not send both full repeated drafts and a near-duplicate claim inventory by default. Cache safe reusable retrieval by canonical source identity, freshness, query scope, and permissions. Cache hits are recorded and still incur relevant context cost.

Classify tool failures: unavailable credentials, explicit denial, rate limits, challenge pages, HTTP errors, malformed results, and transient network problems require different recovery. Avoid repeatedly retrying an unauthorized or disabled service. Choose another already authorized provider where useful, or report the evidence limitation. Bound retries and preserve their actual status. The empty event journal in the supplied export limits reconstruction; future exports should retain enough event detail to explain such decisions. [A1]

### Debug export additions

| Area | Required recorded data |
| --- | --- |
| Policy | Requested slider values, versions, enqueue time, accepted roster, digests, effective restrictions and reasons |
| Work | Root budget, tranches, reservations, attempts, actual and estimated tokens, tools, cache hits, unknown cost |
| Contributors | Original captured drafts, structural status, failures, exclusions, model identities and provenance |
| Evidence | Retrieval level, excerpts, digests, canonical work identity, source ancestry, support and contradiction |
| Decisions | Claim inventory, selected and omitted claims, final spans, qualifications, conflict resolutions |
| Delivery | Revision owners, continuation links, checks, exact repair rejections, terminal outcome and partial limits |

Retain privacy protections and export consent already applicable to the conversation. Redact credentials and unrelated sensitive data. Record sufficient evidence excerpts or immutable retained references to reproduce a support judgment; a URL alone can change or disappear. Each exported verification receipt must identify the answer revision it evaluates. Normal chat remains readable, with richer details in its existing diagnostic experience.

### Proposed evaluation rubric

Use the following 100-point rubric for future independent human or carefully calibrated evaluation. Do not retroactively imply that the earlier 58 score was computed with these weights. Publish category results with the overall score and report delivery, latency, token usage, and cost separately.

| Category | Weight | What earns credit |
| --- | --- | --- |
| Factual grounding and citation support | 25 | Claims match inspected sources and retain the correct applicability |
| Relevant semantic coverage | 20 | Required topics and useful distinct contributions survive selection |
| Uncertainty and qualification fidelity | 15 | Material caveats, negative results, and causal limits remain local |
| Conflict handling | 15 | Disagreements are resolved or accurately exposed without false consensus |
| Fair consideration and provenance | 10 | Selection is defensible and origins are accurate without model quotas |
| Request compliance | 10 | Scope, count, format, language, and requested singular choices are satisfied |
| Coherence and usability | 5 | The answer is organized, clear, and useful without avoidable repetition |

A known citation reversal, unsupported consequential allegation presented as fact, or fabricated verification status is a release-blocking defect even if other categories produce a high average. Judge factual correctness against independently established ground truth at the fixture’s specified date. Judge evidence-use behavior and achievable coverage against the information and tools available to that run. Missing evidence can justify an honest limitation, not a confident false assertion.

Mark rubric dimensions not applicable for jokes or other nonfactual tasks; publish the applicable denominator and compare within task categories before aggregation. Do not award automatic factuality points or require irrelevant citations for a joke. Use a held-out set, blinded answer order, a written rubric, and adjudication of material reviewer disagreements. Treat automatic judging as an aid and retain human review for consequential errors.

Compare the merged answer to each source draft and the best individual candidate on the same request. Report how often merging improves, preserves, or worsens correctness and useful coverage. Also measure selected-contributor consideration, task completion, p50 and p95 latency, total usage, duplicate retrieval rate, and failed-tool work. Do not use lexical match rate as semantic coverage or token growth as a proxy for quality.

## 11 Repository implementation map

### UI and preferences

**Existing integration points.** Update `ChatDialogs.kt` and `ChatScreen.kt` to pass conversation mode, render Combined controls, remove the Combined Delegation card, and route a validated draft save. Update `ChatViewModel.kt` for draft lifecycle, save journaling, new-chat ID adoption, and accepted request snapshots. [R02] [R07]

**Proposed additions.** Add `data/model/ConversationCombinedSettings.kt`, `presentation/ui/chat/CombinedSettingsControls.kt`, and a typed `CombinedChatSettingsDraft`. Extend `AppFeatureSettings`, the existing settings repository, and DataStore mutation path. Keep serialization defaults and normalization centralized. Do not create a second preference store for the same values. [R03]

**Completion evidence.** Both values survive restart and rotation; first-tab Cancel discards drafts; failed saves preserve edits; unrelated settings are not overwritten; Combined has no Delegation entry; other modes retain their appropriate controls.

### Resolver and resource ownership

**Existing integration points.** Extend the request carrier represented by `ChatMcpToolConfig` with a resolved Combined context or companion object rather than conflating it with tool grants. Replace the Combined application clamps in `ChatRepositoryImpl`, integrate with `AgentRunLimits`, `ToolBudgetPolicy`, `RequestConstraints`, `ProviderOutputLimits`, and each supported provider adapter. [R04] [R05] [R06]

**Proposed additions.** Add `data/agent/CombinedPolicyResolver.kt`, `CombinedWorkPlan`, and `CombinedTurnBudget` with atomic reservations. Reuse the existing turn identity and invocation accounting rather than creating a competing cost ledger. Introduce mode-and-role-aware automatic delegation suppression at the shared resolution point.

**Completion evidence.** All roles charge one turn budget; lower optional allocations cannot starve finalization; Full scale can cross the initial tranche while respecting actual profile limits; zero-tool plans execute no tools; reasoning and visible length are independently handled.

### Collection and synthesis ownership

**Existing integration points.** Update `CombinedSynthesisInputs`, `startCombinedSynthesis` in `ChatViewModel`, `CombinedInputSnapshot`, and `CanonicalCombinedHistory`. Keep original source captures immutable and carry the new accepted policy before collection begins. [R07] [R08] [R16]

**Proposed additions.** Add a deterministic eligible-owner resolver and versioned accepted-turn context. Persist fallback ownership, partial statuses, and budget linkage. Reuse the existing local-only and profile eligibility checks.

**Completion evidence.** A failed primary with healthy eligible peers still yields an appropriate final; no unselected provider receives data; fallback is visible in canonical history and later recap; automatic resume preserves the accepted task policy.

### Evidence and merge validation

**Existing integration points.** Extend `CombinedEvidenceLedger`, `CombinedConflictDetector`, `CombinedPassageDelivery`, `CombinedProvenanceValidator`, `CombinedSynthesisPrompt`, and the length-based `CombinedContributionQuality` component. Avoid adding a parallel claims system that leaves the existing delivery validator unenforced. [R08] [R09] [R10] [R11] [R12]

**Proposed additions.** Add canonical work identity, typed claim qualifiers, support edges, exact final-span dispositions, and versioned verification receipts. Make the synthesis prompt consume the effective selection plan; retain its existing useful instructions against primary bias and treating model agreement as proof.

**Completion evidence.** The Parkinson null result stays null; a title-only citation cannot substantiate an outcome; useful minority uncertainty survives; same-source contradictions are detected; unsupported inherited claims are not promoted by repetition; every MERGED decision has a valid semantic destination.

### Delivery and repair

**Existing integration points.** Update `CombinedDeliverySession`, `CombinedResponseBudget`, repository repair request construction, and `FollowUpContext` usage. Preserve legitimate continuation behavior while replacing the incorrect repair wrapper. [R06] [R13] [R14]

**Proposed additions.** Add a dedicated replacement context builder, answer-contract validator, and revision-owner transaction. Bind text, citations, attribution, quality reports, and canonical commit to one digest and version.

**Completion evidence.** Exact length and list requirements pass; progressive revisions can replace a prior draft safely; rejected repairs retain precise reasons; stale callbacks cannot overwrite a later answer; large authorized output can continue coherently without fixed small application segment caps.

### Portability and observability

**Existing integration points.** Extend `CompleteBackupPreferences`, `CompleteBackupManager`, branch and duplicate paths in `ChatRepositoryImpl`, and the ViewModel debug export. Add journaled preference cleanup to `data/privacy/ConversationDeletion.kt` and the repository’s direct-delete fallback; current deletion routing does not itself clean the feature map. The legacy configuration DTO has different scope and must not be silently treated as a conversation backup. [R15] [R17]

**Proposed additions.** Add a versioned portable conversation settings record, stable identity mapping, restore/save journal, and policy/verification export fields. Reuse existing storage transaction and backup patterns where applicable; specify and test any required database migration separately from the JSON preference addition.

**Completion evidence.** Full and conversations-only restore preserve the sliders; settings-only import cannot attach them to the wrong chat; branch and duplicate inherit values; delete removes owned records; diagnostics reproduce actual policy and claim decisions without claiming unknown cost is zero.

## 12 Acceptance tests and release gates

### Control and lifecycle coverage

Exercise all **25 combinations** of the five anchors. Property tests should confirm that permissions, selected roster, and evidence requirements remain invariant when only a slider changes. Increasing effort must not reduce the available optional planning allowance, though actual usage may fall when a better answer needs less work. Increasing Collection must not require inclusion of known false or out-of-scope material.

Test the four corners with explicit 500-word, one-joke, one-event, no-search, compare-all, and large-report requests. Test unsupported provider knobs, small local context, revoked credentials, no tools enabled, and an explicit user spending or time cap. The effective plan must state concrete restrictions rather than silently changing scope.

Test Save, Cancel, Back, outside dismissal, tab changes, rotation, process death during save, failed-save abort, stale concurrent updates, draft-only participants on immediate tabs, invalid restore values, future schema preservation during unrelated feature mutation, new-chat ID adoption, mode switches, branch, duplicate, deletion, and every supported backup category. A queued request keeps accepted intent; a reduced permission takes effect at dispatch; automatic retries retain cumulative usage. Test both global-on delegation with no override and a restored explicit-on override in Combined.

### Replays from the audited export

| Fixture | Required regression result |
| --- | --- |
| France history | Enforce the requested length and keep justified date corrections and useful chronology |
| Joke | Deliver from healthy eligible peers after primary failure and preserve actual participant statuses |
| Portuguese event | Honor the request for one event without treating unused alternatives as unfair omission |
| Death confirmation | Prefer stronger evidence over stale model answers and avoid promoting unsupported allegations |
| Death details | Preserve source support and causal limits across a follow-up turn |
| Bubba response | Resolve or qualify the relationship contradiction instead of inventing reconciliation |
| Hydrogen inhalation | Preserve route, population, null findings, uncertainty, and publication identity |
| Conversation recap | Cover all prior categories while preserving failure status and corrected claim ancestry |

Keep the original candidates, failures, tool outcomes, and source passages as versioned fixtures. A replay with a corrected evidence payload is a separate fixture from a replay of what the original merger actually received. Do not silently give a new system additional evidence and attribute the entire gain to synthesis. Scrub credentials and sensitive incidental metadata from committed fixtures.

### Runtime and semantic gates

Require tests for atomic budget reservation under concurrent contributors, retry and batch accounting, unknown-completion settlement, unknown-price monetary limits, protected finalization, cache costs, zero-tool plans, Full scale renewal, finite-scope stopping, cancellation, and partial delivery. Include cancellation immediately before required finalizer dispatch and a hard cap unable to fund all five initial contributors. Check provider reasoning semantics and context admission independently from whole-turn usage. Verify no repeated application Continue gate within the same authorized task.

Require deterministic checks for word and list contracts, reference resolution, revision digests, and disposition-schema consistency. Add semantic fixtures for preserved qualifiers, unsupported support edges, same-profile conflicts, numeric entities, duplicate publications, shared-history ancestry, failed repair of a known-wrong answer, and checker-requested verification. A delivered but unverified result must remain delivered in recap; a genuinely partial result must remain partial. Use blinded human adjudication on consequential factual examples; do not make an unvalidated model judge the sole release gate.

Test source-order permutations and primary-profile swaps. The outcome need not be text-identical, but supported conclusions, critical caveats, and justified exclusions should remain stable. Test model-count changes without hidden multiplication of optional budgets. Compare each combined result with the best healthy individual response.

The repository already has Combined synthesis, delivery, evidence, provenance, response-budget, follow-up, delegation, and complete-backup test suites. Extend those relevant suites and add focused policy, lifecycle, and UI tests. The names and paths should be confirmed when the implementation branch is created because this specification is pinned to one revision. [R18]

### Release requirements

Do not release the new sliders as complete until the high endpoint reaches the actual runtime, inherited Combined delegation is suppressed, settings survive the required lifecycle paths, and the critical audit regressions pass. Block on citation reversals, fabricated verification, lost primary-failure fallbacks, or corrupt revision ownership. Report held-out quality and resource results with sample size and limitations; no numerical quality improvement is guaranteed by this design alone.

## 13 Delivery sequence

### Phase 1 Policy foundation and settings

Implement the data model, central resolver, queued intent, dispatch revalidation, Combined-only delegation rule, and validated draft save. Add portable identity and journaling decisions before shipping persistence. Wire the UI to the real policy rather than presenting an inactive high-effort promise. Exit with lifecycle tests and a debug trace proving both sliders affect the intended dimensions.

### Phase 2 Shared resources and dependable delivery

Implement the shared turn budget, remove application-only clamps, protect finalization, add Full scale renewal, and fix healthy-owner selection. Audit provider adapters and hidden continuation limits. Exit with primary-failure recovery, cross-role accounting, zero-tool and hard-cap tests, and large authorized task completion.

### Phase 3 Grounded merging and safe revisions

Implement claim qualifiers, canonical evidence works, support checks, disagreement handling, truthful dispositions, answer-contract validation, and replacement ownership. Exit with all eight replay cases and targeted semantic regressions. Validate complete revision bundles rather than only final text.

### Phase 4 Evaluation and rollout

Complete backup and restore coverage, diagnostic exports, accessibility checks, and a held-out comparison against the current pinned baseline and best individual candidates. Measure both sliders at representative anchors and all invariant combinations. Calibrate the proposed numeric allowances and ranking weights using those results.

Use a versioned rollout capability so older snapshots remain interpretable and incomplete migrations are recoverable. Do not introduce another ordinary chat setting to expose implementation phases. If a rollout is paused, keep saved preferences and historical snapshots intact; do not silently re-enable inherited Combined delegation or rewrite previous verification records.

## 14 Final design review

### Decisions confirmed after independent review

**The axes are coherent.** Merge style governs breadth and selection; Response effort governs discretionary work and default response space. Fixed factuality and qualification rules prevent Collection from becoming an error-acceptance control. Task-derived output validation prevents Full scale from becoming mandatory verbosity.

**The high endpoint must be real.** Both observed tool clamps and other application-derived bounds need the shared policy. Renewable tranches avoid replacing a small hidden cap with a larger hidden cap. Explicit profile, provider, device, and user limits remain enforceable and visible in the effective plan.

**Queue snapshots need two stages.** Accepted intent is immutable, while dispatch must revalidate current capability. Separate digests preserve the original preference and explain later restrictions without treating stale permission as authorization.

**Save behavior must match actual storage.** The sliders save together, but a whole-dialog cross-store transaction requires a journal. Immediate tool and privacy actions need clear local copy. This avoids promising rollback that the current callback architecture does not provide.

**Fairness is consideration with justified selection.** Equal model shares would reward repetition and can bury the most important minority caveat. Claim-level origin and support tracking make fairness reviewable without forcing every source into the visible answer.

**The final review remains empirical.** Repository inspection and independent design review establish a concrete implementation path. They do not prove the proposed weights, allowances, latency, or future quality score. Calibrate those values with the stated fixtures and held-out evaluation before making product claims.

## Source register

### Audit material

[A1] **Supplied Combined chat export and prior manual audit.** `export_Combined Chat_1792336506666.debug.zip`, application version 0.9.53.0, build 122. Reviewed conversation, context manifests, provenance, tool records, original combined sources, and final outputs for eight user turns. The event journal was empty. The export supports observation of this run, not a complete wire capture or a general benchmark. The 58 and 68 scores are manual review judgments.

### Repository sources

All repository references below are pinned to commit `ab2123debe5e3929c9128989eeed7f912eea8ba6`, inspected on 18 October 2026. File paths are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` unless stated otherwise. The source links open the exact revision. These references describe the pre-change baseline. The current implementation map appears in section 16; names in the original contract are conceptual where that map specifies a concrete class.

[R01] **Repository baseline.** Merge of pull request 717 on 18 October 2026 at 14:22:57 UTC. [Repository commit](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/ab2123debe5e3929c9128989eeed7f912eea8ba6)

[R02] **Settings UI and save behavior.** `presentation/ui/chat/ChatDialogs.kt` and `ChatScreen.kt`. The Models tab renders the Delegation card, the footer saves model and creativity changes, and other callbacks include immediate writes. [ChatDialogs.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatDialogs.kt) [ChatScreen.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt)

[R03] **Preference storage.** `data/model/AppFeatureSettings.kt`, `data/datastore/SettingDataSourceImpl.kt`, and `data/repository/SettingRepositoryImpl.kt`. Existing per-chat maps, legacy comprehensive flag, atomic JSON mutation, and settings access. [AppFeatureSettings.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt) [SettingDataSourceImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSourceImpl.kt) [SettingRepositoryImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt)

[R04] **Delegation and request configuration.** `data/model/ChatMcpToolConfig.kt`, `data/delegation/DelegationPolicyResolver.kt`, and `data/model/MultiSearchResearchSelection.kt`. Null override inheritance, tool exposure versus execution fields, and research application. [ChatMcpToolConfig.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/ChatMcpToolConfig.kt) [DelegationPolicyResolver.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/delegation/DelegationPolicyResolver.kt) [MultiSearchResearchSelection.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/MultiSearchResearchSelection.kt)

[R05] **Request path and limits.** `data/repository/ChatRepositoryImpl.kt`, especially request preparation around lines 1403–1445, tool budget construction around 1823–1838, and output constraints around 2467–2579. [ChatRepositoryImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt)

[R06] **Runner and response budgets.** `data/agent/AgentRunner.kt`, `ToolBudgetPolicy.kt`, and `CombinedResponseBudget.kt`. Default execution and retention limits, final reserve, response planning, and continuation limits. [AgentRunner.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunner.kt) [ToolBudgetPolicy.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ToolBudgetPolicy.kt) [CombinedResponseBudget.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedResponseBudget.kt)

[R07] **Conversation lifecycle and synthesis ownership.** `presentation/ui/chat/ChatViewModel.kt`, especially settings mutations, new-chat ID adoption, request dispatch, and `startCombinedSynthesis` around line 2209. [ChatViewModel.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt)

[R08] **Synthesis inputs and prompt.** `presentation/ui/chat/CombinedSynthesisInputs.kt`, `CombinedSynthesisPrompt.kt`, and `data/agent/CombinedInputSnapshot.kt`. Source admission, existing synthesis instructions, and frozen input schema. [CombinedSynthesisInputs.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/CombinedSynthesisInputs.kt) [CombinedSynthesisPrompt.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/CombinedSynthesisPrompt.kt) [CombinedInputSnapshot.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedInputSnapshot.kt)

[R09] **Length-based contribution score.** `presentation/ui/chat/CombinedContributionQuality.kt`. Structural rejection and length threshold scoring. [CombinedContributionQuality.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/CombinedContributionQuality.kt)

[R10] **Conflict detection.** `data/agent/CombinedConflictDetector.kt`. Sentence fingerprints, numeric and polarity hints, and exclusion of same-profile pair comparisons. [CombinedConflictDetector.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedConflictDetector.kt)

[R11] **Evidence retention.** `data/agent/CombinedEvidenceLedger.kt`. Evidence record fields, readable-passage handling, tool extraction, and URL-based merging. [CombinedEvidenceLedger.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedEvidenceLedger.kt)

[R12] **Passage and provenance checks.** `data/agent/CombinedPassageDelivery.kt` and `CombinedProvenanceValidator.kt`. Decision decoding, source resolution, and existing verification limitations. [CombinedPassageDelivery.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedPassageDelivery.kt) [CombinedProvenanceValidator.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedProvenanceValidator.kt)

[R13] **Delivery session.** `data/agent/CombinedDeliverySession.kt`. Progressive delivery, repair eligibility and acceptance, owner restrictions, and quality-report flags. [CombinedDeliverySession.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedDeliverySession.kt)

[R14] **Repair context.** `data/queue/FollowUpContext.kt` and `ChatRepositoryImpl.kt` around lines 2684–2692. Replacement repair currently passes through the continuation context helper. [FollowUpContext.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/FollowUpContext.kt) [Repair call site](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2684)

[R15] **Backup scope.** `data/backup/CompleteBackupPreferences.kt`, `CompleteBackupManager.kt`, and `data/dto/ConfigBackupDto.kt`. Preference capture and normalization, category-scoped backup and restore, and legacy config DTO scope. [CompleteBackupPreferences.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/backup/CompleteBackupPreferences.kt) [CompleteBackupManager.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/backup/CompleteBackupManager.kt) [ConfigBackupDto.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/ConfigBackupDto.kt)

[R16] **Canonical history.** `data/context/CanonicalCombinedHistory.kt`. Canonical owner and digest requirements for delivered Combined history. [CanonicalCombinedHistory.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/CanonicalCombinedHistory.kt)

[R17] **Branch duplicate and delete.** `ChatRepositoryImpl.kt` around lines 3459–3497 and `data/privacy/ConversationDeletion.kt`. Existing chat-copy behavior and deletion routing do not provide the proposed Combined preference cleanup. Debug export is assembled in `ChatViewModel.kt` around line 1468. [Conversation copy paths](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L3459) [ConversationDeletion.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/privacy/ConversationDeletion.kt) [Debug export assembly](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt#L1468)

[R18] **Existing tests.** Repository tree includes CombinedSynthesisPromptTest, CombinedSynthesisInputsTest, CombinedSynthesisGateTest, CombinedResponseStateTest, CombinedDeliverySessionTest, CombinedEvidenceLedgerTest, CombinedProvenanceValidatorTest, CombinedResponseBudgetTest, FollowUpAgentSessionTest, ConversationDelegationSettingsTest, CompleteBackupManagerTest, and ConversationDelegationInstrumentedTest. This review inspected source and test inventory but did not run the suites. [Pinned repository tree](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/ab2123debe5e3929c9128989eeed7f912eea8ba6)

[R19] **Existing glass material.** `presentation/common/FadingPopups.kt`, `GlassBackdrop.kt`, and `AppGlassSurface.kt`. Shared fading dialog, 70 percent tint, 40 dp Haze backdrop effect, themed tokens, popup filtering, and lifecycle-dependent fallback. [FadingPopups.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/FadingPopups.kt) [GlassBackdrop.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/GlassBackdrop.kt) [AppGlassSurface.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/ab2123debe5e3929c9128989eeed7f912eea8ba6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/AppGlassSurface.kt)

### External primary evidence for regression fixtures

[E01] **Parkinson hydrogen inhalation trial.** Primary paper, DOI `10.1007/s10072-021-05489-4`, full text in PMC. The inspected study reported no significant benefit in its small studied sample; preserve that limited null finding rather than inventing a positive result or general proof of no possible effect. [Primary trial full text](https://pmc.ncbi.nlm.nih.gov/articles/PMC8519836/)

[E02] **Official police report used to check the death allegation.** Clearwater Police Department report, 72-page public PDF distributed through WUSF. The inspected autopsy discussion described death from natural disease and no reasonable traumatic or terminal toxicologic contributions. This challenges the unsupported causal allegation in the export; it is not a broader finding about whether any substance was ever present or used. [Official report PDF](https://npr.brightspotcdn.com/1c/1d/f5f28d0940ef8bc28b88820833de/hulk-hogan-police-report-060526.pdf)


## 15 Settings Home, search, motion, and glass

### Information bubble and Home tab

The Settings menu starts with a theme-aware summary card. Its rounded shape, tonal gradient, clear hierarchy, status badges, and generous spacing distinguish the summary from the category controls. The whole card is one accessible navigation target labelled **Configure Home tab**. It opens an internal screen titled **Home tab**, with a live summary preview and the available information toggles. Choices are saved independently of a chat's Combined settings.

The maximum is **five selected items**, enforced by the stored configuration and the UI. Existing selections can always be removed when the maximum is reached. A sixth unchecked item is disabled until another item is deselected. Configuration normalization removes duplicates and empty identifiers and retains selection order. Unknown future identifiers and removed profiles remain visible as unavailable items with a removal toggle, so restoring an older app version does not silently discard a saved choice or trap the user at the limit. Zero selections shows a useful configure prompt rather than an empty colored shape.

| Item count | Normal-width arrangement | Narrow width or large text |
| --- | --- | --- |
| 0 | Configure prompt | Wrapping configure prompt |
| 1 | One full-width tile | One full-width tile |
| 2 | Two equally sized tiles | One column |
| 3 | One full-width tile, then two equal tiles | One column |
| 4 | Two rows of two equal tiles | One column |
| 5 | One full-width tile, then two rows of two | One column |

Text and tile heights adapt to content; there are no fixed text-height assumptions. Icons are accompanied by labels and values. Status uses both words and color. Layout logic is deterministic, supports RTL and theme changes, and keeps the reading order consistent with selection order.

| Information option | Source and display behavior |
| --- | --- |
| OpenRouter Wallet | Account credits minus account usage from the authenticated credits endpoint; balance, account name, and observation time |
| AI profiles | Enabled/disabled counts and a distinct latest-request status |
| Individual selected profile | Green **Enabled** or red **Disabled** indicator, profile/model identity, and separately labelled last-request result and age |
| Recent activity | Recent completed, running, and failed activity from a bounded local run projection |
| Tools | Local configured and enabled tool-connection information; absence of a probe is not reported as connectivity success |
| Privacy | Current privacy settings presented as local configuration, without exposing conversation content |
| Backup | Observed backup status and time, including an explicit unavailable state |

The initial selection is Wallet, AI profiles, and Recent activity. Users may replace these with any supported combination up to five items. Only preference identifiers and display choices are saved in general feature settings; no wallet credential is added to backup or chat exports.

### Wallet and status accuracy

A provider inference key's spending limit is not the account wallet. The wallet uses OpenRouter's `GET /api/v1/credits`, whose documented account fields are `total_credits` and `total_usage` and which requires a management key. The Home screen offers a separate one-read connection flow using the existing account client and endpoint validation. The credential is never stored by this feature. This follows the app's established management-access pattern rather than adding a new long-lived management credential store. [E03]

The display uses decimal arithmetic and allows a genuine negative balance. Missing fields are an error, not a zero. The connection's identity and revision are checked again after the response, so a late response cannot populate a different account. Data is session-only, becomes stale after ten minutes, and clears when the session stops or its account changes. Loading, unavailable, expired access, stale, hidden amount, and disconnected states have separate copy. A read is explicit; opening Settings does not perform repeated paid model requests or health probes.

Profile enablement and model availability answer different questions. A green **Enabled** dot means the app profile is enabled. A separate request badge may be unknown, running, succeeded, failed, or stale, with its observation time. A past successful request is not represented as a current provider health guarantee. The database summary excludes internal work and archived generation records, preventing an archived correction or helper pass from inflating visible activity.

### Search and navigation

The compact bottom navigation row is removed while Settings is the active root, including its Home tab. The Settings toolbar retains its back action and adds a search icon at the top right. Search starts hidden. Pressing the icon reveals and focuses the search field; closing it clears the query and restores the category menu. The field's focused and unfocused outlines use the selected theme color.

Back navigation combines horizontal movement with opacity changes through shared transition functions. The duration is 280 ms for movement and 220 ms for opacity, using eased motion. The NavHost and the internal Settings/Home transition share these functions. Predictive back seeks the transition and restores the current destination when the gesture is cancelled. The shared BackFade helper used by internal screens also slides and fades, avoiding inconsistent pure-fade subpages.

Decorative borders are removed from the shared glass surface. Existing blur, tint, popup filtering, solid fallback, foreground contrast, and lifecycle behavior remain in their established components. Functional control outlines, including the requested search outline, remain part of the control. No additional full-screen glass layer is introduced.

[E03] OpenRouter primary API documentation, checked 18 October 2026: [Get remaining credits](https://openrouter.ai/docs/api/api-reference/credits/get-credits). Account-wide credits require management access; ordinary inference-key limits are distinct.

## 16 Implementation in this branch

The files below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. No new Room columns are required: policy, quality, and revision data extend existing JSON snapshots with defaults. Backup schema version 5 carries conversation Combined settings and stable identities.

| Area | Concrete implementation | Result |
| --- | --- | --- |
| Conversation controls | `ConversationCombinedSettings`, `CombinedChatSettingsDraft`, `CombinedSettingsControls`, `ChatDialogs` | Two five-stop sliders, per-chat staged participant/model/creativity changes, no Combined Delegation card |
| Save/recovery | `CombinedSettingsSaveJournal`, `ConversationCombinedLifecycle`, `SettingDataSourceImpl`, `AgentPersistenceDao.applyCombinedSettings` | Durable commit/abort recovery, committed-reader facade, unrelated privacy and draft fields retained |
| Portable settings | `ConversationCombinedPortable`, `CompleteBackupPreferences`, `CompleteBackupManager`, `ConversationDeletion` | Stable-identity matching, deferred settings-only restore, branch/duplicate inheritance, deletion cleanup, shared mutation barrier |
| Accepted execution | `CombinedAcceptedTurn`, `CombinedPolicyResolver`, `CombinedWorkPlan`, `ChatViewModel`, `DurablePromptQueue` | Accept policy, profile/model/temperature/grant snapshots before dispatch; preserve them for queued work and retries |
| Runtime budgets | `CombinedTurnBudget`, `CombinedTurnBudgetStore`, `CombinedInvocationAccounting`, `InvocationLedger`, `PhysicalAttemptBoundary`, `ToolExecutionBudget`, `CombinedAcquisitionBoundary` | Atomic whole-turn admission, baseline/final reserves, actual physical retries, honest unknown usage and cost, finite renewal, actual tool-work units, inherited accounting for background research |
| Delegation suppression | `DelegationPolicyResolver`, request preparation, platform runner and tool catalog | Combined owns its selected roster; inherited automatic routing and nested ordinary model delegation are disabled while explicitly authorized research remains available |
| Claim/evidence representation | `CombinedClaimInventory`, `CombinedClaimSemantics`, `CombinedTextSpanIndex`, `CombinedEvidenceLedger`, `CombinedEvidenceVerification` | Distinct propositions, source aliases and ancestry, canonical work/version identity, complete source spans without per-word regex recursion, readable excerpts, local qualifiers, explicit support and contradiction checks |
| Delivery checks | `CombinedFinalCheck`, `CombinedAnswerPolicy`, `CombinedWordCounter`, `CombinedDeliverySession` | Request-count/length checks use visible prose; material failures invalidate the current revision and trigger bounded evidence-directed correction |
| Complete replacement | `CombinedReplacement`, `ApiState.CombinedReplacement`, reducer, coordinator, `AgentPersistenceDao.replaceCombinedAnswer` | Entire corrected answer replaces the prior revision with a digest/fence comparison; old text, citations, source contributions, and segments remain archived |
| Large frozen inputs | `CombinedCorpusMultipass`, `CombinedInspectionCheckpoint`, repository multipass session | Modern required inspections use the same root budget, reuse valid completed receipts, preserve exact source spans and replacement contracts, and retain actual context limits |
| Owner and history | `CombinedOwnerResolver`, `CombinedRecoverySnapshot`, `CombinedTurnHistory`, `CanonicalCombinedHistory` | Healthy accepted-profile fallback, explicit no-owner result, canonical delivered history, unverified candidate labels and inherited ancestry |
| Retry and regenerate | `ChatViewModel.retryChat`, `retryFrozenCombined`, atomic `regenerateCombinedTurn`, `CombinedLiveRevision` | Failed retries use original accepted input and a new output revision; explicit regeneration accepts current settings and reruns the entire selected roster |
| Visible status and provenance | `CombinedQualityInspector`, `CombinedDeliveryStatus`, `CombinedResponseState`, revision source helpers | Delivery status is independent from factual checking; archived answers use their own contribution snapshots; missing old provenance is labelled unavailable |
| Debug export | `ChatDebugExport`, repository debug records | Requested/effective digests, root accounting, actual attempts, failed tools, captured sources, evidence checks, final spans, revisions, limitations, and per-revision attribution |
| Settings Home | `SettingsHomeConfiguration`, `SettingsHomeWallet`, `SettingsHomeViewModel`, `SettingsHomeScreen`, `SettingScreen` | Adaptive persistent summary choices, authentic wallet values and status, max-five enforcement, hidden themed search |
| Motion and glass | `NavigationMotion`, `NavigationGraph`, `BackFade`, `AppNavigationShell`, `AppGlassSurface` | Shared slide/fade navigation and border-free glass backgrounds |

### Ownership and recovery decisions

Every accepted turn has one root budget and immutable requested-policy and effective-plan digests. A continuation, verification call, corpus inspection, and repair reserve against that same root. Paid physical attempts require a reliable upper price bound when a monetary limit is enforced. Unknown completion retains capacity instead of silently making it available for another worker. Debug-off and private operation still perform admission; private budgets do not persist retained conversation data to disk.

Explicit source requirements create finite required work alongside the optional allowance. The accepted inventory assigns that acquisition and its bounded provider replays once to an eligible owner. Adding contributors does not multiply this allocation, and discovering another URL does not create a new task. Background article examination carries the same accepted context in its checkpoint and coroutine scope; native academic requests and gateway reads pass through shared acquisition admission. Cancellation and closed-root checks apply even after the original tool call returns.

A completed provider stream and a delivered answer are separate facts. A correction-required or partial Combined result remains a partial publication even if its provider emitted a normal completion event. Canonical history and the visible status follow that delivery assessment. Completion and evidence checking never become an automatic factual-accuracy certificate.

An explicit regeneration archives all old selected slots and removed participants in one Room transaction before creating the new contributor runs. Failure of any insert rolls back the entire operation. The new accepted record links to its parent root for diagnostics. A failed or interrupted merge retry keeps the accepted corpus and original limits; it gets a distinct output revision identity. Cancellation is terminal for its root, and the explicit regeneration action accepts new work.

A failed or interrupted merge may reuse public verification reads from validated retry ancestors. The overlay checks immutable corpus and snapshot hashes, the same accepted root and plan, current owner/fence and profile/model/provider, retained verifier invocation identity and timing, exact native tool identity, and current access. Private, connected, local, credential-bearing, revoked, or ambiguously linked evidence is excluded. Frozen snapshots are not rewritten, and previous answer verdicts are never imported: the new revision must establish its own checks against the retained passages.

A correction handoff drains previous checkpoints, compares the prior answer digest and owner fence, archives the prior revision and answer segments, increments the fence, and publishes a replacement with matching quality metadata. Rejected or stale callbacks cannot overwrite the current revision. New archived snapshots carry their own `combinedSources`; a legacy revision with no saved sources does not borrow sources from today's answer.

Large-input inspection and replacement share the same mechanism. Source inspection is finite and exact, uses no tools, records actual provider usage, and reuses only complete, integrity-checked receipts under the same access identity. If the selected model's real context cannot hold the required final inspected report, the app reports the capacity limitation and retains the corpus. It does not silently clip evidence or claim that reading every span proves semantic coverage.

## 17 Validation and evaluation record

The regression corpus includes the eight audited original-export turns separately from corrected-evidence fixtures. These serve different purposes: original replay checks behavior against evidence actually available to that run; corrected evidence checks source support against independently established findings. Fixture metadata explains the distinction. Do not attach the earlier 58/100 score to the new rubric or infer a new score from passing tests.

Automated validation covers policy corners and all 25 slider combinations; serialization and future fields; settings commit, abort, recovery and restore races; queued snapshots; atomic replacement and regeneration; canonical history and source ancestry; explicit counts and visible-word rules; clinically consequential qualifier and null-result regressions; physical retries, parallel budget admission and unknown costs; full-scale finite renewal; and Home layout/selection state. Android test sources also cover actual Compose interaction, accessibility labels, navigation, theme, RTL and large text.

The emulator acceptance script explicitly dispatches the nine new Combined settings, Settings Home, and navigation test methods and validates their instrumentation protocol results. Home receives a device viewport wide enough for its 600 dp case; the motion test enables animation duration while sampling an intermediate frame. Setup/restoration failures remain visible, all legacy acceptance cases remain enabled, and thirty Home screenshots are collected as CI artifacts. Compilation alone is recorded separately from observed execution.

The final build/test result for this branch is recorded in the pull request and `Combined_Mode_Validation_2026-10-18.md`. Device or emulator rendering, provider availability, and a new independently reviewed live Combined chat are reported only if actually exercised. Passing deterministic checks does not establish a measured quality score, latency reduction, or factual accuracy rate.

For a new quality score, replay the same tasks with fixed model versions and prompts, retain the exact tool evidence and request accounting, and perform blinded comparison against the old final, each source draft, and the best individual answer. Publish the seven rubric categories, applicable denominator, catastrophic factual failures, delivery rate, latency, tokens and cost. The acceptance target is fewer semantic reversals and unsupported claims with defensible source consideration—not increased token use or equal model shares.
