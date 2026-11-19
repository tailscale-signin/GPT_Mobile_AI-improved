# Gateway 17 for Windows

Gateway connects GPT Mobile to your model backend and approved PC tools. The Windows x64 installer bundles Python and the pinned gateway dependencies. The model server is a separate prerequisite. No Tailscale installation is needed.

## Install and connect

1. Run `Gateway-17.0.1-windows-x64-setup.exe` under your normal Windows account. Choose the installation folder. On a fresh install, select Standard Gateway or GitHub Engineering, choose the backend and network interface, and review the PC checks. An offline model can be connected after installation. Select **Review advanced connection, worker and update settings** on the last page for additional controls. Repair installs retain the existing settings.
2. Start your model server. Default backend: `http://127.0.0.1:8080`. Gateway listens only on `127.0.0.1:8090` by default. Backend origins must omit `/v1`; remote backends require HTTPS. MCP commands run with your Windows account permissions: use configuration from sources you trust.
3. Open **Gateway Settings** from the Start menu to check status, edit settings, restart, stop, check updates or install an available update. Notify is the default; select Auto for unattended updates. Restart applies saved connection/concurrency changes after work finishes.
4. For phone access, set **Listen address** to the PC interface IP or `0.0.0.0` (all IPv4 interfaces), save and restart. On Android, use a LLAMA connection with `http://<PC-IP-or-hostname>:8090/v1` and leave the API key empty. `0.0.0.0` is a listener setting, not the phone's destination. The PC hostname is admitted automatically; add custom aliases in **Additional hostnames** if needed.
5. If Windows blocks the connection, choose the installer's unchecked firewall task or **Allow LAN through firewall…** in Settings. This asks Windows for administrator approval and creates one installation-specific inbound TCP rule for the saved port, restricted to the **Private** profile and **LocalSubnet**. No rule is created by default or during automatic updates. Reapply after changing the port or bind address; **Remove firewall rule…** and uninstall remove only this installation's rule.
6. Network access is key-free. Reachable devices can use the enabled models and PC tools, and they share network jobs. Enable network engineering separately if wanted. Use only a trusted LAN/private VPN, or put a separately authenticated HTTPS endpoint in front of Gateway for public access. Browsers, remote administration, spoofed identity headers and arbitrary DNS hostnames do not get extra access.

Existing installations retain their backend, port, performance, startup/update settings and saved jobs. Legacy Tailscale settings migrate to localhost until a network address is deliberately chosen; old identity fields remain recorded for recovery. Change them through Gateway Settings. Uninstall refuses active work and retains `data/`, settings and logs for deliberate recovery. Remove that folder manually only if you intend to delete all saved jobs and configuration. Automatic updates keep the previous release and three verified pre-update SQLite backup sets; releases are never allowed to perform a new state-schema migration automatically.

## Updates and recovery

The manager checks the official `tailscale-signin/GPT_Mobile_AI-improved` GitHub Releases feed, at most once per configured interval (24 hours by default). Gateway 17 accepts newer, stable `gateway-v17.x.y` releases with an exact `Gateway-17.x.y-windows-x64.zip` asset and a GitHub-provided SHA-256 digest. Downloads use HTTPS with an explicit redirect-host allowlist. Metadata/download/extraction limits, per-file hashes, path checks, and isolated runtime preflight apply before staging. These are integrity checks anchored in the official GitHub account and TLS, **not Authenticode code signing**.

Auto activation refuses in-flight uploads, chats, workers and unfinished engineering jobs, including paused approvals. It quiesces admission without cancelling work, stops cleanly, backs up SQLite, and atomically records a pending version. The stable launcher starts the new supervisor and runtime. It checks the journal and version before resuming admission; an unavailable model backend is reported separately. A failed or interrupted trial returns to the previous version. A failed version is excluded from automatic retries; explicit Install Update can retry it. Unsupported release majors, incompatible bootstraps and state-schema upgrades are rejected.

If startup fails, inspect `logs/gateway.log` and `status.json`. Logs rotate at 5 MiB with four backups. Do not publish raw logs without reviewing them for model/tool content. The startup shortcut is idempotent, uses a state-directory lock and binds the engine process tree to the supervisor lifetime on Windows. Repeated unexpected exits use a restart backoff of up to five minutes while the per-user supervisor is running. The latest recovery attempt and retry time are recorded in `status.json`. If a job is waiting for input/merge approval, finish or cancel it before updating or uninstalling.

## Build and release

On Windows x64 with Python 3.12 and Inno Setup 6.6 or later:

