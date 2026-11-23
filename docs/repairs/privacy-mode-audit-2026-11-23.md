# Privacy mode: detected-span protection, streaming and diagnostic evidence

## Scope and ownership

This change implements the privacy audit on PR #2367, starting from
`0300b8fcb2a8ef5a64ecf9d6e72cdb6f1c294033`. The preceding release, continuation,
speech and Gateway fixes remain in ancestry. Release identities must be reconciled
with the independently owned PR #2363 before readiness; no already published
Android or Gateway identity may be reused for different source.

Only fictional examples appear in the implementation and tests. Private source
logs, original conversations, credentials and replacement dictionaries are not
repository fixtures.

## Behavior changes

| Audit finding | Implemented behavior | Verification boundary |
| --- | --- | --- |
| Greedy relationship, project and license patterns removed the actual request | Contextual span recognition keeps task words and document labels. A relationship alone does not consume a sentence. Explicit identifiers require identifier-shaped values. | Positive and negative synthetic detector cases |
| Names, surname aliases and wrapped document fields were missed or overmatched | Explicit identity context and request-history learning replace capitalization-only guesses. Names are not limited to four words; labels and headings remain visible. | Identity, mask and content-bound receipt regressions |
| Dates and currency formats were incomplete | Ordinal and day-first dates, leading/trailing currency symbols and ISO currency formats are recognized. | Fictional locale corpus |
| Related domains and address fragments could reveal a masked identity | Known private domains, explicit address context and repeated source fragments are carried across the same request. | Linked-domain/address fixtures and unrelated-public controls |
| Coarse location handling was ambiguous | Default policy masks declared residence and retains an explicit search region unless that region is linked to the residence in the request. | Separate residence and public-search cases |
| Sensitive family narrative could survive the keyword list | Personal health/distress clauses receive typed local substitution while the actual request remains visible. | Personal narrative positives and general teaching negatives |
| Opaque tokens confused draft completion | Request-scoped tokens carry a category and the provider receives a bounded substitution protocol after source masking. Tokens represent values already supplied, preserve roles/labels, and cannot authorize tools. | Exact issued-token, mutation, foreign-namespace and omission-signal tests |
| Private answers waited for the complete response | Ordinary prose and reasoning restore incrementally, retaining only a bounded possible marker suffix. Verified cloud routes use bounded SSE on the same admitted client. Completion-only endpoints and providers that return JSON are identified as buffered; tool-bearing rounds keep the typed control decision boundary. | Chunk splits, cancellation, completion, malformed marker and tool-isolation tests |
| Display evidence could be confused across chunks | Restored ranges accumulate per response with exact content binding; overlapping or ambiguous streams cannot claim another response's highlight authority. | Incremental and parallel receipt tests |
| Diagnostics did not prove the final outbound transformation | Both final serialized request paths validate known protected values before reserving a physical attempt. Each attempt records content-free policy/detector revisions, category counts, scope, timings and a request-keyed payload fingerprint. | Unicode/nested-argument reinsertion, changed revision, malformed/unsupported shape and no-content evidence tests |
| Diagnostic archives retained originals and model thoughts | Redacted content is the default before freezing, chunking, hashing or checkpointing. Full forensic export requires a separate preview and selection. Capture/resume identity includes that content mode. | Redactor, staged archive/hash and dialog-consent regressions |
| Policy, detection and provider retention labels were conflated | Settings distinguish the permanent conversation policy, confirmed local text ranges, and provider route/retention evidence. Any enabled profile remains selectable under existing dispatch controls. | Existing privacy presentation/selection acceptance plus updated explanations |
| A stored memory source could look fully indexed | Source persistence and enrichment completion are separate; current completed outcome codes are recognized, and pending local model readiness remains visible. Deferred receipts carry owner and scope provenance. | Readiness, derived-owner restore and source deletion/retry tests |
| There was no reproducible local privacy/utility check | Settings can run and cancel an offline fictional corpus. The result separately counts detected values, preserved public phrases, exact restorations, map isolation, and local CPU masking/encryption/restoration latency. | Benchmark acceptance, cancellation and percentile regressions |

## Evidence semantics

`KNOWN_VALUES_ABSENT` means the final serialized supported text surfaces do not
contain values that the local detector already protected. It does not establish
that every sensitive value in arbitrary input was detected. Missing supported
text surfaces, malformed serialization and changed privacy authority fail before
the physical inference attempt. A rejected later retry cannot erase an earlier
transmitted or uncertain attempt.

Payload fingerprints use a fresh request-local HMAC key. They support correlation
within the prepared request's retry lifetime without exporting a dictionary,
original values, or an unkeyed digest of individual private values. Source values
needed for the final absence check are sealed with AES-GCM and remain local to the
request lifetime. Display receipts continue to use local, content-bound offsets;
they are a separate artifact from transmission evidence.

Stream observations record incremental, tool-control-buffered, completion-only endpoint or provider-buffered delivery. They distinguish first upstream reasoning, first upstream answer,
first restored answer and restoration work. They contain no answer text or token
mapping. Syntax validation and deterministic formatting repair do not establish
semantic usefulness. A possible protected-field omission or request to reveal a
supplied value is an explicit review signal. Partial output is never silently
replayed to try to manufacture a complete answer.

Default diagnostic exports omit private text, thoughts, arguments and local
network details; credential redaction remains active in both modes. Full forensic
exports deliberately retain more content after the local preview. A forensic
checkpoint cannot be resumed as a redacted capture or vice versa.

## Limits and release qualification

The detector is a bounded heuristic with explicit custom terms, not a general
personal-data proof. Synthetic task-phrase preservation is distinct from live
provider answer quality. The local benchmark does not contact a model, claim
cloud retention, measure network latency, or establish GPU/NPU execution, thermal
behavior or battery consumption. Private memory indexing still requires a ready
authorized local model; a saved source is not an indexed source.

Canonical host checks, pinned Kotlin formatting, focused production JVM behavior
tests, every applicable final-head hosted check and the repository's main gates
remain required. Full Android/Compose/Room execution and signed package acceptance
are qualified by their actual hosted reports, not by host boundary stubs. Publication
must verify the merged source, package signatures and attestations, and complete
published-asset readback for both Android and Windows Gateway.
