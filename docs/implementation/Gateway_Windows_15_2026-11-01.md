# Gateway 15 Windows installation and update

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base branch: fix/github-task-evidence-reporting-2026-11-01 (PR #777)
Base SHA: 01fdadc63aa0b101cc0850cb1eee18d29f750fa2
Working branch: feat/gateway-windows-installer-autoupdate-2026-11-01
Intent: ship a per-user Windows installer, exposed setup/settings, managed runtime and idle-only verified update/rollback; retain Gateway contracts and update Android compatibility and diagnostics.

## Implemented behavior

- Native per-user Inno Setup installer with a bundled Python runtime, stable PowerShell launcher, Start menu shortcuts and an editable native settings window.
- Visible backend, port, worker/admission limits, shutdown grace, MCP path, exact Tailscale hostname/login, engineering scope, startup and update policy controls. Existing settings and journal state survive upgrades; uninstall waits for idle and retains user data.
- Official stable-release discovery, mandatory SHA-256 digest, bounded/allowlisted HTTPS downloads, safe extraction, complete binary manifest and isolated candidate validation.
- Secret-gated local maintenance protocol, request/worker/saved-work quiescence, offline SQLite snapshots, atomic pending version activation, health verification and rollback after failure or interruption.
- Bounded crash restart and rotating logs; Windows Job Object owns engine and descendant process lifetimes. Notify, Auto and Off modes; failed versions are excluded from automatic retries.
- Gateway 15 capability advertisement and Android 14/15 key-free compatibility, shared fixture and connection diagnostics. Existing gateway tool/model execution remains on the characterized runtime.

## Validation

- Gateway regression suite: 289 Python tests and 7 browser tests passed on Linux.
- Canonical fast validation: 285 host tests plus policy, dependency, resource, regex and provenance checks passed.
- Android gateway capability serialization: four Kotlin/JUnit host tests passed using the serialization compiler plugin. This is not an Android build or device test.
- Windows workflow builds the setup EXE and update ZIP and runs actual installer/frozen-runtime/rollback/repair/uninstall smoke tests. Results are pending until that workflow completes.
- Full Android build, interactive installer accessibility, physical phone/Tailscale interoperability and publisher code signing remain acceptance gates. No zero-defect or flawless-install claim is made.

The first Windows run exposed two test portability problems: health polling timed out before Windows completed a refused-backend probe, and ZIP construction normalized backslashes. The regression now preserves raw archive names and waits for the bounded health response. Maintenance also resumes admission after a lost prepare/shutdown response, fences late prepare completion so it cannot re-enter draining, and counts direct backend mutation requests.
