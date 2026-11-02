# Gateway 15 for Windows

Gateway connects GPT Mobile to your model backend and approved PC tools. The Windows x64 installer bundles Python and the pinned gateway dependencies. The model server and optional Tailscale installation remain separate prerequisites.

## Install and connect

1. Run `Gateway-15.0.0-windows-x64-setup.exe` under your normal Windows account. Choose the installation folder, backend origin, port, worker/chat limits, shutdown grace, MCP configuration, private access identity, startup preference and update policy.
2. Start your model server. Default backend: `http://127.0.0.1:8080`. Gateway listens only on `127.0.0.1:8090` by default. Backend origins must omit `/v1`; remote backends require HTTPS. MCP commands run with your Windows account permissions: use configuration from sources you trust.
3. Open **Gateway Settings** from the Start menu to check status, edit settings, restart, stop, check updates or install an available update. Notify is the default; select Auto for unattended updates. Restart applies saved connection/concurrency changes after work finishes.
4. Network listener modernization is planned on this branch: selectable loopback, specific interface, or all-interface binding, with mandatory authenticated remote access. The existing installer still uses loopback and Tailscale Serve until runtime, security, installer, and Android configuration are updated and tested together. Do not expose the current key-free listener directly on a LAN or public interface.

Existing installations retain their settings; change them through Gateway Settings. Uninstall refuses active work and retains `data/`, settings and logs for deliberate recovery. Remove that folder manually only if you intend to delete all saved jobs and configuration. Automatic updates keep the previous release and three verified pre-update SQLite backup sets; releases are never allowed to perform a new state-schema migration automatically.

## Updates and recovery

The manager checks the official `tailscale-signin/GPT_Mobile_AI-improved` GitHub Releases feed, at most once per configured interval (24 hours by default). It accepts only newer, stable `gateway-v15.x.y` releases with an exact `Gateway-15.x.y-windows-x64.zip` asset and a GitHub-provided SHA-256 digest. Downloads use HTTPS with an explicit redirect-host allowlist. Metadata/download/extraction limits, per-file hashes, path checks, and isolated runtime preflight apply before staging. These are integrity checks anchored in the official GitHub account and TLS, **not Authenticode code signing**.

Auto activation refuses in-flight uploads, chats, workers and unfinished engineering jobs, including paused approvals. It quiesces admission without cancelling work, stops cleanly, backs up SQLite, and atomically records a pending version. The stable launcher starts the new supervisor and runtime. It checks the journal and version before resuming admission; an unavailable model backend is reported separately. A failed or interrupted trial returns to the previous version. A failed version is excluded from automatic retries; explicit Install Update can retry it. No automatic major-version or state-schema upgrade is accepted.

If startup fails, inspect `logs/gateway.log` and `status.json`. Logs rotate at 5 MiB with four backups. Do not publish raw logs without reviewing them for model/tool content. The startup shortcut is idempotent, uses a state-directory lock and binds the engine process tree to the supervisor lifetime on Windows. Repeated unexpected exits trip a bounded restart limit. If a job is waiting for input/merge approval, finish or cancel it before updating or uninstalling.

## Build and release

On Windows x64 with Python 3.12 and Inno Setup 6:

```powershell
python -m pip install --require-hashes -r gateway/requirements.txt
python -m pip install --require-hashes -r gateway/desktop/requirements-build.txt
python gateway/build_v14_package.py --manifest-only
python gateway/desktop/build_windows.py
python gateway/desktop/smoke_windows.py
```

CI produces the unsigned setup EXE, update ZIP, SHA256SUMS and actual Windows smoke results. It does not publish a production release. Before distributing, review the exact commit, CI and Windows acceptance evidence, sign the EXE/runtime with the publisher's certificate if available, regenerate the runtime manifest/ZIP after signing, and publish both assets under the matching stable `gateway-v15.x.y` GitHub release. Verify GitHub reports the asset digest. A renamed ZIP, missing digest or incompatible schema is intentionally not an update.

The installer cannot guarantee a flawless installation on every PC. Interactive wizard accessibility, corporate policies, antivirus/SmartScreen, code signing, Tailscale identity on a real tailnet, and physical Android-device interoperability require separate acceptance testing. Windows CI validates fresh/repair installs, frozen startup, offline-backend behavior, failed-candidate rollback, and data-preserving uninstall. The Android build and its required checks must pass independently.
