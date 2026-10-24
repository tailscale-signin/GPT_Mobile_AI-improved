# Home progress, shared icons, and free-provider restoration

Date: October 24 2026, Toronto time.

This implementation follows the user's six requested changes on the Research stability design branch. The [Research design document](../design/Research_Mode_Stability_Implementation_2026-10-23.md) contains the export audit, 17 issue groups, proposed architecture, rollout, and 24 Research acceptance scenarios. Its Appendix D explains this implemented follow-up.

## Result

| Request | Implemented behavior |
| --- | --- |
| Combined loading bar | Inside the title/profile text column, with a themed panning gradient. Current primary delivery is required for 100 percent. A visible bar briefly completes and disappears; failure or stop clears it. Previously completed rows do not replay it. |
| Remove equals icon | Removed the trailing drag handle. Drag organization remains on the leading conversation icon, and row long press still selects. |
| Profile text transparency | Theme alpha is half its previous computed value. |
| Shared type icons | Home and New Chat use the same outline/filled mapping, including Research. New Chat uses outlines; a saved unseen reply produces the filled counterpart. Archive rows use the same unread and Research state. |
| Pollinations in Combined | Uses anonymous Classic OpenAI-compatible POST with its fixed 1,500-token allowance. Contributor eligibility, planning, physical admission, cooldowns, usage, and finish reasons reflect the actual route. |
| Free LLM7, optional key | Restores anonymous access on the existing endpoint. A personal token is optional. Each generation selects a concrete model from a fresh verified free-allowance catalog; paid-only models and app-selected paid fallback are excluded. |

## Correctness details

Progress follows the latest user turn and the current message owner, including its generation fence. An old failed final run cannot hide a new retry. The completion transition also tolerates a brief gap between active-run and durable-delivery updates, while remaining one-shot. Research completion additionally requires acceptance of the exact answer revision. Published reply identity and durable viewport acknowledgements remain the unread authority.

LLM7 now distinguishes `acceptsUserToken` from `requiresUserToken`. Optional secrets survive editing and vault resolution, but are removed when switching to an anonymous provider that does not accept them. Blank keys emit no Authorization header. Catalog and generation requests stay on the validated provider host with redirects disabled. Concurrent catalog checks return independent immutable results, and final admission retains the request's selected model metadata.

Pollinations Classic always forwards a 1,500-token generation cap, even if the client requests less. Requests with smaller hard output allowances or insufficient context headroom therefore fail before dispatch. Low-effort contributor planning funds the fixed allowance without raising explicit turn limits. The current plan uses a common contributor allowance, so including Pollinations may conservatively reserve more for other contributors. Final writers still need sufficient capacity for their task. HTTP 402 with Retry-After is treated as a free-slot cooldown; the separately paid route is not used.

## Validation

The actual Gradle validation compiles production and test Kotlin and executes focused Home, publication, provider, transport, admission, budgeting, credential, and settings suites. The local runner uses JDK 21, the repository's Gradle version, cached dependencies, the verified embedding model, and a Byte Buddy test agent. It does not change project build configuration.

| Check | Result |
| --- | --- |
| Production and test Kotlin compilation | Passed on the final source revision. |
| Focused Android unit tests | 230 passed across all 28 selected classes; zero failures, errors, or skips. Final Gradle run: BUILD SUCCESSFUL in 2 minutes 50 seconds. |
| Source consistency | All 2,370 source/configuration fingerprints remained unchanged during the final run. |
| ktlint 1.3.1, changed Kotlin files | Passed for all 43 changed Kotlin files. |
| Android resource preflight | Passed, including icon reference validation. |
| Android regex compatibility | Passed: 815 literal patterns checked, zero errors; 50 dynamic patterns skipped. |
| Reliability scope check | Passed; scope inventory is not device qualification. |
| Repository script unit tests | 157 passed. Local transport fixtures needed loopback-socket permission. Their synthetic instrumentation output is not a device-test result. |
| Whitespace check | Passed. |

The passing suites include Combined provider eligibility, runtime policy, single-pass delivery and turn budgets; provider adapters; durable reply publication; free-model catalog, access and reliability; request limiting, JSON/SSE transports, contract validation, queue recovery and physical receipts; model discovery and secret migration; all four Home progress/evidence/unread suites; selection gestures; and profile/setup settings. The Compose presentation tests exercise text-column bounds, unread Research icons, stopped-state clearing, and the one-shot completion transition after an asynchronous state gap.

### Live public-endpoint checks

The JSON and streaming generation checks used the harmless prompt “Reply with OK.” on October 24. No account credential was supplied.

| Service and operation | Observed result |
| --- | --- |
| LLM7 public model catalog | HTTP 200; five chat models explicitly marked Turbo and not usage-based-only. |
| LLM7 standard chat completion | HTTP 200, `codestral-latest`, nonempty answer, finish reason `stop`, 7 input and 3 output tokens. |
| LLM7 streamed chat completion | HTTP 200, four SSE events, nonempty answer, finish reason `stop`, usage, and the final `[DONE]` marker. |
| Pollinations Classic POST | HTTP 200, actual model `gpt-oss-20b`, nonempty answer, finish reason `stop`, 312 input and 28 output tokens. |
| Pollinations Classic streamed POST | HTTP 200, 18 SSE events, nonempty answer, finish reason `stop`, 312 input and 24 output tokens, and the final `[DONE]` marker. |

LLM7's current published documentation says a token is required, while this ordinary public endpoint accepted the anonymous request. Historical PR 510 also records anonymous success; PR 732 introduced the app's later required-token behavior. This implementation follows the verified working route, with an actionable optional-token message if the service later rejects anonymous access. No hidden endpoint, fabricated key, or alternate provider was used.

Live checks establish current endpoint behavior, not end-to-end Android execution or continued uptime. A token for a paid or topped-up account remains subject to that account's billing rules, even for a model eligible for the free allowance.

## Scope remaining

The resource-aware Research scheduler, durable execution attempts, benchmark handoff, richer failure receipts, and other architecture work in the design are proposals for subsequent implementation. This change does not claim that all 17 audited issue groups or all 24 Research acceptance cases are repaired.

No physical Android device or emulator was available. Compose regression checks cover layout bounds and presentation transitions, but device rendering, animation feel, and a full Combined conversation still require device review. Existing progress aggregation across legacy independent Multi-chat child lanes is unchanged; current shared-room Multi-chat and durable child-to-parent unread mapping are covered.
