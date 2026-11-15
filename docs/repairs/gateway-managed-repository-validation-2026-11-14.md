# Built-in Windows repository validation

Requested follow-up to PR #1122, on its existing branch
`fix/github-context-and-voice-reliability-2026-11-14`.
The source baseline for this implementation is
`cef330f93ed2af9fae334b38acc5eb25b087159d`.

## Delivered behavior

GitHub settings now contains Connection and Repository Validation subtabs. The
validation form owns repository, mirror folder, profile and timeout inputs. Its
advanced area owns image selection/repinning, approved argument arrays and
acceptance/CI mappings. Prepare & save creates or verifies a dedicated bare
mirror, prepares a real registry-digest-pinned Python image and saves a managed
internal snapshot. No user-authored external JSON file is required.

Legacy selected policies migrate on explicit successful preparation, preserving
other repositories, acceptance mappings and unknown host policy fields. The
original file is not changed. Both launcher stages keep the existing selector
contract. Immutable snapshots preserve running jobs' original approval; restart
uses the existing active-work guard. Failed setup, cancellation, corrupt settings
or concurrent writes never silently activate defaults or bypass admission.

Git runs with hooks, ambient configuration and interactive credentials disabled.
The selected GitHub credential is scoped to the child's HTTPS environment rather
than a saved remote URL. Preparation and execution use the same isolated local
Docker context and resolve installed host executables before stripping child
PATH. No repository code runs on the host during setup. Normal validation keeps
its existing offline, unprivileged, read-only-root container limits.

The recommended source profile checks syntax, schema drift and checked-in CI
policy with Python's standard library. It does not invent arbitrary feature
acceptance. Per-task implementation still needs real behavior producers; remote
required checks, review rules and merge authorization remain independent.

## Local observations

The Gateway sources were extracted from the successful Windows artifact for the
baseline above. All 297 original source-manifest hashes were verified before
editing; the resulting package inventory contains 304 source files.

| Isolated test suite | Result |
| --- | --- |
| Managed repository setup / first-format migration | 31 + 3 passed |
| New native Tk settings UI, with Linux Xvfb | 10 passed |
| Existing desktop validation-policy compatibility | 13 passed |
| Existing isolated validation/execution/report tests | 16 passed |
| Existing engineering admission/preflight | 10 passed |
| Existing engineering retention and selected-execution tests | 26 passed |

Total focused test invocations: 109 passed. Some existing suites import shared
test classes, so this is not a unique overall product-coverage count. Setup tests
mock external downloads and Docker responses; the Git mirror and subprocess
fixtures execute real local processes. Native UI tests render actual Tk windows,
including minimum-width layout and cancellation of the current worker.

Package hashes and Python syntax compile checks passed. The broader local daemon
suite did not complete within the local execution window, and no full Android,
Windows installation or live Docker success is claimed here.

## Concurrent-source reconciliation

The same branch advanced through `6e1ee03687cebd87975d92a9913ba3fd2db8742b`
while the feature was being tested. The integration preserves its app inventories,
managed-policy APIs and shared `validate_policy_entry` parser. Its first mutable
managed settings format migrates into immutable snapshots; explicit disable and
corrupt-selector failure cannot fall back to that older file. The 109 focused
invocations above were rerun after this reconciliation. The enhanced panel
replaces the overlapping form, retaining its editable commands and mappings.

## Final-head requirements

Gateway stability adds an explicit real-Docker job. It builds an exact-checkout
mirror, resolves the real default image digest, loads managed settings and runs
the default source commands inside the canonical offline validation sandbox.
The receipt includes source SHA, image digest and command outcomes. This is
source-check qualification, not per-feature behavior acceptance.

The existing Ubuntu/Windows and Python 3.12/3.13 Gateway matrix, Windows installer,
Android, security and applicable PR checks must qualify the final published head.
Prior baseline successes and the local observations above do not authorize merge.