```powershell
python -m pip install --require-hashes -r gateway/requirements.txt
python -m pip install --require-hashes -r gateway/desktop/requirements-build.txt
python gateway/build_v14_package.py --manifest-only
python gateway/desktop/build_windows.py
python gateway/desktop/smoke_windows.py
```

CI produces the unsigned setup EXE, update ZIP, SHA256SUMS and Windows smoke results. To publish the reviewed version, run **Gateway Windows installer** on `main` with **publish** enabled. The workflow requires exact-source release checks and Windows smoke, uploads a draft and confirms GitHub's SHA-256 digests before making it public. A version already published cannot be replaced. If upload or digest confirmation fails, inspect the draft and remove only that incomplete release before rerunning. The setup executable remains unsigned.

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

### Guided engineering prerequisites

Windows installation offers **Standard Gateway** (Docker not required) or **Full GitHub Engineering**. Selecting Full opens the opt-in **Engineering Setup** wizard after installation. It is also available from the Windows Start menu and the **Guided Docker / WSL setup** button under Gateway Settings → GitHub → Repository Validation. Existing installations are not silently enrolled during upgrades.

The wizard separately checks Git, WSL 2, the local Docker Linux engine and Docker CLI credentials. Missing Git can be installed through the wizard with explicit approval. WSL status is checked by process exit code rather than decoding locale-dependent output. A saved policy with a failed image smoke is clearly marked **prepared, unverified**, never falsely presented as completely ready. Install WSL, Install Docker and Repair Docker PATH require explicit confirmation. Installer actions use Windows Package Manager or the Windows WSL command; Windows may ask for elevation, license acceptance or a reboot, which Gateway never bypasses or forces. No Docker factory reset, credential deletion, WSL distribution removal or unapproved network exposure is performed. If the official Docker credential helper exists but is missing from PATH, the optional repair adds only that installed directory to the *user* PATH; if it is missing entirely, the user is directed to the official Docker repair installer.

**Prepare & verify** uses the existing revision-locked Repository Validation setup: prepare/refresh the approved bare mirror and immutable Linux image, retain existing custom mappings, save the host-approved validation policy, check the prepared resources, and run a disposable offline Python 3.12 image smoke test. The UI is asynchronous and cancellable; reopening it after a reboot reprobes actual machine state. Docker CLI credential warnings remain separate from the Gateway validator's isolated local/default Docker context. A successful smoke test is **not** a full Android build, source behavior test, GitHub mutation or merge permission.

### Repository Validation tab

Open **Gateway Settings → GitHub → Repository Validation**. Connection and
Repository Validation are separate tabs. No external policy file, hand-written
JSON file, Docker digest lookup or terminal command is required for the normal
setup path.

The normal form shows **Enable repository validation**, the repository and the
validation profile. The default repository is
`tailscale-signin/gpt_mobile_ai-improved`; URLs and uppercase GitHub names are
normalized automatically. Its default mirror retains the existing
`data/mirrors/GPT_Mobile_AI-improved.git` location. Other repositories use
`data/mirrors/<owner>/<repository>.git`. Existing selected locations are retained.
In **Advanced validation settings**, Browse can select an existing bare mirror or
a new destination. A working checkout, a mirror of another repository, filesystem
links and folders with unrelated contents are not overwritten.

**Prepare & save** confirms the local host's approval, then checks installed Git
and Docker Desktop, creates or refreshes the mirror, prepares the official
`docker.io/library/python:3.12-slim-bookworm` image by default and resolves its actual registry
SHA-256 digest. It saves the approved commands and settings internally. Git and
Docker Desktop with Linux containers are prerequisites; missing prerequisites get
a specific explanation. Downloads need connectivity. Existing approved digests
remain pinned until **Refresh image pin** or a different image is selected. Setup
and validation address the same local/default Docker daemon, not a saved remote
context. Private GitHub access uses the PC-selected credential only in the Git
child environment, never in a URL, saved policy or displayed subprocess output.

**Save policy** applies an already prepared contract without downloading again.
The enable choice is explicit: preparing and saving while disabled retains a
draft without enabling execution. **Check setup** verifies the existing mirror and pinned local image without
fetching, pulling, saving or executing repository commands. Setup runs off the
UI thread; Cancel and closing the settings window stop its pending subprocess
work. A failure or cancellation observed before the atomic selector replacement
leaves the previous selection in place; a concurrent edit is reported as a
conflict. Once that replacement commits, the completed save is reported as saved
even if cancellation arrives afterward. Successfully fetched mirrors or images
can remain cached after cancellation.

