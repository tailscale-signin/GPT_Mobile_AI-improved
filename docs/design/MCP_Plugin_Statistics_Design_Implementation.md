# MCP & Plugin Statistics
## Design & implementation specification

**GPT Mobile AI · Universal statistics and health in every tool’s settings**

A consistent, premium settings experience that answers three questions immediately: **How often is this tool used? Is it working? What needs attention?**

Prepared for **Emanuel Melo** · October 12, 2026 · Version 1.0

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Inspected baseline: `main` at `7553e4ff14316f0d28639d5cc0ed24cb59511066`.

> **Design deliverable, not an implementation report.** This document specifies proposed UI, architecture, integration changes, and release gates. No application code was modified, built, or device-tested for this document. Repository observations are tied to the inspected snapshot; proposed file names and performance targets are explicitly recommendations.

### The recommended direction

Build **one local statistics service and one reusable settings experience**, rather than adding separate counters to individual plugins. Every plugin, connection, and callable tool receives its own statistics surface. Retain existing configuration, permissions, and execution behavior.

**Core experience:** lifetime total, successful and failed uses; timestamped availability; success rate; response times; recent activity; useful failure reasons; retries, cache use, and connection diagnostics.

**Engineering foundation:** canonical tool identities, structured execution events, independent persistent aggregates, evidence-based health, and explicit backup and privacy behavior.

---
# 01 · Product decisions & reading guide

### Decisions to implement

**Statistics belong inside each item’s settings.** A user opening Calculator, Memory, Deep Research, a remote MCP connection, or one tool exposed by that connection sees the same recognizable summary. A separate global dashboard is optional navigation, never a substitute.

**A use is not a network request.** Logical tool uses, transport attempts, retries, cache responses, connection checks, and orchestration runs are separately identified. The interface explains which population each figure represents.

**Availability is evidence, not a toggle.** “Enabled” means the app permits a tool; “Online” means a recent connection check or real response supports that claim. A reachable server does not prove every exposed operation works.

**Lifetime means retained lifetime.** Counts survive restarts, upgrades, activity-history pruning, and ordinary chat deletion. The interface states when collection began and whether collection gaps exist.

**Polished, not crowded.** Use tonal cards, consistent rounded icons, clear number hierarchy, a restrained accent treatment, and focused tabs. Avoid dense debugging text, decorative gauges, heavy blur, or perpetual animation.

### Reading guide

| Area | Sections | What it resolves |
|---|---|---|
| Repository fit | 02–03 | Existing foundations, universal coverage, scope boundaries |
| User experience | 04–07 | Navigation, screen blueprints, component and icon specifications |
| Correctness | 08–11 | Counting rules, availability, safe connection checks |
| Engineering | 12–16 | Identity, instrumentation, storage, lifecycle, interface contracts |
| Data stewardship | 17–18 | Retention, privacy, reset, migration and backup |
| Delivery | 19–22 | File-level changes, implementation order, acceptance and performance gates |
| Evidence | 23–24 | Commit-pinned repository references and official technical guidance |

### Release principle

Do not ship accurate-looking counters on only the easiest execution path. Release requires coverage of native tools, both MCP transports, plugin-specific operations, research children, and gateway observations—or an explicit, visible “Not observable” state where the upstream system cannot report a metric.

---
# 02 · What the repository already provides

The inspected snapshot supplies useful execution and health foundations, but they are not yet a universal statistics experience. These are code observations, not results of running the application.

| Existing component | Verified observation | Design consequence |
|---|---|---|
| PluginConfigurationDialog | Shared Material 3 dialog with plugin-specific controls, Save/Cancel, and a scrollable content region capped at 560 dp; no common usage panel. [R1] | Add a shared statistics summary and a roomier details destination; preserve form behavior. |
| McpClientManager | Connection-health StateFlow, authentication and cooldown handling, tool discovery, and SDK/modern transport paths. [R2] | Reuse its evidence and transport lifecycle rather than inventing a competing monitor. |
| ToolExecutionBudget | Admission, authorization, concurrency, timeouts, duplicate prevention, and a Boolean completion callback. [R3] | Introduce structured telemetry at actual execution boundaries; a Boolean cannot describe cancellation or unknown outcomes. |
| AgentToolResolver | Native and remote tools, connection IDs, real names, model aliases, and profile gating. [R4] | Attach stable statistics identity during resolution; never use a display alias as the primary key. |
| Research telemetry | Per-engine HTTP-attempt events and a settings panel with time filters. [R5–R6] | Integrate these as attempt-level evidence, not additional logical uses. |
| ToolEvent | Chat-associated execution events cascade-delete with their AgentRun. [R7] | Keep lifetime statistics independent of chat-owned records. |
| ChatDatabaseV2 | Existing Room database at schema version 37. [R8] | Reuse the repository’s Room dependency; isolate the new store rather than choosing a new library generation. |
| Connection/settings layer | Stable connection IDs, secret handling, configuration validation, and a second UI-level health representation. [R9–R10] | Share identity and project one health model into existing screens. |

### Additional integration cautions

The generic complete-backup file collector excludes some directories but otherwise traverses configured roots. A new statistics store needs an explicit backup policy, not accidental raw-file capture. [R11]

A separate legacy McpToolRegistry also has an execution method. Its reachability must be audited; instrumenting it alone would not establish universal coverage. Its generic exception catch also needs cancellation-preserving treatment wherever the path remains active. [R12]

**Inspection limits:** selected files and relevant portions of large classes were read. A full call-site inventory, gateway producer audit, build validation, and on-device performance measurements remain implementation tasks.

---
# 03 · Coverage & ownership of settings

### Every supported surface receives statistics

| Surface | Required embedded information | Detail behavior |
|---|---|---|
| Native plugin settings | Lifetime uses, success/failure, local readiness, recent use | Open shared detail screen; preserve native controls. |
| Remote MCP connection settings | Connection health, owned-tool totals, last check, tool count | Show every discovered tool, including never-used tools. |
| Individual MCP tool settings | That tool’s own counts, result health, server status, permissions | Add a generic tool-detail route when no dedicated settings page exists. |
| Marketplace plugin settings | Installed-instance statistics and dependency readiness | Include native and connection-backed operations without double aggregation. |
| Composite plugin settings | Public orchestration uses plus a separately labeled child breakdown | Distinguish a research/delegation run from the operations it performs. |
| Profile and chat tool selectors | Compact status and a shortcut to the same detail route | Preserve selected profile scope; do not create another statistics database. |
| Headless or background execution | Same capture contract while no settings screen is open | Screens subscribe to persisted state; opening a screen never creates usage. |

### Define one owner, permit multiple views

Each callable capability has one canonical owning plugin/installation. A connection page, plugin page, and profile page may display the same underlying invocations, but these are **overlapping views**, not independent counts to add together. Global summaries aggregate unique canonical subjects, not displayed cards.

A plugin with several tools shows a breakdown and an aggregate of its directly owned capabilities. Nested calls owned by other plugins appear under “Related activity” or “Child operations,” not as additional direct uses of the parent plugin.

For a non-callable plugin, show **operations** with a small explanation rather than fabricated tool calls. Document each operation’s start and completion contract in the coverage manifest. Merely opening configuration or enabling a plugin is not an operation.

