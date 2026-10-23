# Free provider budgets, cooldowns and failure evidence

The remaining failures reported for `export_Multi-chat_1792697950265.zip` were
reviewed against main `d7e231604677e100a27c975d37d4ce3a5571ab59`. This change treats
request size, allowance restrictions, access denials and terminal queue failures
as separate conditions. No diagnostic export or private conversation is committed.

## Behavior

- Free catalog records now store context and output capacities separately, merge
  discovered limits with reviewed defaults, and preserve them in the existing
  disk cache. BridgeNode's `gpt-oss-20b` route has an observed 8,000-token context
  fallback. Other unknown capacities remain explicitly unknown.
- Final serialization counts the complete payload, including system instructions,
  history and tool schemas, and resolves output allowance with the existing input
  estimate and safety reserve. One recognized total-context rejection can teach
  the endpoint/model capacity and retry with a smaller output allowance before
  any answer is delivered. Output-only limits cannot become context limits.
- Anonymous Horde text payloads cap `max_length` at 256 and set `allow_downgrade`.
  The queued notice reports the actual transmitted allowance and explains that
  additional server reductions are possible but not reported. Image requests
  retain their separate parameters. KudosUpfront, SharedKeyEmpty and AnonForbidden
  remain distinct diagnostic codes.
- Free HTTP failures retain bounded sanitized details before handling 429/403.
  Failure records include stage, endpoint/model, HTTP status, response format,
  request ID, safe code/message, reset headers, supplied quota scope, retry deadline,
  and the actual admission budget. The independent 64-record history is available
  in conversation and diagnostic exports even when verbose tracking is off.
  Temporary chats do not create retained records; deleting a chat removes its
  records and clearing diagnostic logs clears this history.
- Gradio error events preserve bounded details, including empty/null payloads and
  a final event without an SSE separator. Accepted job ID, last progress and
  terminal status are retained. Failed jobs remain failed and cannot resume;
  interrupted streams keep their existing resume behavior without resubmission.
- Provider cooldown deadlines persist across process restarts, with OVH model
  isolation. The provider picker and Free Models cards show a live countdown,
  suggest choosing another provider, and describe expiry as eligibility to recheck,
  not a guaranteed quota reset.
- Health distinguishes transport failures, invalid requests, rate limits, allowance
  restrictions, access denials, generation failures and completed responses.
  Catalog reachability does not erase a generation failure. Repeated access denials
  pause only the rejected model for one minute; blocked requests cannot clear the
  pause. No paid fallback, credential substitution or alternate host is introduced.
- PorchLM is labeled Demo / Experimental. Its seeded profile is disabled by the
  versioned migration and in new installations; users can enable it explicitly.
  Custom profiles are preserved. Transport completion remains quality-unverified;
  the existing benchmark tasks score explicit answers separately.

## Verification

Focused JVM coverage exercises actual production request preparation, OpenAI
stream transport, queued adapters, catalog parsing, failure retention and request
limiting using Ktor MockEngine. The isolated harness substitutes Android/framework
services and the injected network client; it is not a complete Android build.
37 focused checks passed. Regression checks cover serialized BridgeNode budgets (including tool definitions),
a single corrected retry, output/context separation, anonymous Horde payloads,
Gradio terminal and interrupted jobs, diagnostic redaction and deletion, cooldown
restart/expiry, and reachable-versus-ready health. Changed-source ktlint, Android
regex checks and resource preflight are also run.

The local Gradle wrapper could not download Gradle 9.8.0 because its Java network connection was unavailable; full Android build/Compose validation remains unrun here.

Live provider success is not asserted. OpenCode access policy, Qwen's upstream
service and shared free-provider quotas remain controlled by those services.
