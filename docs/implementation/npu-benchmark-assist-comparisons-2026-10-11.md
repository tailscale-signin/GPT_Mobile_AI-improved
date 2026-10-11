# NPU benchmarks, score coverage, and Assist controls

## Evidence and repair

The attached 0.9.41.0 / build 110 export records successful SM8750 NPU short trials followed by two `speed-medium-0` failures against a 4,224-token context. The benchmark generator counted lexical comparison units while local admission conservatively counts UTF-8 bytes / 2. The medium input alone was estimated at 2,222 tokens by the invocation ledger but exceeded the local planner budget. The runner then stopped all remaining accuracy tests.

Suite v5 resolves the installed export context using the same metadata/catalog/hardware limits as the local adapter. It rounds down to a stable context tier and removes only synthetic padding from throughput and recall fixtures, reserving output, template and instruction headroom. The context tier is persisted and included in the scoring cohort so differently sized workloads do not share performance anchors. Local correctness output is bounded at 256 tokens; speed remains 512. Remote fixtures retain their existing sizes.

Standard runs continue after individual quality/context/time-limit errors. Provider connectivity, authentication, quota and initialization failures still stop requests and save explicit skipped outcomes for remaining fixtures. Unsupported exports preserve their fixture metadata and are excluded from reliability and quality; no invented tool scores are supplied. Errors in attempted correctness fixtures count against quality rather than hiding all overall scores. Quick runs stay provisional. The five scoring dimensions are shared by the overview and comparison cards.

## UI

- All planned test categories show recorded counts, passed/failed/unsupported/skipped/pending status.
- Comparison results respect the selected test mode and separate context tiers, device and remote cohorts.
- Theme-colored bars show overall rating, quality, reliability, speed, first response and consistency, with numeric labels and accessible descriptions.
- Local Assist is visible on the Local Models page, its Settings page, and the conversation Models panel. All three control the existing persistent global setting; the copy explains the scope and warm-model prerequisite. Existing Assist fallback and privacy behavior is preserved.

## Runtime audit: 2026-10-11

The baseline already pins LiteRT-LM 0.18.0 and QNN runtime 2.51.0, with dispatch built against the LiteRT revision pinned by 0.18.0 and QAIRT SDK 2.51.0.260929. No speculative version bumps or replacement DSP skeletons are introduced. The strict dispatch provenance gate and 11 QNN inventory/provenance tests pass. APK CI verifies the precise runtime inventory, SHA-256, sizes, and 16 KB host ELF alignment, including the SM8750 V79 components. Cache/speculative/native-metric settings remain supported. Benchmark configuration identity now also includes QAIRT and execution policy.

Sources checked:
- https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.0 (latest release; dynamic NPU KV growth and GPU attention-mask improvements)
- https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md (official Android QNN runtime coordinate 2.51.0)
- docs/audits/qualcomm-dispatch-inputs.json
- docs/audits/local-runtime-native-libraries.json

## Validation

Regression tests cover 2,048/4,224/8,192-token text fixtures, preserved recall/instructions, stable context cohorts, continued correctness tests after context failure, complete skipped outcomes, unsupported tool capabilities, and score coverage. Android resource preflight and QNN provenance/inventory checks pass locally. Local Gradle execution is blocked because this environment cannot download the Gradle distribution; PR CI must compile, run unit tests and lint, and audit the review APK. Physical SM8750 benchmarking and visual device review remain necessary to confirm native performance and interaction on the phone.


## Scoring reanalysis prompted by the screenshot

The screenshot reports 0.56 s first text and 38.1 tokens/s while the corresponding normalized dimensions say Not measured. `benchmarkRating` accepted speed/latency anchors but the screen never supplied them. It then replaced only the overall headline with the dynamic snapshot score, leaving legacy dimension rows disconnected. The overview now reads the same five dimension values as the comparison engine. When an official anchor genuinely does not exist, it shows Needs verified baseline with per-band timing coverage, rather than pretending no raw timing exists.

