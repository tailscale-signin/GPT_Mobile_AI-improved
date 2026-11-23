# Repository native benchmark pack

The executable and live lanes can select `task_pack=repository`. Quick contains 4 distinct defects, Standard 8, and Deep all 12. These are actual problem counts; repeated seeds do not become additional independent problems. The pack keeps a separate manifest and comparison population from the Python coding pack.

| Group | Four defects | Evidence scope |
| --- | --- | --- |
| Kotlin | stale asynchronous generation, stable profile UID selection, one-shot event replay, empty editor draft restoration | Actual Kotlin/JVM compilation and execution of state reducers. No Compose rendering or Android instrumentation claim. |
| SQLite | persisted default column, foreign-key table rebuild, normalized unique index, transaction rollback | Actual SQLite schema/data behavior for Room-compatible migrations. No Room annotation processor or device-runtime claim. |
| Workflow YAML | required dependencies, skipped-job gate, exact event/ref, fork permission inheritance | Parsed YAML, bounded expression semantics, actual shell gate execution. It is a workflow simulation; actual live qualification additionally requires remote check readback. |

## Operator runtime contract

Set `repository_image` in benchmark settings to an already installed immutable image, for example an operator-owned repository followed by `@sha256:` and its actual 64-character digest. The desktop settings screens expose this field. The application does not select, build, or download a native compiler image automatically. A missing image/compiler/parser is a setup failure with an unknown model outcome.

The configured Linux image must contain `python3` with the standard SQLite module and PyYAML, `kotlinc`, a compatible `java`, and `/bin/sh`. Its binaries and runtime libraries must be readable/executable as UID/GID 65534. It must contain no credentials. Kotlin must be able to compile with `-include-runtime`; `/tmp` is its only writable build location. Compiler and SQLite/YAML versions are retained in observed runtime receipts.

Execution uses the local default Docker context, `--pull=never`, no network, no capabilities, no privilege escalation, a read-only root, UID/GID 65534, 2 CPUs, 1 GiB memory, 96 PIDs and a 256 MiB temporary filesystem. The native process deadline is `min(180, max(60, sandbox_seconds))` seconds. Reference, known-broken and model candidates each receive an isolated container under that same policy. The task's outer budget bounds the full attempt, including calibration and validation.

## Independent verification

Before grading a model, the native reference must return all expected observations and the shipped defective control must execute and disagree with the oracle. A failed calibration makes the task unqualified. The model's final candidate is then compiled/executed separately. The host compares typed observations with frozen expected values; a candidate-provided pass flag has no authority.

Candidate source and input-only driver files are mounted separately, read-only. Expected outputs stay on the verifier host. Kotlin receives immutable UTF-8 strings. SQLite receives a fresh database per scenario. These boundaries do not prove immutability of arbitrary caller-owned objects; exported `inputObjectImmutability` remains `NOT_CLAIMED`.

Workflow expression support is deliberately bounded and exported as `ACTIONS_YAML_GATE_SIMULATION`. Supported functions and syntax are listed in every task prompt; unsupported features are rejected. Equality, case handling, short-circuit conditions, default success gating, and dependency skipping follow the applicable [GitHub expression rules](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions) and [job dependency rules](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idneeds). This adapter cannot certify the complete hosted Actions runtime.

Verified task completion is independent of the secondary 1000-point rubric. Criteria not measured by this native pack remain unavailable. First, final and best candidate outcomes remain separate, and both model attempts contribute to total effort even when unsuccessful.

## Live native qualification

`remote_bundle(task, candidate, image, run_id)` returns the exact files and workflow path for the root live qualification service. Its job and verification step are named `Independent coding verification` and `Verify immutable artifact` so the service can bind remote evidence to the published artifact.

Use a disposable repository with a prepared **self-hosted Linux runner labeled `gateway-native-benchmark`**. The same immutable image must already exist on that runner. Workflow permissions are read-only, checkout credentials are not persisted, and only the selected source directory plus input-only evaluator directory enter Docker. Expected results remain outside the container. Push triggers are restricted to `benchmark/**` branches and this run's own bundle/workflow paths. Concurrency does not cancel an earlier run; the workflow timeout is 15 minutes. An unavailable runner or image remains unqualified.

## Validation recorded for this change

All 12 positive and broken controls were exercised: Kotlin 2.4.20 with JDK 21 compiled and ran all four state repairs; SQLite ran all four migrations; PyYAML parsed and executed all four shell-gate families. Ten focused tests also cover exact blob binding, failed calibration, missing runtime, forged pass flags and remote mount isolation. These tests run only repository-owned controls on the development host. Untrusted generated source has no host execution path.

There is no Docker daemon in the development workspace used for this change, so container execution and self-hosted live CI remain runtime qualification gates. They are implemented and fail closed; they are not represented as completed validation.

The optional host compiler control test requires `GATEWAY_KOTLIN_COMPILER_CLASSPATH`, `GATEWAY_KOTLIN_STDLIB`, and `GATEWAY_JAVA_EXECUTABLE`, pointing at already installed trusted compiler artifacts. Tests never fetch compilers.
