# Runtime, research and navigation repair — October 13, 2026

Baseline: `25e3c1d6b3ab4f90d1f8e1d6660cf540b98badf7`, 0.9.52.0 (121).
Evidence: user-provided diagnostics, 4,155 JSONL records; design: Unified AI Runtime & Local-First Optimization, October 13. No private log or source payloads are committed.

## Incident findings and changes

| Finding | Implementation | Acceptance boundary |
| --- | --- | --- |
| Back waited for draft persistence, temporary-run cancellation and archive compression; pop fade was 500 ms | Queue captured exit work in an application-owned scope; navigate immediately; await pending save asynchronously on reopen; 160 ms pop and 120 ms nested fade | Verify navigation latency, process death and archived/temporary chats on device |
| Main-thread IndexOutOfBoundsException in ChatViewModel | Match persisted rows by identity after suspension; tolerate missing window rows; guard loading slots; rebuild provider history from persisted messages | Obfuscated trace identifies the class, not the exact source site; physical replay is required |
| Chat grouping ran on main during streaming | Move persisted-message grouping to Default dispatcher | Device frame timing remains unmeasured |
| Two simultaneous Combined synthesis run IDs for one turn; both returned 4,096 reasoning tokens and no visible output | Remember dispatched contribution snapshots across delayed Room emissions; allow changed contributions/explicit retry; disable optional reasoning for editorial synthesis | Providers may ignore request settings; no claimed guarantee against provider-side caps |
| Local ENGINE_INITIALIZATION incompatibility repeated in benchmark and chat | Failure cache keys use native engine configuration, independent of workload labels; retain native cause; publish FAILED state; text-only requests do not initialize optional vision; explicit Verify load action in Local AI | Missing-tensor model cannot be converted by retry. Matching installed artifact/backend and device execution still require verification |
| Research toggle could have zero depth or disabled profile/plugin prerequisites | Enabling from zero chooses a useful depth; show exact blocking reason in chat; separate web-only permission from academic consent | Explicit network and plugin restrictions remain authoritative |
| Modern MCP discovery ignored passive retry time | Enforce cooldown before rediscovery; explicit refresh and changed credentials/configuration remain recovery paths | Cannot repair external Pipeworx availability or missing credentials |
| Different URLs hit one reader-name repeat limit | Count source-reader repetition by canonical arguments; independent URLs remain eligible under the existing run budget | Identical reads still stop after three; action tools keep their name-based limit |
| AppCrash at info severity and Rendering.jankFrames exported as informational | Classify crashes as critical and nonzero frame stalls as performance issues | No change to privacy/redaction rules |

## Runtime design alignment

The baseline already contains execution policies, parent accounting/budgets, warm local assistance, evidence handoff, efficiency settings and runtime qualification components. This repair extends those components; it does not add a second runtime, remove crash protection, enable arbitrary NPU fallback, or claim the design's device/paired-evaluation gates have passed. Verify load is an explicit, isolated load check and leaves hardware qualification unproven. Native runtime dependency pins are unchanged.

## Validation

- Focused JVM harness: 12 tests passed for exit scheduling/order, moved conversation windows, duplicate synthesis admission, diagnostic classification, incompatible-load cause retention and per-source reader limits. The harness uses small Android-independent boundary stubs; full Android compilation is delegated to PR CI.
- Android regex preflight and resource validation passed.
- Repository Python test suite passed (see PR CI for full outputs).
- Kotlin formatting passed for changed sources.
- Local full Gradle invocation could not download the wrapper distribution due to the environment's Java network access; PR CI must compile and run the complete suite.
- Added full-suite tests for modern MCP cooldown and research readiness.

## Device acceptance still required

Use the user's ROG Phone 9 Pro and the failing installed model. Record model digest/edition, selected backend, runtime version and exact failure stage. Test empty draft, populated draft, archived chat, temporary chat, active streaming, rapidly reopening chat, large histories, Combined mode, web-only research, paper consent off/on, unavailable engine recovery and explicit Verify load. Require useful text from local inference and research evidence actually read; do not infer success from metadata or a green build. Compare Back gesture-to-first-frame p50/p95 before and after under the same release configuration.
