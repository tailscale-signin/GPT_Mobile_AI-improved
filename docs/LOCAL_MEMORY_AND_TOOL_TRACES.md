# Local memory and inline tool traces

Open **Settings → Fact Vault** and enable **Learn and recall local facts** to opt in. Simple statements such as “I prefer Kotlin” are extracted from user messages using the existing knowledge graph patterns. Review the extracted facts, disable individual facts, or delete them. Extraction is heuristic, not an embedding model; it currently recognizes simple English relationships.

Enabled facts matching a later query are prepended to the system prompt for both cloud providers and LiteRT-LM. At most five facts are recalled per request. A “Recalled: User preference” or “Recalled: Saved fact” chip appears above the assistant response. These chips record that recall happened; deleting a fact prevents future recall but does not rewrite historical messages.

The vault holds up to 64 facts, encrypted through the existing Android Keystore-backed `SecretVault`. Facts are included in the app's existing secret backup/restore flow. Relevant facts are sent to the selected provider when recall is enabled. Turning memory off stops learning and recall while keeping saved facts. Clear deletes the vault contents and turns memory off. Individual deletions retain hashed suppression markers so retries cannot immediately relearn the same fact; clearing resets those markers.

Tool calls now appear above assistant messages as expandable pills, including status, execution duration, and normalized response bytes. Details include argument character/UTF-8 byte counts and a character-based result token estimate. These are payload estimates, not billed model usage. Client duration uses a monotonic clock; shared calls identify delivery/wait time. Gateway tools show only the measurements supplied by the gateway, without inventing response sizes. Older tool events remain viewable without metrics.

Metrics and recall references persist in existing assistant timeline JSON with backward-compatible defaults. Fact text is not copied into timeline metadata. No Room schema migration or new dependency is required. Aggregate tool telemetry is updated only when diagnostics collection is enabled; inline measurements do not require debug mode.