### Preserve functional scope

Do not change execution limits, research depth, retry policy, saved permissions, billing behavior, or available capabilities as a side effect of adding statistics. Unknown health must not newly block execution. Existing security and permission checks remain authoritative.

### Installation and removal

Uninstalled items retain a clearly labeled historical record until the user deletes statistics. Reinstallation is a new instance unless stable identity continuity is proven. Historical records must not retain credential material or automatically reconnect to a removed endpoint.

---
# 04 · Information architecture & configuration flow

### Shared detail destination

Use a full-screen detail route on compact phones and an adaptive detail pane or large dialog on wider windows. Keep the current lightweight dialog as a transitional entry point with the embedded summary and “View statistics.” Do not squeeze charts and diagnostics into the existing 560 dp alert content. [R1]

Recommended route contract: `ToolDetail(subjectKey, profileScope, initialTab)`. The key is opaque; it must not contain endpoint URLs or credentials.

| Region | Content and behavior |
|---|---|
| Top app bar | Back, item title, overflow for export/help. Back preserves list position. |
| Identity header | Consistent icon plate, plugin/tool name, local/remote/connection type, enabled control. |
| Persistent summary | Availability with freshness; lifetime Total / Successful / Failed; visible collection scope. |
| Primary tabs | Overview · Activity · Diagnostics · Configuration. Use scrollable tabs where text does not fit. |
| Contextual action | Check connection, Sign in, Manage permission, or Open connection—chosen from the actual issue. |
| Configuration actions | Sticky Save/Cancel only when configuration has changes; no Save button for read-only statistics. |

### Navigation rules

Overview opens by default from the plugin’s general settings entry. The existing configuration gear opens **Configuration**, but retains a compact lifetime-and-health strip above the form. This ensures statistics are genuinely built into configuration, not merely available elsewhere.

Selecting a metric opens Activity with its filter applied. Selecting a tool row opens that tool’s own detail screen. Selecting a health issue opens the relevant Diagnostics group. A breadcrumb identifies the parent plugin or connection without replacing the tool’s own title.

### Keep editing safe

Hold the configuration draft in a separate state object from live statistics. Statistics refreshes, health changes, tab switches, or background completions must not overwrite unsaved settings. Validate and save through the existing configuration layer. [R10]

After process recreation, restore safe draft fields and selected tab; do not persist secrets in saved-state bundles. When the underlying configuration changes elsewhere, show a conflict notice rather than silently replacing the draft. Probes use saved configuration; testing an unsaved endpoint is a separate explicit action whose result cannot update the saved connection’s health.

### Collection scope is always visible

Use the label: **“Lifetime · This device · All profiles · Since [date]”**. A profile filter changes its scope label. Restored history or collection gaps receive a small information chip; they are never hidden behind an apparently complete lifetime number.

---
# 05 · Overview screen blueprint

The following values are **illustrative UI fixtures**, not measurements from the repository or the user’s device.

> **DEEP RESEARCH**  /  Built-in plugin  /  Enabled
> **Degraded · 6 of 7 configured engines ready**
> Checked 41 seconds ago · View diagnostics

| Lifetime total | Successful | Failed |
|---|---|---|
| **1,240** uses | **1,180** uses | **35** uses |

**Also:** 10 partial · 8 canceled · 5 unknown · 2 in progress. These categories account for the remaining uses; failure is not calculated as total minus success.

| Success rate | Typical response | Last used |
|---|---|---|
| **96.3%** | **1.2 s** median | **2 min ago** |
| 1,180 / 1,225 determinate | Successful live executions | All profiles |

### Page composition

Place the lifetime strip above the tab row. Below it, Overview contains a time-range control, one usage chart, a compact performance card, and the first few owned-tool rows. On narrow phones, show the total as a wide tile and Successful/Failed as a two-column pair. Wrap the additional categories naturally.

The range selector changes **range cards and charts only**. The lifetime strip remains labeled Lifetime. Provide 24 hours, 7 days, 30 days, and All time. Time buckets use the device’s display timezone, with a visible timezone explanation; stored timestamps remain UTC.

### Charts that explain, rather than decorate

The default chart shows uses over time with outcome segments and a text legend. Tooltips contain date range, exact counts, and excluded/unknown categories. A chart “View data” action exposes an accessible table. Do not use a donut to imply that every non-success was a failure.

Place response-time trends in a separate card with a clear population label. Cache responses and connection probes do not mix into live execution latency. Historical percentile estimates are explicitly marked approximate.

### Owned-tool rows

Each row includes a rounded tool icon, display name, latest status, total uses, and a concise recent result. For MCP, show “Server online · Tool not yet verified” when discovery succeeded but the tool has not executed. Empty rows show **0 uses** and **No executions recorded**, not 100% success.

### Premium detail without clutter

Use one softly accented header, neutral tonal metric cards, and small semantic status badges. Reserve prominent warning treatment for actionable problems. Avoid displaying raw stack traces, endpoints, or large JSON blocks on Overview.

---
# 06 · Activity & diagnostics blueprints

### Activity: readable execution history

| Example row | Supporting information | Tap behavior |
|---|---|---|
| Successful · Search papers | Today 14:32 · 1.2 s · Phone · Research | Open redacted execution details. |
| Successful after retry | Today 14:29 · 2 attempts · Remote MCP | Show attempt sequence; retain one logical use. |
| Unknown outcome | Today 14:27 · Connection lost after dispatch | Explain reconciliation; never offer a blind repeat. |
| Served from cache | Today 14:25 · 0 network attempts | Show cache age when known; do not claim server health. |

Filter by outcome, tool, time range, profile, execution owner, and origin. Include a clearly labeled “Blocked before execution” filter, but exclude these records from Total uses. Page history rather than loading the entire dataset. Keep the filter summary visible when arriving from a metric card.

Execution details show an opaque ID, timestamps, execution outcome, delivery outcome, duration, attempts, sanitized reason, and parent/child relationships. “Successful execution; result delivery failed” must remain distinguishable from an upstream tool failure. No request arguments, conversation text, health readings, file paths, or response bodies belong here.

### Diagnostics: actionable connection health

| Diagnostic group | Fields | Relevant action |
|---|---|---|
| Readiness | Enabled state, selected-profile access, permissions, dependencies | Manage permission or configuration |
| Connection | Transport, authentication state, last real response, last check | Check connection or Sign in |
| Reliability | Recent failure categories, timeout count, recovery events | View filtered activity |
| Limits | Known cooldown, next allowed retry, provider-reported quota | Wait or manage credentials; no bypass |
| Coverage | Tracking start, gaps, unobservable upstream attempts, retained history | Open statistics preferences |

Group failures by bounded reason codes such as authentication, rate limit, network, malformed response, or missing local dependency. Show count and most recent occurrence. Default to a small set of issue groups; expand the technical detail on demand.

### Empty, loading, and error states

“No uses yet” is a welcoming state with an explanation—not an error. “Statistics unavailable” retains configuration controls and offers a local reload. A removed tool remains inspectable as history. Loading uses stable card dimensions to avoid layout jumps. A stale connection retains “Last seen online [time]” instead of flipping immediately to unavailable.

