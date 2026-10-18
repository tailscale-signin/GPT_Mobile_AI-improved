# Local prompt-tool reliability follow-up

The supplied v0.9.53.0/build 122 export describes seven Gemma E2B GPU chat turns with no executed tools. The installed source commit and rejected raw control payloads are unavailable. This change addresses the corresponding source paths; it does not attribute the initial empty catalog to an unproven cache or settings bug.

## Changes

- A leading public `<progress_update>...</progress_update>` streams through the existing public-progress channel while the local parser continues looking for the command that follows. Command envelopes remain withheld across arbitrary chunk boundaries. Unterminated/oversized progress and malformed JSON calls fail closed. Ordinary prose/code examples do not become executable merely because they contain a command.
- Bounded repair supplies an exact authorized envelope and the relevant registered parameter schema. Zero-argument calls explicitly use `arguments: {}`. Rejected raw payloads are not repeated as instructions. Names, arguments, authorization, budgets and revocation checks still pass through the existing executor.
- Repair has a pending execution outcome. A repair response containing only prose, an apology, an empty response or a promise cannot emit `Completed`; it emits a tool-task failure. A successfully parsed and validated call can continue through its actual tool result to an answer.
- Context selection protects the preceding task's required capabilities across a contiguous chain of short follow-ups such as “check again” and “test toola.” It examines at most six prior turns, stops at a new substantive request, retains the task's history anchor, and never reinstates a tool missing from the authorized catalog. Current date/time requests protect `current_date`.
- Local upstream prompts defer capability declarations to the adapter. The adapter renders one inventory from the final selection and charges it to the context budget. Per-tool logs distinguish current-request requirements, inherited requirements, optional admission and context exclusion. Upstream catalog logs retain preparation mode, tool support, profile/chat disable switches, default-selection state and exclusion count.

## Regression coverage

Parser tests cover progress followed by zero-argument date calls at every chunk size, authorization rejection, ordinary examples, malformed JSON, incomplete progress and schema-specific repair. Planner tests cover constrained search follow-ups, topic changes, disabled tools, date intent and capability-prompt accounting. Adapter tests assert actual date/search dispatch, result delivery, markup-guard compatibility, final selected inventory and failure instead of completion after a prose-only repair.

The focused local JVM harness passes 50 tests, compiling the production parser/planner and existing schema/document helpers with boundary stubs. Full adapter tests, Android compilation/lint and conversation glass instrumentation run in repository CI. No physical Gemma GPU inference is claimed by the scripted runtime tests.