The 36-test Core Text v3 plan had no tool cases. Tool success is now labeled Not in this suite in Core Text, Unsupported for an explicitly chat-only export, or an actual measured percentage after a tool suite. Completed request count is not accuracy: an incorrect completed answer still lowers accuracy. Agent Standard keeps twelve isolated fixture-tool tests.

Historical v3/v4 measurements have their own manifests and cohorts. No Intelligence score is backfilled into old runs. Current configuration enrollment/freshness requirements still apply. Re-run the updated Standard plan to measure the new capability column.

## Intelligence column: research and bounded implementation

There is no validated scalar that measures all AI intelligence. The recommended architecture separates capability, operational reliability, latency/throughput and tool execution. The shipped mobile screen uses 36 original, compact, objective questions across math, logic, reading, planning, code reasoning and knowledge. It is an explicitly labeled screening index, not a substitute for a full public benchmark or an IQ measurement. The question set is deliberately small enough for a local NPU and can saturate on stronger models.

Each domain contributes one-sixth of the Intelligence score. All six answers in all six domains are required for an overall value. Incomplete/error/unsupported outcomes leave coverage incomplete; actual incorrect answers score zero. The latest complete block is used, so repeating the same items does not inflate the claimed sample size. No network access, model judge or execution of generated code is needed. The answer checker tolerates case and an Answer label but does not award points for an answer appearing somewhere inside unrelated prose. Intelligence preserves the model profile's reasoning choice; timing fixtures disable optional reasoning. Output budgets and configuration identities remain recorded. Scores therefore describe this configured inference policy, not a model's theoretical maximum.

Suite v5 has 72 Core Text trials or 84 Agent trials, including the 36 Intelligence questions. Quick Check retains six diagnostics and explicitly says Intelligence is not included. Intelligence stays independent of the operational App Rating and can be used to sort comparison cards. Six domain results and coverage appear in the profile detail.

### Why this design

- **HELM:** broad scenarios, multiple metrics, standardized evaluation and explicit missing coverage. This supports separate capability/reliability/speed columns and honest scope labeling. https://crfm.stanford.edu/2022/11/17/helm.html
- **LiveBench:** objective ground-truth evaluation across varied tasks; refreshed test releases address contamination. This supports deterministic checking and versioned fixtures. The app's original mini-suite is not LiveBench and is not asserted to be contamination-free. https://github.com/LiveBench/LiveBench
- **MMLU-Pro:** more challenging reasoning-focused questions and expanded answer choices improve discrimination compared with older knowledge-only tests. A formal knowledge/reasoning extension should use its official versioned dataset, licensed materials and standard scoring, rather than relabeling these 36 screening questions as MMLU. https://arxiv.org/abs/2406.01574
- **BIG-Bench Hard/Extra Hard:** a diverse challenge set and controlled reasoning policy matter; easy tests saturate and direct-answer settings can underestimate reasoning capability. https://github.com/suzgunmirac/BIG-Bench-Hard and https://github.com/google-deepmind/bbeh
- **IFEval:** objectively verifiable instruction adherence is distinct from knowledge or reasoning. Existing exact-instruction/JSON/tool columns remain explicit. https://huggingface.co/datasets/google/IFEval
- **EleutherAI lm-evaluation-harness:** provides reproducible public task definitions, configuration and integrity checks. For research-grade comparison, the best follow-on is an optional separate full evaluation pack or a controlled harness run, with dataset version, license, prompt template, sample selection, reasoning/token budget and grading recorded; imported results must remain separate from this native mini-suite. https://github.com/EleutherAI/lm-evaluation-harness
- **LLM-as-judge research:** position, verbosity and self-enhancement biases justify avoiding an opaque model-given Intelligence rating. https://arxiv.org/abs/2306.05685

No external benchmark questions are redistributed in the original mini-suite, and no public leaderboard equivalence or statistical confidence interval is claimed. Six fixed items per domain are insufficient to support those claims. Physical-device runs and formal evaluation packs are still needed for stronger capability conclusions.


## Follow-up: Quick 5 / Full 50 and two-model comparison

Suite version 6 supersedes the earlier 72/84-test plans in this document. Run now exposes exactly two options:

