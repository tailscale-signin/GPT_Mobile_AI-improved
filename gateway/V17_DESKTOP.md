# Gateway 17 desktop and benchmark contracts

Gateway 17 keeps the existing Windows installation identity, per-user location,
settings schema and conversation journal. The internal `v14` package remains the
composition namespace. Existing v14, v15 and v16 API aliases remain available;
`/gateway/v17` advertises the additive contract.

## Everyday navigation

The dashboard presents **Overview, Chat, Saved, Jobs and Benchmarks**, with a
prominent **New prompt** action and one **Settings** entry. Device, search and
engineering observations remain available through Jobs filters. Advanced settings
are hidden until requested. The palette follows the Android app's default Cyan
theme; navigation artwork is exported from its versioned vector assets. Native
controls retain text labels, keyboard focus and Windows font rendering.

Chat sends to the selected model through the existing Gateway completion route.
It is currently tool-free ordinary chat. Saved engineering conversations can be
read and organized; their execution and continuation stay with the engineering
owner. An existing conversation retains its original model and profile.

The desktop stores drafts and pending command identity locally. Transcript text,
messages, jobs and admission receipts remain canonical in the Gateway journal.
The client assembles revisioned message parts and checks their SHA-256 before
displaying a logical message. A lost response offers **Check send**, which reads
the original receipt instead of submitting a new command. Closing the dashboard
detaches its observer and does not stop the Gateway.

Saved supports search, favorites, rename, archive/unarchive and individual message
bookmarks. Archiving changes organization, not retention or job execution.
Search is a bounded literal query; `%` and `_` are not wildcards. Bookmark search
matches its conversation title or message text and returns bounded excerpts with
an explicit truncation flag. Open the source conversation for the complete text.

Network mode continues to use the existing shared-network principal. The desktop
shows that scope explicitly; a request arriving over loopback does not create a
private account inside a shared network configuration. No supervisor management
credential is exposed to these views.

## Additive conversation APIs

| API | Contract |
| --- | --- |
| `GET /gateway/conversations` | Existing paging plus optional `query`, `archived`, and `favorite`; omitted filters preserve legacy behavior. |
| `GET /gateway/conversations/{id}` | Existing snapshot plus `metadata_revision`, `archived`, `favorite`, and `tags`. |
| `PATCH /gateway/conversations/{id}/metadata` | Independent metadata revision precondition; optional title, archive, favorite and bounded tags. |
| `PUT /gateway/conversations/{id}/bookmarks/{message_id}` | Idempotent `saved` boolean for an owned logical message. |
| `GET /gateway/saved-messages` | Principal-scoped, cursor-paged bookmark excerpts; cursor binds identity and search. |

Mutations accept the existing installation/principal preconditions. Ownership and
job-kind scope are checked independently. Metadata edits do not advance transcript
revisions, dispatch work or change the original execution binding. Schema changes
are additive and do not rewrite saved message parts. Ordinary polling does not
require a write once migration has completed.

## Benchmark evidence

The Gateway lifecycle owns a bounded SQLite benchmark journal beside the job
journal. The desktop observes run IDs and history, rather than owning execution.
An interrupted process records an interrupted run; it does not automatically
replay uncertain model requests. Only terminal history can be removed.

The five-case quick check retains its original 100-point scope. The paired
comparison measures serial versus concurrent HTTP request scheduling using the
same selected route, fixtures and output budget. Warm-up is excluded. It reports
the two arms separately and does not infer native inference batching from HTTP
concurrency. Missing GPU, model-artifact, cache, queue and native batch evidence
remain unavailable. Short previews are exploratory; qualified comparisons require
the declared complete paired sample population.

Coding runs use disposable Python repository fixtures, anchored changes and an
independent host evaluator. A locally approved digest-pinned container image is
required. No candidate code runs directly on the host, no image is pulled by a
benchmark, and no live GitHub mutation is performed by this fixture track. The
container has no network, credentials or write access to the source/evaluator
mounts, and has bounded memory, CPU, process count and execution time. Expected
answers and score calculation remain outside the candidate container.

The report distinguishes unique repair problems, seeded trials and integration
capstones. Repeated seeds do not count as new coding capabilities. Scores describe
the declared fixture criteria: hidden functional results, regression boundaries,
model-authored test vectors, changed-source diagnosis, interface preservation,
anchored patch workflow, repeated-call recovery, patch scope and evidence honesty.
They are not a claim of general intelligence, live GitHub coverage or production
repository correctness. First submission, bounded repair attempts, solved counts,
coverage and grading failures remain separately inspectable. Missing required
grading evidence prevents an overall score.

## Updates and qualification

Queued or running benchmarks prevent maintenance from preparing an update, just
as accepted chats and engineering work do. The installer does not silently cancel
them. Existing backups include the new SQLite history. Version 17 is admitted by
the existing official-release URL, digest, archive and schema checks; unrelated
future major versions remain excluded.

The Windows installer workflow exercises native pages at normal and minimum
sizes and captures the daily views for review. Linux host tests do not establish
Windows layout or physical Android audio behavior. The issue disposition ledger
records each report's source fix or diagnostic resolution and its remaining live
qualification: [Gateway 17 issue dispositions](../docs/repairs/gateway17-issue-dispositions.json).
