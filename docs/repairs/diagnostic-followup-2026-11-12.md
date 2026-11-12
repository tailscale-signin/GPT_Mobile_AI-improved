# Diagnostic follow-up on PR 819

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Working branch: `fix/voice-chat-crash-and-diagnostics-2026-11-11`
Starting head: `3eafd903c1ea433e4b8ec98962611119f233664c`
Base at task start: `main`, `e62bded83b3861816c2c6e97116b30c9eb0920f1`

This change implements the remaining source-backed defects identified by the
diagnostic review. It builds on the branch's existing exact-model preparation,
Gateway streaming, approval publication, and recovery changes. The companion task
record contains validation results and the publication target. Concurrent Windows
CI, voice recovery, Gateway polling, and issue-report repair commits through
`c6fbdf0031be37f426a4832c847c7ab4cfa9b1ec` were retained. Their additional source
and test paths were added explicitly to the existing release scope.

## Behavior changes

- **Stable recovery contracts.** Application-owned recovery metadata records its
  base API schema separately from the decorated provider schema. A subsequent real
  schema replacement invalidates that projection. An unchanged authorized catalog
  therefore survives recovery without hiding required/type/enum changes.
- **Durable approvals in ordinary chats.** The ordinary chat route can restore the
  exact frozen patch owned by that conversation and display the existing native
  approval controls. Task, chat, connection, patch digest, repository, and source
  revision checks remain mandatory. New chats follow their allocated chat ID;
  GitHub workspaces keep their existing approval host.
- **Independent read and write outcomes.** A successful recovery/status read can
  pass while the referenced mutation remains waiting for approval. Reading a
  status never approves or repeats the write.
- **Honest legacy evidence.** Complete retained receipt chunks are validated and
  reconstructed before projection. Legacy page metadata reports fetched counts
  with its evidence qualification; it does not invent absent item identities or
  claim complete consumption. A missing saved view can recover only through a
  verified original source-read recipe under the current contract and authority.
  Generic cursor retries and uncertain mutation replay remain prohibited.
- **Bounded evidence work.** Equivalent pending read recipes are consolidated
  without mixing source revisions or weakening requested live verification.
  Verification events describe state transitions; exact duplicate snapshots do
  not create another history event. Verified fact labels identify their resource.
- **Progressive answer recovery.** A literal repetitive-prose guard retains useful
  text and ends the upstream stream. Repair is bounded and text-only; completed
  tool actions are not replayed. Continuation text is released once overlap is
  resolved, instead of waiting for the entire request. Code, tables, and quoted
  passages retain their content. A repair prompt excludes a degenerate saved tail.
- **Bounded paragraph layout.** Long ordinary paragraphs use bounded annotated
  pages with explicit expansion. Text, links, styles, and Unicode pairs survive
  paging. Settled annotations are remembered; plain-text formatting fallback is
  bounded too. Paragraphs containing Markdown images keep the renderer's complete
  image promotion path.
- **Explicit draw ownership.** Timeline text retains conservative output origin
  and physical invocation identity through serialization and display filtering.
  Mixed ownership remains unknown, and restored text is marked retained. A draw
  event is recorded after drawing the corresponding prepared content; it is not
  presented as proof of physical screen presentation.
- **Accurate Stop labels.** A locally canceled observer with a saved Gateway job
  displays local waiting stopped, remote cancellation requested, remote confirmed,
  or already finished according to its retained receipt. Local cancellation alone
  cannot establish remote termination. Existing job identity and reconciliation
  paths remain in place.
- **Lower export allocation.** Valid diagnostic JSON trees are reused rather than
  copied recursively. Hash encoding avoids per-byte formatters and duplicate
  identity work. Protected workspace export buffers use the measured encrypted
  entry size within the existing ceiling, then zero their contents. Source growth,
  hash mismatch, revoked authority, and malformed content still fail closed.
- **Typed Gateway recovery checkpoints.** Persist the explicit checkpoint kind
  even when it equals the default. Previously saved markerless checkpoints remain
  readable, with the original command fence, unknown-outcome protection, and
  retry backoff intact. Reconnect tests exercise actual scheduling through a
  WorkManager fixture while retaining their no-replay and no-stop assertions.
- **Windows fixture cleanup.** Model preparation HTTP tests explicitly close their
  SQLite connections and HTTP clients before removing temporary files. The prior
  Windows failure was an open test database handle, not a failed load assertion.

## Verification boundaries

Focused tests cover the changed contracts and behavior, including serialization,
ownership ambiguity, bounded repetition repair, streaming cancellation, exact patch
restoration, source-read proof checks, Unicode/span preservation, retained receipt
hashes, redaction, and protected-source growth. The task record names the commands,
results, and any unavailable checks for the published source.

The focused JVM run passed **690 tests in 65 suites** after compiling **319 current
production files** in bounded batches. It reused unchanged dependency bytecode and
generated Android resources; it is not a complete Android build. The canonical
fast validation passed **462 tests**, and the nine targeted Gateway suites passed
**149 tests**. The issue-report service passed **14 tests**. Formatting passed for
all 77 changed Kotlin files. The final configured-Gateway update was then recompiled, and all **47 tests**
in its three affected Gateway suites passed. The subsequent queue update was
recompiled and passed all **30 queue tests**. The local Gradle
daemon disappeared during KAPT stub generation, so full Android packaging and
current-head CI remain required.

Host checks cannot establish reference-device frame time, real Gateway model
availability, or physical Android voice behavior. Those acceptance outcomes remain
unqualified until measured on the corresponding deployment. Ending an upstream
stream does not itself prove that a durable remote Gateway job was canceled.

No private diagnostic archive or conversation export is added to the repository.
