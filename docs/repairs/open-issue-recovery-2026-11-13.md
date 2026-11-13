# Open report review — November 13, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. No draft PR was open at intake. This single repair branch starts at PR #1046, `cb401a9d6936264e1d9a053adcfd82613a6b0cc9`, preserving its interface and diagnostic changes. The issue reports were captured on app commit `feb187a6f1f85b584c826c9f5f4e8ef5dfecd1a6`, build 141. No new diagnostic archive was attached to this request; this pass reviewed the current issue payloads and repository code. It does not claim to have rerun the earlier phone capture.

| Issues | Finding and disposition |
| --- | --- |
| #1047 | Confirmed lifecycle defect: the preferences ViewModel cancelled the process-shared asset store when cleared, including an install owned by the background worker. Removed that cancellation. Added immediate queued/waiting feedback, themed progress, readiness feedback, visible scheduler/worker errors, and fixed-enum installation diagnostics. Actual phone scheduling and downloads remain unqualified. |
| #1048, #1049, #1050, #1052 | Retained reports show transient workflow-definition reads and circuit cooldown, but omit the transport subtype and wait duration. Capture DNS/disconnect/timeout/HTTP/circuit classification at the failing boundary and preserve it through dispatch accounting. Reporting-only resolution: this cannot repair an unobserved network or host configuration. Existing bounded read recovery and mutation reconciliation remain enforced. |
| #1051, #1053, #1054, #1055 | Reports show conditional ASK fixture approval, absent owned fixtures/workflow inputs, incomplete operation coverage, and pending remote continuations. Preserve repository-input, workflow-input and live-evidence blocker counts plus failed-operation counts from the exact verdict. Reporting-only resolution; missing authorization, fixture ownership and live executions are not fabricated. |
| #1056, #1059, #1060 | Source reads were rejected for line-window constraints. Reuse one validator for admission and fixed diagnostic subtypes: invalid start, conflicting limits, invalid max-lines and invalid end. Reports retain range/error counts without source paths or contents. Corrective tool feedback explains a valid bounded request. The original raw ranges are unavailable, so no original provider proposal is declared repaired. |
| #1057, #1058 | Issued, unexpired saved cursors were rejected. The context-mismatch path returned an explanatory body but omitted its typed reason from the diagnostic receipt. Preserve SAVED_VIEW_CONTEXT_MISMATCH and conflicting field names; retain exact-cursor correction and scope checks. These captures are consistent with that path; original arguments are not available to prove which action conflicted. |
| #1061 | per_page was out of range. Add explicit schema-bound corrective guidance and the bounded numeric page-size observation. No IDs, source values or credentials are copied. Reporting-only diagnosis of the original proposal. |
| #1062 | Acceptance rejected unsupported claims despite one eligible receipt. Add unresolved-claim counts and fixed failure reasons from the exact retained verdict, distinguishing absent pointers, semantic mismatches, stale observations and contradicted values. Reporting-only resolution; no criterion becomes verified without evidence. |
| #1063, #1064, #1065, #1066 | Strict Reasoning Off rejected before dispatch. Preserve requested mode, lack-of-verified-control basis, direct/gateway route, observed control state and hashed template revision when present. Reporting-only resolution; strict Off is not silently changed to Auto and unsupported template controls are not bypassed. |

## Reporting deployment

The client requires `ISSUE_RECOVERY_DETAILS_V1` before sending these new observations. The matching reporting service validator and advertised capability are included. Deploy that service version before collecting a new approved report from the updated APK. Old approved payloads are not rewritten. Manual voice snapshots remain subject to diagnostics enablement, private-session exclusion, explicit preview, bounded fields and ten-minute retention.

## Prior log limitations

Inherited PR #1046 contains stable operation IDs, URL-reader DNS observations, model preparation and other gateway evidence improvements. This repair does not establish physical-device latency, eliminate the earlier UI stalls, configure private MCP servers, or prove complete fixture/live-tool coverage. Those require device/host access and fresh captures. Issue closure for a reporting-only item records the diagnostic improvement, not proof that its original external cause disappeared.

## Validation

Canonical fast validation, focused JVM report/argument tests and reporting-service checks are recorded in the task checkpoint. Host tests use explicit Android/application boundaries and are not a full Android build. The local Gradle Android attempt lost its daemon during `kspDebugKotlin`. Hosted CI and physical-phone download qualification remain required before release.
