# Diagnostic log recovery — October 15, 2026

Source: app-diagnostics-9051658385008735988.jsonl (7,079 records).
Observed build: 0.9.52.0, code 121, commit d11493036bf08663a1fd7082ed21d54b9b5288d9.
Implementation base: main at 501e2e9a4fcbab44ec1a39e0b19d73c884d95e9b.
Branch: fix/log-runtime-recovery-2026-10-15.

## Changes prepared

- Local Assist settings test now passes the admitted runtime context and runtime fingerprint into its coordinator. Previously its default 16,384-token planning capacity could feed an oversized task to a 4,096-token engine.
- Evidence chunk and query planning now use the native planner's conservative UTF-8/2 estimate, with 768 tokens reserved for worker instructions, capability text and template overhead. Every source record remains either in a chunk or explicitly unprocessed; no evidence is silently truncated.
- A native worker death, native quarantine, unsupported backend, memory admission failure, or engine load failure now stops subsequent benchmark cases even when quality failures are configured to continue. Remaining cases are marked skipped rather than producing repeated failed load attempts.
- Readiness hardware, configuration and invalidation observers restart after a five-second backoff on an ordinary exception. Cancellation still propagates. Logs identify the observer and application stack location without including exception payloads. A failed hardware observer clears hardware eligibility until it recovers. This repairs permanent observer loss; the original NoSuchElementException's precise origin was absent from the uploaded log and is not claimed diagnosed.
- Memory enrichment timeouts no longer receive three automatic generation retries. A five-minute cooldown shared by the loaded runtime fingerprint defers other pending source messages. Workers recheck foreground activity before native dispatch. Source references remain persistent and ordinary deterministic memory capture remains available. The eight-second inference budget is unchanged.
- Native quarantine errors now direct users to the existing Local AI profile menu's Verify load action. Protective quarantine is retained until a deliberate isolated verification; updates and ordinary retries do not clear crash evidence.

## Complete issue disposition

| Log observation | Disposition |
| --- | --- |
| Local Assist test LOCAL_CONTEXT_EXHAUSTED | Corrected test wiring and chunk estimation; added planner regression tests. Device rerun required. |
| Repeated native worker stops and CPU/GPU quarantine | Benchmark fan-out stopped; explicit recovery guidance corrected. Underlying native compatibility cannot be established without exact artifact and worker crash evidence. No assertion that the binary initialization fault is fixed. |
| Unsupported backend, CPU-only artifact | Preflight correctly rejects an incompatible selection. Select the declared CPU backend or a compatible export. Automatic permission/backend changes were not introduced. |
| MEMORY_ADMISSION_REJECTED with 4,096 MB reserve | Existing admission accurately reports insufficient available memory and offers the recommended 768 MB reserve action. Log later confirms the reserve changed to 768 MB. Reservation safety retained. |
| Readiness OBSERVER_DEGRADED / NoSuchElementException | Observers recover with backoff and record origin for a future failure. Exact triggering stack was not exported. |
| Repeated eight-second memory timeouts | Timeout retry fan-out reduced and shared runtime cooldown added. Successful enrichment on the phone remains unverified. |
| TOOL_BATCH_OVERSIZE | Existing runner rejects the full batch before dispatch and tries bounded tool-free finalization. Existing storm/continuation tests cover this path. Do not remove limits or execute a partially rejected batch. |
| Repeated search argument limit | Existing per-response limit prevents duplicate work; other authorized sources remain available. |
| DISPATCH_POLICY_CHANGED after preparation | Existing authorization guard correctly blocks stale permissions/budgets. Existing preparation replay fence permits rebuilding only before physical dispatch. Preserve this boundary. |
| Research ACQUISITION_SKIPPED | Explicit network-tool and Deep Research profile settings were disabled. Acquisition must not bypass these controls. |
| web_search errors with private payload redacted | Insufficient retained error detail to establish provider cause. Do not infer a server error or expose private payloads. Requires a structured provider error/device rerun. |
| Model download socket abort | Existing resumable download machinery retained. Network abort alone does not establish a code defect. |
| ASUS asus_leak_detect_proj SecurityException/finalizer | Vendor framework stack; application must not request hidden settings access. A vendor firmware fix is outside this repository. |
| WorkManager onNetworkChanged, inactive IME callbacks, GC references, denied chip property, feedback tensor warning | Platform/runtime messages, not evidence that each is an application defect. No broad suppression or hidden permissions added. |
| Rendering jank, EGL fence wait and 7.7-second main-thread stall | Confirmed symptoms, but no frame trace identifies the responsible application operation. Requires device Perfetto/frame profiling; no claim of complete performance remediation. |
| Historical process exits with unknown cause | Retain uncertainty. Exit reason/status does not by itself prove model corruption or out-of-memory. |

## Validation

Passed locally: 116 Python script tests; Android regex scan (536 literal patterns, 34 dynamic patterns skipped); Android resource preflight (28 XML files); committed Room schema validation; scope inventory and branch PR gate; git diff whitespace check.

Added JVM regressions: native planner fit for 2K/4K/8K contexts and multilingual evidence; oversized/unknown-context retention; observer recovery/backoff/cancellation; full-suite benchmark termination for native and admission failures; timeout retry policy.

Android unit tests and lint could not start: Gradle 9.8.0 download failed with Network is unreachable. This environment has Java 17 rather than the required Java 21 and no prepared Android build toolchain. Added JVM regressions are not reported as executed.

The existing signed-release completion gate fails on main: 48 tasks and 107 acceptance cases lack completion/pass evidence. Do not edit their evidence statuses to manufacture a green release. Exact device validation must precede a production release.

## Remaining delivery steps

1. Obtain explicit destination approval required by automatic publication review, then push only the reviewed source/test/document changes to tailscale-signin/GPT_Mobile_AI-improved and create a draft PR targeting main. Never include the uploaded log or credentials.
2. Run complete PR CI, investigate genuine failures, and run device validation for the unresolved native/performance observations above.
3. Merge only after the relevant checks pass; verify main at its exact merged SHA.
4. Choose the next unused version and increment versionCode after the release evidence gate passes, then use the signed release workflow. Do not publish the existing version again or bypass signature/provenance/device-evidence checks.

No GitHub PR, merge, or release has been completed by this local patch.
