# Ordinary provider CI correction

## Intent recorded before source edits

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`, draft PR #2367.
- Shared branch: `fix/diagnostic-execution-completion-2026-11-22`.
- Current integration base: `b50c29febaeb54808a4561955220f5b33bd09253`.
- Isolated branch: `work/root-provider-ordinary-ci-d50`.
- Root publicly reserved these two failures at 01:58:39 UTC before edits;
  exact PR readback SHA-256:
  `4a3e9da9c970b3f10902f0df3601cabe860de4fc8b0c48081f6a8670008c2d83`.

The completed historical 822 ordinary run reported two transport failures.
Their full test bodies and relevant route/transport inputs were independently
bound across 822, 557 and b50. Read-only source diagnosis:
`a3a3c13179c97a2c4ed7b9075030ee3cac6b45d993d26e4786e0c9f2475d261a`.
Those historical outcomes do not qualify current source.

## Narrow corrections

`boundedHttpRetriesRetainKnownProviderFailureInBothTransports` already retains
the true provider retryability on ErrorDetail. Its structured detail is then
overwritten by a PROFILE_UNAVAILABLE durable-scheduling refusal despite the raw
API call not requesting durable scheduling. Apply that additional scheduling
projection at the two API catch sites only when durable rate limits are actually
requested. Preserve the original helper, provider classification, explicit
HTTP/SSE body refusals, remote acceptance and retry/replay boundaries.

`persistedDirectChatsHandOffHttpAndStreamedRateLimitsForBothApis` sets a locked
model but omits its locked endpoint. The unchanged selected-route guard therefore
rejects the first request before MockEngine. Complete the fixture's endpoint/model
binding. Preserve the production route guard, all four API/HTTP/SSE cases and
every existing assertion. Do not raise timeouts or weaken expected outcomes.

Root owns only these changes and combined metadata. Preserve the published
64-case provider correction, all other test methods and every incoming worker
commit. Existing owners retain report/Compose/lifecycle, exact scanner-pin and
Responses retained-work HTTP400 changes. The native owner has already published
the QNN SDK-manager setup correction at b50; root makes no competing workflow
or production runtime-pin edit. Reconcile the three incoming QNN candidate paths
in release scope and regenerate its missing workflow index entry.

## Qualification boundary

The existing failing ordinary methods are the direct regressions and remain
mandatory alongside every newer refusal and safe-wait control. No local Kotlin,
privacy substitute harness or extra historical archive is used. Canonical host,
format, scope and immutable-input checks and independent final source review
must bind the combined commit. Actual final PR tests, complete XML/workers,
heap/APK/DEX/device/native/security gates, exact-main gates and joint public
release verification remain required. Candidate QNN construction is separate
from production-pin promotion or physical-device qualification.

Both narrow edits are now implemented. The prior bounded-retry method is
unchanged; the persisted-route method adds one shared endpoint value and its
matching lockedApiUrl without changing any assertion. All64 recently added
refusal/positive cases and every other method remain intact. The incoming QNN
workflow, candidate script/manifest and compiler setup remain byte-identical
to b50; only their missing scope/index metadata is reconciled here.

## Additional intent recorded before workflow edit

Public `55b8490ffdb7a11d78f9721d422c78b2ea467948` has since added the
separate lifecycle disposal-frame correction and reconciled the incoming QNN
scope/index. Preserve and adopt that work in this combined candidate. Root does
not repeat its metadata repair; the new root ledger is the only additional
scope path. The provider source changes compose unchanged.

Independent current-source review found that the candidate input-check step
pipes Python output through tee without an explicit pipefail shell. Its later
build step revalidates inputs, so no whole-job acceptance bypass is claimed.
Root now takes only this newly identified reporting correction: set the existing
preflight step to explicit Bash so Python failure reaches the step outcome.
Preserve all commands, immutable pins, artifact retention, compiler setup,
concurrency and build gates. This narrow additional scope supersedes the earlier
no-workflow-edit plan above; it does not duplicate the native owner's SDK fix or
promote candidate runtime pins. No new workflow test is necessary for this
one-line shell correction; canonical workflow checks and actual hosted
execution remain required.

## Incoming diagnostic repairs preserved

Before publication, compose the unchanged reviewed provider and shell changes
onto `f06066bc6016100d2ef7ac55c9f36ebb2d954cda`. Preserve its complete owning-stage
checkpoint requirement, bounded-document host-frame barrier, exact waveform
model-pin scanner exception and controls, all three task ledgers, and incoming
scope. Regenerate combined inventories from actual source. The parent now has
525 scope paths and 32 limitations; this ledger is the only additional path.
The separate Responses retained-work and voice accelerator owners remain in
progress. Their future source and final ordinary/hosted qualification are still
required before readiness, merge, and publication.

## Incoming progress-preview integration preserved

Recompose the same reviewed transport and preflight changes onto public
`099603c699bba0b00a0a7380b5b45fc10229296f`. Its new progress instructions,
summary-boundary and evidence presentation changes, tests, task records,
feature/index entries and all incoming release limitations remain intact.
Combined scope contains 540 paths. Regenerate inventories after composition;
independent review and normal final CI must qualify the new progress feature.
No earlier candidate or isolated owner test result qualifies this combined tree.