| Category | Quick tests | Full tests |
| --- | ---: | ---: |
| Timing | 1 | 18 (3 warm-ups, 5 measured repetitions per context band) |
| Task accuracy | 1 | 8 |
| Structured JSON | 1 | 6 |
| Tool contracts | 1 | 12 |
| Intelligence | 1 | 6 |
| Total | 5 | 50 |

Each Intelligence test contains one compact question per domain. Full retains all 36 original questions; Quick samples six and explicitly reports a provisional score. Each domain answer is graded independently and persisted before preview truncation, so one wrong answer does not erase credit for the other five. Infrastructure failures still leave incomplete coverage. Unsupported tool cases retain an outcome and remain excluded from accuracy. Test counts refer to fixtures; a fixture may involve tool round trips or a transport retry. Quick never manufactures a consistency or normalized speed rating from one timing observation.

Versions 3–5 retain their original manifests, workloads and cohorts. Existing saved results and backup data remain readable. The historical Agent mode remains available in stored history; new Run plans are Quick and Full, both with tool coverage. Delegation keeps its dedicated workflow.

The new Compare view offers two explicit model pickers, a baseline swap button, side-by-side numbered bar charts and a results table with model 1, model 2 and model 2 minus model 1. Score differences use percentage points. Raw throughput uses reference tokens/second; response latency uses milliseconds. Deltas are withheld for incompatible cohorts or missing values. Charts retain a minimum readable width on very narrow displays and support horizontal scrolling. Navigation, filters and main actions use labelled icon buttons, and verbose local diagnostics are collapsed by default.

Regression coverage checks exact 5/50 plans, all five Quick categories, all 36 Full Intelligence questions, partial domain credit, incomplete/duplicate outcomes, old suite manifests, signed comparison deltas, incompatible scales and local context budgets. Prior PR revision 51a5b01 passed Kotlin lint, Android unit tests/lint, review APK/runtime packaging and CodeQL; this follow-up requires its own CI result.

## Provider/model scoreboards and delegation lab

Results is now Scoreboard, with Local and Online sources and separate Quick/Full
rankings. Each best entry is one complete remote-model run identified by provider
and actual model ID; renaming a profile cannot duplicate it. Opening an entry
shows weighted score bars, intelligence domains, raw timings, tokens, every test
outcome, protocol and provenance. Device models retain Run/Compare/History.

Capability rankings use an absolute, versioned policy rather than timing anchors;
see `scoreboards/README.md` for the formulas and limitations. Existing cohort
performance ratings remain in Run/Compare. Shared results are self-reported and
cannot independently establish real provider execution or complete intelligence.

Delegation has Run and Scoreboard views with its own Local/Online data. New plans
contain exactly five or fifty fixtures. They force a reproducible research and
reviewer preset without changing conversation settings, require an independent
reviewer, and score the delegate while retaining primary/reviewer identities.
A final assessment receives trusted synthetic fixture truth; it cannot rewrite
or repair the candidate. Completed outputs require a valid independent score.
The legacy 24-test delegation manifest remains available for historical records.

Online publishing is explicit, previewed and uses a selected connected GitHub
account. The app ships no repository write credential. The trusted default-branch
issues workflow validates allowlisted numeric/boolean data, recomputes scores,
archives accepted submissions and updates the `scoreboard-data` branch with
non-forced, race-retried writes. There are no prompts, replies, keys, endpoint
URLs, profile names or private diagnostics in the payload. Public reads require
a public repository; the workflow becomes active only after merge. Nothing is
merged, released or submitted to the live scoreboard as part of this change.

Retest bars retain the theme colour up to the overlapping score, add yellow for
gained points, and extend red to the prior score for lost points. Numeric deltas
and accessibility text accompany colour. Comparisons use matching protocols and
plans. Run shows the latest retest even when it loses; Scoreboard preserves the
best run. Cohort performance charts compare individual runs using one current
cohort scale, avoiding changes hidden inside five-run averages.

Validation includes a shared Android/Python score contract (both 5/50 plans and
both kinds), malformed/private/oversized payload rejection, manifest integrity,
immutable accepted issues, non-fast-forward publishing retries, best-run identity,
independent review of correct/incorrect outputs, and exact gain/loss segment math.
