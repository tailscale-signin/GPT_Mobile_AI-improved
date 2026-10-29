# GitHub chat workflow repair

Source: `app-diagnostics-1657405888625829696.jsonl`, app 0.9.63.0 / build 131, commit `a478c10659396ec347913986b7b76376b07e48f3`. Work is on `feature/improve-in-chat-workspace-ui`, preserving the existing PR #758 navigation changes.

## Implemented behavior

- Repeated GitHub calls return the previous successful call ID, retained evidence preview, and exact continuation rather than only a generic repeat-limit error. Existing repeat, authorization, and total-call limits remain enforced.
- GitHub replay and per-result compaction retain continuation/correction objects as valid JSON. Oversized control objects are explicitly omitted rather than damaged or fabricated.
- Mixed saved-view arguments return a precise correction call. Cursor-only execution remains task-scoped; explicit repository fields are never silently ignored.
- An existing-branch HTTP 422 is reconciled by reading the exact ref. The result reports the actual SHA, whether it matches the requested base, and an explicit branch-selection recovery call. It never force-updates, implicitly selects, or reports creation of an existing branch.
- `github_stage_patch(target_file, search_snippet, replacement_snippet)` fetches the complete immutable blob, checks the branch/head, rejects ambiguous anchors, and stages the exact replacement in one tool call. Existing multi-file SHA-checked edits remain available. A stage is not a commit.
- GitHub chat contains native approval cards with a bounded diff, **Approve & Commit**, **Reject**, and exact inline editing. Editing creates a new approval digest and cancels the superseded task. Approval changes are observed from Room invalidations rather than a two-second polling loop. Successful commits update the pinned head before resuming the conversation.
- `github_watch_checks(pr_number, run_id)` registers a durable native WorkManager subscription. Either ID can be supplied; supplying both requires matching heads. Status cards update from Room without model polling. Watches stop on cancellation, changed PR head, denied access, connection changes, or expiry. Empty/incomplete checks never count as success. Subscriptions are limited to saved chats, 20 active watches per chat, 48 attempts and 24 hours.
- GitHub-linked prompts preload the repository, branch, pinned SHA, observed local edit state and up to three recent commit summaries. Commit text is bounded and identified as repository data. Mutable heads are still checked before writes.
- Workspace subscriptions follow lifecycle state, chat selectors are lazy, evidence previews are memoized, and initial-chat selection no longer resets when the chat list changes.
- Exact Android IME cleanup and optional jank-monitor permission messages are classified as platform information. MCP health failures retain an actionable connectivity classification.

## External and device limits

The diagnostic contains ten failed connections to the configured private MCP endpoint on port 8101, additional port 8102 transport failures, and DNS failures for `mcp.jina.ai`. Existing transport backoff remains in place; app code cannot start an unavailable remote server or repair its network/DNS configuration.

The ASUS `asus_leak_detect_proj` exception originates in framework finalization. Its raw evidence remains retained; this change does not claim to repair the device firmware. Frame timing includes stalls up to approximately 753 ms. Reduced UI work needs verification on the affected device; correlation with blur is not proof of causation.

CI subscriptions use native background polling and push state changes into the chat UI. They do not provision a public webhook relay or promise immediate delivery while Android defers background work. CI cards describe observed checks, not permission to merge or publish.

## Validation

Local checks: Android regex compatibility, resource/icon/provenance preflight, and all 204 Python script tests passed. Changed Kotlin files are checked with ktlint 1.3.1. Regression coverage was added for exact continuation replay, existing-branch reconciliation, atomic patch anchoring, mixed-cursor correction, inline digest approval, CI head changes/empty results, prompt bounds, and diagnostic classification.

The local Gradle wrapper could not download its distribution through the Java network path, and this environment lacks the Android SDK/JDK 21. Android compilation and Kotlin/Compose regression execution are delegated to PR CI. Do not treat the source-only checks as a successful Android build.
