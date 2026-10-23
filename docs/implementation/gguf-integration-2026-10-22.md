# GGUF Android integration repair — 2026-10-22

## Evidence and scope

Reviewed `export_New Chat_1792710126494.zip` (app 0.9.55.0, build 124) and main at
`d7e231604677e100a27c975d37d4ce3a5571ab59`. The selected profile is **Gemma-4-E2B-It · GGUF**,
with Vulkan GPU selected. `LITERT_LM` is the persisted profile/provider category;
the export explicitly resolves its actual artifact and runtime to `LLAMA_CPP`.
It is not evidence that the app attempted to load GGUF bytes into LiteRT.

The two retained chat requests contain only `hi`. The first fails with
`LOCAL_CONTEXT_EXHAUSTED` after 134 ms and zero output. Initial advertisement estimates
769 system tokens, 3,249 schema tokens and 17 current-message tokens; these are
estimates from the upstream request builder, not the native tokenizer. The retained
app log contains four such context failures across this and another chat.

The second retained request reaches a 4,096-token native context plan, selecting
three of fifteen tools, with an estimated 2,268 prompt tokens and 547 reply tokens.
GPU memory admission succeeds (7,686,126,464 required bytes, 805,306,368 reserve,
9,178,738,688 available). Loading is then denied by an existing native interruption
journal. The export does not retain the original signal, tombstone, or exact GGUF
weights. It therefore cannot establish the original GPU crash cause or certify the
user's particular model file. Repeatedly clearing quarantine is not a repair.

## Upstream research and decision

Sources checked on October 22, 2026:

