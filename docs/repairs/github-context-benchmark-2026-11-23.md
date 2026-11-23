# Controlled repository context experiments

## Scope and revision

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.

Parent work: PR #2254, `fix/diagnostic-execution-completion-2026-11-22`.
Isolated implementation base: `a7698653f8917f1b99043a4f2b0ad6a6519dd715`.
Working branch: `agent/context-benchmark-2026-11-23`.

Intent: make CURRENT, REFERENCE, TASK and SEMANTIC repository context comparable
with the same selected model, public source, tasks, task seeds, generation policy
and repair limits. Preserve ordinary two-model benchmark grading. This is an
implementation checkpoint; final integration must reconcile the current PR head
and rerun its required checks before publication.

## Behavior

Android exposes a separate context experiment in the engineering workspace.
Select one saved profile, one candidate arm or all four variants, and the existing
task extent and repeat controls. The queue alternates arm order across repeats.
Gateway exposes the same optional experiment through its durable coding runner,
native Windows workspace and Python desktop panel. Gateway's fixed desktop
choice uses three repeats; Deep coding with all four arms explicitly uses two
repeats (800 episodes) to respect the journal's existing 1000-case bound. The API
rejects oversized experiments before admission.

The public fixture projection copies only the displayed broken starting source
and public task contract. It derives a small source reference and feature map
from filenames and declarations. Trusted repairs, hidden vectors, grader answers
and model outputs are excluded. The source identity is explicitly a frozen
fixture manifest, not an actual Git commit.

Plans freeze context manifests, source/reference identities and declared source
coverage. Gateway additionally freezes the selector implementation digest,
execution route, settings and task manifest. Both runners prepare the actual
source-bearing prompt supplement with the production selector. CURRENT supplies
no additional context and performs no selector reads. Descriptor or source
identity changes fail before generation.

An already provisioned phone task catalog must be provisioned again to include
the public `contextInputs` and `contextManifestDigest` fields before a context
experiment can run. Older catalogs remain usable for ordinary benchmarks. A
missing context manifest blocks experiment preparation rather than silently
substituting source or weakening the frozen identity.

SEMANTIC on Android can call the existing bundled local encoder under the same
real isolated benchmark lease. A scope string alone cannot obtain that lease.
No inference provider or profile is substituted. Gateway's executable fixture
experiment can use its existing configured local engineering encoder. It freezes
the encoder endpoint/model hashes and operator-declared immutable revision at
admission, then carries that identity into every arm and comparison. Configuration
changes fail before source/cache reads or embedding dispatch. A bounded background
ticket uses the existing local inference scheduler, with current owner and
cancellation checks around cache/transport work. Strict selected-model and
session-only policies disable the auxiliary encoder. Missing configuration or
revision, unavailable local capacity and unusable vectors record lexical fallback;
fallback evidence cannot establish a semantic ranking benefit. The adapter never
downloads a model or chooses another generation profile.

Each arm records source-cache cold/warm selector time, actual loader calls and
cache hits, repeated reads within each preparation, selected-source coverage,
added UTF-8 context bytes, an explicitly labelled token estimate, measured
generation usage and independently verified outcomes. Successful tasks retain
time to first verified patch. Android reuses its existing all-in usage, elapsed
time and frozen-price cost accounting. Missing usage and prices stay unknown.

The comparison pairs exact task/repeat identities and clusters outcomes by
independent family. Its interval and difference are descriptive evidence for the
frozen fixture suite, without a new capability or superiority score. Context
arms are excluded from ordinary model score/enrollment selection. Existing
two-model engineering comparison behavior remains separate. A failed terminal
identity or cleanup gate invalidates Gateway comparison labels while retaining
the completed case facts and measurements.

## Measurement limits

- The existing executable task prompt already supplies every public starting
  source file. These experiments measure the effect of adding reference/context
  supplements; they do not measure a full repository agent's tool-loop retrieval
  reduction or repeated remote file reads.
- Cold/warm measurements describe the local source-loader cache probes. They do
  not establish cold model startup, cold network access, device energy or a clean
  persistent semantic-vector cache.
