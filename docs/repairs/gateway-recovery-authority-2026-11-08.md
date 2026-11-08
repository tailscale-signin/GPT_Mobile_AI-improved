# Gateway recovery, account authority and offline history follow-up

## Reviewed identity and scope

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Working branch: `fix/diagnostic-remediation-8597579-2026-11-07`; PR 813.
- Base branch: `main`, at `3ac89ceafd9083f67da30e92f9f93aae27be4600`.
- Reviewed integrated head: `6058217056cec0fecc5bdea0733ae33a10baf45e`.
- Preserved subsequent head: `0b5661fbe9652841f2f1f6968bcf5c697db4ba99`,
  including the shared no-commit fix, its bound-conversation regression and the
  two gateway profile-card device cases in the strict acceptance runner.
- Preserved implementation: `66ac834e09806c187e6fa92fad8d82c837be1ed5`,
  including Room 51, canonical server conversations and the Home Gateway browser.
- Intent: complete the requested audit remediation and gateway continuity work,
  repair demonstrated integration regressions, and publish to the requested branch.
  This follow-up does not authorize merging or publishing a release.

The integrated architecture remains documented in
[Gateway availability and persistent conversations](../architecture/gateway-persistent-jobs.md).
The original MCP fixes and the historical audit's unresolved live qualification
requirements remain documented in
[App-audit MCP remediation](app-audit-mcp-remediation-2026-11-07.md).

## Corrections

| Finding | Reproduction or source evidence | Resulting behavior |
| --- | --- | --- |
| A recovered queued worker could lose a failed final persistence result. | A real SQLite trigger rejected the completed job update; retrying the same command executed the model a second time on the reviewed head. | Recovered workers retain their persistence error before completion/expiry bookkeeping. Both streaming and non-streaming late retries keep the original job and return an uncertain durable-commit failure without another model execution. |
| Stop of an undispatched queued job could disappear across restart. | Stop returned a requested receipt, but the persisted admission remained runnable and executed after journal reload. | Stop confirms cancellation only after matching queued generation and undispatched SQLite evidence are checked and the terminal result/conversation are saved. Worker admission shares the generation/cancellation fence. Already dispatched work reports requested cancellation with retained intent. |
| Ordinary Chat Stop could use a replacement account at the same endpoint. | Its direct cancellation path used the current token and URL without the saved command's installation/principal authority. | Canonical Stop resolves the original reservation or conversation, verifies current identity, rechecks configuration and local reply ownership, and sends paired installation/principal preconditions. Missing or conflicting canonical proof yields an unconfirmed receipt with no cancellation POST. Explicitly identified legacy-only work retains its prior control path. |
| A saved conversation could bypass an explicit no-commit instruction. | The shared admission predicate missed “without committing”; both the policy regression and the actual bound-conversation submission regression failed against the reviewed head. | The same prohibition applies before implementation admission for initial and saved conversations. Read and plan requests remain allowed. No binding, job creation, control request or durable submission is made for the prohibited implementation. |
| Cached Jobs could mix principals before a fresh identity check. | The observer admitted all cached rows while its current identity was unknown; its database selection used the saved connection and endpoint alone. | Cached display requires an exact verified installation/principal scope bound to the current saved configuration revision. Editing credentials invalidates that proof. A successful authorized catalog read establishes the new scope. |
| Portable backups could transfer private cached-account authority. | The real encrypted-backup regression exported a `gateway_scope_` record on the reviewed source; a crafted archive could also overwrite it. | Export and import exclude these device-local authority records. Restore preserves existing local proof and rejects injected proof; ordinary provider credentials still restore. |
| Offline Home navigation could hide retained gateway history. | The Gateway entry depended on current connection freshness, so a disconnect or readiness expiry removed the route to cached conversations. | A previously verified, unchanged configuration retains the Gateway entry. Offline records are read-only cached history with explicit freshness and inventory limits; this does not claim the selected model is currently Online. |

The offline scope receipt is private encrypted state in the existing no-backup
secret vault. It binds an opaque credential-record revision to the saved connection;
it contains neither raw credentials nor a credential-derived hash. Unsupported
credential metadata cannot establish offline account proof. Room retains its
existing version and schema; the added conversation read query changes neither.

