# Provider and Combined follow-up, build 125

The draft PR retains the earlier model-capacity budgeting, anonymous Horde allowance,
persisted cooldowns and structured failure records. This revision addresses the later
queue, final-writing and task-completion failures reported for build 124.

## Implemented behavior

- Gradio simplified `/call` omits `session_hash`. The accepted event ID identifies the
  result stream. Receipts are persisted before result retrieval. Legacy receipts and
  disconnected result streams cannot be silently resubmitted as continuations.
  Gradio 5.27 cleans events on SSE disconnect, so this adapter does not promise replay.
  Cancellation closes the stream; it no longer posts to the unrelated `/call/cancel`
  generation endpoint. Horde retains its separate polling and cancellation protocol.
- Anonymous Horde retains 256-token output and `allow_downgrade`. Automatic text
  selection requires an active Qwen2/2.5 Instruct or Llama3 Instruct worker with a
  supported instruction template. Unsupported/reasoning templates fail before
  submission. Empty and reasoning-only results are diagnosed separately; a queue
  completion no longer invents a `stop` finish reason.
- Short local rate windows wait up to 60 seconds under the provider/model permit.
  Cancellation releases the slot without consuming a request. Server Retry-After
  deadlines remain blocking and persistent. Tool results remain in the same round.
- Compact Free prompts retain device date/timezone and project constraints. Exact
  date-only questions use the device clock and compute the weekday locally.
  OpenCode's externally rejected anonymous route is unavailable with its reason.
- Final ordinary prose is checked before completion. Provisional drafts keep streaming;
  full rewrites use the existing ownership fence and archive the previous revision. Length is checked in both
  directions; detected wrong subjects, substantial repetition, corruption and
  unfinished essay endings block completion. One bounded full rewrite corrects
  writing defects; queue/access/provider failures do not become rewrite requests.
  Failed repairs retain the draft and an incomplete status.
- Thrown provider stream failures now use the same draft-retention path as explicit
  failure events, without scheduling a rewrite. Cancellation and downstream consumer
  failures still propagate. A Gradio socket read failure retains its last parsed
  snapshot and accepted receipt, without submitting a second job.
- Combined freezes a task contract, isolates new subjects from earlier turns, and
  resolves explicit follow-up references. Wrong-subject output triggers fresh
  synthesis without the unrelated draft. Subject and required coverage defects are
  blocking. France → interrupted Portugal → Germany has a regression fixture.
- Contributor screening separates relevance/coherence checks from structural scores.
  Demonstration models are ineligible for factual synthesis. Raw responses remain
  inspectable. Historical selection preserves representative periods and removes
  introductory filler first. Contributors receive complementary collection roles.
- The writer gets a compact outline, selected claims, qualifications, disagreements
  and evidence references. Full provenance remains in the frozen local audit.
  Task/coverage repair can expand the packet. Reasoning-only attempts change to a
  direct-writing recovery and then allow another already-authorized writer after
  repeated stalls. Defaults no longer impersonate hard model output ceilings.
- Combined preflight checks route capabilities and availability. Auto-selection is
  optional and manual selections remain editable. The UI distinguishes received
  responses, admitted contributions, partial answers and passed completion checks,
  and exposes a retained contributor draft when synthesis fails.

## Validation and limits

Local JVM harnesses compile actual production policies/transports with Android and
framework substitutes. They are not a full Android build; GitHub CI performs that
build and the full test suite. Regression cases cover serialized budgets, cooldown
restart, short-wait cancellation, queue identity and disconnects, no OpenCode
network dispatch, full-answer replacement, device date/timezone, historical coverage,
wrong-topic refusal, reasoning-only recovery and an approximately 500-word fixture.
The follow-up draft repair passed 106 focused JVM tests, including socket failure
after a snapshot, thrown provider exceptions, cancellation and downstream failure
propagation. Changed Kotlin files passed ktlint 1.3.1. Full-suite regression fixes
retain uncertain short contributions, exclude unrelated comparison domains, and
avoid adding a redundant successful-completion event. Date-only interception has
a repository integration test; explicit date-tool requests still exercise tools.

A live public Qwen Space probe used the same simplified payload shape without a
session hash: schema lookup HTTP 200, submission HTTP 200, result stream HTTP 200.
The result was `event: error`, `data: null`, not `Session not found`. This verifies
route/session compatibility only. No completed live generation was obtained; the
service supplied no more specific cause. No other provider's availability is claimed
by these tests.

Task anchors and content checks are deliberately conservative deterministic checks.
They are not a universal semantic coherence or factual-accuracy evaluator. Unknown
subjects retain lexical hints without imposing speculative blocking requirements.
Historical-period recognition uses broad cues; factual support still depends on the
retained evidence. Arbitrary Horde prompt templates and unbounded Gradio/Pollinations
Combined transport output remain unsupported and are rejected before dispatch.
