# On-device memory in 0.9.30.0

The native `memory` tool combines facts, recurring topics, semantic recall, a knowledge graph, documents, and explicit forgetting. Its database, sentence encoder, extraction rules, and context-window management run on the Android device. No memory server, Python runtime, account, or embedding API is required.

## Architecture and defaults

- **Authoritative facts:** the existing encrypted, chunked SecretVault snapshot. The default capacity is 4,096 facts, configurable up to 16,384. Existing saved settings remain intact.
- **Semantic index:** ObjectBox Java 5.4.2, with 100-dimensional cosine HNSW vectors. The derived index lives in the app's private no-backup directory, has a 64 MiB database limit, and explicitly caps the HNSW vector cache at 16 MiB. Vectors are not independently encrypted; the source fact vault is encrypted. The existing Room graph remains app-private.
- **Embeddings:** bundled Google MediaPipe Universal Sentence Encoder QA model, approximately 6 MB. MediaPipe Tasks Text 1.0.0 computes embeddings locally, including tokenization. The first 100-dimensional output head is used consistently for facts and queries. The pinned asset URL and SHA-256 are recorded beside the model. The APK integrity checker verifies the asset and native libraries for both supported ABIs.
- **Context windows:** LangChain4j 1.21.0 `TokenWindowChatMemory`, from the `langchain4j` module (not solely `langchain4j-core`). Complete conversation turns are atomic window entries; attachment costs are included. System instructions, tool schemas, the current request, and output reservations are budgeted separately. Token counts remain conservative estimates, not a provider tokenizer's exact count.
- **Extraction and consolidation:** Kotlin implementation of the extract → retrieve candidates → compare → insert/update/supersede cycle. No Mem0 Python service is embedded or contacted.
- **Retrieval:** semantic similarity and lexical/intent relevance, with bounded frequency and recency boosts. An unrelated frequently mentioned fact does not become relevant solely because of its frequency. Defaults recall up to 12 facts within a 1,536-token memory budget.

Automatic capture learns explicit preferences, relationships, constraints, interests, and supported profile facts. Repeated questions can establish a discussion topic after three distinct message IDs; a question is not interpreted as evidence that its premise is a personal fact. Topic evidence is bounded to 512 topics and 16 message IDs per topic. Reinforcement tracks distinct evidence and avoids inflating a fact when a message is retried.

Near-duplicate merging requires compatible entities, relation, scope, polarity, numbers, strong vector similarity, and substantial word overlap. Contradictory exclusive profile facts supersede old values; opposing preferences invalidate prior preferences. These are intentionally conservative rules. They are not a guarantee that every paraphrase or subtle contradiction will be understood.

Optional model-assisted extraction accepts only an on-device LiteRT-LM profile, with source-grounded quotes and existing re-entrant native-session guards. A LAN server does not qualify as on-device. Rules and embeddings continue to work without a downloaded generative model. Network-based connected memory plugins remain separate, explicitly configured integrations.

## Controls and lifecycle

`prepareMemoryModel` fetches the version-pinned Google asset during the build and refuses to package a checksum mismatch. Generated assets are wired into every Android variant. The first build requires network access; an offline build can use `-PmemoryModelFile=/path/to/universal_sentence_encoder.tflite` with the same enforced checksum. Installed apps need neither a model download nor a network connection for memory.

Memory settings are organized into Memories, Topics, Documents, and Controls. Users can inspect, edit, pin, disable, delete, adjust learning/retention, or rebuild the semantic index. Deleted facts create tombstones so retries do not silently recreate them; deleting a topic also removes its evidence. Clearing memory disables learning and clears derived stores. Cloud recall defaults **off** for new settings. Enabling it explicitly allows selected recalled facts to enter a remote model's prompt; embedding computation and storage still remain local.

Index updates are incremental. A rebuild processes small cancellable batches and rechecks current settings between batches. SQLite lookups are chunked to avoid parameter limits at large capacities. The embedder and ObjectBox store are released on idle/memory-pressure paths without interrupting active native operations. If native semantic initialization fails, exact/topic recall remains available and the UI reports the degraded state.

## Candidate research and integration decisions

Reviewed 2026-10-03 using upstream documentation, repositories, Maven metadata, and actual published artifacts. User-supplied ratings were treated as preferences, not measured benchmarks.

| Candidate | Decision | Reason |
| --- | --- | --- |
| [ObjectBox Java](https://docs.objectbox.io/on-device-vector-search) | Integrated | Embedded Android store and HNSW; no daemon. Explicit cache limits matter on phones. [AGP 9 setup](https://docs.objectbox.io/getting-started) uses the legacy kapt bridge for generated entities. |
| [LangChain4j chat memory](https://docs.langchain4j.dev/tutorials/chat-memory/) | Integrated | Real token-window implementation, with app-specific complete-turn budgeting. Verified the class in the published main module. |
| [Mem0](https://github.com/mem0ai/mem0) | Kotlin consolidation pattern | Upstream Python/Node components are not a native Android library. Reused the architectural cycle, without introducing its server/runtime dependencies. |
| [sqlite-vec](https://github.com/asg017/sqlite-vec) | Considered, not installed | Useful embedded vector extension, but Room's framework SQLite path does not provide a drop-in Android extension loader. A custom SQLite/JNI stack would duplicate storage work here. |
| [Graphiti](https://github.com/getzep/graphiti) | Not embedded | Python and graph database deployment requirements conflict with zero-server operation. |
| [MCP reference memory server](https://github.com/modelcontextprotocol/servers/tree/main/src/memory) | Native equivalent retained | Its Node stdio server is unnecessary; equivalent graph operations already run through Room and the unified Kotlin tool. |
| [MediaPipe text embedding](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android) | Integrated | Official Android inference API with a small bundled model and no runtime download requirement. |

## Validation limits

Regression tests exercise relevance ranking, contradiction guards, recurring topics, retry deduplication, cloud defaults, forgetting, context windows, persistent queue transfer, and existing Room migrations. APK checks verify native payloads and 16 KiB ELF alignment. Phone measurements of semantic quality, memory use, startup latency, thermal behavior, NPU compatibility, and Compose gesture behavior still require representative devices. No accuracy or speed percentage is claimed from JVM tests.