## Preserved requested behavior

The changes extend the existing engines and preserve the acceptance boundary:
the gateway owns a job only after durable admission. Android lifecycle events
release observation; explicit Stop remains a separate action. Pending commands
keep their original identity, and reconnect reads that identity rather than
creating a replacement request.

New chat retains model-specific fresh readiness, stable saved profile selection,
the current sort and filters, a steady Online indicator, and the six-second themed
name fade with reduced-motion and contrast handling. A reachable server whose
selected model is unavailable is not promoted as Online.

Jobs retains gateway-wide grouping, repository/profile attribution, total elapsed
time from acceptance, cached stale state, and read-only opening of the original
conversation/profile/model/repository/branch. Ordinary and engineering job state
mapping, queued admission, saved transcripts, command reconciliation, and device
tool/approval waits retain their distinct meanings.

The audit continues to separate configuration, fixtures, discovery, execution and
device evidence. Typed local MCP failures remain NOT_DISPATCHED; OAuth refresh
remains bounded to eligible 401 discovery failures across both transports, with no
403 refresh loop or replay of uncertain tool mutations. Cached blockers retain
their observation/configuration identity, and the UI retains reason codes,
retryability, tailored repair guidance and exact-connection navigation.

## Validation at the source checkpoint

The local results below use frozen copies of the changed production source and the
actual pinned Android dependencies. Earlier runs identify their own commits and do
not qualify changed source. Full generated-source and device gates remain separate.

- Gateway focused regressions: 79 passed, including five new regression cases.
- Authentication: 25 passed.
- Gateway canonical aggregate: 624 Python cases across 51 suites; 623 passed and
  one LAN-interface test skipped because this host has no bindable LAN IPv4.
  All seven browser bridge cases passed; all 177 package-listed source/test files
  match the frozen validation copy.
- Android canonical cancellation and ownership: 36 passed, including 30 tests on
  actual Room 51 generated code and six cancellation helper tests. This host run
  does not exercise the subsequently added cached-conversation DAO query.
- Bound-conversation commit permission: all eight corrected-source cases passed.
  The baseline reproduced the two expected failures. The stronger equivalent
  regression subsequently published in `0b5661fbe965` was adopted and all eight
  cases were executed again successfully against the pinned Android dependencies.
- Account-scoped cached Jobs and offline history: 40 passed across the scope store,
  cache isolation, menu, conversation browser and Jobs view model. The additional
  real Room cached-conversation query test compiled; it needs regenerated DAO code
  in the complete Android gate before it can qualify that query.
- Portable-backup account proof: the new real encrypted-backup/restore case
  reproduced the export defect on baseline source and passed with the correction.
  Existing private proof survives restore; injected proof is never imported.
- Canonical fast: 400 host cases passed; resource/regex/provenance and diff checks
  passed. Kotlin formatting passed for all 24 changed Kotlin files. Architecture
  index, source manifest and the 2,292-file audit inventory were regenerated.
  Inventory remains a source listing, not executed line coverage.
- Complete Android/KSP/Hilt/Compose, full unit/heap, emulator, APK, security and
  operating-system matrix checks: require the final published head.

The complete Android and required-check success at ancestor `0582f3428736` is
historical evidence. The later integrated head changed the gateway and Android
storage architecture; those earlier passes cannot substitute for its own gates.

## Remaining deployment qualification

Source and controlled regression tests do not supply the missing live receipts
from the original audit. Targets 7, 9 and 11 must be resolved on the affected
installation using their current saved names, fresh protocol/discovery evidence,
and an authorized representative tool result. Audit aliases are local to that
export. The repaired diagnostic paths help identify the actual cause without
guessing from the old generic result.

The 25 configured targets' historical untested execution checks, the ten broader
external suites, useful selected-model generation, complete GitHub outcomes,
physical-device process death/force-stop/reconnect, real network/provider behavior,
native/backend performance, persistence at representative scale, and independently
deployed service identities still require their corresponding live evidence.
Optional disabled or uninstalled integrations remain outside required scope unless
the user chooses to use them. No missing receipt becomes a passing audit result.
