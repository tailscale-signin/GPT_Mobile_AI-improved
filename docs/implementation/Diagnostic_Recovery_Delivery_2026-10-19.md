# Diagnostic recovery and delivery repairs

Input: `app-diagnostics-8530666975550316155.jsonl`, exported from version 0.9.53.0 build 122. No raw requests, credentials, addresses or conversation content are committed.

## Findings and changes

| Recorded symptom | Change or disposition |
| --- | --- |
| Repeated `BLOCKED_PERMISSION` during saved-response preparation | Search-result reuse now compares request policy and profile revisions rather than every display preference. Actual permission, destination and credential changes still block reuse. |
| `DISPATCH_POLICY_CHANGED` after queued work | Price quote refresh timestamps/provenance do not invalidate authority. Changed prices and spending limits still do. Other genuine configuration changes continue to stop stale requests. |
| Discovery rejected by the optional Combined allowance | Local schema selection coordinates its children instead of reserving an external evidence operation. Wrapped cold acquisitions keep their own admission. The closed-turn and call-count checks remain. |
| Research finishes with zero readable pages | Resume a saved plan with no frontier cursor using undispatched fallback queries. Empty model gap queries cannot declare success while application coverage is incomplete. Existing evidence-gain and user limits still stop unproductive work. |
| Research repeatedly rewrites an unread-only corpus | Emit the retained partial evidence report and a precise acquisition failure without repeated writer repairs. An unread lead is never upgraded to reviewed evidence. |
| Combined structured delivery rejects provider formatting | Accept JSON/JSONL fences around strictly validated records, including fragmented fences and checkpoint restarts. Prose, invalid references, conflicting replays and sequence gaps remain invalid. |
| Rendering stalls and methods over Android's compiler instruction threshold | Scan only new characters of a fragmented Combined record. Move AgentRunner result finalization and OpenAI-compatible Ollama collection into separate coroutine methods. Device frame-time improvement still requires on-device measurement. |
| Nine tool slots, incomplete function arguments and output limits | Preserve pre-dispatch rejection and existing bounded recovery; never execute a partial or oversized batch. |
| MCP endpoints unreachable; provider connection abort and DNS failure | External connectivity remains a prerequisite. Retained work must remain available, but this branch cannot start a server on the user's network or restore the phone's DNS connection. |
| Repeated identical marketplace search suppressed | Preserve the repeat limit; it prevents repeating identical work. Independent requests remain eligible within accepted budgets. |

## Regression coverage

Tests cover display edits versus revocation, quote refresh versus changed rates, local discovery with zero external-work allowance, saved plans without a cursor, unread-only reports, and fenced records split at every character boundary with checkpoint restoration. Existing AgentRunner and provider tests validate the coroutine extraction.

Validation results are recorded in PR #722. Merge requires the applicable checks to pass; local formatting alone is insufficient.
