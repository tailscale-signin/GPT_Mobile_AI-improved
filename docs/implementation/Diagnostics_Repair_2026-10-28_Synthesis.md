# October 28 synthesis diagnostics repair

Evidence: 2,351 exported records from version 0.9.62.0, commit
`fe4de044363aed9f9143f882e5fedaf02286d493`. The export labels 57 distinct
incidents actionable: 28 frame windows, 18 Android events, eight model
output-limit completions and three tool errors. One duplicate and zero
truncated records were reported. Raw prompts, tool payloads and the uploaded
diagnostic export are excluded from this repository.

| Observation | Change | Remaining qualification |
| --- | --- | --- |
| Synthesis and repeated structured continuations use 723 output tokens against roughly 74,000–79,000 input tokens. | Separate the eligibility floor from preferred packet funding. Capable append writers reserve a useful packet, bounded by route capacity, configured output and context, and the existing accepted turn budget. Small routes retain short-packet support. Frozen dispatch limits remain exact. | Replay on the device and inspect physical invocation count, total input, end-record completion and attribution. Existing frozen snapshots retain their accepted limits; start a new turn to use the new packet plan. |
| An ordinary continuation ends at its accepted 2,048-token cap. | Existing output-limit continuation and retained-prefix behavior remain active. A provider output-limit completion is partial, not a crash. | The log does not establish that arbitrary output caps can safely be removed. |
| Two exact GitHub read requests hit the repeat barrier. | Keep the barrier and provide continuation/unread-range guidance. Validate malformed read schemas before backend dispatch. | Repeated remote actions must remain blocked. |
| One saved GitHub view is unavailable. | Reject mixed cursor/new-read arguments, explain task-scoped cursor recovery and immutable re-reads, renew actively read views, and evict inactive views first. Preserve task/operation authorization and memory bounds. | The export cannot establish whether this cursor expired, was evicted, was invented or came from a different task. No automatic mutation replay is introduced. |
| Main-thread dispatch stalls exceed two seconds. | Offload checkpoint document decoding/comparison/staging through the DAO entry point; retain transaction and ownership checks. Reduce routine operation-log volume while preserving slow-operation logs and every enabled Perfetto slice. | Device profiling is required to establish remaining causes. Coexisting background work and graphics effects do not establish blur as the cause. |
| High-refresh windows with one or two missed frames are labeled issues. | Retain samples, but mark windows actionable for a missed-frame ratio of at least 5%, a 100 ms maximum, a stall counter or dropped samples. Preserve explicit warnings/errors and conservative older-counter classification. | Triage thresholds do not establish that rendering is repaired. |
| A finalizer/reference warning appears among Android events. | Retain the evidence without inferring a leak from one platform warning. | A heap trace and repeated reproduction are needed before changing object ownership. |

Regression coverage includes packet funding and frozen output limits, small-route
compatibility, configured context/output caps, cursor renewal and expiry,
cross-task/operation rejection, mixed arguments, malformed source ranges, cache
eviction, and sparse-frame versus sustained-jank/stall classification.

In the original local run, 204 script tests, seven diagnostics JUnit tests,
ktlint 1.3.1, Android regex preflight, scope inventory and tool-statistics coverage
checks passed. Two Android validation attempts stopped when their Gradle daemons
disappeared alongside recorded out-of-memory kills at the 8 GiB environment
ceiling. The larger Android tests did not execute in that run. Device
qualification remains outstanding. The user subsequently authorized merging to
main; this restored branch must pass CI before merge. No release or production
publication is requested.
