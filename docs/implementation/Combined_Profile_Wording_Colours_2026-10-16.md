# Combined synthesis and profile font colours

## Confirmed export findings

The supplied culture-and-era debug export contains three completed Combined turns. The first final answer retains Athens but omits the Tang Dynasty contribution. Its five source bodies are retained. A synthetic regression fixture models the omission without copying the uploaded conversation, account identifiers, model configuration or diagnostic traces. The last turn contains mutually incompatible model assertions about a time-sensitive event without independently verified shared evidence. A provider overload incident recovered within a completed contributor run; it is not evidence of a persistent failure or a local runtime crash.

Current main already contains comprehensive synthesis instructions, immutable input freezing, omission detection and bounded repair. This branch adds the missing font-colour path and tightens the remaining repair and editorial behavior.

## Changes

* Pass frozen Combined sources and the existing persistent profile palette through ChatScreen, OpponentChatBubble and AssistantChronologicalContent into ChatMarkdown. This applies to ordinary, non-debug display, including retained answers.
* Index contiguous matching five-word phrases, ignoring case and Markdown punctuation but preserving original UTF-16 offsets. Project those ranges through the existing real Markdown source map. Matching wording from exactly one profile gets that profile's accessible foreground colour. Shared wording stays neutral and names every matching profile in badges. Unknown paraphrases remain neutral. This establishes textual lineage, not semantic ownership or factual verification; qualified semantic provenance keeps its separate validator.
* Recompute on answer/source changes; reject stale maps, malformed ranges and oversized attribution corpora. Math placeholders receive no source colour. Attribution performs no network requests, changes no answer bytes, and does not modify copying or exports.
* Keep the same wording-match basis for prose and badges. Explain the colour meaning next to the contribution inspector.
* Ask the final editor to preserve distinctive source wording while integrating topics, and distinguish model ignorance/clarification from factual counterclaims. Do not treat suggested follow-up questions as additional findings. Coverage no longer requires reproducing the app's trailing `Next questions:` block.
* Retain a repair that reduces omissions even if it cannot pass the full coverage check. Previously the better repair was discarded and the initial single-topic draft displayed. Such a repair still fails explicitly; it never emits Completed. Prefix immutability, cancellation, access validation and tool-free repair remain enforced.

## Verification

* 70 focused Kotlin/JVM tests pass using production delivery, coverage, wording matching and related orchestration components with minimal Android contracts. Includes a synthetic Athens/Tang omission, recovered-topic retention, question-block handling, Unicode offsets, formatting, shared/overlapping wording and source replacement.
* 116 Python repository tests pass. Resource and Android regex preflights pass. ktlint 1.3.1 and git diff whitespace checks pass on changed files.
* Added Android/Robolectric regressions for actual Markdown source-map transforms and rendered red/blue font spans, including removing colours after source replacement. These require Android CI.
* Full Gradle tests could not start locally because the wrapper distribution download is blocked by this environment's network access. No local APK/device acceptance or complete Android build success is claimed.

## Limits

Wording colours do not attribute arbitrary paraphrases or verify model-generated historical/current-event claims. Shared matches deliberately avoid assigning one profile as the owner. Corpora exceeding 262,144 combined answer/source characters render without wording colours rather than indexing an unbounded corpus. All original contributions remain inspectable. Unresolved disagreements still require independent evidence; this editorial pass does not invent a verification result.
