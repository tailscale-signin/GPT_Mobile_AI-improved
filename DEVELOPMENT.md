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
