# Smartwatch Health Connect plugin

## What this adds

A native Android, read-only plugin. It reads the phone's Health Connect store,
not a watch's Bluetooth sensors or a vendor cloud account. It registers nine
phone-owned AI tools through `AgentToolResolver` and uses the same schemas and
execution pipeline as other native plugins. No remote MCP endpoint, API key,
watch app or standalone MCP server is required. No new network client is created.

Find **Smartwatch Health Connect** in **Settings → Plugins/Tools → Configure**
or the integrated-plugin marketplace. Its themed screen has **Overview**,
**Access** and **AI Tools** tabs. Overview is a local, transient statistics view.

## Setup

1. Pair your watch using its normal companion app. Enable that app's supported
   Health Connect sharing and sync the watch. Brand alone does not establish
   which record types it shares. iPhone-only HealthKit is not supported.
2. Open the plugin's Access tab. Select only the categories you need and request
   Android read permissions. Partial grants are supported. Existing permissions
   are removed through Health Connect settings, not by unchecking a request box.
3. Refresh the local Overview to verify records. This does not contact an AI and
   does not require the AI-sharing switch. Optionally, use Access → Import Google
   Health data locally to save a 1-day to 5-year summary on this phone. Imports
   beyond 30 days require Health Connect's separately granted history permission.
   Local imports stay separate from AI access.
4. For AI questions, separately confirm **Share permitted health results with
   AI**, enable the plugin, and opt in each desired AI profile on the AI Tools
   tab. Local tools must be enabled for that profile. Per-chat tool exclusions
   still apply. Global, profile and AI-sharing defaults are all off.
5. Keep the app visible and the phone unlocked while tools read health records.
   AI tools are limited to 30 days and the plugin never requests background reads.
   Extended history is requested only for an explicit local import.

Health Connect is integrated into Android 14+; older supported Android versions
need the Health Connect provider app. This repository's minimum Android version
remains unchanged. An install/update/unavailable state is not a zero measurement.

## AI tools

| Tool | Reads |
| --- | --- |
| `health_connect_status` | SDK/provider availability and current permissions; no health measurements |
| `health_connect_summary` | All permitted categories below |
| `health_connect_activity` | Steps, distance, active and total calories |
| `health_connect_heart_rate` | Heart-rate aggregates, resting heart rate, recent HRV RMSSD samples |
| `health_connect_sleep` | Aggregated sleep duration and bounded recent sessions |
| `health_connect_workouts` | Exercise-duration aggregate and recent session type/time metadata |
| `health_connect_vitals` | Recent oxygen-saturation and respiratory-rate samples |
| `health_connect_body` | Recent weight measurements and sample statistics |
| `health_connect_hydration` | Aggregated hydration volume |

`status` takes no arguments. The other tools take:

```json
{"days": 7, "limit": 50, "source_package": "com.example.health"}
```

`days` is optional (default today), integer 1–30, interpreted as calendar days
including today in the phone's current time zone. At a fall daylight-saving
transition, the default 30-day range is clipped to the rolling 30-day history
limit; it is never expanded. Alternatively supply **both**
`start_time` and `end_time` as ISO-8601 timestamps with `Z` or an explicit offset.
The end is exclusive. Reject reversed, future, ambiguous-offset, unknown-field
and out-of-window requests; never silently extend the requested window.
`source_package` is optional and filters the source app, not the physical watch.
`limit` is an integer 1–100, default 50, per scalar/session record type. At most
four pages are fetched per sampled type; repeated page tokens terminate safely.
Aggregate totals are independent of the recent-record limit.

Outputs carry `gptmobile.health.v1`, explicit status, window/time zone, units,
source packages and query time. `last_source_sync_at` is deliberately null:
Health Connect query time is not the last watch synchronization. Sampled values
have `records_complete` and a sample-statistic method label. Session spans are
not presented as time asleep. Routes, notes, record IDs and device identifiers
are omitted. Explicit null/no-data/permission/error states are never zero-filled.

Activity and sleep totals use Health Connect aggregation instead of summing
raw records. Health Connect's applicable data-origin priority and de-duplication
rules are preserved. Do not promise that every category is de-duplicated across
vendors; use `source_package` when a single source is required. Units are SI
(distance metres, mass kilograms, volume litres), calories kcal, duration minutes,
heart rate bpm, HRV milliseconds, oxygen percent and respiratory rate breaths/min.

## Privacy and execution boundaries

