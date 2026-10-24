# GPT Mobile AI 0.9.60.0 (build 128) — integration draft

This candidate integrates the latest Research/free-provider branch with provider privacy controls, durable pre-dispatch waits, and Combined source-preserving delivery.

- Free routes require a device-local policy acknowledgement unless verified for no retention. Personal-tool grants are scoped to their recipient and cannot authorize prohibited routes.
- PorchLM and OpenCode are retired from executable selectors and dispatch. Saved conversations remain available.
- Rolling request limits and observed cooldowns survive restart. Never-dispatched requests can wait durably, with permission/input rechecks and cancellation before resuming.
- Combined uses a dedicated final message, retains contributor records, stages strict finals, and records exact source spans. Capacity or private-source transfer limits can use on-device source assembly without a writer request.
- Room schema 46 adds final identities and provider waits. The source branch's Research/HF diagnostics and typed failure handling are included.

See [implementation scope](docs/releases/0.9.60.0.md) and [validation record](docs/validation/release-0.9.60.0.md). This branch is a draft, not a signed or published release.

## Qualification limits

- Unified Reliability v2 is not complete; its remaining acceptance cases are not represented as passed.
- Physical-device performance and a production baseline profile remain unqualified until measured on a reference device.
- Qwen demo backend availability and live authenticated LLM7 generation are not established by deterministic tests.
- Optional runtime extraction remains a staged packaging project; this release adds a measured payload inventory without claiming APK size savings.
- Automatic provider resume covers never-dispatched requests; stage-specific continuation after transmitted failures remains incomplete.
- Optional append continuations, complete transfer authorization and derived-query taint, and daily token quota reconciliation remain incomplete.
- Durable block manifests and virtualized long-answer rendering remain incomplete; final staging currently uses memory.
- The three 2026-10-24 design documents are only partially implemented; this integration remains a draft pending the documented design and qualification gates.
