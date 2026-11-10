# GitHub verification and continuation repair

Scope: PR #816, `fix/audit-execution-recovery-2026-11-09`, building on `f18cdf21e05658b29417a919707f0761f2698f3a`. The supplied November 9 capture is historical evidence; this change does not alter its measured 2/5 result or replay its completed writes. A subsequent run can recalculate acceptance from the retained, correctly bound receipts.

| Finding | Delivered behavior |
|---|---|
| 1. Catalog mismatch | Preserve received inventory separately; qualify requested GitHub contracts independently of unrelated search tools. GitHub contract changes still require reconciliation. |
| 2. Fixture proof | Explicit proposal-to-publication links retain task, binding, branch, parent, result and content evidence. Resolved publication satisfies its original proposal without another write. |
| 3. Accounting | Historical approval observations and current resolved states are separate. Cancelled proposals are not completed commits. Verified remote state does not invent native dispatch counts. |
| 4. Read prerequisites | Generate dependency-aware recipes once per checklist; ready reads carry their exact next call and an untested classification. |
| 5. Test scope | The new integration preset admits a supported fixture matrix and reports unsupported lifecycles separately. Genuine user decisions are conditional. Historical all-tools requests retain their original scope and cannot claim unsupported actions passed. |
| 6. Terminal reason | Retain the concrete acceptance stop, affected contracts, budget and recovery action in delivery failures; no cursor advice without an issued continuation. |
| 7. Failure owner | Approval, application verification, tool execution and provider failures have distinct domains; selected provider remains context. |
| 8–9. Restoration | One bulk restoration callback and one restoration event replace repeated fresh-result callbacks, narration and handoff writes. No dispatch or new tool-budget charge occurs during hydration. |
| 10. Advancement | Use evaluated criterion/capability states, blockers and fixture transitions rather than raw response fingerprints. |
| 11. Continuations | A terminating newline is not another unread source line. Equivalent recipes prefer a stronger compatible immutable revision binding. |
| 12. Inventory | Existing canonical counts survive projection; legacy incomplete projections report unknown fetched counts and an explicit lower bound. |
| 13. Requirements | Reuse the canonical request parser, excluding headings, budgets, preset markers and resolved continuation wrappers. |
| 14. MCP | Existing per-connection recovery is preserved; catalog receipts also retain phase, validation rule/field, HTTP status, state revision and recovery condition. |
| 15. Release failure | Existing draft-by-ID/tag reconciliation is retained. CLI failures expose bounded resource/status/exit details without dumping stderr. Published tags must resolve to the source. |
| 16. Binary provenance | The signed-release workflow verifies downloaded binaries with GitHub CLI attestations pinned to repository, source SHA and signer workflow/SHA. Android can verify the exact release-evidence artifact digest, publication receipt, current successful run attempt, current tag and every remote binary digest. Missing, stale, expired or mismatched evidence remains unverified. |
| 17. Subordinate reads | Retain bounded failed request IDs, endpoint classes, target digests, statuses, causes and local elapsed times even when the parent succeeds. Essential/optional scope stays explicitly unrecorded unless known. |
| 18. Invocations | Saved GitHub tasks retain minimal invocation accounting independently of verbose logging, including selected-profile private passes. Missing accounting stops admission. Temporary chats keep their existing privacy path. |
| 19. Policy | Persist a protected, immutable per-run admission snapshot with selected identity digests, authority revision, admitted contracts, chat policy, original/remaining call budget and test matrix before the engineering tool loop. These records are in workspace export; the older normalized policy tables are specific to supported Combined policy shapes. |
| 20. Collection state | Export configured network diagnostics separately from configured/active app-log collection, privacy state, change time and reason. One switch is not presented as evidence that the other recorder ran. |
| 21. Capture recovery | Distinguish unfinished cursor collection, failed collectors that can be retried, and missing source evidence requiring reproduction and a new capture. |
| 22. Redaction | Typed authorization-state enums survive; raw credentials and arbitrary authorization strings remain redacted. |
| 23. Profiles | Current profiles are configuration records with zero execution-evidence count. |
| 24. Checkpoints | One digest-addressed checkpoint per scope/chat replaces duplicated JSON trees; lightweight provenance references stay outside semantic recall. |
| 25. Journal | Coalesce noncritical events for at most 250 ms or 32 events. Terminal/failure events wake immediate persistence. Sequence, privacy and frozen-export behavior remain tested. |
| 26. Allocation | Remove duplicate checkpoint trees, repeated completion side effects and per-capability recipe rebuilding. Archive serialization remains bounded; device allocation/latency improvements require measurement. |

## Release evidence trust boundary

Native release status requires `workflow_run_id` and `expected_head_sha`. It accepts only a successful exact attempt of `.github/workflows/release-build.yml`, and a digest-bound `release-evidence-<sha>` artifact containing `release-publication.json`. It rechecks the run attempt and current tag after fetching evidence. Remote asset digests must match every binary in the attested receipt. Workflow correlation with an app-initiated dispatch remains a separate field and can legitimately be unknown.

The publication verifier requires GitHub CLI 2.102.0 or newer when `--signer-workflow` is supplied; older versions had a signer-workflow prefix-matching defect. It denies self-hosted attestation signers for this repository's hosted release workflow. It does not publish a release or install a binary itself.

References: [GitHub CLI verification contract](https://cli.github.com/manual/gh_attestation_verify), [patched signer-workflow matching](https://github.com/cli/cli/security/advisories/GHSA-wjmr-j3rp-mh2g).

## Gateway 16 negotiation follow-up

Exact-head CI at `f18cdf21e` exposed the Android key-free version allowlist still accepting only majors 14 and 15. It now also accepts stable 16 versions with the existing explicit authentication and contract checks. Complete v14/v15/v16 response fixtures and negative unknown/malformed-version tests qualify the decoder. The same CI head passed API 36 instrumentation; API 34 stopped during external prerequisite installation. These are predecessor observations, not validation of this new commit.

## Voice and qualification

The Moonshine implementation and Android error-10 recovery are preserved. Concurrent commits `c6a0badda` and `f18cdf21e` fix Compose assertion imports in the voice and inline interaction instrumented tests. No new speech engine, multilingual pack, Android fallback or NPU claim is introduced.

Host regressions cover actual policy, state-machine, transport mock, redaction, archive and journal code. They do not establish microphone accuracy, deployed gateway recovery or emulator success. The release artifact verifier also needs a future successful signed workflow producing the new attestation receipt; older releases remain honestly unverified. Noncritical journal events have a bounded persistence delay and can be lost on abrupt process death within that interval; critical outcomes flush immediately.
