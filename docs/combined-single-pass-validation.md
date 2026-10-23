# Combined single-pass delivery and attribution validation

The regression fixture `app/src/test/resources/combined/portugal-2026-10-23.json` contains only the request, four supplied contribution bodies, and two retained synthesis texts from the diagnostic export. It excludes credentials, account configuration, and journal metadata. `completeTestCandidate` is a deterministic test candidate assembled from retained text; it is not a new provider run or a factual certification.

## Acceptance checks

- The 387-word revision stays selected when a later 198-word candidate fails. The later text remains inspectable as a separate revision.
- A complete candidate near 500 words covers the retained historical periods. Local validation must not issue another model request, verification call, or continuation.
- Treaty of Tordesillas and transatlantic slave-trade wording link to BridgeNode and Vireonix respectively. Merged 1488/1498 clauses retain event/date associations; swapped dates, negation, and lost qualifications cannot receive source colour.
- Shared text is neutral with a shared-source indicator. A passage opens source excerpts; profile markers provide a non-colour identifier.
- Persisted profile hues determine all foreground colours. Reordering/removing profiles does not change assignments; foreground contrast is at least 4.5:1 against the actual surface.
- Terminal failed contributors do not block usable contributions. Pending contributors remain behind the terminal/deadline barrier. A persisted synthesis owns its frozen inputs, so late arrivals do not recompute the answer.
- ISO retry timestamps and incompatible demo routes survive serialization. User retries obey cooldowns; a shared failure dialog exposes combine, eligible retry, and removal actions.
- Reasoning usage is recorded per served route. Passage arrival alone cannot calibrate successful delivery; only a terminal, locally complete answer can do so.
- Length, requested-topic coverage, textual source links, structural usability, and evidence verification remain distinct. Lexical uncertainty is not reported as proven omission.

## Device validation still required

Use the affected routes and a restored copy of the conversation to request the Portugal answer in 500 words. Check normal stop and output-limit receipts, visible word count, all historical periods, source inspectors, and revision retention. Restart the process during collection and during synthesis, then confirm exactly one accepted synthesis dispatch for the turn. Exercise multiple failed profiles, a future ISO cooldown, a demo exclusion, and a late result after the source set is frozen.

Record the answer screen on the affected device in light, dark, and custom themes. Verify the actual bubble surface, resolved-colour diagnostics, profile markers, and source-link hit targets. Measure frame timing while streaming, opening source excerpts, switching revisions, and scrolling a long answer. Compare cold preparation with repeated committed passages; source fingerprints and line mappings have bounded caches, and matching runs off the UI thread. The original frame stalls do not identify attribution processing as their cause.

Inspect `WRITER_USAGE`, `DISPLAY_COLOUR`, `RETRY_REQUESTED`, `RECOVERY_INSPECTION`, and `REVISION_PROMOTION` diagnostics, along with the export's existing dropped-event count. An incomplete journal is not proof that an unrecorded interaction did not occur.