Do not add an unrestricted “Retry tool” action to history. Connection checks are safe diagnostics; repeating a business operation requires its normal workflow, current permissions, and any required confirmation.

---
# 07 · Visual system, icons & accessibility

Reuse the app’s MaterialTheme, ThemeIcon, SettingsHero, and SettingsPanel conventions rather than introducing a competing theme. The shared dialog already uses these components. [R1] Material 3 provides the semantic color, typography, and shape foundation. [S3]

| Token | Proposed specification |
|---|---|
| Layout | 20 dp compact-screen side padding; 12 dp card gaps; 8 dp spacing rhythm. |
| Surfaces | Tonal neutral cards, 20–24 dp corner radius, subtle outline; accent tint concentrated in the header. |
| Numbers | 28–34 sp primary values; tabular figures when supported; full value in accessibility text. |
| Text | 14–16 sp body, 12–14 sp supporting labels; no fixed-height text containers. |
| Icons | Rounded vector family, consistent 20–24 dp glyphs, 40–48 dp tonal plates where appropriate. |
| Motion | 180–220 ms state transitions; no constant pulse or repeated count-up; honor reduced motion. |
| Density | Wide total plus paired success/failure cards on phones; adaptive columns on tablets; one column at large font sizes. |

### Icon vocabulary

| Meaning | Proposed icon token | Treatment |
|---|---|---|
| Total / success / failure | QueryStats / CheckCircle / ErrorOutline | Neutral accent / success / error |
| Latency / last used / retries | Speed / History / Replay | Neutral supporting accents |
| Cache / local readiness | Cached / Devices | Neutral; not a substitute for online status |
| Online / unavailable | CloudDone / CloudOff | Icon plus explicit label and timestamp |
| Authentication / limited | Key / WarningAmber | Actionable warning treatment |
| Diagnostics / configuration | MonitorHeart / Tune | Consistent navigation hierarchy |

These are semantic icon names, not guaranteed imports in the repository’s current icon set. Reuse existing assets or add small vetted vectors; do not add a large dependency solely for decorative symbols. Remote icon URLs must follow the existing secure image policy or fall back to a bundled icon.

### Accessibility acceptance requirements

Interactive targets must be at least 48 dp, consistent with Android’s Compose guidance. [S4] Target 4.5:1 contrast for normal text and 3:1 for large text; validate every supported theme rather than assuming an accent passes. [S5]

Status always combines icon and text, not color alone. Charts have text summaries and table alternatives. Test TalkBack order, keyboard/switch access, RTL, 200% font scaling, and compact widths. Announce meaningful status changes politely; do not announce every counter increment or steal focus during refresh.

---
# 08 · Metric contract: what each number means

### Primary counter model

**Total uses** counts unique logical invocations that begin actual tool execution or are fulfilled from a valid cache/shared result after authorization and admission. A request rejected before that boundary does not count as a use. Queued requests are displayed separately until execution begins.

For a single subject, scope, collection epoch, and start-time window:

```text
Total = Successful + Failed + Partial + Canceled + Unknown + InProgress
SuccessRate = Successful / (Successful + Failed + Partial)
```

Show a dash when the success-rate denominator is zero. Display its denominator and the excluded Canceled, Unknown, and In progress counts. Label samples below 20 determinate outcomes “Low sample”; this is a proposed product threshold, not a statistical confidence claim.

| Outcome | Required interpretation |
|---|---|
| Successful | The executor returned a contract-valid success. Valid empty results are successes. Cache success is included, but separated in performance metrics. |
| Failed | A definite failure is known. MCP tool errors with `isError=true` are failures even when transport delivery succeeds. [S1] |
| Partial | An adapter explicitly reports a documented partial result. Never infer partial success from prose. |
| Canceled | Execution began and cancellation is confirmed for the operation; no unknown business effect remains. |
| Unknown | Dispatch may have occurred, but completion or effects cannot be established. Includes ambiguous write timeouts. |
| In progress | A started invocation has not reached a terminal outcome; asynchronous job acceptance alone is not success. |

### Separate execution from delivery

Keep `executionOutcome` distinct from `deliveryOutcome`. Output clipping, result materialization, or model handoff can fail after the tool itself succeeded. The primary success metric describes **tool execution**, while a separate delivery warning exposes the downstream problem. This distinction is necessary because the current budget wrapper can change the returned result after execution. [R3]

### Retry and cache rules

An automatic retry retains its logical invocation ID and adds an attempt. An explicitly new user/model request gets a new invocation ID. Two separately admitted callers sharing one cached or coalesced result count as two logical uses, but share one physical execution/attempt set. Each caller’s receipt is recorded once; a UI refresh or replayed notification adds nothing.

Provider transport attempts are credited only to their producer record. References from coalesced callers are not duplicated attempt rows. No usage statistic should be interpreted as a billing total unless an authoritative billing adapter supplies it.

---
# 09 · Additional metrics & worked examples

### Useful secondary statistics

| Metric | Definition and display contract |
|---|---|
| Attempts and retries | Actual instrumented executor/transport attempts. Separate network requests from local execution attempts. Unknown upstream detail shows “Not reported.” |
| Live response time | Execution-start to validated outcome using a monotonic clock; cache, probes, and queue wait excluded. |
| Median / p95 | Aggregate a documented histogram; label estimates approximate. Never average per-period percentiles. |
| Queue wait | Admitted-to-executing duration, separate from tool response time. |
| Last use / success / failure | Latest corresponding timestamps; show absolute time on detail. |
| Cache share | Cache-served logical uses divided by started uses in the same scope; distinguish shared in-flight results. |
| Failure categories | Bounded taxonomy, counts, and latest occurrence; no raw exception text. |
| Result and data volume | Optional adapter-reported result counts and payload bytes with units and coverage; no content retained. |
| Recovery | Successful calls following a failure incident; distinguish recovered retries from a new call. |
| Availability history | Observed state transitions and coverage, not guaranteed service uptime. |

Cost, token savings, battery savings, and remaining provider quotas are not universal metrics. Show them only when a reliable adapter supplies their basis; otherwise omit them or explicitly say “Not available.”

### Counting fixtures

| Scenario | Logical result | Attempt accounting |
|---|---|---|
| One successful search | 1 total, 1 success | 1 network attempt |
| Failed read attempt, successful retry | 1 total, 1 success | 2 attempts; 1 failed attempt |
| Two callers share one in-flight search | 2 total, 2 successes | 1 producer attempt, referenced by both |
| One cache fulfillment | 1 total, 1 success, 1 cache use | 0 network attempts |
| Permission denied before execution | 0 uses; 1 blocked request | 0 attempts |
| Valid zero-result search | 1 total, 1 success; result count 0 | Actual attempts only |
| Dispatched write loses response | 1 total, 1 unknown | Record observed dispatch; no automatic replay |
| Ping and tools/list refresh | 0 tool uses | Separate probe/control counters |

---
# 10 · Availability that users can trust

Represent health as independent dimensions: **configuration readiness, permission/profile access, connection evidence, tool-result evidence, and freshness**. Derive a display badge from those dimensions; do not store one irreversible online/offline Boolean.

