# Development

Use JDK 21, the checked-in Gradle 9.8 wrapper, Android platform 37.0/build-tools 37.0.0,
and the NDK/CMake versions declared in app/build.gradle.kts. Native Vulkan compilation
requires glslc, Vulkan development headers and SPIR-V headers. Accept SDK licenses using
sdkmanager; a failed license setup must fail validation.

```bash
bash scripts/validate-local.sh fast   # Python contracts, policy, secrets, resources, whitespace
bash scripts/validate-local.sh full   # Also gateway/OAuth, Android tests, lint and APK checks
python3 scripts/generate_architecture_index.py
```

Full validation needs Node 22 for gateway session contracts. Install the hash-locked gateway
requirements in a virtual environment first. Fast validation does not compile Kotlin or prove
Android runtime behavior. Android instrumentation uses the existing emulator acceptance and
physical-device workflows; only the latter can qualify SM8750 QNN and native GPU execution.

Gradle uses one worker and bounded heaps to avoid CI OOM. Configuration cache remains disabled
because ObjectBox retains a Project during serialization. Do not enable parallel Gradle work
indiscriminately; repository/gateway checks run independently at the job level instead.

The local composite `.github/actions/setup-android` establishes JDK, SDK, wrapper validation
and cache policy for PR validation. PRs read caches; only pushes to main write them. Dependency
verification remains in `gradle/verification-metadata.xml`; wrapper distribution SHA-256 is
also checked. No secret-bearing Gradle configuration cache is uploaded.

Use CI artifacts for `ci-diagnostics.json`, first actionable failures, JaCoCo, XML results,
Android lint and review APKs. Diagnostic summaries are bounded and redact credentials;
raw runner logs may still contain application data and must be reviewed before sharing.

## From a clean checkout

Create and activate a Python virtual environment, then install the host-check dependencies with `python -m pip install --require-hashes -r scripts/requirements-ci.txt`. The full suite also uses the pinned Gateway requirements. Use `npm ci` in the relevant MCP directory when developing its Node service.

The Android application, portable Kotlin module, native local-runtime module and Android benchmarks live in `app/`, `core-domain/`, `local-gguf-runtime/` and `macrobenchmark/`. Unit and device tests are next to the module under `src/test` and `src/androidTest`. The Gateway, OAuth broker and tool servers are in `gateway/`, `authentication/` and `mcp/`.

| Checked-in material | How to maintain it |
| --- | --- |
| Room schemas in `app/schemas/` | Change the entity and migration, then run `./gradlew :app:kspDebugKotlin` and review the schema diff. |
| App diagnostic inventory | Run `python3 scripts/generate_app_audit_inventory.py` after changing indexed production code. |
| Gateway package manifest | Run `python3 gateway/build_v14_package.py --manifest-only` after changing packaged files. |
| Model catalogue and registry snapshots | Follow the pinned-source normalization command in [catalog/README.md](catalog/README.md). |
| Bundled MathJax, SQLite and native runtimes | Treat these as dependencies. Preserve the bundled license and provenance records. The Android SQLCipher AAR is resolved through the version catalogue. |

Use different service credentials for development, staging and production. Configure the broker origin, callback host and client ID for the intended deployment. Release signing uses protected configuration documented in [.signing/README.md](.signing/README.md). The Android build embeds public client identifiers. Provider credentials are supplied by the user or the configured gateway.

## Updating release dependencies

Both Android editions lock their runtime and Room processor dependencies. The root build also locks the Gradle plugin classpath. Refresh the affected graphs after changing a dependency. Verify any new checksums against publisher artifacts before committing the lockfiles and verification metadata.

```sh
./gradlew buildEnvironment --write-locks
for graph in releaseRuntimeClasspath kspReleaseKotlinProcessorClasspath; do
  ./gradlew :app:dependencies --configuration "$graph" --write-locks
  ./gradlew -PgeniexRuntime=true :app:dependencies --configuration "$graph" --write-locks
done
python3 scripts/audit_locked_dependencies.py
```

Release qualification includes the required PR jobs, the emulator acceptance suite and the release workflows for the selected edition. The encrypted-history upgrade test runs within the existing Secret Vault instrumentation suite. See [CI and release validation](.github/workflows/README.md) and [security reporting](SECURITY.md).

Android lint publishes unused resource counts and file locations on pull requests. The weekly maintenance run regenerates the source and architecture indexes and attaches an apply-ready patch when they have drifted.
