# Privacy-Verified AI Profiles

Privacy Mode is shared by Settings and New Chat. On-device text inference needs no provider approval. The Windows Gateway checks its configured loopback llama.cpp or Ollama server and installed GGUF model, then sends text directly to that server. The protected endpoint skips saved jobs, external tools and Gateway debug capture. An older Gateway or a cloud relay cannot satisfy its capability check. The private Gateway host and its model-server logging configuration are under the operator's control.

## Quick personal OpenRouter setup

Open Settings → Privacy, choose your saved OpenRouter profile, enter a management key once, and select **Check my account**. Leave the key field blank to reuse the securely saved key. A read/write management key scoped to the profile's workspace enables automatic setup. The app retains the ordinary inference key in its profile.

The app identifies the workspace from the exact saved inference key and shows its name, current controls and any policy review needed. It never chooses an account by a key label or scans every policy to inspect the selected account. Review default plugins in that workspace, confirm they are disabled, then select **Apply recommended settings**. There is no public-key, signed-JSON, digest, timestamp or guardrail-ID entry in this personal flow. OpenRouter's assignment GETs may materialize its existing assignment representation and require read/write management access; checking does not submit the privacy-settings PATCH or policy-creation/assignment writes.

| Control | Setup behavior |
| --- | --- |
| Prompt/input-output logging | Read the exact workspace, apply `is_observability_io_logging_enabled: false` if needed, then read it back. |
| Product-improvement/data-discount collection | Apply and verify `is_data_discount_logging_enabled: false`. |
| Broadcast exports | Apply and verify `is_observability_broadcast_enabled: false`. |
| Strict ZDR | Reuse a verified strict direct or inherited policy. For a key with explicitly no member creator, no policy and no workspace default, create a dedicated strict policy and attach it with the API's assignment comparison. |
| Default plugins | One explicit account-owner confirmation. The documented API does not expose default-plugin settings or override locks. Request-level plugin disabling is also enforced. |

Applying the three workspace controls affects other keys and applications in that workspace. The preview explains this before the user applies them. Setup does not replace existing model/provider restrictions, change spending limits, or overwrite shared policy configuration. If an existing or inherited policy needs stricter settings, setup identifies that review step: enable ZDR for every model group and turn off both Safety Retention options in OpenRouter Guardrails, then check again. The API provides an assignment comparison but no atomic version check for policy configuration, so copying or replacing that configuration would not safely preserve concurrent changes.

The plugin confirmation is explicitly **confirmed by you**, expires within 24 hours, and is not presented as provider-observed telemetry. Current API checks of account controls, exact model, upstream, policy and key assignment still run before use and again at the final dispatch boundary. Returning from the Privacy panel in New Chat triggers profile checking automatically. Unsupported or unverified models remain unavailable without turning Privacy Mode off.

Personal setup is bound to the official inference endpoint, exact credential digest, verified workspace, current policy and a fresh device-local setup identity. It can serve profiles sharing that key and endpoint. Managed configuration remains authoritative. Selecting a signed issuer disables the personal path; signed and managed setup remains under **Advanced / organization setup**. Personal confirmations and the privacy management key are excluded from the app's backup/import workflow, and restore revokes personal confirmations. Removing setup or changing credentials invalidates prepared work.

Every supported account mutation records a bounded, secret-free intent before transmission. Lost PATCH, POST or assignment responses are reconciled by reading the original workspace and exact intended policy. A retry button, process restart, renewed permission or removal of setup cannot repeat an uncertain mutation. Policy creation uses a stable opaque name and exact configuration matching so a confirmed, unassigned creation can be reused after restart. An unresolved intent for another account must be reconciled there first.

Account setup sends metadata requests and fixed privacy settings, never conversation text. Authentication stays on the official OpenRouter management origin with redirects and connection retries disabled. Metadata uses its own network dispatcher so dispatch-boundary verification cannot queue behind the inference calls waiting for it.