Recommended source checks need only the standard library in that Python image:
Python syntax, diagnostic-schema consistency and checked-in CI policy. The
600-second per-command timeout is editable. These checks are **not** full Android
builds, Gateway behavior tests, Windows qualification or permission to merge.
Per-task implementation admission still requires explicit acceptance requirements
and genuine behavior-test mappings. The default includes the repository's exact
`Required verification` and `Analyze (java-kotlin)` CI names, but does not invent
acceptance cases or claim those remote checks ran.

Advanced validation settings expose the image source, custom argument-array
commands, exact CI check names, behavior acceptance mappings, parallel groups,
objective templates and draft-repair requirements. Existing mappings and unknown
host policy fields are preserved on migration; a generic repository must use its
own Custom commands. No model-selected shell command is run on the host. Custom
images must already provide their commands' offline dependencies; registry
credentials are not inherited by the isolated validation Docker client.

Existing external selections remain readable until Save policy or Prepare & save
migrates their entries into managed internal snapshots. The original file is not
changed. Internal immutable snapshots under `data/validation-policies/` and the
schema-1 selector under `data/validation-settings.json` survive updates and both
launcher stages. A valid first-generation managed policy remains selected until
an explicit selection is saved; it cannot override an explicit external choice
or disable. A failed save preserves the previous active policy, and another
window's changes produce a conflict instead of being overwritten. **Reload**
explicitly discards local edits and reads the current settings. Active jobs retain
their original policy approval; keep prior files needed by saved work or rollback.
**Restart** applies the new selection through the existing guarded restart flow;
setup never cancels engineering jobs. Refresh the mirror with Prepare & save when
newer source commits are needed.

Clear **Enable repository validation** and save, or choose **Disable validation…**
in Advanced, to disable the selection without deleting mirrors, images, old
snapshots or the original external policy. A corrupt saved selection stays visible
and never silently inherits a different policy. The old Advanced-tab file picker
is no longer required.

## General, Advanced and Add-ons

Open Settings from the dashboard. General, Add-ons and GitHub are available there; select Show advanced settings for worker limits, batch controls and benchmark setup. Repository validation lives in its own GitHub subtab. General exposes the backend, listener address, hostnames, startup/update preferences and explicit firewall actions. Advanced exposes worker/chat limits and shutdown grace. Add-ons shows the enabled services in the selected MCP JSON configuration. Saving add-ons preserves commands, arguments and credentials, and refuses a save if the configuration changed externally. Save the selected file path in settings and restart when work finishes to apply it. The gateway service allowlist remains authoritative.

The token graph uses provider completion usage or llama.cpp timing receipts, including reasoning when counted by the backend. Missing measurements remain unavailable and partial coverage is disclosed. Counters start with this update, persist in `data/usage.sqlite3`, and exclude cached deliveries. These are measurements for this installation, without inferred per-phone attribution. Old logs are not estimated. A single background observer delivers both usage and activity without blocking generation; stale data is visibly marked.


### Export gateway debug logs

Open **Settings → Advanced**, enable **Debug mode**, save and restart after active work finishes. The gateway keeps at most **5,000** sanitized diagnostic lines in `logs/gateway-debug.jsonl`; **Export debug log…** writes the retained history to a file you choose. Enable Debug mode in Android as well to include the selected profile/model digests, app build, Android SDK and network revision. When local log tracking is enabled, the phone records the gateway acknowledgement and runtime instance ID. Temporary conversations suppress client diagnostic metadata. Prompts, response bodies, code, credentials and device identifiers are excluded. Disabling the option stops new records; saved diagnostic history remains available for export. This setting does not change the selected model, backend or transport permissions.

### Benchmarks

In the dashboard, open **Benchmarks**, select an advertised model and choose a suite. **Quick check** uses five tool-free fixtures. **Batch comparison** measures matched serial and concurrent HTTP requests, with warm-up excluded; it does not establish native inference batching. **Repository coding** evaluates 12 repair problems and four integration capstones in disposable, isolated Python repositories. Deep mode runs 100 trials across those 16 problems, including repeated seeded variants. It requires a locally approved, digest-pinned sandbox image and does not modify a live GitHub repository. Choose **Setup coding sandbox…** from Benchmarks, or **Settings → Show advanced settings → Advanced → Benchmark settings…**. **Prepare sandbox** explicitly downloads the official Python image, verifies its registry digest and restricted offline startup, and saves the pin. **Check setup (offline)** verifies an existing image without downloads. Missing Docker or an image is reported before a coding run is submitted. Quick check and batch comparison do not need Docker. The Windows installer offers an optional **Coding Benchmark Setup** shortcut after installation; installing Gateway does not silently install Docker Desktop.

