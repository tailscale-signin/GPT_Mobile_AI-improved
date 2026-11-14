# GitHub chat motion, voice presentation and open reports — November 14, 2026

## Repository and scope

This work continues PR #1122 on `fix/github-context-and-voice-reliability-2026-11-14`.
The starting head is `a4581f94e40f106ace5f18bc37bdd6763f7dad4d`; the initial `main`
revision is `5d4b4d81c121b36cf01b79fe8162fb78f793f203`. There was one open PR and
25 open non-PR issues at intake, #1101–#1126 excluding the PR itself. The concurrent
updates at `0189cb747b5ca256597ee58f326fbd81162c7efd`,
`7872874d975858e9cff4232270e873448b18c77f` and
`4d89a6847feec2d513f9af472f42a7cebbbb7c7f` were reconciled before publication.
Their scoped-admission regression changes are preserved with the complete
authorized discovery checks. The final update retires its temporary source-capture
workflow and removes two invalid UTF-8 files at incorrect package paths; the
canonical test files remain under `data/agent/provider` and `data/agent/tool`.

The remote inventory contains 748 branch refs. Of those, 384 tips are ancestors
of `main`, one is the current repair PR, and 363 retain other historical or
self-test work. Recent repair branches are already included in `main`. Historical
refs are preserved; this inventory does not claim every historical branch
independently builds. All current changes stay on the existing PR branch.

## Requested chat behavior

The existing top-fade geometry already adds 3% of the current application window
height before IME resizing. It uses the live measured GitHub header height, so
expanding the top bar extends the fade and collapsing restores it. Its retained
navigation and geometry regressions verify this behavior; no second 3% increment
is added.

The common chat gesture handler now recognizes deliberate downward input at the
bottom edge even when the list consumes zero pixels. Reaching the bottom follows
the same anchor transition as the bottom button. Upward input still detaches;
keyboard, layout or viewport changes alone do not reattach it.

GitHub text arrivals now receive the following reading budgets:

| Newly rendered arrival | Duration |
| --- | ---: |
| One-line preview or text arrival | 1,500 ms |
| Two-line block | 2,000 ms |
| Three-line summary | 3,000 ms |
| Larger block | 1,000 ms per measured line |

This supersedes the earlier 1,500 ms-per-line behavior recorded in the preceding
chat/voice audit. Summary publication buffers a complete summary before adding its
timeline item. Ordinary streamed answers are measured as new lines arrive. The
FIFO queue keeps an existing segment's deadline when new output arrives. Width,
font, inset and non-text geometry corrections do not add reading time.

## Voice presentation and caption following

The voice surface uses the selected theme's primary, secondary and tertiary
colors for its orbital stage, soft background, state chip and rounded control
dock. Microphone input bars use the measured input level; orbital motion indicates
the current session state. Effects respect system animation settings, window
focus, lifecycle and the existing frame-pressure guard.

Captions retain the complete text in a stable, scrollable area. Active captions
use the theme accent. Valid engine-provided ranges add emphasis without changing
font weight or rewrapping the sentence. Missing word timing is not synthesized.
The following policy separates automatic movement to the spoken range from a
reader's manual scroll, avoiding the previous accidental detachment after an
automatic upward movement. Earlier text remains accessible and returning to the
bottom resumes following.

## Gateway conversation visibility

Issue #1126 identified GitHub chats that use a gateway but never reached a
successful server submission. The Gateway list previously depended on a persisted
gateway mode, mirror, binding or submission, so those chats could be missing.
Membership now also considers saved gateway selections, verified current
gateway profiles, and GitHub inference profiles bound to a distinct verified
engineering gateway. It uses active profile membership, retains existing offline
records, and excludes archived chats. Gateway-tab conversation bubbles identify
the actual GitHub, research, combined, multi-model or standard chat mode. Opening
a profile-only local chat preserves its mode and draft rather than adopting it as
a frozen server conversation. Existing explicit Gateway conversations still
validate their saved participant boundary. Membership projection runs off the UI
thread and ignores unrelated feature-setting changes.

