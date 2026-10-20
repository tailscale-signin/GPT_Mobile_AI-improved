# October 20 diagnostic repairs

Source: `app-diagnostics-4794752372377745972.jsonl`, 8,399 records,
app commit `7d100d85c905384a3c04a637d34ca53b1f274131`.
Implementation starts from main `94f3a9825`.

## Native GGUF

The captured Nanbeige model loads and tokenizes 518 tokens, then its first
256-token CPU batch makes no completed-token progress for roughly 109 seconds.
The Android cross-build reproduced a separate concrete defect: CMake's cache
said `-O2`, but actual ARM compilation commands had no optimization flag. The
NDK toolchain shadows cache variables with normal variables. Set both and inherit
a Debug `-O2` target option through every llama/ggml subdirectory. CI now checks
actual Android compile commands and builds ARM64, in addition to host inference.

CPU prefill now starts with one token, doubles after fast batches, halves after
slow batches, and never exceeds 32 tokens per observable native call. Auto uses
up to four CPU threads; explicit thread settings remain honored. Prefix reuse,
operation-scoped cancellation, admission, process isolation and model-owned chat
templates remain in the production native route. Build receipts identify the
adaptive scheduler. A larger timeout alone is not the repair.

## Examine

A configured reviewer UID was unconditionally treated as mandatory independent
review, even when the selected policy was best effort or off. Missing, disabled,
same-model and unresolved aliases blocked all reading with
`REQUIRED_REVIEWER_UNAVAILABLE`. Optional unavailability now logs a warning and
allows appraisal, retaining an unavailable independent-review status. Explicit
REQUIRED policy still blocks before dispatch and requires valid review at commit.

## Combined

The log freezes synthesis prompts between 182,048 and 345,665 characters. Two
multipass problems are corrected: final assembly preserved only PASSAGES_V2
contracts, losing V3 revision identity; and collected inspection reports could
still exceed final context with no recovery. Preserve the frozen protocol and
revision, consolidate whole records with the pinned primary through bounded
text-only calls, and validate exact source-span membership before final writing.
Original inspection checkpoints are retained. Permissions and existing physical
request/root budgets still apply. Incomplete or truncated inventories fail
explicitly rather than being credited as complete.

The log also rejects ordinary nine-call tool batches at an inconsistent eight-slot
stream limit. Parser, recovery and default runner admission now allow up to 32;
argument byte, duplicate-ID, same-tool, total-run and concurrency limits remain.

## Interface

Override Navigation 2.10 predictive-pop transitions as well as ordinary pops with
fades; remove navigation scaling. New Chat reveals a glass panel from the actual
plus-button center over one second without scaling its text, honors disabled
animations, and remembers completion on return. The plus button is opaque flat
theme color. Chat input and down-arrow controls sample a real backdrop even when
menu material is flat. Profile subtitles use the actual primary theme hue at
18.75% opacity (25% less than the previous 25%). Plugin modes are grouped below
built-ins and are absent while their owning plugin is disabled.

## Verification

- Android debug main and unit-test Kotlin compilation: passed.
- Focused unit/integration suite: 38 tests across 8 suites, no failures or errors.
  Includes provider-switch appraisal recovery, a stale optional reviewer, oversized
  corpus consolidation, V3 revision preservation, tool storms, and plugin gating.
- Real host JNI inference: ChatML and Jinja fixtures passed, including adaptive
  prefill, prefix reuse, operation cancellation and reopening.
- Android ARM64 Debug native library: compiled successfully; actual compilation
  commands verified optimized and portable for 18 inference sources.
- Changed Kotlin files pass ktlint; resource preflight and Android regex checks pass.

## Verification limits

Host JNI tests cover real inference with two template fixtures, cold and warm
sessions, bounded adaptive prefill, prefix reuse, cancellation and stale handles.
Android ARM64 builds and compile-command verification cover packaged instructions.
These do not establish performance on the user's exact phone and Nanbeige file;
that hardware/model combination must be retested. DNS outages, provider 429s,
HTTP 403 source access, revoked MCP permission, unreachable LAN MCP endpoints and
the ASUS framework's hidden-settings finalizer exception are external conditions,
not evidence that the native GGUF runtime failed to load.
