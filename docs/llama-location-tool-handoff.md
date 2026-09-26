# Llama device-location handoff

The reported `ACCESS_DENIED`, `permission_status`, and string `accuracy: "high"`
do not match `DeviceLocationTool`'s output. A model-written tool report does not
establish that Android denied permission. Check the actual tool card and trace.

## App fixes

- Decode non-streaming OpenAI-compatible `choices[].message` responses, including
  reasoning, usage, complete tool calls without streaming indices, and Gateway headers.
- Accept valid structured tool calls completed with `stop`, `tool_calls`, or an
  explicit SSE `[DONE]`. Generate missing call IDs once and replay those IDs in
  assistant/tool continuation messages.
- Fail incomplete calls on interrupted streams, output limits, or content filters.
  Do not interpret prose, code examples, or Gateway progress as executable calls.
- For narrowly matched, explicit requests such as “Where am I?” or
  “Test device_location”, llama and Ollama sessions first ask the app's existing
  agent runner to execute the resolved native tool. The actual result then reaches
  the provider as a normal tool exchange. This uses the same permission checks,
  budgets, tracing, result measurements, and multi-chat sharing as other tools.
- Profile settings, local-tool restrictions, Advanced Settings, and per-chat
  exclusions still apply. The direct-request step requires the built-in tool to
  survive resolution and to be exposed by the runner's current budget.

Complex prompts (including nearby-place searches), other languages, and indirect
location requests continue through ordinary model function calling. The direct
step deliberately avoids reading GPS for logs, quotations, or capability questions.

## Server requirements

For ordinary model-generated calls, llama.cpp requires Jinja function calling and
a tool-aware chat template appropriate for the model. See upstream
[function-calling documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)
and [server options](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).
The Gateway must return client-owned calls as structured `tool_calls` and accept
the app's subsequent tool-result messages. A remote Gateway cannot read Android
GPS or determine the phone's permission state by itself.

## Device verification

1. Install a build containing this fix and enable Device location for the llama
   profile, with local tools and the Advanced Settings location feature allowed.
2. In a new chat, send “Where am I?”. Verify a real “Finding location” tool card
   completes before the answer. Expand it to inspect latitude, longitude,
   `accuracy_meters`, provider, and timestamp (when available).
3. Repeat with streaming disabled; the answer and tool card should still appear.
4. Revoke Android location permission and repeat. The actual Android permission
   error should appear in the tool result. Re-enable permission afterwards.
5. Disable the profile's location tool and repeat. No native location lookup should
   occur. “Explain device_location” and quoted tool examples should not cause one.
6. Test a nearby-place request separately to verify the server's normal function
   calling and the existing map-marker flow.

Automated tests cover the HTTP transport, compatible adapters, tool exchange IDs,
interruption handling, native tool results, permission denial, and disabled/budget
gates. Device GPS and the user's running Gateway require the device check above.