## Open report dispositions

The latest previously supplied archive, `diagnostics-a199704a-2fe7-4afd-9832-24d1035b97c6-part-1-e6fa218d-8b59-4aaa-8a91-7ce5bfdcab6e.zip`,
was available locally; no additional attachment accompanied this request. All 20
stage digests matched. Its 11 retained runs comprise seven failures, two blocked
runs and two completed runs. These are run outcomes, not a count of independent
defects. Raw reports and private account, session, prompt, endpoint and model
details are not committed.

| Issues | Implemented disposition |
| --- | --- |
| #1101–#1103, #1105–#1106, #1115, #1123 | The preceding commits on this PR admit task-sized GitHub schemas and retain on-demand discovery rather than preloading the full catalog. A regression exercises 5,632-token planning. Genuine capacity limits still reject before dispatch and retain their numeric budget evidence. |
| #1104 | Preserve the typed GitHub admission failure across the standard chat boundary. The supplied archive provides the matching precheck evidence. |
| #1108, #1110 | One bounded retry for transient playback focus failure, with observed focus attempts and terminal playback evidence. Device audio contention still needs matching phone evidence. |
| #1109, #1111–#1113, #1125 | One recorder recreation for a silenced zero-sample start, retaining the actual capture stage and outcome. This does not establish that another Android audio owner is permanently removed. |
| #1107 | Diagnostic follow-up: capture both voice-pack readiness states and scheduler status together. A completed download alone does not establish why voice remained unavailable. |
| #1114 | Stable themed captions, complete text, validated range emphasis, and continued following after automatic movement to an earlier spoken phrase. |
| #1116 | Diagnostic follow-up: retain explicit tool-result budget exhaustion and the spent/remaining resource evidence. |
| #1117, #1119 | Diagnostic follow-up: retain bounded completion-repair and reasoning-only attempt evidence when the provider produces no visible answer. |
| #1118 | Preserve the typed invalid tool-protocol frame failure; the original provider frame remains a provider-specific diagnosis. |
| #1120 | Preserve draft focus across background run changes and retain opted-in keyboard/focus observations. |
| #1121, #1124 | Diagnostic follow-up: preserve bounded sanitized application stack frames and exception type when a terminal Flow failure no longer carries its Throwable. These reports retained UNKNOWN classification; a shared fingerprint alone does not establish their cause. |
| #1126 | Include gateway-backed GitHub chats before successful submission, resolve distinct engineering-gateway bindings, preserve original workflow modes during navigation, and show Gateway-tab mode icons. |

The terminal exception enhancement reuses the existing approved `errorType` and
`frames` fields. It carries at most eight application-package frames using the
existing grammar; it excludes exception messages, URLs, source filenames and
arbitrary external stack text. Capture-boundary evidence does not infer a failed
stage, dispatch state or retry authority. Existing diagnostic opt-in and private
session suppression remain in force.

Issue closure is performed after the complete PR qualifies and merges. Diagnostic
closures identify the evidence added for the next occurrence rather than claiming
an unobserved historical cause was repaired.

## Verification status

The focused scrolling host passed 28 tests with no failures, errors or skips,
including real measured single-line and multiline motion, bottom-edge input,
reflow, queue deadlines and fade geometry. The voice host passed 13 tests using
production caption/stage sources, including two native Compose caption-follow
regressions and one rendered stage. The actual stage image was visually inspected.
The emulator runner passed its 46 host boundary tests. Canonical host validation
passed 507 tests, and the authentication/reporter contracts passed 97 tests. Changed
Kotlin formatting and generated-source inventories passed. Full app and emulator qualification
must be bound to the published head before merging. The existing effective main
rules require `Required verification` and `Analyze (java-kotlin)` with a current
base; no bypass actors are configured.

Physical phone audio, microphone contention, private gateway connectivity and
hosted report-service deployment require their respective environments. A
diagnostic disposition records the evidence added for the next occurrence; it
does not claim a historical external-service or device failure was reproduced.
