# Startup diagnostic fixes — 2026-10-26

Evidence: app-diagnostics-265276125657843409.jsonl, 470 records
(166 events and 304 continuation lines). Installed build: 0.9.61.0 (129),
ca9b20d9e859b88b5333c2e2b7ae1abb0d215ad3. Changes extend branch head
73a4ae88240a7f74dc5bd2b81913ea6be9abe030.

| Observation | Repair or disposition |
| --- | --- |
| Two configured private MCP endpoints on ports 8101 and 8102 time out after 8000 ms | Automatic health discovery now respects the manager's retry cooldown; only explicit checks force refresh. The app cannot make an unavailable gateway reachable. Configured endpoints are retained. |
| A cold catalogue can take longer than the interactive two-second deadline | Health discovery now waits for completion under the existing total catalogue deadline. A pending catalogue is no longer treated as an empty successful advertisement. Interactive tool resolution retains its short deadline and shared producer. |
| Cached schemas can outlive healthy connectivity | Health checks bypass the resolver's unbounded cached-catalogue shortcut, and the manager only reuses its fresh catalogue for health checks when health is CONNECTED. |
| Main-thread frame stalls: maxima 226.139 and 209.241 ms; skipped-frame reports | Connection/settings refresh and probe preparation now run on Dispatchers.IO, including configuration/credential preparation. This reduces one startup source of main-thread work; the log does not prove a single cause for every stall. Device performance remains unverified. |
| ASUS restricted asus_leak_detect_proj read from BinderInternal.GcWatcher.finalize | Framework stack, with no app stack frames. Existing diagnostics already classify the named exception as platform/non-actionable. No private settings workaround or exception suppression is added. |
| Ignored important-while-foreground JobInfo flag | Already classified as an informational platform job hint on this branch. |
| Two Examine worker NO_OP receipts with zero processed items | Not evidence of a failed assessment. No failed Examine job, local model generation, or GPU invocation is present in this export. |
| Legacy MCP discovery fallbacks | Jina, Firecrawl and Pipeworx subsequently connect and advertise 12, 3 and 44 tools. Fallback itself is not a failure. |

Regression tests added to McpCatalogueDeadlineTest cover a catalogue that exceeds
the interactive deadline and an explicit health deadline that reports a normal
failure while preserving caller cancellation semantics. Existing passive cooldown
tests remain applicable. Health timestamps now reflect completion time.

Validation limitation: this workspace has no Gradle/Kotlin compiler or Android
SDK checkout; the connected GitHub file API was used after git transport could
not authenticate. Android unit tests and lint must run in the existing PR CI.
No physical device or private gateway is available here.
