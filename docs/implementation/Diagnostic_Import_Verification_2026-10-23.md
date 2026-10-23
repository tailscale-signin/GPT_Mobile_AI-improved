# Diagnostic import and verification repair

Source: `app-diagnostics-3229024066493516407.jsonl`, app 0.9.56.0 (126), commit `d9db21d0823e03b547c42d1b52f012ec17af42a3`. Reviewed 3,905 records: 1,433 events and 2,472 continuation records. Continuations and repeated symptoms are not separate bugs.

## Changes

- Four local import failures hit the native-path readability probe. A document-provider stream can be readable while reopening its descriptor through a filesystem alias fails. Add an explicit **Copy to app** option for individual `.litertlm` and `.gguf` files. The persisted worker reads the granted stream into the existing validated, content-addressed installers. Linking remains the default and never silently copies weights. Folder imports remain links. Copying consumes separate storage and leaves the original unchanged.
- Keep progress, cancellation, stable job staging, checksum validation, format checks, and grant cleanup. Add a 16 GB streaming bound to the LiteRT importer. Replace the dead-end linking error with the available copy action and log the provider authority and exception classes without the document path.
- The log contains `no such module: fts5`. Existing code catches this and falls back, so this is not evidence of memory loss. Probe registered SQLite modules before compile options; choose FTS4 or LIKE when FTS5 is known to be absent. Preserve actual creation probes when capability diagnostics are unknown, and reuse existing indexes.
- A Combined verifier with zero visible output and a DNS failure was reported as completed after recovering tool output. Require a complete provider transport for verification, like other structured stages. Retain collected tool evidence independently, but do not label it a completed verification response.
- Simplify import labels, explain the storage tradeoff in the review dialog, and use mode-neutral background notification text.

## Regression coverage

- Default link routing without opening a stream; explicit stream-only LiteRT copy through the real installer; source checksum preservation; progress and staging cleanup; GGUF routing; stream closure and cancellation propagation; unsafe filenames; manifest-folder copying, cancellation, and interrupted-job staging cleanup.
- FTS4 selection from registered modules, portable LIKE fallback, and unavailable module diagnostics.
- Combined verifier and structured-role transport requirements, while retaining ordinary delegate recovery behavior.

## Validation and limits

Local validation: 154 Python script tests passed; resource/icon/QNN-provenance preflight, Android regex checks, reliability inventory checks, Kotlin formatting, and whitespace checks passed. Full Android unit tests/build/lint are delegated to PR CI because the local Gradle distribution download is unavailable.

The log also records BridgeNode/Kilo/AI Horde DNS failures, Cehpoint 429s, a Hugging Face hosted `NoneType.choices` failure, Vireonix 502, and frame skips. This change does not claim to restore external services or establish the cause of the frame skips. Test both link and copy on the affected phone/provider, then use **Verify load** for native execution. Existing GGUF architecture/runtime restrictions still apply. No release or device validation is claimed.

## PR integration

The reconciled import flow retains the native-access precheck and explicit file/folder copy support from PR #734, together with the bounded stream import, memory-search capability detection, and Combined verifier transport handling from PR #733. Worker input accepts the newer mode string and the earlier copyToApp boolean so already-queued copy choices remain intact. Explicit mode strings take precedence. Regression tests from both branches are retained; folder copying is covered by the folder staging tests rather than rejected.
