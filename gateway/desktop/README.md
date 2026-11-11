# Gateway 16 for Windows

Gateway connects GPT Mobile to your model backend and approved PC tools. The Windows x64 installer bundles Python and the pinned gateway dependencies. The model server is a separate prerequisite. No Tailscale installation is needed.

## Install and connect

1. Run `Gateway-16.0.0-windows-x64-setup.exe` under your normal Windows account. Choose the installation folder, backend origin, port, worker/chat limits, shutdown grace, MCP configuration, listen address, optional LAN firewall rule, startup preference and update policy.
2. Start your model server. Default backend: `http://127.0.0.1:8080`. Gateway listens only on `127.0.0.1:8090` by default. Backend origins must omit `/v1`; remote backends require HTTPS. MCP commands run with your Windows account permissions: use configuration from sources you trust.
3. Open **Gateway Settings** from the Start menu to check status, edit settings, restart, stop, check updates or install an available update. Notify is the default; select Auto for unattended updates. Restart applies saved connection/concurrency changes after work finishes.
4. For phone access, set **Listen address** to the PC interface IP or `0.0.0.0` (all IPv4 interfaces), save and restart. On Android, use a LLAMA connection with `http://<PC-IP-or-hostname>:8090/v1` and leave the API key empty. `0.0.0.0` is a listener setting, not the phone's destination. The PC hostname is admitted automatically; add custom aliases in **Additional hostnames** if needed.
5. If Windows blocks the connection, choose the installer's unchecked firewall task or **Allow LAN through firewall…** in Settings. This asks Windows for administrator approval and creates one installation-specific inbound TCP rule for the saved port, restricted to the **Private** profile and **LocalSubnet**. No rule is created by default or during automatic updates. Reapply after changing the port or bind address; **Remove firewall rule…** and uninstall remove only this installation's rule.
6. Network access is key-free. Reachable devices can use the enabled models and PC tools, and they share network jobs. Enable network engineering separately if wanted. Use only a trusted LAN/private VPN, or put a separately authenticated HTTPS endpoint in front of Gateway for public access. Browsers, remote administration, spoofed identity headers and arbitrary DNS hostnames do not get extra access.

Existing installations retain their backend, port, performance, startup/update settings and saved jobs. Legacy Tailscale settings migrate to localhost until a network address is deliberately chosen; old identity fields remain recorded for recovery. Change them through Gateway Settings. Uninstall refuses active work and retains `data/`, settings and logs for deliberate recovery. Remove that folder manually only if you intend to delete all saved jobs and configuration. Automatic updates keep the previous release and three verified pre-update SQLite backup sets; releases are never allowed to perform a new state-schema migration automatically.

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

The installer cannot guarantee a flawless installation on every PC. Interactive wizard accessibility, corporate policies, antivirus/SmartScreen, code signing, LAN/firewall behavior under corporate policies, and physical Android-device interoperability require separate acceptance testing. Windows CI validates fresh/repair installs, frozen startup, offline-backend behavior, failed-candidate rollback, and data-preserving uninstall. The Android build and its required checks must pass independently.

## Desktop dashboard and taskbar icon

The running supervisor adds the app's robot icon to the Windows notification area. Click it to open **Gateway Dashboard**; right-click for settings, restart and stop. Its tooltip shows observed clients and open jobs. The dashboard has its own matching taskbar icon and Start menu shortcut; setup also offers an optional desktop shortcut. Closing the dashboard leaves the gateway running.

The dashboard uses GPT Mobile's default dark teal, cyan and purple palette. Overview shows persistent total generated tokens, today's output, measured generation speed, open jobs and a seven-day output graph. Devices shows actual socket addresses, client agents, active requests and last-seen times. Recent activity combines saved chats, engineering jobs and tool executions. Search jobs and GitHub jobs have their own filterable views with status and details. Start, restart, stop and connection settings are available from the window.

Device observations are grouped by socket address, not authenticated identity. “Connected” includes active requests and addresses seen in the last 60 seconds; NAT or a proxy can combine clients. Offline/stale snapshots never claim live connections. Connection and request counters reset when the engine restarts; measured token totals persist. The latest 300 tool/request activities persist locally; interrupted activities are marked on restart. Saved chat and engineering views each show up to 100 jobs. Search entries represent tool operations, including operations inside chats, rather than a separate search scheduler.

Only the local supervisor can retrieve desktop observations. The desktop reads local metadata snapshots, with no API credential required from the phone and no prompts, tool arguments or result bodies added to activity history. Repair/uninstall asks desktop windows to close before replacing their executable; saved activity and jobs remain in `data/`.

## GitHub

Public repository maps are enabled by default. Once your phone connects to this gateway, open **GitHub Mode → Status → Repository map** and choose **Build repository index**. GitHub's anonymous rate limits apply. You can turn public maps off in **Gateway Settings → GitHub**. Save settings and restart to apply this preference.

For private maps or saved GitHub jobs, select **Open GitHub settings for private repositories or saved jobs** when the installer finishes, or open **Gateway Settings → GitHub** later. The gateway reuses one eligible GitHub add-on automatically. To connect a different account, use **Create GitHub token**, grant access to the repositories you need, and paste the token into the masked field. For mapping, grant **Contents → Read-only**. Enter a repository to check its source access, then click **Connect**. The gateway stores the token in Windows Credential Manager after GitHub confirms it. **Test connection** checks the current configuration, and **Remove saved token** restores automatic selection.

On a LAN listener, **Allow network devices to run GitHub engineering tasks** grants connected devices access to private maps and saved engineering operations through this PC's GitHub connection. Enable it only for your intended network, save settings, and restart after active work finishes.

## General, Advanced and Add-ons

Open each section directly from the dashboard sidebar or from Gateway Settings. General exposes the backend, listener address, hostnames, startup/update preferences and explicit firewall actions. Advanced exposes worker/chat limits and shutdown grace. Add-ons shows the enabled services in the selected MCP JSON configuration. Saving add-ons preserves commands, arguments and credentials, and refuses a save if the configuration changed externally. Save the selected file path in settings and restart when work finishes to apply it. The gateway service allowlist remains authoritative.

The token graph uses provider completion usage or llama.cpp timing receipts, including reasoning when counted by the backend. Missing measurements remain unavailable and partial coverage is disclosed. Counters start with this update, persist in `data/usage.sqlite3`, and exclude cached deliveries. These are measurements for this installation, without inferred per-phone attribution. Old logs are not estimated. A single background observer delivers both usage and activity without blocking generation; stale data is visibly marked.


### Export gateway debug logs

Open **Settings → Advanced**, enable **Debug mode**, save and restart after active work finishes. The gateway keeps at most **5,000** sanitized diagnostic lines in `logs/gateway-debug.jsonl`; **Export debug log…** writes the retained history to a file you choose. Enable Debug mode in Android as well to include the selected profile/model digests, app build, Android SDK and network revision. When local log tracking is enabled, the phone records the gateway acknowledgement and runtime instance ID. Temporary conversations suppress client diagnostic metadata. Prompts, response bodies, code, credentials and device identifiers are excluded. Disabling the option stops new records; saved diagnostic history remains available for export. This setting does not change the selected model, backend or transport permissions.