| Route | Finding | Integration decision |
|---|---|---|
| [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) | Native Android GGUF loading, metadata, templates and token streaming are supported. The NDK build must preserve a portable CPU instruction baseline. | Keep the existing isolated JNI runtime and update its pinned source. |
| [Latest stable llama.cpp release](https://github.com/ggml-org/llama.cpp/releases/tag/v0.7.0) | v0.7.0, released October 15; annotated tag resolves to `620d2225cc436ab0b001e3200e590e3b286ef9ab`. | Use this immutable stable revision, not a moving master URL. Archive SHA-256: `1d4a987d9bd048f6e93107e838026438df70fbde237fc0c69c63d600512b4bd9`. |
| [Google Gemma integration](https://ai.google.dev/gemma/docs/integrations/llamacpp) | Google's documented llama.cpp example uses Gemma-4-E2B-it GGUF. | The family has an upstream integration; individual conversions, quantizations and device backends still need validation. |
| [LiteRT-LM](https://developers.google.com/edge/litert/next/litert_lm_npu) | Uses `.litertlm` packages. | Keep format routing separate; renaming or importing GGUF into LiteRT is not conversion. |
| [Snapdragon backends](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md) | CPU, Adreno OpenCL and experimental Hexagon backends exist, with additional SDK, packaging and device requirements. | Retain packaged CPU/Vulkan support. Do not advertise OpenCL or Hexagon merely from SoC detection. |
| [Qualcomm GenieX releases](https://github.com/qualcomm/GenieX/releases) | A separate runtime/SDK route exists, including GGUF support. | Preserve the separate preview integration; no GenieX requirement for ordinary standalone GGUF. |

## Implemented behavior

1. Updated native source URL and archive hash, Java/Kotlin identities, packaged inventory,
   APK inventory check and both native CI workflows to the same v0.7.0 commit.
2. Standalone GGUF manifests retain the importing runtime revision as provenance.
   A different engine revision no longer rejects portable tensor data before inspection.
   Existing hash checks, path validation, architecture/template checks, native loading,
   quarantine and per-runtime qualification still apply. QAIRT/GenieX rules are unchanged.
3. Fixed `compact=true, deferCapabilities=true`: the previous early return bypassed
   the compact policy. Local profiles now receive the compact policy from initial
   assembly. Original profile instructions and the Research attribution contract remain.
   Optional schemas are still selected by the existing context planner.
4. Context rejection diagnostics include actual planned capacity, estimated input,
   reserves and prompt sizes, instead of an unexplained generic context error.
5. After rendering/tokenizing, JNI clamps the reply maximum to the remaining native
   context. Fitting input is retained and can generate even if the requested reply
   maximum is too large. Oversized input is still rejected intact. The adjustment is
   emitted as `OUTPUT_BUDGET_ADJUSTED`; output-limit completion remains identifiable.
6. New standalone GGUF profiles default to CPU in both creation paths. Vulkan remains
   selectable. Existing user-selected accelerators are not silently rewritten.
7. A caught `GPU offload is unavailable` load error can use the existing CPU fallback,
   in addition to the existing caught Vulkan pipeline-creation fallback. Cancellation,
   corrupt models, memory admission failures and native quarantine do not trigger it.
8. Completed GPU failures now close their journal before CPU fallback begins, avoiding
   a false native-interruption record. CPU generation after fallback has its own active
   journal, so a genuine crash still protects the correct backend.
9. Generation fallback restores every previously completed conversation turn after
   reopening the CPU engine. GPU failure classification checks a bounded cause chain,
   rejects wrapped cancellation, and cannot loop forever on cyclic exception causes.

## Validation

- Built the production JNI library and Jinja renderer from the hash-verified v0.7.0
  source, in Release mode on Linux x86-64.
- Real CPU inference smoke passed for both ChatML and custom Jinja GGUF fixtures:
  repeated loads/conversations, history, exact token counts, prefix reuse,
  cancellation before and during inference, stale-operation protection, allocation
  observations, oversized-input rejection and oversized-output-budget recovery.
- Jinja tests passed, including upstream Gemma 2/4, Qwen 3/3.5, Phi and Mistral templates.
- Verified optimized portable flags for 18 inference source files.
- Focused JVM harness: 36 tests passed against production planning, prompt bridge,
  manifest validation, digest validation and accelerator-policy code. Android/data
  boundary stand-ins were used; this is not a full Android app test.
- Python GGUF governor, APK inventory and native-memory report parser tests passed;
  Android regex preflight passed. Parser tests do not constitute device execution.
- Changed Kotlin files passed ktlint 1.3.1.
- The standard Gradle unit-test command was attempted but could not download Gradle
  through this host's Java network setup. Full Android compilation, lint and APK
  packaging remain CI checks. No physical ROG Phone 9 Pro was connected.

## Device acceptance before release

Install the resulting APK and reuse the existing import; re-downloading model weights
should not be necessary. For this already-quarantined profile, explicitly select CPU
and run **Verify load**, then send `hi`. Test a second turn, Stop, unload/reload and an
actual tool request. Keep logs showing runtime revision, actual backend, context,
native progress and final status. Test Vulkan separately after CPU passes. A failed
GPU tuple must remain protected until its controlled verification succeeds.

This is a text-generation integration. GGUF is a container, not a guarantee that
every architecture, quantization, shard set, projector or model size can run on a
particular phone. Multimodal projector loading and new Hexagon/OpenCL packaging are
separate deliverables and are not claimed by this change.


## CI follow-up, 23 October 2026

The native-memory job on PR #730 failed in `ApkFlinger.writeFile` / Zipflinger
`BytesSource` with a 4 GiB heap. AGP 9.4.1 dispatches compression through
`ForkJoinPool.commonPool`, independently of Gradle's worker limit. Both native
packaging workflows now set `java.util.concurrent.ForkJoinPool.common.parallelism=1`
for APK assembly, preserving the heap ceiling, R8 and packaged-library audits.

The GenieX suite on PR #731 found a race in `CombinedPassageIndicatorsTest`:
recomposition resumed inline on `DefaultDispatcher-worker-4`, causing Android's
`CalledFromWrongThreadException`. Its Compose rule now uses `StandardTestDispatcher`
so matching results recompose on the test clock, as in `ChatMarkdownWordingTest`.
The badge, click and stale-result assertions remain intact. Changed Kotlin passes
ktlint 1.3.1; both workflow documents parse successfully. Full APK assembly and
Android test validation run in CI.