- Public-source coverage is not ground-truth relevant-file or test recall.
  Those fields remain unknown without independent annotations. The production
  selectors have separate deterministic retrieval qualification tests.
- Structured patch tasks do not assess general false completion claims. The
  exported field remains unknown; Android separately reports contradictions
  between a passed status and independently verified completion evidence.
- A local encoder timeout is cooperative between native calls. Source budgets
  and fallback labels do not imply a hard native embedding latency bound.
- A configured Gateway encoder revision is an operator assertion. It is not an
  independently measured model-artifact digest. Its endpoint and model are
  hashed in public receipts, and vectors remain encrypted within the authorized
  owner/run/source/encoder namespace.

## Verification

At the implementation checkpoint:

- Kotlin 2.4.20 host compilation of actual benchmark domain, preparation,
  comparison, phone context selector and admission code passed. The suite passed
  97 tests, including new arm identity, prompt delivery, stale source rejection,
  cache measurement, fallback and real lease-ownership regressions. Android,
  HTTP and native embedding boundaries were excluded from this host harness.
- Fifteen new Python behavior regressions passed against the production Gateway
  selector and real durable store/runner. Provider transport and Docker were
  replaced at explicit test boundaries; the frozen Python interpreter grader
  executed. Tests checked public-input isolation, injected prompts, unchanged
  model selection, unique durable arm IDs, source and experiment mismatch,
  unknown final attempts, native bridge defaults and terminal invalidation.
  A final focused run also passed the 15 existing durable journal and lifecycle
  regressions alongside those new tests (30 tests total).
- Seven additional configured semantic-arm behavior regressions exercise the
  real selector, encrypted cache, background scheduler, durable service and
  runner. They verify actual reordered prompt delivery, frozen encoder identity,
  owner isolation, policy/revision/capacity fallback and cancellation without
  sending hidden grader inputs. Embedding transport and generation are explicit
  deterministic test boundaries; no encoder quality measurement is claimed.
  The final integrated Gateway overlay, including the latest durable lifecycle
  changes and the guarded local transport, passed all 86 context, benchmark v17,
  phone and desktop regressions. Canonical fast validation was rerun afterward
  and passed all 623 host tests.
- Existing Gateway benchmark v17: 48 tests passed. Existing phone benchmark
  provisioning/independent execution: 11 tests passed. Existing desktop benchmark:
  5 tests passed.
- Canonical `bash scripts/validate-local.sh fast` passed with 623 host tests after
  regenerating local architecture/audit inventory outputs. Integration owns the
  combined generated outputs and must regenerate them on the integrated head.
- ktlint 1.3.1 formatting passed for changed Kotlin sources.
- Native Windows WPF project compiled with .NET 10 after restore from the cached
  packages: zero warnings and zero errors. This is a cross-build, not interactive
  execution on a Windows desktop.

No model performance measurements, Windows interactive execution, Android device
embedding performance or physical energy results were fabricated. Full Android
compilation/tests and exact-head CI remain integration
gates until their results are recorded by the parent task.

## Final combined-path review

Review started from integrated commit
`bdcd87da4bac15ab71508c6937aee196e159707a`. It checked the shared first-freeze
guard in queue admission and persistence, public-only fixture projection,
unchanged generation routes and repair bounds, explicit Gateway case limits,
counterbalanced arm order, and context/token/cache measurement labels. The
Android encoder is a build-pinned asset with packaging checksum verification;
its vector cache also includes the actual asset digest. Gateway encoder identity
and owner-scoped cache/admission are explicit in the experiment receipts.

One additional comparison defect was reproduced: an altered Android run
configuration/source identity or duplicate trial still contributed paired
completion statistics despite an identity-mismatch label. Comparison now
requires every run's valid frozen identity and unambiguous trial membership
before counting matched pairs or computing differences/intervals. Individual
observations remain available. Both new regressions failed before the fix; the
patched actual comparison and existing domain regressions passed 22 tests in a
lightweight Kotlin host harness. This incremental check uses the previously
compiled domain dependencies and does not replace the integrated Android gate.
Canonical fast validation also passed with 631 host tests after local generated
references/inventories were refreshed; integration owns the final generated files.
