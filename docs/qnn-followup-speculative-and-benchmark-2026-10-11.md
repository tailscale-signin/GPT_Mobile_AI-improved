# QNN follow-up: speculative decoding and benchmark startup

## Evidence

The supplied `app-diagnostics-4562666320839614591.jsonl` has 1,947 JSONL rows: 692 events and 1,255 continuations. It identifies version 0.9.41.0, build 110, debug merge commit `8edac713f94772a4d5242977be0bb6650f47eb9d` (PR #654 on main `cb6941e2`). It therefore already includes the Gemma 4 filename-block removal.

All three logged local requests select `gemma-4-e2b-it_qualcomm_sm8750.litertlm`. All finish failed with zero output:

| UTC time | Failure | Interpretation |
| --- | --- | --- |
| 11:40:02 | Export has no verified speculative-decoding support | Application capability validation rejects forced speculative decoding before `Engine.initialize()` |
| 11:41:01 | Same speculative-decoding rejection | Retrying without changing the configuration repeats the same rejection |
| 11:41:20 | This download is an NPU package | The selected accelerator cannot run the compiled NPU artifact; another backend needs a separate compatible export |
| 11:41:36 | `ForegroundServiceDidNotStartInTimeException`, `BenchmarkForegroundService` | Android terminates the application because foreground promotion did not complete in time |

The first two failures are incorrectly wrapped as `UNKNOWN_NATIVE_ERROR` / `UNKNOWN_WITHIN_NATIVE_INITIALIZE`, then wrapped again after IPC erases the exception type. This is not evidence of a DSP, driver, firmware, or QNN library failure. No GPU-edition generation attempt appears in this capture. Remote MCP ports 8101/8102 also time out; those failures are separate from model initialization.

The crash log does not identify the precise service scheduling interleaving. Code inspection finds two avoidable risks: benchmark work begins before foreground promotion is acknowledged, and service dependency construction precedes notification publication. The patch addresses both; actual device retesting remains necessary.

## Changes

- Resolve global speculative-decoding On against the selected LiteRT artifact's metadata. Unsupported or unknown support uses ordinary decoding with an explicit notice; supported artifacts retain On. Model default and Off retain their meaning.
- Preserve explicit, tuning-locked benchmark configurations so a requested MTP test is never silently scored as ordinary decoding.
- Retain actual backend, context, model integrity, SoC and native-crash checks. Choosing GPU cannot convert SM8750 compiled weights.
- Surface application capability failures as `LocalModelConfigurationException` instead of reporting a native QNN failure or trying an unrelated backend.
- Carry configuration and NPU-package exception identities across the worker IPC boundary, avoiding repeated fallback instructions.
- Gate benchmark execution on successful foreground promotion for the exact session. A cancelled pending session does not stop the service before promotion; stale deliveries are rejected after satisfying Android's foreground requirement.
- Publish the initial notification without resolving the benchmark coordinator/database dependency graph. Resolve that graph lazily afterward.

## Regression coverage

- Global On with an unsupported NPU artifact reaches ordinary decoding, passes that setting to the conversation, and reuses the warm engine.
- Supported packages retain On; explicit benchmark Off stays Off; unknown capabilities do not force On.
- Explicit unsupported settings remain typed configuration errors; remote package errors retain a single message.
- Benchmark work cannot execute before acknowledgment, an immediate completion stops only afterward, and cancellation/restart rejects stale session acknowledgments.
- QNN capability rejection retains its application error identity.

## Device check

For the currently installed APK, choose **NPU** for the SM8750 package and **Speculative decoding: Off** (Model default also avoids forced On). Start with a short plain-text request. This removes the observed settings rejection but does not establish successful NPU execution.

For the patched build, repeat with global On: expect an ordinary-decoding notice and an actual NPU load attempt. Then confirm nonempty completed output. Start/cancel/restart a benchmark, including an immediately failing profile, and verify the app remains alive. Any subsequent native error needs its own diagnosis; no hardware success is claimed here.

## Runtime references

- [Android foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting)
- [LiteRT-LM speculative-decoding flag semantics](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/ExperimentalFlags.kt)