| Display state | When to use it | Supporting text |
|---|---|---|
| Online | Fresh successful remote connection evidence, with no known access blocker | “Server responded 41 seconds ago.” |
| Available locally | Required local capability and permission checks pass | “Runs on this device; internet not required.” |
| Degraded | The connection works but a relevant capability or dependency is impaired | “Search available; document reader failing.” |
| Unavailable | Fresh evidence establishes the endpoint cannot currently be reached | Specific transport reason and last check |
| Authentication required | Credentials rejected, expired, or absent when required | “Sign in to restore access.” |
| Permission required | Local or app policy permission is missing | “Location permission is not granted.” |
| Rate limited | Known provider cooldown or quota response is active | “Retry permitted after [time].” |
| Disabled | User, global, or selected-profile toggle prevents use | “Disabled for this profile.” |
| Unknown / Not checked | No applicable real evidence exists | “No connection check recorded.” |
| Stale / Last seen | Previous evidence is older than its freshness window | “Last seen online yesterday.” |
| Checking | A probe is in progress | Retain prior state and its timestamp underneath. |

### Parent connection versus individual tool

A connection can be online while one tool fails. A tool discovered on a healthy server but never executed shows **“Server online · Tool not yet verified.”** A successful ping supports connection responsiveness, not correctness of every tool operation. MCP defines ping as a connection-level mechanism. [S2]

Native tools use **Available locally**, not Online, unless they actually depend on a remote service. Cached responses may be usable while the server is unavailable. Display both facts rather than letting cache success refresh remote health.

### Display precedence

Apply configuration/profile blockers first, then active authentication or quota blockers, then current connection evidence. Preserve the underlying connection fact in detail—for example, “Server reachable; disabled for this profile.” Unknown evidence must not be presented as a failure.

A disconnected phone should say **“Device offline; remote availability unknown.”** Do not blame every server for a local network outage. Missing background samples are gaps, not failed checks. Do not advertise an uptime percentage in the initial release; show an observed health timeline with coverage instead.

---
# 11 · Connection checks without side effects

### One check per connection, shared by its tools

The health repository consumes passive execution evidence and coordinates explicit checks. Reuse the existing manager’s sessions, authentication flow, and cooldowns; project its state into the shared UI rather than maintaining another competing map. [R2, R10]

| Proposed default | Behavior |
|---|---|
| Passive observation | Always consume real tool and transport results while statistics collection is permitted. |
| On opening details | Show cached evidence immediately. Check only when stale, configured, enabled, and permitted. |
| Manual check | Coalesce concurrent requests; debounce repeated taps for 5 seconds. Honor active provider cooldowns. |
| Visible-screen refresh | Optional; at most once per 60 seconds for the selected connection, not every listed tool. |
| Remote evidence freshness | 5 minutes initially; adapters may use a justified policy. Show the timestamp even while fresh. |
| Check concurrency | At most 2 independent connections; no fan-out per exposed tool. |
| Background monitoring | Off by default. Optional approximately hourly work for explicitly selected connections. |

These are initial product defaults, not measured optimal values. WorkManager periodic work has a minimum interval of 15 minutes and execution is affected by constraints and system scheduling; it cannot support a guaranteed real-time availability promise. [S6]

### Safe probe contract

Prefer an established-session MCP ping when supported. If unavailable, use a permitted initialization/catalog check and clearly label it. Native probes inspect capability and permission readiness without executing business actions or prompting for new permissions.

Do not automatically call arbitrary tools as health tests. An operation described as read-only by an untrusted server is not sufficient authorization for unattended probing. [S1] A plugin-specific semantic check must be explicitly allowlisted, bounded, non-mutating, and opt-in when it consumes a paid request or sensitive resource.

### Prevent false freshness

A cached tools/list result must not refresh “last checked online.” The current manager records success after its catalog path, so the adapter needs an explicit `evidenceSource` and `networkObservedAt` distinction. [R2] A late response from an old endpoint/configuration generation must be ignored for current health.

### Timeouts and recovery

Use the established transport timeout policy rather than changing tool timeouts for monitoring. Back off with bounded jitter after failures; share the existing cooldown authority. A successful check may resolve a transport incident but cannot clear a provider quota, grant permission, or prove an ambiguous write completed.

Statistics reset never resets operational backoff, quotas, tokens, or approval state.

---
# 12 · Canonical identities & aggregation

### Identity model

| Identifier | Purpose |
|---|---|
| subjectKey | Opaque identifier for a plugin operation or tool capability. |
| pluginId | Stable owning catalog/plugin ID, not a localized name. |
| instanceUid | Native installation or remote connection identity. |
| realToolName | Exact capability name; keep MCP name case and server scope. [S1] |
| profileUid | Optional filter dimension, not part of tool identity. |
| configGeneration | Identifies the endpoint/account configuration that produced health evidence. |
| invocationId | App-generated unique logical call ID; provider call IDs are only correlation fields. |
| parentInvocationId / rootId | Connect orchestration and child activity without copying counters. |
| producerId / sequence | Deduplicate event delivery and identify observation ownership. |

AgentToolResolver already distinguishes connection identity, real tool name, and the alias presented to a model. Add canonical identity there, but record no use during discovery or resolution. [R4]

### Stability rules

Renaming a connection, translating a tool title, or changing a model alias keeps history. A credential refresh for the same account does not create a new tool. Moving to a different account or materially different endpoint creates an explicit new identity boundary or generation with separate history segmentation; it must not silently inherit a healthy status from the old service.

A schema/catalog change records its version and invalidates relevant capability evidence. It does not automatically destroy lifetime history. If the operation’s meaning changes incompatibly, introduce a new capability version and present the old version as history.

### Shared results and retries

Maintain one physical execution/attempt producer and separate logical receipt records for independently admitted callers. A new subscriber to a UI flow is not a new logical caller. Automatically retried attempts stay under the original invocation; corrected arguments submitted as a new tool request create a new invocation.

### Gateway ownership

The phone owns the logical request it submits. The gateway may own execution-attempt evidence. Correlate these records through a negotiated, namespaced invocation ID; never add a gateway echo as a second logical request. Gateway-global activity from other users or devices is excluded unless explicitly selected and authorized.

Where a gateway cannot report its retries, write **“Upstream attempts not reported.”** Do not infer zero attempts or one attempt from the final response. Treat data imported from a gateway as bounded, authenticated metadata—not trusted instructions or raw result content.

---
# 13 · Capture architecture & coverage manifest

### Proposed data flow

```text
Identity -> Authorized execution/cache boundary -> Logical observer
Executor -> Attempt and health adapters -> Shared recorder
Recorder -> Atomic invocation records and rollups -> Database
Database -> Repository Flow / health projection -> Settings UI
```

The logical observer is the only owner of use counters. Transport and native adapters report attempts, execution semantics, and health. UI components read state; they never increment counts.

### Required instrumentation surfaces

