# GitHub engineering modernization — implementation ledger

Branch: `feature/github-engineering-modernization`, branched from
`feature/improve-in-chat-workspace-ui` at `c7d11b03d81b3814acd6be35013c1eb2fa71f65e` (PR #758).
The parent PR was open/draft. Its checks were still running at initial inspection. The
repository exposed no repository rulesets; branch-protection reads returned HTTP 403 from
the integration, so existing classic protection is unknown. Auto-merge was disabled.

This ledger distinguishes code delivered here, existing behavior retained, and evidence or
administrative work still required. “Implemented” does not mean Android CI/device qualification
has passed. The full Android build needs JDK 21 and Android SDK 37, absent from the editing host.

| # | Requirement | Implementation / evidence | Remaining gate |
|---|---|---|---|
| 1 | Document workflows / checks | `.github/workflows/README.md` inventories all actual workflows | Configure required checks in GitHub |
| 2 | Reuse setup without nested entry points | Local setup-android composite used by PR validation | Broader migration can follow measured parity |
| 3 | Gradle cache and dependency verification | setup-gradle with SHA pin, wrapper validation, PR read-only cache; existing verification metadata retained | Review changed dependency checksums; no blind regeneration |
| 4 | Independent parallel jobs | Repository/gateway contracts and Kotlin formatting run beside Android validation | Measure warm/cold run changes |
| 5 | Concurrency and timeouts | Every runner workflow has explicit bounded jobs/concurrency | Tune from collected timings |
| 6 | Failure summaries and artifacts | First failure, repeat grouping, redaction, bounded JSON; explicit artifact retention | Review full Android CI diagnostics |
| 7 | Pin Actions | All third-party uses pinned; policy rejects floating refs | Dependabot pin review |
| 8 | Protect main | Reviewed candidate ruleset in `main-ruleset.json` | Admin API unavailable; not applied |
| 9 | Block unverified merges | Aggregate gate rejects missing/skipped/cancelled; app requires current GitHub merge rules and successful checks | Activate main ruleset; Android tests |
| 10 | Merge queue / verified auto-merge | merge_group trigger; app refuses blocked/unknown states | Queue/auto-merge disabled; repository admin configuration required |
| 11 | Stale/conflicting branches | GraphQL BEHIND/CONFLICTS, head/base revalidation; watch detects moved head | Device/UI review |
| 12 | Review ownership / templates / size | CODEOWNERS, evidence-based template, size warning script | Independent reviewer needed for owner-authored approvals |
| 13 | Permission failures | Existing HTTP/rate-limit classification retained; unavailable merge rules stay unknown; refresh errors are visible | Test real GitHub App installation permissions |
| 14 | Unit tests / JaCoCo | Existing tests/coverage retained; empty/malformed reports fail | Full Android CI |
| 15 | Emulator smoke | Existing Android acceptance workflow retained with cache modernization | Emulator run on relevant branch/main |
| 16 | OAuth / token lifecycle | Existing PublicAuth/DeviceAuth tests and broker contracts run; 15 broker tests passed locally | App registration and device sign-in/revocation validation |
| 17 | Workspace chat / switching / tabs | Parent branch tests retained; merge-health and moving-head tests added | Android/Compose suite and device UI review |
| 18 | Combined / Multi-chat | Existing Android suite remains in canonical validation | Current-revision regression run |
| 19 | Screenshot / accessibility | Existing visual/device fixtures retained; status uses text + polite live region | Full screenshot/accessibility baseline not newly qualified |
| 20 | GGUF / QNN / provider recovery | Existing admission/recovery tests, host and physical workflows retained | Compatible GPU/NPU device evidence remains required |
| 21 | MCP auth and contracts | Gateway security/recovery/session contracts run independently | Live server end-to-end validation |
| 22 | Gateway-to-Android integration | Existing gateway + Android contract suites retained | Live authenticated phone/server test still needed |
| 23 | Dependency vulnerability checks | Existing Dependabot retained; PR dependency review rejects introduced high/critical advisories; checksum verification maintained | Dependency graph/API availability must be verified in CI; unsupported manifests are not covered |
| 24 | Secrets / permissions | High-confidence credential scan, least-privilege defaults, Action pin policy | GitHub native push protection remains an admin setting; scanner is not exhaustive |
| 25 | Build/memory/startup baselines | Weekly/manual run history + existing device baseline tooling | No fabricated startup/memory baseline; collect matched runs |
| 26 | Regression thresholds | Existing measured device qualification gates retained | New thresholds require reviewed physical-device measurements |
| 27 | Release manifest / provenance | Existing signed release manifest/scope/attestation gates retained | Signed release validation; no release triggered by this work |
| 28 | AI engineering rules | AGENTS + `.github/instructions/ai-engineering.md` | Reviewer adherence |
| 29 | Architecture index | Generated `docs/architecture/repository-index.json`; freshness check | Regenerate when entry files change |
| 30 | Task checkpoints / resume | Existing durable approval/checkpoint/reconciliation reused | Android regression tests; no mutation replay on uncertain outcomes |
| 31 | Actual CI/PR states | Timestamped cards with reviews, required checks, failures and merge eligibility; GraphQL query | Runtime/API/device validation |
| 32 | Contributor docs | CONTRIBUTING.md and DEVELOPMENT.md | Keep toolchain versions aligned |
| 33 | One command validation | `bash scripts/validate-local.sh fast\|full` | Full requires SDK/JDK and Node |
| 34 | Workflow health history | Weekly/manual summary + bounded JSON artifacts retained 30 days | First scheduled/manual run after deployment |

## Rollout and merge policy

This is a stacked change for review against the original feature branch. Keep the parent and
this PR draft until the relevant Android and security/device checks pass. No main merge or
release is part of this change. Use the exact head SHA when reviewing CI evidence.

After a successful run, apply the candidate ruleset through repository Settings → Rules,
review its one-review requirement with the owner, require `Required verification` from GitHub
Actions, disallow force-push/deletion and enable strict currency. Add merge queue only if the
account/repository plan supports it. A missing administration capability must be reported,
never interpreted as an unprotected or safe branch. Do not disable protections to unblock CI.

## Limits and intentionally preserved safeguards

- A complete GraphQL health inventory is bounded to 100 contexts. Larger/partial results stay
  unknown and link to GitHub; no optimistic fallback.
- Merge status cards are snapshots. Completed watches can be refreshed; merges always recheck.
- Configuration cache and parallel Gradle workers remain disabled for the existing ObjectBox
  and memory reasons. Parallel job execution does not change these process limits.
- The high-confidence scan reports locations, never credential values, and complements native
  secret scanning. It is not a guarantee that all secrets or vulnerabilities are detected.
- Account settings, compatible-device qualification and measured performance are explicit
  remaining gates, not checked-off items inferred from source files.

## Local validation performed

- 211 Python repository tests passed.
- Gateway validation: 190 Python contract tests and 7 Node session tests passed.
- 15 GitHub authentication broker tests passed.
- Four merge-health scenarios executed against the production Kotlin evaluator using a
  standalone compiler/harness (separate from the unexecuted full Android/JUnit suite).
- Actionlint validated all workflow YAML; changed Kotlin passed ktlint 1.3.1.
- Android regex/resource/icon/provenance preflights and whitespace checks passed.
- New Android/JUnit/Compose integration tests still require CI. No device qualification,
  repository protection update, production deployment or release is claimed.
