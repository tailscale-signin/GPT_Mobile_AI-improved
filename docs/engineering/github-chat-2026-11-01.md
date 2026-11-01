# GitHub chat privacy, presentation and diagnostics repair

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Base: `main`, `2109afb8dbfd3e8913ad206c420aeb64bb39c971`
Branch: `fix/github-chat-privacy-performance-2026-11-01`

## Evidence and changes

Reviewed all 6,795 records in the supplied build 133 diagnostic export. One unique application OOM is duplicated across critical/current logs. Its stack runs through encrypted diagnostic batch decoding during retention cleanup. The export also includes 2,416 blocking-GC wait records, severe frame skips, log-recorder monitor contention, and an ASUS framework finalizer permission error. The ASUS hidden-setting failure is platform code and remains outside this patch.

- Retention cleanup now counts encrypted file sizes without decrypting batches. Batch count, snapshot bytes and decoded event count are bounded, and omitted snapshot batches remain reported. Vault reads validate physical size before allocation and decrypt without copying ciphertext. Log formatting no longer holds the persistence monitor; entering private mode does not wait for disk writes.
- GitHub work excludes automatic general-memory recall and general-memory tools. If existing history contains incompatible local-only context, a request projection drops the whole derived assistant history and old recovery state before preparing a fresh repository turn. The current user request remains intact and saved messages are preserved. Repository memory can return only to its bound chat and GitHub connection; local inference does not authorize disclosure of personal memory.
- A saved conversation mounts from its pinned scope before remote workspace refresh. Routing subscriptions start at app navigation startup. Tools and workspace mutations retain their existing authorization/freshness checks.
- Home rows now recognize GitHub contexts and use a themed GitHub outline, filling it for unread replies. Creation, profile selection and Plugins use the same outline.
- The idle autonomous-profile warning is removed from the composer-adjacent area. A short profile notice appears in the header. Heartbeats omit the user prompt, arbitrary gateway messages, tool counters and elapsed time. Distinct activity updates append as independently fading lines; the working indicator follows the response updates and decision/job content.

## Validation

- Host validation: `bash scripts/validate-local.sh fast` (285 Python tests plus policy, resources, secrets, dependency and regex checks).
- Isolated Kotlin behavior suite compiles the actual journal, privacy policy/projection and GitHub progress sources with platform boundary stubs. Covers retention without decryption, bounded snapshots, private-context projection, same-connection reuse, heartbeat privacy and presentation.
- Added reducer behavior regression for append-only status lines and Android instrumentation coverage for oversized vault files and metadata reads without a key.
- Changed Kotlin files formatted with ktlint. No Android device/emulator is available here. Local Gradle bootstrap cannot download its distribution through this runner's Java network path; full Android compilation and device timing/animation checks are not claimed.