| Path | Required hook | Explicit exclusion |
|---|---|---|
| Agent execution | After permission, budget, and concurrency admission, immediately around actual invocation | Resolution, UI rendering, and denied preparation |
| Cache/coalescing | One receipt per distinct admitted invocation; producer reference for shared work | Replayed callbacks or extra UI collectors |
| MCP SDK and modern transport | Attempt evidence; structured `isError`; auth/quota/transport events | tools/list, ping, resource browsing as tool uses |
| Native and marketplace tools | Contract-valid result classification and local readiness | Feature-toggle changes |
| Research engine adapters | Existing research attempts mapped to child evidence | Recounting HTTP attempts as orchestration uses |
| Delegation and async tasks | Parent/child identity; final task completion or reconciliation | Treating a queued/accepted task as success |
| Legacy registry | Instrument only if reachable, with the same observer guard | A second wrapper around an already recorded invocation |
| Gateway results | Correlated producer evidence where supported | Remote echo counted as a second phone use |

### Integration constraint

The current ToolExecutionBudget callback reports `(callId, success)` and runs in a finalizer. Preserve existing behavior needed by execution safety, but add a structured observer contract rather than deriving every metric from that Boolean. Preserve the no-permit orchestration branch to avoid nested-call deadlocks at concurrency one. [R3]

### Coverage manifest

Create a machine-readable manifest mapping every registered tool/plugin operation to canonical owner, entry point, outcome adapter, probe policy, and configuration route. CI must fail when a new registry entry lacks a mapping. Explicitly mark unsupported attempt or health metadata; **all entries must still have their own statistics UI and collection-coverage explanation**.

Audit direct calls bypassing AgentToolResolver before release. A passing unit test around one wrapper does not establish universal coverage.

---
# 14 · Persistent model & query design

### Recommended storage choice

Add a dedicated **ToolStatisticsDatabase**, schema version 1, using the repository’s existing Room dependency and dependency-injection conventions. Keep it independent of the chat database’s cascade lifecycle and execution-critical migrations. The inspected chat database is version 37; no replacement of that database or library generation is proposed. [R7–R8]

This choice requires explicit backup and restore integration. Do not assume the existing complete-backup mechanism will correctly snapshot a second database.

| Proposed table | Essential fields / responsibilities |
|---|---|
| tool_subject | Canonical key, owner, instance, capability, safe display snapshot, tombstone, schema version. |
| tool_invocation | ID, subject, profile, root/parent, origin, owner, epoch, start/end, state revision, execution and delivery outcomes. |
| tool_attempt | Unique producer attempt ID, invocation link, operation kind, attempt result, bounded reason/HTTP code, durations. |
| tool_result_receipt | Coalesced/cache recipient mapping, physical producer reference, receipt state; no duplicated attempt credit. |
| tool_rollup | Lifetime and hourly/monthly buckets; counter vector, duration counts/sums, histogram version and bins. |
| tool_health_observation | Subject/connection, generation, evidence source, observed time, expiry, reason and next allowed check. |
| statistics_epoch | Collection start/stop/reset, completeness watermark, known gaps, retention and measurement version. |
| telemetry_delivery_state | Producer sequence watermark, out-of-order gaps, migration/import checkpoints and deduplication state. |

### Storage invariants

Use 64-bit counters and checked arithmetic. Transactionally update the invocation, time bucket, lifetime aggregate, and deduplication checkpoint. Do not cascade-delete these tables with chats. A deleted profile becomes a safe “Removed profile” dimension; it must not erase a tool’s aggregate history.

Keep raw arguments, results, URLs, credentials, and arbitrary exception messages out of the schema. Store bounded enumerations and explicitly permitted numeric metadata only.

### Query contracts

The summary reads rollups, not every historical invocation. Use indexed queries for `(subject, epoch, startedAt)` and relevant profile/owner filters. Persist disjoint dimensional buckets and aggregate them once; never mix pre-aggregated “all profiles” rows into a sum of profile rows.

All time is a retained-lifetime aggregate. Recent charts use hourly buckets; older history uses explicitly labeled monthly buckets. Percentiles use a versioned mergeable histogram, not averages of percentiles. Duration populations exclude missing measurements, cache receipts, and probes; the UI reports sample counts.

Time-window counters attribute an invocation to its start bucket. Later completion or reconciliation updates that same bucket so the accounting identity remains true.

---
# 15 · Event lifecycle, concurrency & failure handling

### State transitions

```text
Offered -> Blocked / Queued -> Started -> terminal outcome
                               |
                     Success / Failure / Partial /
                     Canceled / Unknown
Unknown -> authoritative reconciliation -> corrected terminal outcome
```

Only Started increments Total. Queued, blocked, and pre-dispatch canceled requests remain outside it. The Started record is created at the execution boundary; interruption in the tiny persistence/dispatch gap must remain uncertain, not be declared a successful or failed business action.

### Atomic transition algorithm

**Begin:** allocate a unique invocation ID, attach identity/epoch, and insert a Started record idempotently. Increment Total and In progress in the same transaction. Preserve the start bucket and capture monotonic duration information for the current process.

**Complete:** compare the stored state revision, decrement the previous outcome bucket, increment the new outcome bucket, and update time/lifetime aggregates transactionally. Duplicate completion events do nothing. A timeout with ambiguous effects moves to Unknown, not definitive failure.

**Reconcile:** authoritative remote task status or another valid completion signal may correct Unknown. Update the same invocation and adjust the outcome buckets; never increment Total again. Record a reconciliation timestamp and reason so activity explains the correction.

### Do not overpromise exactness

A local database transaction cannot atomically commit a remote side effect. The system can provide idempotent accounting for durably captured events, not a guarantee that every remote event survives device/storage failure.

Telemetry failure must **fail open for tool execution**, never trigger an automatic tool retry, and never crash configuration. Use a bounded queue and bounded finalization budget; persist a completeness-gap marker where possible and show “Statistics may be incomplete.” If the database itself is unavailable, expose that state rather than showing stale totals as current. Do not wait indefinitely inside a NonCancellable finalizer.

### Crash, reset, and replay

On restart, unfinished persisted calls are reconciled where possible; otherwise classify them Unknown. Do not synthesize failure from “app stopped.” Async jobs remain pending only while an active, valid task reference can be queried.

Deduplicate replay using stable producer IDs plus monotonic sequence checkpoints. Retain gap tracking for out-of-order delivery and never advance a contiguous watermark past a missing event. After compaction, reject old events already covered by committed checkpoints. Reset advances the collection epoch, so late completions from the previous epoch cannot repopulate freshly cleared totals.

Record elapsed time with a monotonic clock within a process; after restart, wall-clock-derived durations must be labeled estimated or omitted.

---
# 16 · Typed interfaces & integration constraints

The following is an **interface sketch**, not a drop-in patch. Types must be adapted to the repository’s existing result envelopes and coroutine contexts.

```kotlin
enum class ExecutionOutcome {
    SUCCESS, FAILURE, PARTIAL, CANCELED, UNKNOWN
}
enum class DeliveryOutcome {
    DELIVERED, TRUNCATED, FAILED, NOT_APPLICABLE
}
data class ToolIdentity(
    val subjectKey: String,
    val pluginId: String,
    val instanceUid: String,
    val realToolName: String,
    val configGeneration: Long
)
data class ToolCompletion(
    val invocationId: String,
    val revision: Long,
    val execution: ExecutionOutcome,
    val delivery: DeliveryOutcome,
    val reasonCode: String?,
    val durationMs: Long?
)
interface ToolStatisticsRecorder {
    suspend fun begin(event: ToolStarted): RecordingReceipt
    suspend fun attempt(event: ToolAttemptObserved)
    suspend fun complete(event: ToolCompletion)
    suspend fun health(event: ToolHealthObserved)
}
```

