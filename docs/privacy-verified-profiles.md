# Privacy-Verified AI Profiles

Privacy Mode is shared by Settings and New Chat. On-device text inference needs no provider approval. The Windows Gateway checks its configured loopback llama.cpp or Ollama server and installed GGUF model, then sends text directly to that server. The protected endpoint skips saved jobs, external tools and Gateway debug capture. An older Gateway or a cloud relay cannot satisfy its capability check. The private Gateway host and its model-server logging configuration are under the operator's control.

## Cloud account setup

Obtain current evidence from the provider for the account, credential, exact model, inference endpoint and all enabled features. The account administrator issues an approval only after checking no routine storage of prompts or completions, no training, disabled content logs, disabled broadcast and default plugins, and the applicable strict retention tier. The approval is for stateless text only. Retract an approval immediately after learning of a policy change. Approvals expire within 24 hours.

Provide the JSON array below through the Android managed configuration key `verified_provider_routes`. Managed configuration takes precedence, including an empty array for revocation. For a personal phone the same array can be signed by the account administrator. Distribute the administrator’s public key over an independently trusted channel. A signed statement authenticates its issuer. The issuer is responsible for checking the actual provider agreement and dashboard settings.

```json
[{
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

To sign for a personal phone, use a P-256 ECDSA signing key controlled by the administrator. Sign the exact UTF-8 bytes of the approval array with SHA-256. Encode those bytes and the DER signature as standard base64 in an envelope with string fields `payload` and `signature`. In Settings → Privacy → Set up account verification, first trust the separately received base64 DER SubjectPublicKeyInfo public key, then import the signed envelope. Replacing the issuer clears the old approval. Removing personal verification settings clears the trust anchor, approval and locally encrypted management credential.

OpenRouter also needs a management credential for live guardrail reads. Set the applicable model group to require ZDR and both Safety Retention choices to ZDR only. Attach the guardrail directly to the inference key. The app checks the live key, endpoint catalog, guardrail and key assignment each time. Automatic tightening requires explicit permission in Settings and `allowGuardrailTightening` in the administrator's approval. Use a dedicated guardrail. Tightening affects every key or member subject to that rule.

OpenAI uses stateless Chat Completions with storage disabled. Anthropic uses Messages. Google requires an independently approved Vertex OpenAI-compatible deployment and a current bearer token. The standard Gemini Developer API stays blocked. Of the bundled free routes, only the fixed anonymous OVHcloud API can use an account administrator’s exact-model approval. Automatic routers, volunteer workers, retired endpoints and routes without independently enforceable upstream privacy remain unavailable.

## What is sent

The protected wire format accepts text, a single model and a bounded output allowance. Local masking runs over the entire outgoing text history and system message. Its encrypted, request-scoped replacement map stays in memory. Custom terms are stored in the Android Keystore-backed vault. Automated pattern detection has limits, particularly for unmarked names and contextual facts. Original conversation history remains on the device. Account agreements may retain usage metadata and permit legally required exceptions.

Policy checks record a timestamp, eligibility and terms URL under a hashed route identity. Historical receipts never authorize a new request. Network failures and rejected proofs preserve the conversation and keep Privacy Mode enabled.
