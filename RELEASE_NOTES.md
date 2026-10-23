# GPT Mobile AI 0.9.56.1 (build 127)

Local model selection now checks whether Android can open the selected document in place before starting installation.

- If the file provider prevents native path access, the review offers **Import copy** with a clear storage notice. This uses the granted document stream and preserves the original file.
- Compatible files still link in place by default. Copying is always an explicit choice; saved link requests never silently change into copies.
- File and manifest-folder copies reuse validation, hashes, cancellation cleanup and durable import jobs. Folder import temporarily needs about twice the model size.
- Added an Android regression covering a readable descriptor whose path cannot be reopened, plus copy/link dispatch and UI coverage.

After updating, select the model again and confirm **Import copy** if it cannot be linked. Model-format and device/runtime compatibility checks still apply.

## Qualification limits

- Unified Reliability v2 is not complete; its remaining acceptance cases are not represented as passed.
- Physical-device performance and a production baseline profile remain unqualified until measured on a reference device.
- Qwen demo backend availability and live authenticated LLM7 generation are not established by deterministic tests.
- Optional runtime extraction remains a staged packaging project; this release adds a measured payload inventory without claiming APK size savings.