`ToolStarted`, `ToolAttemptObserved`, `ToolHealthObserved`, and `RecordingReceipt` are proposed DTOs containing the bounded identifiers, timestamps, ownership, and coverage fields defined in sections 12–15. In-progress is the persisted lifecycle state before ToolCompletion, not an extra terminal enum value.

### Contract requirements

The recorder implementation converts storage problems into typed recording failures or coverage state. It must not alter tool success, swallow parent cancellation, or cause re-execution. Use a telemetry marker in the invocation context to prevent double wrapping.

Adapters classify the tool result before output-budget compaction, then record delivery warnings after compaction/materialization. A generic `!isError` check on the final model-facing envelope is insufficient for this separation. [R3]

MCP JSON-RPC errors and `isError=true` results map to appropriate bounded failure categories, while connection responsiveness remains separate. [S1] Adapters must not scan arbitrary prose for words such as “error” or “success.”

Health events require `evidenceSource`, `observedAt`, `expiresAt`, and `configGeneration`. Cached catalog reads and cache result receipts cannot masquerade as network observations.

Expose immutable repository snapshots as Flow to a scoped ViewModel. Compose performs no direct telemetry file scans or database writes. Statistics state, configuration draft state, and one-off user actions remain separate streams.

---
# 17 · Privacy, retention & reset behavior

### Local-first defaults

Collect aggregate counts and bounded activity metadata locally by default for ordinary sessions. Retain detailed activity for **30 days**, with 7/30/90-day choices. Do not introduce an external analytics service, cloud synchronization, or model-visible telemetry context for this feature.

Temporary/private sessions do not persist statistics by default. Any optional aggregate-only participation must be clearly explained and explicitly enabled; detailed private-session history remains excluded. The summary explains that excluded sessions are outside the measurement population.

| Data | Proposed retention / policy |
|---|---|
| Lifetime outcome aggregates | Until explicit statistics deletion or app-data removal; survive detailed-history pruning. |
| Detailed invocation/attempt metadata | 30 days by default; prune oldest details under storage pressure after aggregate checkpointing. |
| Hourly chart buckets | 90 days, then compact to calendar-month buckets; older charts label their resolution. |
| Health observations | 30 days; current evidence expires according to its separate freshness policy. |
| Deduplication and unresolved records | Compact checkpoints and unresolved identifiers retained as required for safe replay/reconciliation. |
| Secrets, prompts, result bodies | Never collected in statistics. |

Use a configurable metadata-storage budget with a proposed initial target of 25 MiB. If compaction cannot maintain it, pause detailed history and display the limitation; do not discard lifetime aggregates or let statistics exhaustion block tool execution. Validate the actual size on device before finalizing the default.

### Preferences in Configuration

Provide “Collect local statistics,” “Keep activity history,” retention choice, “Background availability checks,” “Include statistics in backup,” and “Export or clear statistics.” Basic capture and richer history can be disabled independently. Explain the battery/privacy effect beside background checks.

### Redaction and export

Store safe error enums, not raw stack traces. Omit URLs, query text, filenames, coordinates, health data, credentials, and headers. Export only explicitly selected summaries or metadata through the normal Android file/share flow. Bound fields, redact again at export, and escape spreadsheet-formula prefixes in CSV text cells. Exports disclose scope, timeframe, metric version, and coverage gaps.

### Clear statistics

Offer per-tool, per-plugin, and all-statistics scopes with confirmation and a plain-language preview. Keep operational quotas, approvals, configuration, and backoff untouched. Advance the epoch, clear associated detail/rollups, and prevent delayed old events from reappearing. Pausing collection records a gap; resuming starts a new coverage interval rather than pretending collection was continuous.

---
# 18 · Migration, backup & restoration

### Start honestly

Display **“Tracking since [activation date]”** for new logical-use counters. Existing research records describe HTTP attempts, not complete logical tool uses; import them only into a separate historical-attempt series with their original measurement version. [R6]

Do not fabricate past use counts from old chat events, truncated logs, or retained messages. Their coverage and deletion semantics are insufficient to establish a complete lifetime total. [R7] A future migration may add explicitly labeled partial history, but it must not silently mix populations.

### Explicit snapshot format

Add a versioned, sanitized `tool-statistics-v1` logical snapshot to the selected backup workflow. Include canonical subjects, epochs, retained rollups, bounded history if selected, unresolved records, and deduplication checkpoints. Capture a consistent checkpoint under a short statistics-write barrier; validate counts and schema before declaring the snapshot complete.

Exclude the raw statistics database and its WAL/SHM files from generic file traversal. Otherwise a backup may capture an inconsistent or unexpectedly detailed copy in addition to the intended snapshot. The existing collector’s traversal and exclusions need an explicit update for this feature. [R11]

### Restore semantics: replace, not add

Initial release supports **replace selected statistics**, not additive merge of overlapping lifetime snapshots. Explain that restoring an older snapshot may replace newer statistics. Keep a rollback copy until validation and commit complete. A second restore of the same snapshot produces identical totals, not doubled counts.

Restore the snapshot’s tracking dates and measurement versions. Generate a new active producer namespace for subsequent activity; preserve historical producer checkpoints so replay cannot duplicate imported events. Reset current connection health to Unknown/Stale and re-check with current configuration. Never restore “Online now,” active probes, credentials, or grants from a statistics snapshot.

### Compatibility and failure handling

A backup without statistics must leave current statistics untouched unless the user separately chooses a full data reset. Reject unsupported future snapshot versions with a clear message; do not silently import a subset and present it as complete.

Stream validation/import with bounded memory, verify nonnegative counters and accounting identities, and respect reset/epoch boundaries. A canceled or interrupted restore rolls back cleanly. Statistics-only restoration must not alter chat, model, memory, health, or benchmark data.

Room schema changes require exported schemas and migration tests; test upgrades from every shipped statistics schema. Reuse the repository’s installed Room toolchain rather than adopting a new major version for this work. [S7]

---
# 19 · Repository implementation map

All paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. **New** names are proposed. **Modify** targets exist in the inspected snapshot; some listed UI/backup targets require a complete call-site review during implementation.

| Change | File / area | Responsibility |
|---|---|---|
| New | data/telemetry/tool/ToolStatisticsDatabase.kt | Dedicated Room store and DAO entry points. |
| New | data/telemetry/tool/ToolStatisticsEntities.kt | Subjects, invocation lifecycle, attempts, health, rollups, epochs. |
| New | data/telemetry/tool/ToolStatisticsDao.kt | Indexed snapshots, transactions, pruning, idempotent corrections. |
| New | data/telemetry/tool/ToolStatisticsRecorder.kt | Single logical-use capture contract and coverage/error reporting. |
| New | data/telemetry/tool/ToolStatisticsRepository.kt | Immutable summary and paged activity queries. |
| New | data/telemetry/tool/ToolIdentityResolver.kt | Canonical ownership, alias separation, version boundaries. |
| New | data/telemetry/tool/ToolHealthRepository.kt | Evidence projection, freshness, shared probes, incident history. |
| New | data/telemetry/tool/ToolStatisticsSnapshot.kt | Validated logical backup/restore format. |
| New | di/ToolStatisticsModule.kt | Shared recorder, database, repositories, clock and dispatcher bindings. |
| New | presentation/ui/setting/toolstatistics/ | Detail route, ViewModel, summary panel, metric cards, health chips, charts, history and diagnostics. |


