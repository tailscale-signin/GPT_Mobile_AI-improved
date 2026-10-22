# Free provider diagnostic repair — October 22, 2026

## Evidence and limits

Reviewed `export_Multi-chat_1792669758006.zip`: 48 runs across twelve profiles,
52 messages, 1,808 retained journal events, 500 recent app-log records and 125
diagnostic records. The export identifies version 0.9.55.0 / build 124. It records
21 failed runs and 27 completed runs; completion is not evidence of answer quality.
The manifest reports 34 dropped events and incomplete provider wire capture.
No private conversation content or raw export is committed with this repair.
Implementation base: `bc6e5c0a07ef196ac4056e5ef039061ccd41fb56`.

## Findings and changes

| Provider | Observed result | Repair / remaining boundary |
| --- | --- | --- |
| Kilo | Three completions; Toronto-news run discovered tools and produced progress without a visible answer | Current-news intent invokes the existing authorized app search before generation. Existing bounded empty-answer finalization remains in place; progress publication is batched. No speculative model substitution. |
| Pollinations | Four completions; no live evidence for news | News intent now uses app search with the existing smaller evidence budget. Successful legacy responses update status. Legacy endpoint availability remains provider-controlled. |
| OVHcloud | Four completions; current-news refusal | Same app-search repair, preserving its per-model quota and native tool capability policy. |
| LLM7 | Four transport completions, including policy echoes and refusal to answer ordinary history | Short, task-oriented text prompt permits normal general-knowledge answers and limits verification caveats to current information. Existing approval and quota gates are preserved. |
| BlockRun | Four completions; generic background presented for current news | Search evidence is acquired for fresh-news requests before asking the model. No claim that successful transport proves factual quality. |
| AI Horde | Initial 403; three later requests blocked by persistent denial | Anonymous denials expire after one minute. Queue errors retain recognized semantic return-code categories, identify the client, and distinguish rejected submissions from uncertain or accepted jobs. A definite rejection can be explicitly retried without duplicating an accepted job. Server-side credit/access restrictions cannot be removed by the client. |
| Vireonix | Four completions; one answer displayed encoded text-block JSON | Provider-scoped, bounded decoder handles serialized text blocks across stream boundaries, preserves unrelated JSON, and retains terminal metadata. |
| OpenCode | Initial 403; three later requests blocked locally | Anonymous denial expiry and provider-specific cooldown wording. No alternate endpoint, credential workaround or paid fallback is attempted. Actual anonymous access must be accepted by the service. |
| Hugging Face Space | Three generation failures; one interrupted SSE response | Terminal failures persist as failed receipts and are never advertised as resumable. Complete events may reuse the last generating snapshot when they contain only UI updates; answer extraction tolerates hidden-state output layouts. Multiline SSE event data is assembled before parsing. Interrupted jobs retain their IDs without duplicate submissions. |
| BridgeNode | Four DNS failures | Failure now names the provider and unresolved host and updates the provider status. The app cannot repair external DNS or route around a dead hostname. |
| Cehpoint | Repeated 429/cooldown failures | Cooldown wording no longer claims an unverified quota reset. Retry-After and shared limiting remain enforced. |
| PorchLM | Four completions containing incoherent or minimal output | Persistent generation notice identifies its educational/demo quality. Response status explicitly does not certify answer quality. No unreliable language-based gibberish filter or fabricated replacement answer. |

## Cross-cutting behavior

- Fresh-news detection respects explicit requests not to browse. It only executes
  already authorized search tools; disabled/missing engines produce a specific
  unavailable-search outcome instead of an invented current answer.
- Ordinary free-provider chat no longer displays Research-specific “Final writer”
  recovery instructions after a cached 403. Keyed-provider denial policy remains
  unchanged.
- Catalog reachability is labeled “Reachable”, not a promise that generation works.
  Failed DNS, queue and transport attempts update status; empty/error responses
  are not marked successful just because they report a model ID.
- Model-authored progress uses the existing publication interval, preserving the
  final checkpoint while reducing per-token UI updates during parallel chat.
- Frame timing in the retained log shows jank, not a demonstrated ANR or heap OOM.
  Android's IME-unregister and ignored JobInfo-flag messages are system diagnostics;
  this export does not establish an app-side causal defect for them.

## Validation

Regression coverage includes current-news and offline intent, prompt contamination,
anonymous-denial expiry, every encoded-text stream split, Gradio update-only
completion, terminal queue replay prevention, rejection followed by explicit retry,
and batched progress retention. Twenty focused JVM tests passed against production
Kotlin helpers and queue transport, using an isolated harness with framework stubs
and the repository's Kotlin 2.4.20 / Ktor 3.6.0 versions. This is not a full Android
build. Resource preflight, changed-source ktlint and Android regex validation also
passed. The first PR CI run also passed the debug APK build, Android lint,
native-memory smoke test, acceptance checks and CodeQL. Its full 3,990-test suite
identified three stale message assertions and one intermittent Compose test
dispatcher failure, addressed in the follow-up below.

### PR validation follow-up

- Updated the repository integration tests to check the provider-neutral 403
  message, explicit retry guidance and retained evidence. They still verify that
  Continue cannot repeat tool preparation, an explicit retry rechecks the writer,
  and the 24-request research replay does not reacquire saved evidence.
- Updated provider-card rendering checks for the intentional “Reachable” wording,
  while requiring the generation-not-tested qualification and an unknown status
  for services that have never been checked.
- Supplied a `StandardTestDispatcher` to the Combined Markdown Compose test. The
  former unconfined test dispatcher could resume view updates inline on the
  Markdown parser's background thread. Parsing remains asynchronous in production,
  and the test still checks both profile colours and their removal when sources
  change. This follows the [Compose test dispatch guidance](https://developer.android.com/develop/ui/compose/testing/migrate-v2).

Full CI is rerun for this follow-up; no failing tests are disabled or skipped by
the repair.

A minimal public-prompt check of the Qwen Space accepted a job but its response
stream ended incompletely. This is a live service limitation, not proof of successful
inference. Its deployed `/gradio_api/info` currently exposes two public inputs and
two public outputs; the source implementation includes hidden State components.

Provider references reviewed: [Qwen Space source](https://huggingface.co/spaces/Qwen/Qwen3-Demo/blob/main/app.py),
[AI Horde return codes](https://github.com/Haidra-Org/AI-Horde/blob/main/README_return_codes.md),
[OpenCode model documentation](https://opencode.ai/v2/docs/console/models/).
