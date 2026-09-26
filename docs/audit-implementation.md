# Audit implementation and review notes

Branch: `feat/audit-reliability-memory-workspaces`, based on main `c5ace46` (includes PR #502); synchronized with main `72097d6` and its existing 0.9.14.0 version update.

The changes below implement the 22 findings from the September 26 repository audit. This is a feature branch, not a published release. Automated validation results are recorded at the end; hardware-dependent checks remain explicitly separate.

| Finding | Implemented behavior | Principal validation |
| --- | --- | --- |
| F01 Backup privacy | Default backups exclude credentials and Fact Vault. Each has a separate selection and requires an encryption password. Restored queued work is paused and interrupted actions cannot resume themselves. Derived search indexes are rebuilt. | Backup round trip, rollback, sentinel-secret exclusion and password requirement tests. |
| F02 Artifact sandbox | Scripts start disabled. Explicit interactive mode still blocks external network, file/content access, frames, navigation and form submission. Content changes reload the document; disposal destroys its WebView. | Compile/lint; Chromium network and lifecycle checks require a device. |
| F03 Gateway recovery | HTTP 202 is pending, cancellation propagates, recovery uses the current profile credential and matching destination/job, and a transaction refuses stale or canceled revisions. Private Wi-Fi/VPN can trigger recovery. | Mock HTTP contract and Room recovery tests. |
| F04 Shared tool limits | Remote and native tools share count, concurrency, timeout and aggregate UTF-8 output budgets. Approval waiting is outside execution timeout. Every tenth completed call requests a public progress update. | Shared-budget, UTF-8, cancellation and runner approval-delay tests. |
| F05 Delegation limits | Request output ceilings override provider preferences. Delegate requests disable tools and requested reasoning. Native output limits are distinct from context allocation. | Serialized requests for all remote adapters; native runtime configuration tests. |
| F06 Memory provenance | Automatic capture and the memory tool receive the original user text, never appended attachment text. Assistant/tool output is not learned as a user fact. | First-person attachment regression test and existing memory-tool tests. |
| F07 Durable queue | Room owns accepted prompts, attachments, destination profiles and stable IDs. Users can edit, reorder, pause or remove entries. Dispatch consumes the entry in the same transaction that creates the turn/run. | Queue reload, stale-edit/pause and duplicate-dispatch tests. |
| F08 Release gates | AAR checks and error-blocking lint are restored. Publication requires validation of the exact SHA, a fresh version/tag, matching APK/AAB identity and signing certificate, checksums and provenance. Actions and bundletool are pinned. | Compatibility, unit and lint gates; release scripts reviewed. A signed upgrade smoke test requires the release key/device. |
| F09 Companion prototype | The incompatible Node MCP prototype is retired and exits without starting a listener or executing tools. | Direct startup/exit check. |
| F10 Write policy | Each MCP connection has read-only, ask-for-writes or trusted policy. Only explicitly trusted read tools qualify for sharing. Approval shows a redacted argument preview; durable decisions prevent the same call ID from dispatching twice. Interrupted writes have an unknown outcome. | Room approval, duplicate dispatch, policy and redaction tests. |
| F11 Document retrieval | Persistent project/chat document chunks use BM25, bounded source excerpts, private citation links, version replacement and deletion tombstones. Explicit reimport can restore an intentionally removed document. | Scoped index/deletion tests and Room migration. |
| F12 Fact Vault | Multiword and selected multilingual extraction, provenance/confidence, manual editing, personal/project scopes, location corrections, relevance/recency ranking and scoped forgetting. Master memory preference is stored separately from encrypted fact contents. | Scope, correction, retention, recall opt-out and vault reload tests. |
| F13 Context budgeting | System prompt, memory, documents, history, tool schemas/results and output reserve share a visible estimate. Fixed six-turn windows no longer hide extra truncation. Model discovery can offer an explicit per-profile context ceiling. | Budget, omission and model-discovery tests. |
| F14 Local setup | Connection doctor reports actual destination, auth/model discovery and Gateway capabilities; an explicit fixed benchmark checks streaming and client cancellation. Single-use expiring pairing links/QR codes preview both pairing and model destinations. | Endpoint classification and pairing contracts; pairing server HTTP integration. |
| F15 MCP interoperability | Paginated resource/prompt browser, explicit prompt previews, bounded image/audio cache and private viewers, user-entered elicitation forms, structured-output checks and visible discovery errors. Connection checks carry timestamps. | Existing initialization/list/call protocol tests plus form/output contract tests. |
| F16 Secret URLs | Query-bearing MCP endpoints are stored in SecretVault and masked in connection rows. Diagnostics redact URL values/userinfo and sensitive headers; release request logging is off and debug logging respects diagnostics choice. | Synthetic sentinel tests for persistence, execution and previews. |
| F17 Long chats | Live message overlays update independently of 500 ms durable checkpoints; terminal state always flushes. Chat loads the latest 40 turns with an older-history action. Room FTS replaces general message LIKE search. Saving/exporting a loaded window preserves older messages. | Window, FTS edit/delete, recovery and export tests; no device speed claim. |
| F18 Ownership | New queue, context, memory, accounting and permission owners have narrow contracts. Unused alternate renderers were removed after caller checks. The chat timeline has one production renderer. | Compilation, caller search and regression suite; further module extraction remains incremental. |
| F19 Accessibility | Theme-colored expand/collapse controls expose state, message actions have a visible alternative to long press, queue operations have buttons, settings are searchable, and new controls use string resources. Missing translations are visible warnings, not globally disabled checks. | Resources/format/lint; 200% font, landscape and TalkBack require device review. |
| F20 Voice | Opt-in foreground recognition → generation → speech loop, on-device recognition/offline voice by default, explicit installed-service alternative, interruption, audio focus and lifecycle cleanup. | Compilation and existing coordinator tests. Bluetooth, phone-call and physical microphone tests remain necessary. |
| F21 Sign-in/tuning | Hugging Face uses public build-time OAuth configuration or the existing token fallback. Fixed benchmarks show measured first text, total time, estimated/reported throughput, sampled client PSS, thermal state and actual native backend/fallback. | Build configuration and benchmark path reviewed; real performance must be measured on each device/model. |
| F22 Accounting | Primary, delegated and synthesis model requests have invocation IDs linked to the parent run and shared turn budget. Usage is reported when available and otherwise labeled estimated. Reservations are transactional. Shared tool deliveries do not rebill original execution telemetry. | Concurrent ledger reservation and provider usage tests. |

## Chat behavior requested after the audit

Reasoning, tool entries, answer segments and follow-up segments render in their stored arrival order, in one downward timeline. Earlier entries remain where they arrived. The separate pinned activity/flight-recorder presentation has been removed from chat.

Reasoning and tools use the theme's upward chevron when collapsed and downward chevron when expanded. Each entry has its own expansion state. A public progress bubble is visible while generation starts and after every ten distinct completed tool calls, including failed calls. Models are instructed to write a brief completed-work/next-action update. A factual app activity summary fills the checkpoint if the model omits one; it is not invented private reasoning. When the model supplies the adjacent public update, it replaces that fallback without moving earlier events.

Old saved conversations without event ordering show a legacy-order notice. Their exact historical ordering cannot be reconstructed.

## Authoritative owners

- `AgentRunCoordinator`: lifetime, cancellation, in-memory streaming and durable checkpoints.
- `DurablePromptQueue` / `AgentPersistenceDao`: queue scheduling and transactional dispatch.
- `ContextBudgetService` / `InvocationLedger`: request context and shared turn accounting.
- `ToolExecutionBudget` / `ToolApprovalManager`: execution limits and user authority.
- `FactVaultRepository` / `KnowledgeWorkspaceRepository`: personal facts and scoped document sources.
- `AssistantChronologicalContent`: chat event presentation. Persisted `AssistantTimelineItem` order is the source of truth.

Legacy utilities that still have consumers were not silently redirected or deleted. None of the new paths depends on the retired Node server.

Queued prompts wait for both persisted and live generation to finish. In combined mode, a transaction also prevents the next prompt from overtaking the pending synthesis. Synthesis is still owned by the chat view model: if you leave before it is scheduled, reopening that chat resumes the combination before its queue advances.

## Local server pairing

On the computer, use its private address that the phone can reach:

```sh
python3 -m pip install qrcode
python3 scripts/pair_local_server.py \
  --host 192.168.1.20 --url http://192.168.1.20:8080/v1 \
  --provider LLAMA --model my-model --qr pairing.svg
```

Scan the SVG with a QR-capable camera, or paste the printed link into Settings → Tool connections → Pair a local model server. The phone previews the destination before fetching and previews the received model configuration before saving. Enter any API credential separately in AI Platforms. The profile starts disabled.

The helper binds only to the explicitly supplied private interface, rejects browser-origin requests, checks a random 256-bit code, accepts one configuration request and exits after success or expiry. Default expiry is three minutes, maximum ten. It shares no provider credential or conversation. HTTP pairing is for a trusted LAN or an encrypted private network such as Tailscale; the one-time code does not encrypt an untrusted LAN. Omit `--qr` to use a link without the optional QR package.

## Hugging Face configuration

Set `HF_OAUTH_CLIENT_ID` and `HF_OAUTH_REDIRECT_URI` as Gradle properties or build environment variables to a registered public client and its exact redirect URI. Both are public app configuration; no client secret belongs in the APK. Without a registration, the existing access-token flow remains available and the OAuth guard explains the missing configuration.

## Validation and limitations

Validation is in progress; replace this paragraph with the final command outcomes before publishing for review.

The context/token budget is an estimate unless the provider reports usage. Remote servers can enforce different model limits. Benchmark setup time includes queue/loading/prefill, and client PSS does not measure a remote server's RAM. No hardware performance percentages or hands-free/full-duplex claims are made.

MCP forms support bounded primitive fields; unsupported constraints or secret-requesting forms are declined. Structured outputs support common object/array/type/enum/range/composition contracts, while external schema references and unsupported constraints fail explicitly. This is not a complete JSON Schema implementation. Resource/prompt previews are never inserted into a chat automatically.

Device review should exercise: TalkBack and 200% fonts; portrait/landscape; a conversation with at least twenty tool calls and interleaved answer/thinking chunks; process death with queued attachments; native/cloud voice with interruption, Bluetooth and phone calls; sandboxed HTML attempting fetch/frame/file access; signed upgrade preserving chats and credentials. These checks cannot be replaced by a JVM test result.