---
# 19 · Repository integration map, continued

The following targets connect the shared store and UI to existing behavior. Preserve execution, permission, and configuration contracts while adding telemetry.

| Change | File / area | Responsibility |
|---|---|---|
| Modify | data/agent/tool/AgentToolResolver.kt | Add canonical metadata to resolved capabilities. [R4] |
| Modify | data/agent/ToolExecutionBudget.kt | Structured boundary capture; preserve admission and orchestration safety. [R3] |
| Modify | data/agent/tool/McpClientManager.kt | Real-vs-cached health evidence and attempt correlation. [R2] |
| Modify | data/research/telemetry/ResearchTelemetryStore.kt | Bridge old attempt records without double counting. [R6] |
| Modify | presentation/ui/setting/PluginConfigurationDialog.kt | Embedded summary, shared route, preserved draft/Save/Cancel. [R1] |
| Modify | ToolConnectionsScreen / ViewModel; ToolServiceComponents | Connection and plugin summary integration; one health projection. [R10] |
| Modify | ProfileToolsPanel; McpToolsSelectionScreen | Tool-level settings entry and scoped statistics access. |
| Modify | ResearchEngineHealthPanel | Read shared statistics/health; retain engine-specific fields. [R5] |
| Modify | data/backup/CompleteBackup* | Explicit snapshot selection, exclusions, validation and rollback. [R11] |

The short UI file names in the final rows are under `presentation/ui/setting/`. Also update SettingsSearchRegistry, localized resources, and theme-aware previews. Inventory AgentRunCoordinator, ModernMcpTransport, gateway producers, native direct calls, and the legacy registry before selecting the remaining exact patches. Do not treat this table as proof that no other execution entry points exist.

---
# 20 · Implementation sequence & completion gates

### Phase 1 — Contract and coverage

Create the registry coverage manifest and canonical identity map. Freeze counting fixtures, metric version, outcome taxonomy, default privacy behavior, and probe rules. Trace each native, remote, research, marketplace, gateway, and async execution path. Document which source owns logical uses versus attempts.

**Gate:** every registered item has a settings destination, an owner, an outcome contract, and an explicit observable/unobservable declaration. No undocumented direct execution path remains.

### Phase 2 — Persistence and capture

Implement the dedicated store, idempotent transitions, rollups, coverage gaps, and structured recorder. Add hooks without changing tool permissions, dispatch limits, or retry behavior. Validate side-effect ambiguity and output-delivery separation before adding charts.

**Gate:** concurrent/replayed events, process interruption, coalescing, and reconciliation pass deterministic and property-based tests. Telemetry storage failure never causes a tool replay or changes the tool’s return value.

### Phase 3 — Universal settings UI

Build the shared summary and detail route, then integrate each existing configuration entry. Add individual MCP-tool details where absent. Preserve form drafts, list positions, and profile scope. Wire every displayed value to the shared repository; placeholder data is allowed only in previews.

**Gate:** the user can open any supported plugin or tool’s own settings and see real counts, collection scope, and readiness—even with zero prior activity.

### Phase 4 — Health, privacy and backup

Project manager and native health into the common model. Add safe shared checks, freshness, stale-generation rejection, retention, reset epochs, sanitized export, and opt-in snapshot backup. Adapt the research panel and historical attempt migration.

**Gate:** no automatic business operation is used as a health probe. Restore/reset preserve operational safeguards and cannot double totals. Collection exclusions and unknown upstream coverage remain visible.

### Phase 5 — Device validation and rollout

Run UI, migration, integration, and representative device performance tests. Verify both supported MCP transport paths and gateway capability negotiation. Compare pre-change execution behavior to ensure telemetry has not introduced retries, authorization changes, or new execution blocks.

**Gate:** all applicable tests in sections 21–22 pass; remaining external observability limits are displayed, not concealed. Roll out behind one feature switch with safe recorder/UI disablement. Disabling the feature leaves configuration and tool execution working and does not destructively downgrade stored schemas.

### Suggested repository artifacts

Add the design Markdown under `docs/design/`, the coverage manifest beside telemetry tests, and an implementation checklist in the eventual pull request. This document does not create a branch or PR and does not claim any phase is complete.

---
# 21 · Functional acceptance matrix

| ID | Test | Required result |
|---|---|---|
| A01 | 100 concurrent unique successful invocations | Total 100, success 100; no lost updates or duplicate history. |
| A02 | Replay each start and completion repeatedly | Counts identical to a single delivery. |
| A03 | One failed attempt followed by success | 1 use, 1 success, 2 attempts, 1 failed attempt. |
| A04 | Two admitted callers share one result | 2 logical uses; producer attempts recorded once. |
| A05 | Successful cache receipt | Success counted; zero network attempts; remote freshness unchanged. |
| A06 | MCP HTTP success with isError=true | Definite tool failure; no false execution success. |
| A07 | Valid empty result | Success with result count 0, not failure. |
| A08 | Permission/budget rejection or cancellation while queued | No started use; separate blocked/not-started category. |
| A09 | Cancellation confirmed after execution starts | Canceled outcome; not both failure and canceled. |
| A10 | Ambiguous write timeout, later successful reconciliation | Unknown becomes success; Total unchanged; no automatic repeat. |
| A11 | Output cap or result delivery failure after successful execution | Success remains; separate delivery warning visible. |
| A12 | Async job accepted, then completes later | In progress until final status; one use and one terminal outcome. |


---
# 21 · Lifecycle & data acceptance, continued

These tests verify persistence, data quality, and aggregation in addition to individual execution outcomes.

| ID | Test | Required result |
|---|---|---|
| A13 | Ping, discovery, resource browse, task status polling | No additional tool-use counts; separate control/probe records. |
| A14 | App restart during in-flight activity | Reconcile or show Unknown; never assume failure or success. |
| A15 | Prune detailed history | Lifetime counts and measurement scope unchanged. |
| A16 | Reset while previous calls remain active | New epoch stays clear of old completions; quotas/grants unchanged. |
| A17 | Restore the same snapshot twice | Identical totals; no additive merge; health no longer claims current Online. |
| A18 | Telemetry database unavailable/full | Tool continues normally; visible coverage failure; no retry or UI crash. |
| A19 | Same tool title on two servers; renamed alias | Distinct servers remain separate; rename preserves correct identity. |
| A20 | Parent research run with 24 child operations | One orchestration and correct child counts; overlapping cards not summed. |
| A21 | Provider returns no upstream attempt metadata | “Not reported,” never fabricated attempt or cost values. |
| A22 | Property-based random state transitions | Counter invariant holds; no negative values; duplicate/out-of-order events handled. |

