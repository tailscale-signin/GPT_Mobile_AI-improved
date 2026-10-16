# Qualcomm SM8750 NPU catalog and import repair

## Problem
The SM8750 Gemma 4 package is an NPU-compiled LiteRT-LM export. Profiles showed **Model has no NPU build**, because manual imports were stored under a generic filename and persisted with empty accelerator metadata. The bundled Gemma 4 catalog also omitted the published Qualcomm SM8750 variant. Attempting to use the NPU export through the GPU engine could fail with `Input tensor not found`.

## Implemented corrections
- Preserve original `.litertlm` filename on new imports; content-hash IDs still deduplicate identical bytes imported under different filenames.
- Persist declared NPU/backend identity and the exact Qualcomm SoC variant from the original import filename. These metadata declarations are **not** proof of successful native execution.
- Restore older content-addressed `model.litertlm` imports using the original imported basename stored in app-owned `.catalog.json`. Rehydrate in memory, without moving multi-GB model weights or changing the database schema.
- Block GPU/CPU fallback after a QNN initialization error for those legacy NPU-only imports.
- Add SM8750 Gemma 4 to bundled and hosted V1/V2 catalogs with checksum and immutable upstream revision.
- Retain the SoC-match, model integrity, native driver, dispatch and context checks.

## Upstream artifact
- File: `gemma-4-E2B-it_qualcomm_sm8750.litertlm`
- Repo: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Published revision: `84b6978eff6e4eea02825bc2ee4ea48579f13109`
- Size: `3016294400` bytes; SHA-256: `41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e`
- Context size: 4096 tokens for this Qualcomm NPU export (distinct from CPU/GPU export limits)
- Source: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm

## Test and rollout
- Unit tests cover filename retention, hash-id deduplication, legacy recovery, exact SM8750 catalog variant, SoC mismatch and no NPU-to-GPU fallback.
- Run Android compilation and pull-request checks. A green host CI build is not an on-device NPU verification.
- Device: install the branch APK, select the `Gemma 4 E2B · NPU` SM8750 package (or recovered legacy import), choose **Profile** policy and **Speculative decoding Off**, then issue a short plain-text prompt.
- Capture `QNN_PROBE_RESULT`, `QNN_ENGINE_INIT_SUCCEEDED`, completion and nonempty token output. A successful load without generated text does not qualify the NPU.
- Investigate any remaining QNN native driver/QAIRT/firmware error separately. Never pretend a CPU/GPU fallback ran the compiled NPU export.

## Merge audit follow-up
- Updated the hosted-catalog regression to verify the new SM8750 filename, context limit and pinned revision; its old assertion incorrectly required that variant to be absent.
- Preserve the saved original SoC identity when a legacy content-addressed import is re-imported under a generic filename. Metadata is reused only for the same model ID and LiteRT-LM type, so a different model cannot inherit NPU eligibility.
- Integrated current main, including the Combined/Local Assist and composer-readiness repairs, before final CI.
- 33 isolated JVM catalogue, accelerator and package tests pass, along with changed-file Kotlin lint and whitespace checks. The harness substitutes Android/build configuration and excludes the unrelated Hub-network result test; full Android CI remains the merge gate. Physical SM8750 inference still requires the device checks above.
