# Built-in Windows repository validation

This feature stays on PR #1122 and its existing branch,
`fix/github-context-and-voice-reliability-2026-11-14`.
The combined correction preserves the published feature at
`fdc808976f1df2024856dac01ffd2a0a1dddbda2` and its subsequent inventory-only update.
It retains the repository/profile setup, private-repository connection, immutable
image pins, migration, read-only readiness and real-Docker CI producer while
reconciling every editor through one revision-checked persistence contract.

## User experience

GitHub settings contains **Connection** and **Repository Validation** subtabs.
The normal form owns the enable choice, repository and validation profile.
Advanced validation settings contain the mirror folder, command timeout, image selection and repinning, approved
argument arrays, exact CI check names, acceptance mappings, parallel groups,
objective templates and draft-repair requirements. A normal setup does not require
an external policy file or terminal command. Existing custom contracts retain their
unknown valid host fields and other repository entries.

Preparation explicitly creates or refreshes a dedicated bare GitHub mirror and
resolves an installed registry image to a real SHA-256 digest. Existing approved
image pins remain fixed until the user asks to refresh them. Read-only setup checks
inspect the existing mirror and image without fetching, pulling, saving or running
repository commands. Both paths report prerequisites separately from repository
tests and task acceptance. Cancellation and closing Settings stop pending setup
work; a completed mirror fetch or image download can remain cached.

Recommended source checks parse Python source, check diagnostic-schema consistency
and check repository CI policy. They need only Python's standard library inside
the validation image. Their configured remote CI names do not claim that Android,
CodeQL, Windows or required GitHub checks ran. Implementation admission still needs
real behavior producers and explicit acceptance requirements. A generic repository
must use its own Custom commands and offline image dependencies.

## One selection and save contract

Every cooperating Settings writer uses the same cross-process editor lock and a
revision derived from the observed selector, selected policy, draft pointer and
draft bytes. A stale window cannot replace another window's new repository or
contract. The editor preserves unsaved values when a save conflicts and offers an
explicit reload. Invalid on-disk state remains visible and repairable.

A valid first-generation managed policy remains selected only when no schema-1
selector and no inherited explicit selection exist. This preserves that earlier
format's actual Save/startup behavior. Once a selector exists, its empty value,
external path or immutable managed path is authoritative. Invalid explicit state
fails closed; the mere presence of an old managed file cannot override it.

Saving writes a content-addressed immutable policy revision under
`data/validation-policies/`, retains the editable draft reference and finally
atomically replaces the existing schema-1 selector. The selector replacement is
the activation point. A failed replacement preserves the old active policy bytes.
Disable writes an explicit empty selector while retaining valid drafts, original
external policies, images, mirrors and prior revisions. Old policy revisions are
not garbage-collected while saved work or a rollback reader could reference them.

The unchanged selector format supports launcher and rollback readers that honor
that selector. The historically buggy intermediate build which prioritizes the
old mutable file cannot be made to obey newer disable semantics by changing the
newer code. Compatibility does not claim otherwise. Restart remains governed by
the existing active-work guard; setup never cancels engineering jobs.

## Host and execution boundaries

Git uses the selected repository's fixed GitHub HTTPS origin, disabled hooks,
disabled ambient Git configuration and noninteractive credentials. Existing
mirrors are checked before fetching. Local URL redirects, includes, custom HTTP
settings and worktree configuration are rejected; a worktree-scoped URL rewrite
cannot bypass the effective-origin check. The selected GitHub credential is scoped
to the Git child's HTTPS environment and is never saved in a URL or policy.

Preparation and actual validation resolve installed Git/Docker executables before
stripping child PATH and use the same isolated local/default Docker endpoint.
Setup does not start a container or execute repository code on the host. Actual
validation retains its offline, unprivileged, read-only-root container limits.
Subprocess output and duration are bounded; Windows process ownership and Unix
process groups provide cancellation cleanup.

Desktop form validation can parse a not-yet-prepared empty image through an
explicit opt-in parser argument. Every runtime policy loader and validation caller
keeps the strict default requiring a digest. Commands, acceptance mappings, groups,
templates and repair requirements use the same runtime grammar. This does not
relax any execution, CI or acceptance gate.

## Validation and final qualification

The combined local run passed **84 isolated Python suites: 1,198 cases collected,
1,171 passed and 27 declared platform/environment skips**, followed by all seven
browser tests. The 50,000-event, 782-commit restart/digest-expiry stress case is
included in that Python total. Canonical fast checks passed 510 tests, and the
current-base authentication suite passed 111. These authentication results do not
qualify separately composed Android/reporting components; the complete PR must
rerun its final checks.

The focused settings/backend suites are included in the broad Gateway run, not
added to its count. Five display-independent UI/parser/owner-lock cases passed;
24 native Tk interaction cases were skipped because this local environment has no
X display. Two Windows-only desktop cases and one unavailable LAN IPv4 environment
case account for the other three skips. Windows native layout and actual Docker
execution remain final CI gates. The later canonical Windows path assertion,
smoke-image capture/copy steps and documentation wording were checked separately;
they do not alter the runtime source exercised by the full run.

Earlier prototype results and the published feature's 109 focused test invocations
are historical observations. They are not summed into this final local coverage.

Gateway stability includes **Managed repository validation (real Docker)**. It
installs the pinned Gateway host dependencies, creates a mirror of the exact CI
checkout, pins the real default image and executes the three recommended source
commands in the canonical offline sandbox. The retained receipt includes source
SHA, image digest and command outcomes, with no feature-acceptance claim.

The final source must pass that job, all four Ubuntu/Windows and Python 3.12/3.13
Gateway jobs, the packaged Windows smoke including both nested GitHub tabs at the
minimum window size, and all applicable Android, report, build-download, security
and PR checks. Historical partial-head successes do not authorize merge.