Overview, Breakdown, Evidence and History provide charts, criterion scores, solved counts, first-submission and repair results, and saved comparisons. Latency views show time to first text, total time, stream gaps and provider-reported throughput where available. The coding score uses declared functional, regression, testing, diagnosis, design, workflow, recovery, scope and evidence criteria; missing required grading evidence leaves it incomplete. Results describe the tested fixtures, with unavailable measurements shown explicitly. See [Gateway 17 contracts](../V17_DESKTOP.md) for the complete benchmark scope and recovery rules.

## Headless startup on Windows

The installed launcher accepts `-Headless` and suppresses tray windows, update pop-ups and startup dialogs. It still serves the dashboard and records status and recovery diagnostics. For startup before you sign in, use Windows Task Scheduler to run the same launcher under the same Windows account that configured Gateway and saved its GitHub credential. Select **Run whether user is logged on or not**, leave **Do not store password** and **Run with highest privileges** unchecked, and enter the account password only in Windows Task Scheduler.

Use an **At startup** trigger and set the program to Windows PowerShell. Set the arguments to `-NoProfile -File "<installed Gateway folder>\Start-Gateway.ps1" -Headless`. Disable the task's execution time limit, allow Task Scheduler to restart failed launches, and select **Do not start a new instance**. Turn off Gateway's **Start when I sign in** setting when using the scheduled task. Use Gateway's normal Stop or update controls so active work can checkpoint. Gateway records startup failures in its installed logs and status when the supervisor can initialize.

## Continuous engineering qualification

Generate a ten-case manifest with `python gateway/qualify_engineering.py --continuous-template OWNER/TEST-REPO --output continuous.json`. Add the saved Gateway job ID for each exercised scenario. In the desktop Benchmarks view, open the qualification manifest to read and export the results. The CLI can also read the manifest with `--root`, `--manifest`, and `--output`.

Each scenario uses an authorized job in the designated test repository. The runner reports missing jobs as unexecuted and leaves cases unverified when durable evidence is incomplete. Induce outages and throttling in the designated test environment. Windows restart, phone disconnect, provider isolation, and concurrent publication require verified behavior evidence on the corresponding job. Merge still requires normal exact-head approval.

## Chat, saved messages and benchmarks

The dashboard now has Overview, Chat, Saved, Jobs and Benchmarks. New prompt opens the selected-model composer; Ctrl+Enter sends. Saved offers history search, bookmarks, favorites and archiving. Advanced setup is optional. See [Gateway 17 contracts](../V17_DESKTOP.md) for conversation identity, benchmark scope, safe recovery and upgrade behavior.

## Manage the Gateway from Android

Open **Android controls** in the Windows dashboard. Select the PC’s private IPv4 interface, a separate HTTPS port (8443 by default), and an Owner or Viewer grant. Create an invitation and scan it on the intended phone. You can also paste the invitation under **Settings → Windows Gateways**. Compare the displayed certificate fingerprints. Select the waiting phone on Windows, choose **Confirm device**, and compare its 12-character code before granting access. Each invitation expires after five minutes and can pair one device. The optional Windows firewall button allows only the selected port and interface on a Private network for the local subnet.

In GitHub settings select the paired PC as the engineering Gateway for the desired inference profile. On first binding the app can apply that profile’s Engineering preference to the PC. The phone and Windows both show the per-device job admission state. Turning Engineering off leaves already accepted jobs in their original journal. A viewer can inspect settings. Shared-LAN traffic and ordinary inference credentials have no owner privileges.

Owners can change worker and chat limits, select approved backends and validation profiles, choose a permitted batch mode, and manage repository maps, startup, update policy and diagnostic recording. Windows approves new backend origins and validation profiles. The app reports desired and active values separately and looks up the original command after a lost response. A backend must return a usable model catalogue before activation. Requested Windows actions appear for local review under Android controls. Revoke individual devices there, or pause phone access for the installation.

The Windows workspace uses the installer’s WPF theme. Open a section to inspect the runtime, then open the associated chat, job, benchmark or settings editor. **Diagnostics** previews the fields included in its text export. Closing the workspace leaves the supervisor and jobs running.

The Windows account that runs the Gateway protects its phone-pairing authority and HTTPS private keys with DPAPI. Upgrading an existing installation preserves its certificate fingerprints and paired devices.
