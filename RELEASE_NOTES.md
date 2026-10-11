# GPT Mobile AI 0.9.42.0

## Smartwatch Health Connect
- Add a native, read-only Health Connect plugin for health records synced to the Android phone by compatible watch companion apps. No external MCP server or API key is required.
- Add themed Overview, Access and AI Tools tabs for local statistics, Android permissions, provider installation/settings, per-profile access and disconnect controls.
- Expose nine phone-owned AI tools: status, summary, activity, heart rate, sleep, workouts, vitals, body measurements and hydration.
- Support thirteen read-only record categories: steps, distance, active and total calories, heart rate, resting heart rate, HRV RMSSD, sleep, workouts, oxygen saturation, respiratory rate, weight and hydration.
- Bound queries to the last 30 days, with explicit time ranges, source-app filtering, units, timestamps and provenance. Missing data, denied permissions and provider failures remain distinct; unavailable measurements are not reported as zero.

## Privacy and access
- The plugin and per-profile access are off by default. AI access requires separate, device-local sharing consent; viewing the local Overview does not grant that consent.
- Require the app to be visible and the phone unlocked, recheck access before returning records, and prevent cross-profile cache reuse and recovery replay of health results.
- Redact stored health-tool arguments and payloads; protect the dashboard with the app lock and screenshot restrictions.
- Authorized remote profiles, gateways and delegates can receive tool results. Health information repeated in AI replies can still be retained by existing conversation, memory, backup/export and provider settings. This release does not claim end-to-end deletion.

## Setup and scope
- Open Settings > Plugins/Tools > Smartwatch Health Connect > Configure. Grant only the desired read categories, check Overview, then explicitly enable AI sharing and the permitted profiles.
- The watch companion app must already share supported records with Health Connect. This plugin reads synced history, not live Bluetooth/watch sensors, and does not provide medical diagnosis.
- Availability depends on the phone, companion app, permissions and shared record types. See docs/health-connect.md for setup, privacy details and the physical-device test checklist.

## Validation and distribution
- The feature's strict Android unit-test, lint and debug-APK validation passed before release preparation. The release workflow independently validates its exact commit, builds the artifacts and verifies native packaging and signing-certificate continuity before publication.
- Includes 28 targeted regression tests covering health queries, SDK requests, permissions, tool access, recovery, trace redaction and a daylight-saving boundary.
- Physical-watch/device testing and Google Play health-data declarations/privacy-policy review remain required before claiming device certification or store readiness. This publication is a GitHub release, not a Google Play submission.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.42.0
- Version code: 111