Protocol references: [key hash](https://openrouter.ai/docs/guides/overview/auth/oauth), [key/workspace binding](https://openrouter.ai/docs/api/api-reference/api-keys/get-a-single-api-key), [workspace controls](https://openrouter.ai/docs/api/api-reference/workspaces/update-a-workspace), [policy hierarchy](https://openrouter.ai/docs/guides/features/guardrails/policies), [conditional assignment](https://openrouter.ai/docs/api/api-reference/guardrail-policies/change-an-api-key-policy-assignment), and [default-plugin behavior](https://openrouter.ai/docs/guides/features/plugins).

## Advanced managed or signed account setup

Obtain current evidence from the provider for the account, credential, inference endpoint and all enabled features. The account administrator issues an approval only after checking no routine storage of prompts or completions, no training or product-improvement collection, disabled content logs, disabled Broadcast and default plugins (including defaults that prevent request overrides), and the applicable strict retention tier. The approval is for stateless text only. Retract an approval immediately after learning of a policy change. Approvals expire within 24 hours.

OpenRouter supports two explicit approval scopes. An existing approval defaults to `exact-route-v1` and continues to authorize only its exact model and upstream. An administrator may instead issue `openrouter-account-v1` with both `model` and `upstream` set to `"*"`. That scope approves the account settings which an ordinary inference key cannot verify; the app must separately verify and pin every selected model and upstream before dispatch. It does not approve arbitrary providers, endpoints or features. It applies only to official OpenRouter API endpoints and the exact credential digest. Other providers retain exact-route approvals. Do not broaden an old signed record or reuse its signature after editing it.

Provide the JSON array below through the Android managed configuration key `verified_provider_routes`. Managed configuration takes precedence, including an empty array for revocation. For a personal phone the same array can be signed by the account administrator. Distribute the administrator’s public key over an independently trusted channel. A signed statement authenticates its issuer. The issuer is responsible for checking the actual provider agreement and dashboard settings.

```json
[{
  "scope": "exact-route-v1",
  "endpoint": "https://openrouter.ai/api/v1/chat/completions",
  "model": "<exact model ID>",
  "credentialSha256": "<SHA-256 of the exact UTF-8 inference key, without a newline>",
  "upstream": "<exact catalog endpoint tag>",
  "policyUrl": "https://openrouter.ai/docs/guides/features/zdr",
  "policyDocument": "https://openrouter.ai/docs/guides/features/zdr.md",
  "policySha256": "<SHA-256 of the approved first-party UTF-8 policy document>",
  "verifiedAtMillis": 0,
  "expiresAtMillis": 0,
  "feature": "stateless-text-v1",
  "retentionTier": "zdr",
  "noTraining": true,
  "loggingDisabled": true,
  "externalToolsDisabled": true,
  "providerDefaultsDisabled": true,
  "safetyRetentionExcluded": true,
  "guardrailId": "<guardrail UUID>",
  "providerKeyHash": "<key hash issued by OpenRouter>",
  "allowGuardrailTightening": false
}]
```

Pin the content of the provider’s current policy document. Prefer a stable first-party plain text or Markdown edition. The verifier performs a credential-free GET with `Accept: text/markdown, text/plain, text/html`, follows redirects only within the provider documentation hosts, and hashes the UTF-8 response including trailing newlines. Hash the same representation when issuing the record. A changed document, unreachable policy host or mismatched digest disables the route.

Replace the placeholders, including both epoch-millisecond timestamps. The example is expired. For anonymous OVHcloud use the SHA-256 of the empty string. The three guardrail fields apply only to OpenRouter. Do not put API keys in the document. The policy URL for each route is the provider terms link shown on its profile card.

For an administrator-signed setup, use a P-256 ECDSA signing key controlled by the administrator. Sign the exact UTF-8 bytes of the approval array with SHA-256. Encode those bytes and the DER signature as standard base64 in an envelope with string fields `payload` and `signature`. In Settings → Privacy → Advanced / organization setup, first trust the separately received base64 DER SubjectPublicKeyInfo public key, then import the signed envelope. Replacing the issuer clears the old approval. Removing personal verification settings clears the trust anchor, approval and locally encrypted management credential. A device-local `openrouter-personal-v1` record cannot be imported through a signed file or managed configuration.

OpenRouter also needs a management credential for live guardrail reads. Set the applicable model group to require ZDR and both Safety Retention choices to ZDR only. Attach the guardrail directly to the inference key. The app checks the live key, endpoint catalog, guardrail and key assignment each time. Automatic tightening requires explicit permission in Settings and `allowGuardrailTightening` in the administrator's approval. Use a dedicated guardrail. Tightening affects every key or member subject to that rule.

Before an authorized guardrail PATCH, the app durably records an intent bound to the guardrail, assigned key and exact intended strict settings. A lost response or interrupted confirmation leaves that intent unresolved. Readback can confirm the same settings; checking a different model group cannot clear the intent. Renewing permission or removing personal verification settings does not erase this opaque pending receipt or authorize a repeat. Confirm or apply the strict settings in OpenRouter, then recheck the same route so the app can confirm the original intent.

## Automatic OpenRouter qualification

New Chat distinguishes **Checking**, **Available**, **Not verified** and **Incompatible**, shows the specific blocker and offers **Recheck** and account setup. Verification checks metadata without sending conversation text. Multiple profiles are checked with bounded concurrency; a newer check or a changed privacy setting invalidates old results. Starting a conversation rechecks the selection, and the request path performs its own live checks before dispatch. A failed check keeps Privacy Mode enabled and preserves the conversation.

For a configured or approved OpenRouter account, the verifier:

1. Checks the inference key, current personal setup or signed/managed approval, and pinned first-party policy document. Personal setup additionally requires current workspace control and modern policy observations.
2. Resolves the selected named model using the provider's model metadata. Account approvals may follow one explicitly observed alias to a concrete model; unresolved aliases, automatic routers, presets and online variants stay blocked. A free model is never silently changed into a paid selection.
3. Cross-checks the ZDR endpoint catalog against the complete model endpoint inventory. The catalog must explicitly report the literal boolean `safety_retention: false`; missing, malformed, ambiguous or contradictory evidence is not approval. A provider-prefix selector is allowed only if every matching endpoint variant is verified, even when a variant is currently unavailable.
4. Combines connection-wide and profile routing restrictions. Provider allowlists intersect, exclusions accumulate, quantization restrictions intersect and price ceilings remain bounded by both settings. No compatible endpoint means a visible blocker, not a relaxed restriction.
5. Verifies the actual model group's strict guardrail and assignment. Personal setup verifies current modern policy and exact direct or inherited applicability. The `zdr: true` request flag alone does not exclude Safety Retention.
6. Pins the observed concrete model and verified provider selector for this request. The wire enforces `zdr: true`, `data_collection: deny`, `allow_fallbacks: false`, `require_parameters: true`, disables response caching, and explicitly disables the ten documented overridable plugins: Alignment, Armor, Auto Beta Router, Auto Router, Context Compression, File Parser, Fusion, Pareto Router, Response Healing and Web. Current account evidence must also exclude locked default plugins and external exports.

Immediately before the HTTP body can be sent, the app rechecks approval, original request authority, cancellation, connection routing revision and privacy settings. Privacy revisions include custom-term edits and off/on transitions, so a queued mask cannot be reused after settings change. No inference retry or fallback is authorized by an eligibility result.

These are separate checks because OpenRouter's routing metadata is not an attestation of every account setting. An ordinary API key alone cannot establish disabled I/O logging, external Broadcast exports, product-improvement collection or locked account plugins. The guided setup obtains the supported API observations automatically and records the plugin confirmation separately; advanced users may retain their signed/managed evidence. Profiles remain **Not verified** until those requirements pass. The app must not display **Available** solely because a model appears in a ZDR list.

Protocol references: [ZDR and Safety Retention](https://openrouter.ai/docs/guides/features/zdr), [provider selection](https://openrouter.ai/docs/guides/routing/provider-selection), [single-model metadata](https://openrouter.ai/docs/api/api-reference/models/get-a-model-by-its-slug), [model endpoint inventory](https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model), and [plugins](https://openrouter.ai/docs/guides/features/plugins). Live acceptance must recheck the current provider contract and use the actual selected account and model.

OpenAI uses stateless Chat Completions with storage disabled. Anthropic uses Messages. Google requires an independently approved Vertex OpenAI-compatible deployment and a current bearer token. The standard Gemini Developer API stays blocked. Of the bundled free routes, only the fixed anonymous OVHcloud API can use an account administrator’s exact-model approval. Automatic routers, volunteer workers, retired endpoints and routes without independently enforceable upstream privacy remain unavailable.

## Require verified retention

Enable **Require verified retention** in Privacy Options to apply it to sends and retries in every locked private conversation on this device. All enabled profiles remain selectable. On-device inference stays available. A Gateway or cloud profile must pass the existing route, account and model checks before inference. When verification is unavailable, the message stays in the chat and the provider receives no conversation content. Verification can make metadata requests without sending the conversation. A change to this setting invalidates requests prepared under the previous policy.

The option starts off for existing installations. In that mode an unverified cloud profile may receive locally masked text under the provider’s own policy.

## What is sent

The protected wire format accepts text, a single model and a bounded output allowance. Local masking runs over the entire outgoing text history and system message. Its encrypted, request-scoped replacement map stays in memory. Custom terms are stored in the Android Keystore-backed vault. Automated pattern detection has limits, particularly for unmarked names and contextual facts. Original conversation history remains on the device. Account agreements may retain usage metadata and permit legally required exceptions.

Policy checks record a timestamp, eligibility, bounded status/reason and terms URL under a hashed route identity. Historical receipts never authorize a new request. Typed failure evidence distinguishes a request rejected before dispatch from a dispatched request whose remote acceptance is unknown. Neither state authorizes automatic replay or logical continuation. Private sessions continue to suppress incident and diagnostic journal capture; the typed contract does not opt private conversations into reporting.

Source tests and CI do not establish live account policy or installed-device behavior. Physical Android, live selected-model GitHub operations, durable Android-to-Windows Gateway recovery and real Windows installer execution remain separate release gates until their matching-build acceptance evidence is recorded. A code merge does not authorize publishing a release.