Run these tests against native adapters, both MCP transport paths, and the gateway path wherever each scenario is applicable. Include accounting tests across timezone changes, daylight-saving boundaries, and delayed completions so time-range filters retain their documented start-bucket semantics.

---
# 22 · UI, health & performance release gates

| Area | Required verification |
|---|---|
| Universal coverage | Every manifest entry opens its own configuration statistics; never-used and disabled items remain visible. |
| Accurate freshness | Cached catalog reads do not refresh online timestamps; an old configuration’s late response is ignored. |
| Local outage | Airplane mode shows device connectivity limits; remote servers are not all declared down. |
| Background gaps | Doze, app suspension, and skipped work create unknown coverage, not fabricated outages. |
| Safe checks | No writes, arbitrary tools, sensitive data reads, or paid semantic checks run without their required explicit consent. |
| Editing stability | Live metrics never reset unsaved configuration; Save/Cancel, validation, and back navigation still work. |
| Accessible layout | 320/360 dp compact widths, tablets, RTL, light/dark themes, 200% font size, and 48 dp targets. |
| Assistive technology | TalkBack names/counts/statuses, chart table alternative, correct focus and no noisy repeated announcements. |
| Data boundaries | No secret/prompt/result/health-content leakage through history, exports, backup files, or error text. |
| Restore safety | Interrupted, incompatible, old, and repeated snapshots behave as specified; unrelated app data is untouched. |

### Proposed performance budgets—not benchmark results

Use a representative supported Android device and a dataset with 100,000 retained metadata records plus at least a year of rollups. Record device, build, power mode, and test conditions. Include the user’s ROG Phone 9 Pro in device validation when available, without treating it as the only supported device.

Target a warm summary query p95 below **50 ms** and no synchronous disk work on the main thread. Measure database writes separately from tool duration. The target for capture overhead is **under 1% on a representative mixed-tool workload**, while reporting absolute milliseconds for very fast native tools where percentages are misleading.

A statistics-only update should not recompose the full settings form. Coalesce UI updates during bursts while persisting each accepted event. Use paged activity, indexed aggregate queries, and bounded chart point counts. Verify no polling continues after leaving the screen unless explicitly enabled background work owns it.

### Definition of done

The interface is finished only when its real-data states—not just previews—are polished and consistent. Counts must survive lifecycle events, statuses must disclose evidence freshness, and diagnostic actions must resolve the relevant settings without changing execution safeguards.

No screenshots or performance measurements in a future PR should be presented as proof of universal coverage. Attach the completed manifest, automated test results, migration/restore fixtures, device accessibility checks, and before/after overhead measurements. Any unavailable upstream metric must have tested fallback copy.

---
# 23 · Repository evidence register

All repository references use the inspected commit `7553e4ff14316f0d28639d5cc0ed24cb59511066`, not the moving main branch. Paths below are relative to the Kotlin root stated in section 19. Links resolve to the commit-pinned file.

**[R1] [PluginConfigurationDialog.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PluginConfigurationDialog.kt)** · Full shared configuration dialog inspected. Evidence for current controls, MaterialTheme/ThemeIcon, bounded dialog layout, and absence of a common usage panel.

**[R2] [McpClientManager.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt)** · Lines 1–270 inspected. Health model, StateFlow, cached discovery, transports, tool calls, auth/quota handling, task/resource operations.

**[R3] [ToolExecutionBudget.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ToolExecutionBudget.kt)** · Lines 1–250 inspected. Admission, duplicate protection, permits, orchestration, unknown outcomes, compaction, Boolean finalizer.

**[R4] [AgentToolResolver.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt)** · Lines 1–230 inspected. Connection identity, model aliases, native/remote integration and profile gating.

**[R5] [ResearchEngineHealthPanel.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ResearchEngineHealthPanel.kt)** · Full file inspected. Existing engine-specific attempt summaries and range controls.

**[R6] [ResearchTelemetryStore.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/telemetry/ResearchTelemetryStore.kt)** · Full file inspected. Per-event JSON, event IDs, HTTP-attempt rollups, empty-result treatment and file-count bound.

**[R7] [ToolEvent.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/ToolEvent.kt)** · Full file inspected. AgentRun cascade relationship, payload fields, lifecycle statuses and timestamps.

**[R8] [ChatDatabaseV2.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2.kt)** · Full file inspected. Existing Room entities, DAO interfaces and schema version 37.

**[R9] [ToolConnectionRepository.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ToolConnectionRepository.kt)** · Full file inspected. Stable connection/binding identity, credential protection and deletion behavior.

**[R10] [ToolConnectionsViewModel.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ToolConnectionsViewModel.kt)** · Lines 1–190 inspected. UI-level health type, refresh and configuration-state handling.

**[R11] [CompleteBackupFiles.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/backup/CompleteBackupFiles.kt)** · Lines 1–155 inspected. File traversal, exclusions, validation and restore staging.

**[R12] [McpToolRegistry.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/mcp/McpToolRegistry.kt)** · Full file inspected. Additional registry/execution surface; actual runtime reachability remains to be traced.

---
# 24 · Official guidance & decision rationale

Official documentation was consulted on October 12, 2026. The MCP references intentionally name the **2025-11-25 specification revision**; this document does not assume every installed server or transport supports every feature of that revision.

**[S1] [Model Context Protocol: Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)**

Basis: tool identity within a server, tools/list versus tools/call, tool-result errors, and the trust boundary around server annotations. Design application: count logical execution separately from discovery, preserve tool-error semantics, and do not authorize automated probes from untrusted metadata alone.

**[S2] [Model Context Protocol: Ping](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/ping)**

Basis: optional connection responsiveness checks, reasonable timeouts, configurable frequency, and avoiding excessive pinging. Design application: use connection evidence with freshness; do not imply that ping proves business-operation correctness.

**[S3] [Android Developers: Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)**

Basis: theme-driven color, typography, and shape. Design application: build premium metric cards using the app’s established semantic theme rather than hard-coded brand colors throughout settings.

**[S4] [Android Developers: Compose accessibility API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)**

Basis: semantic component behavior and 48 dp minimum interaction targets. Design application: properly labeled controls, comfortable icon hit areas, and explicit testing of custom metric/chart components.

**[S5] [W3C: Understanding minimum contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)**

Basis: text contrast guidance. Design application: 4.5:1 normal-text and 3:1 large-text targets across themes. This is a design target, not a claim of accessibility certification.

**[S6] [Android Developers: Define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)**

Basis: periodic-work scheduling, minimum interval, and constraints. Design application: optional, inexact background checks and honest coverage gaps instead of a guaranteed real-time uptime monitor.

**[S7] [Android Developers: Room database migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)**

Basis: explicit schema migration and migration validation. Design application: export/test each shipped statistics schema with the project’s pinned Room toolchain; do not opportunistically upgrade the runtime for a statistics feature.

### Final implementation judgment

The strongest implementation is **shared infrastructure with plugin-specific adapters**: consistent UI, common definitions, and durable counts, while allowing genuine differences in local readiness, remote transport, asynchronous execution, and observability. The polished presentation must make those differences understandable—not hide uncertainty behind confident-looking numbers.
