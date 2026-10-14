# GPT Mobile AI 0.9.53.0

## Local model recovery and benchmark reliability
- Include the memory-reserve recovery controls merged in #677: an explanation of the reserve, a high-reserve warning, and a one-tap reset to the recommended 768 MB.
- Keep memory safeguards and explicit user settings intact. Do not silently resize model context or override a selected reserve.
- Add overflow-safe memory accounting, exact current shortfall diagnostics, and advice to reset the reserve only when the estimate would fit with the recommended value.
- Preserve the MEMORY_ADMISSION_REJECTED classification during both model loading and generation. Diagnostics distinguish an unloaded engine from a warm engine whose new request was blocked.
- Keep the rejected state visible across delayed runtime status updates, clear it after successful readmission, and reuse an already loaded engine after memory recovery.
- Distinguish thermal rejection from memory rejection so a hot phone is not told that resetting the memory reserve solves its thermal problem.
- Acknowledge native drain fences in the local comparison collector. Preserve native metrics and partial output counts, and classify empty answers, missing completion events and native errors explicitly.
- Add 19 regression tests covering the reported memory values, arithmetic boundaries, load/generation recovery, delayed state delivery, and benchmark stream handling.

## Included main-branch improvements
- Includes the previously merged research workspace improvements from #678 and the existing local runtime, research and delegation changes below.

## Delegation and recovery
- Preserve evidence and stage checkpoints when the final writer fails; Continue resumes the original work and retained budget.
- Separate source collection, question coverage, claim verification, independent review and answer delivery.
- Admit writer/reviewer roles before expensive work, retain precise cancellation reasons and enforce child capability restrictions.
- Include the durable encrypted memory and diagnostic repairs merged in #668.

## Local AI and execution
- Add the pinned, QAIRT-independent GGUF CPU engine to the normal app, including verified imports, bounded generation and cancellation.
- Keep LiteRT/QNN and GenieX preview dependency families separate. Unsupported GGUF GPU/NPU, tools, vision and speculative decoding remain unavailable.
- Account for native input/output tokens with provenance, including partial and failed requests.
- Add Test Local Assist, explicit warm/automatic preparation, owned-session cleanup, exact source handoffs and configuration/artifact readiness checks.
- Add opt-in execution policies, retained shared model budgets, final-writer reserves and subscriber-owned shared read-only requests. Optimization remains off by default; no measured savings are claimed.

## Research
- Add Settings, Statistics and Archive views, publication filters, adaptive/expert appraisal and scoped automatic examination.
- Retain up to 1,000 reusable public works in FIFO order; preserve immutable source versions and separate chat/job retention roots.
- Add citation-linked article details, source-quoted study fields, method concerns, independent review reports and resumable article examination.
- Support approved gateway readers and bounded native full-text HTML discovery. Access refusals remain terminal; unsupported PDFs and unverified figures/supplements are reported explicitly.
- Add topic/category organization and study comparison without an overall truth score or pooled dependent findings.
- Preserve durable research statistics, clear epochs, scoped history migration and encrypted research backup/restore. Temporary/deleted chats cannot create durable examination or archive records.

## Profile picker
- Use compact profile cards with stable UID selection, separate/combined modes, searchable labels and focused insight details.
- Keep confirmation available in narrow layouts; expose accessible favorite and insight actions.

## Verification and limitations
- CI checks unit regressions, Android lint, exported Room schemas, dependency/runtime packaging, CodeQL, gateway contracts and Android acceptance.
- Existing GGUF CPU JNI acceptance covers warm generations, partial cancellation, context rejection and reopening in the release-minified Android 16 x86-64 app.
- Synthetic fixtures do not establish real-model quality, provider-cost savings, battery benefit or physical ROG Phone qualification. The newly added recovery tests use a fake native runtime; phone-side token generation still requires device verification.
- The signed-release workflow verifies application identity, certificate continuity, native packaging, checksums and source provenance before publication.
- For a saved 4,096 MB memory reserve, use Local Models → Settings → Memory → Use recommended 768 MB when offered, then retry chat and a benchmark. Passing admission alone is not proof of native compatibility.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.53.0
- Version code: 122