* Android grants, a global plugin switch, per-profile selection and a separate
  device-local AI-sharing consent gate apply. Enabled profiles and consent are
  rechecked after a read, and permissions are rechecked before releasing data.
* The provider is accessed on the IO dispatcher. AI reads have a 20-second timeout;
  a user-started local import has a 120-second timeout. Cancellation propagates;
  provider errors are sanitized. Foreground and unlocked-device checks precede
  and follow measurement reads. The `READ_HEALTH_DATA_HISTORY` permission is
  feature-checked and requested only when the selected local import exceeds
  30 days.
* Results are not eligible for cross-profile shared-result caching. Recovery
  cannot reuse old health tool results instead of making a fresh authorized read.
* The plugin has no health-record database, scheduled worker or independent
  upload endpoint. The optional user-triggered import stores one summary snapshot
  in app-private storage. Aggregate totals span the selected 1-day to 5-year
  window; sampled readings and session examples remain subject to Health Connect
  paging limits. The snapshot stays on the phone until deleted or disconnected and
  is never automatically supplied to an AI. It is excluded from Android's device
  backup. To move it across devices, select **Health data** in the complete
  backup/restore dialog; that section requires encryption and is separate from
  conversation data. Restore validates the saved snapshot and preserves its
  selected time range. The transient Overview preview is cleared when leaving the
  activity and is never stored in `SavedStateHandle`.
* AI-sharing consent lives in `noBackupFilesDir`, outside configuration/complete
  backup payloads. An imported configuration cannot by itself authorize sharing
  on a new installation. Global/profile flags may be backed up; consent is separate.
* `ToolEventRecorder` replaces **both** live/retained recovery payloads and visible
  traces with a redaction marker before persistence. Health query arguments and
  provider error details are not persisted there. Live authorized model tool
  results remain useful for the current answer.
* Health details typed by the user or repeated by an AI **can still exist in
  conversations, backups, memory, exports, or provider retention** under existing
  app settings. Trace redaction is not a promise of end-to-end deletion. The UI's
  explicit consent dialog explains that profiles, gateways and delegates can
  receive data and that those existing retention rules apply.
* Disconnect disables the plugin, revokes AI sharing, deletes this app's saved
  local snapshot and requests revocation of this app's Health Connect permissions.
  It does not delete watch/Health Connect records, previously generated
  conversations or provider-held data.

The public Android permission-rationale activity contains static policy only;
it cannot read data or accept sharing consent. The dashboard is not exported,
uses the app-lock activity base and prevents screenshots of its health view.

## Architecture

- `data/health`: 30-day AI query contract, explicit history-gated local-import
  reader, no-backup consent and app-private import store with backup codec.
- `data/agent/tool/HealthConnectTool`: per-call policy gate and AI schemas.
- `di/HealthConnectModule`: Hilt binding for the native reader.
- `presentation/ui/health`: permission launcher, dashboard and rationale.
- Existing service catalog, marketplace, global/profile settings and resolver:
  real integration, not just a catalog placeholder.
- `ToolEventRecorder` / `ResponseRecoveryContext`: persistence and replay safety.

## Validation and release gates

Run `:app:testDebugUnitTest` (health contract, reader, tool, resolver, recovery and
recorder tests), `:app:lintDebug`, `:app:assembleDebug`, ktlint and resource checks.
The unit tests use synthetic/mock records, never a user's health data.

A physical-device check remains necessary: Android 13 with provider missing/
installed, Android 14+ system Health Connect, partial/denied grants, revoke while
reading, app switching/locking, process restart, source-filtering, delayed sync,
overlapping sources, sleep across midnight/DST, and backup/restore to a clean
installation. Test a real companion app that actually writes each claimed type.
CI compilation is not proof that a particular watch has synced data.

Before store distribution, review Google's Health Connect/Google Play health
permissions policies, complete the appropriate app declaration and data-safety
form, and publish a privacy policy consistent with the in-app rationale and
actual AI provider/retention behavior. No medical-device or universal-watch
compatibility claim is made. This branch does not publish a release.

## SDK references

Verified for this implementation on 2026-10-11. Stable SDK pin: 1.1.0 (not the
1.2 alpha channel).

- https://developer.android.com/jetpack/androidx/releases/health-connect
- https://developer.android.com/health-and-fitness/health-connect/get-started
- https://developer.android.com/health-and-fitness/health-connect/aggregate-data
- https://developer.android.com/health-and-fitness/health-connect/read-data
- https://developer.android.com/health-and-fitness/health-connect/plan/data-types

