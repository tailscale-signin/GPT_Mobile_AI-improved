# Local gateway 18.0.0

Optional [CUDA voice synthesis](VOICE-CUDA.md) supports the Android voice screen
through the existing Gateway connection. The published Windows package includes
a setup helper and a private GPU worker; CUDA is enabled only after a measured
warmup proves that the selected model executed GPU nodes.

Gateway 17 preserves the existing state schema, installation identity, accepted job IDs, conversations and explicit execution budgets. It adds native Chat and Saved views, searchable message bookmarks, a graphical durable benchmark workspace, and paired request-scheduling comparisons. See [Gateway 17 desktop and benchmark contracts](V17_DESKTOP.md) for evidence scope and setup. `/gateway/v17` and `/v1/gateway/v17` advertise the current contract; v14/v15/v16 aliases remain available.

For an existing v15 Windows installation, install the reviewed v16 package in the existing installation directory after accepted work reaches a safe stopping point. The already-installed v15 updater may filter out major 16; it cannot discover this change until its manager is upgraded. Keep the state directory. The managed trial backs it up, checks the new process and rolls back an unhealthy candidate; an unavailable model backend does not erase saved work. A source branch is not a published installer release.

`gateway.py` is the stable launcher for Gateway 17 (the `v14/` module name is retained for compatibility). Install the complete reviewed package, including `manifest-v14.json`, the `v14/` package, the retained v13 runtime and transport modules, `gateway_multisearch.py`, `gateway_security.py`, and the pinned requirements. See [V14_UPGRADE.md](V14_UPGRADE.md) for installation, validation, upgrades and rollback. `gateway_v13.py` is a retained compatibility runtime, not the current launcher.

Use Python 3.12 or newer and an isolated environment:

```powershell
cd gateway
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests
```

The server defaults to `127.0.0.1:8090`. Select a PC interface IP or `0.0.0.0` in Gateway Settings to open key-free network access. Android connects directly to `http://192.168.1.100:8090/v1` (replace the address with your PC IP or hostname); leave its API key empty. No Tailscale installation or identity setup is required. Anyone who can reach the listener can use its enabled models/tools and shared network jobs. Keep this mode on a trusted LAN or private VPN; public access needs a separately authenticated HTTPS proxy. HTTPS alone does not restrict who can connect.

For Windows, use the bundled installer and [desktop setup and update guide](desktop/README.md). The listener, backend, performance, optional LAN firewall rule, engineering scope, startup and updates are configurable. Existing installations retain their backend/settings and default to localhost; old Tailscale settings migrate to localhost and remain recorded for recovery. The runtime retains its legacy loopback proxy mode only for manual-install compatibility; it is not a requirement or a desktop setting.

For manual Python installations set `GATEWAY_HOST=0.0.0.0`, `GATEWAY_PORT=8090`, `GATEWAY_AUTH_MODE=open_network`, and `GATEWAY_ALLOWED_ORIGINS=[]`, then run `python gateway.py serve`. Exact destination IPs are accepted; to use a hostname, add its authority to the JSON `GATEWAY_ALLOWED_HOSTS` list, e.g. `["127.0.0.1:8090","my-pc:8090"]`. The Windows manager includes the PC hostname automatically and offers additional hostname aliases. Enable network engineering explicitly with `GATEWAY_REMOTE_ENGINEERING=true`. Admin/configuration/debug routes remain local-only; native Android access does not require browser origins or identity headers.

Open-network clients share the `network:shared` principal. Existing local or legacy private jobs are retained under their old owner and are not silently transferred to the network. Legacy optional bearer/proxy modes keep their existing ownership rules.

In network mode, opening the Gateway address (with or without `/v1`) in a browser shows a read-only connection page with a link to the liveness check. Use the Gateway app on the host PC for its desktop dashboard and settings. The proxied llama.cpp web UI is available in loopback mode.

Environment options: `GATEWAY_HOST`, `GATEWAY_PORT`, `GATEWAY_AUTH_DB`, `GATEWAY_JOB_DB`, `LLAMA_BASE` (default localhost:8080), `MEMORY_BASE` (default localhost:8765), and `MCP_CONFIG_PATH`. The gateway token is stripped from backend proxy requests. Configure backend authentication independently if needed.

Bounded context, explicit failover, output caps, cancellation, durable recovery, progress events and delegation diagnostics remain available. This server is optional; Android's local memory has no gateway dependency.

To update dependencies, edit `requirements.in`, resolve in a clean environment, test, and regenerate the pinned `requirements.txt`. CI imports the real application and tests the HTTP admission boundary with backend/MCP startup stubbed. Pure contract tests alone do not validate installation.

### Independent delegation retries and reviewer isolation

Install the complete Gateway 17 package on the server, validate its manifest, then restart using `gateway.py`. The historical `gateway_v12.1.py` launcher still loads v12. Each new delegated generation sends an `X-Gateway-Attempt-ID`; reconnects within that attempt retain it, while retries and output repairs receive new IDs. Singleflight now includes the attempt ID, completion budget, reasoning configuration, and tool policy, so a failed 768-token completion cannot be replayed as the next inference or a larger repair.

The app sends worker isolation headers even when reasoning is enabled. Reviewer requests have no tool catalog and explicitly disable gateway-local tools. Page crawling is app-owned; reviewers assess completed delegate evidence only. External Perplexity authentication failures and MCP initialization 404s still require correcting the configured key or endpoint; the app stops automatic repeated attempts and reports those configuration errors.

GitHub-focused routing, bounded immutable reads and new diagnostics are documented in [GITHUB_UPGRADE_14_3.md](GITHUB_UPGRADE_14_3.md).

App-launched engineering jobs that continue after Android closes, reconnectable updates, adaptive read batches, CI repair and exact-commit merge approval are documented in [INTELLIGENT_ENGINEERING.md](INTELLIGENT_ENGINEERING.md).

## Optional Copilot cloud task supervision

Copilot cloud tasks support both **Android direct** and **Gateway** supervision. Choose the Copilot execution owner in the app's GitHub profile. Android direct uses the selected app GitHub connection without a Gateway; GitHub runs an accepted cloud task independently, and Android reconnects to its saved receipt when the app opens again. Gateway uses this PC's GitHub connection and durable journal, submits the approved task once, and keeps observing it while Android is closed or disconnected. The inference model's location does not determine the Copilot execution owner. This REST integration does not require Copilot CLI or the Copilot SDK on the PC.

Copilot is off by default. Enable the app's Copilot setting and the current conversation's Copilot setting before submitting a task. For Gateway supervision, also open **Gateway Settings → GitHub → Connection**, select **Allow Gateway to supervise Copilot cloud tasks**, save settings, and restart when idle. If Android uses a LAN listener, the existing **Allow network devices to run GitHub engineering tasks** setting is also required. The host uses its existing saved GitHub credential; Android does not upload its token to the PC.

For a manual Python installation, set `GATEWAY_COPILOT_ENABLED=true` before starting `python gateway.py serve`, with durable jobs enabled and the normal host GitHub credential selected. `GATEWAY_ENGINEERING_GITHUB_SERVER` selects an existing host GitHub connection, or `GATEWAY_ENGINEERING_GITHUB_TOKEN` supplies a dedicated host credential when no connection is selected. The managed Windows installation reads its saved checkbox and does not inherit this consent from an environment variable.

The [GitHub Agent Tasks REST API](https://docs.github.com/en/rest/agent-tasks/agent-tasks) is a preview, using API version `2026-03-10`. Task creation currently requires a Copilot Business or Enterprise user and a fine-grained personal access token or GitHub App **user** access token with repository **Agent tasks: write** permission; observation requires **Agent tasks: read**. GitHub App installation tokens are not supported by this API. Base/head checks additionally need access to the selected repository's contents. A successful host connection check identifies the account; the task API determines that account's subscription and repository eligibility.

The authenticated endpoints below also have `/v1` aliases. Creation requires both `X-Gateway-Installation-ID` and `X-Gateway-Principal-ID`, explicit `execution_owner: "GATEWAY"`, both Copilot consent flags, a stable `request_id`, the verified numeric GitHub actor ID, and the selected base branch and exact commit. A selected work branch also requires its exact head commit.

| Endpoint | Purpose |
| --- | --- |
| `GET /gateway/engineering/copilot/capabilities` | Read host opt-in, installation/principal identity and configured GitHub actor. |
| `POST /gateway/engineering/copilot/tasks` | Durably accept an explicitly authorized cloud task. |
| `GET /gateway/engineering/copilot/tasks?request_id=…&repository=…` | Recover the original receipt after a lost Android response without repeating a creation request. |
| `GET /gateway/engineering/copilot/tasks/{gateway_job_id}` | Read the saved checkpoint scoped to the admitted owner. |

Receipts distinguish the local `cp_…` Gateway job ID from GitHub's upstream task ID. The desktop **GitHub jobs** view shows bounded task metadata. Existing tasks remain readable if Copilot is disabled; disabling delegation does not cancel an accepted GitHub task. A Gateway restart resumes known upstream tasks with GET requests. The installed credential identity stays pinned to the accepted receipt, so changing credentials pauses observation rather than silently transferring ownership.

The journal records the submission fence before sending POST. A lost response or crash after that fence becomes `RECONCILE_REQUIRED`; it is never automatically submitted again. Inspect GitHub's task history to establish the remote result. GitHub documents neither an idempotency key nor a task cancellation endpoint for this preview. Disconnecting Android, stopping polling or shutting down Gateway does not claim to cancel the remote worker.

Exact base/head commits are checked immediately before submission, but GitHub's creation API has no commit comparison precondition. Receipts therefore label this check `PREFLIGHT_ONLY`. A task marked `COMPLETED` is not proof of passing CI, the current pull-request head, or merge authorization. Existing exact-head review, verification and explicit merge controls remain separate.


## Readiness, diagnostics and live status

`GET /gateway/ready?model=<selected-model>` (also under `/v1`) uses the normal configured transport identity. Its versioned response separates a reachable connection, an available selected model, generation readiness, and administrative metadata access. A model catalog returning HTTP 200 does not establish inference readiness; restricted `/health` or `/props` responses remain distinct from a failed model. Readiness never starts a model or sends a completion. It caches each exact backend/model probe for three seconds and bounds network waits. Responses include a process instance ID so Android can discard stale capability results after a gateway restart.

The app's Status tab can read `GET /gateway/dashboard` or `/v1/gateway/dashboard` using the same connection. This returns `schema: "gptmobile.gateway.dashboard.v1"`, `schemaVersion: 1`, measured usage, worker load, observed socket devices, recent activity, and authorized job history. Device/activity/history are scoped to the admitted identity; aggregate provider-reported usage and worker load are explicitly scoped to this installation. `availability` flags and nullable `openJobs` distinguish missing measurements. The open-job count comes from the active inventory, separately from the bounded history rows; inventories above the bounded inspection limit remain unmeasured. Status polling does not create fake connected-device activity. The management dashboard remains restricted to the desktop supervisor.
The mobile status poll requests a 24-tool preview with `inventory_preview=true`. Opening the inventory requests `inventory_page_size=128` on the same dashboard endpoint and follows each `nextCursor` using `inventory_cursor`. Each page is scoped to the admitted caller and catalog revision. A 409 starts a fresh read so removed tools are discarded. Older clients can still request the original dashboard response.


For an exportable local diagnostic history, enable **Settings → Advanced → Debug mode**, save, and restart when work finishes. **Export debug log** defaults to a ZIP bundle containing transport records, retained diagnostic events and a build/coverage manifest. JSONL remains available as a transport-only option. Manual installations can set `GATEWAY_DEBUG_MODE=true`; `GATEWAY_DEBUG_LOG` optionally chooses its path. The default is `gateway-debug.jsonl` beside the job database, and the desktop uses its managed `logs/` directory. Both transport memory and disk retain at most 5,000 lines. Logging uses a coalesced background writer; disk errors never replay or fail a model request. See [DIAGNOSTICS.md](DIAGNOSTICS.md) for backend failure codes, exact-model checks, timing and export coverage.

With Android Debug mode enabled outside a temporary conversation, the app sends a negotiated metadata header containing the Android SDK version, app version code, network revision, request role, and digests of the selected profile/model. The gateway acknowledges accepted metadata and sends its instance/request identity back; the phone records the acknowledgement when local log tracking is enabled. Physical dispatch rechecks the Debug and temporary-conversation gates, including for requests prepared earlier. The exported gateway log contains status codes, timings and byte counts, including restricted endpoint responses. It excludes prompt/response bodies, source text, API credentials, device identifiers and raw profile/model names. The app's full log is never uploaded. Turning gateway Debug mode off stops new records and retains the existing bounded history for export.

Capability discovery distinguishes a definite unsupported endpoint (HTTP 404/501) from a timeout, authentication failure or malformed response. A failed gateway-control check stops before inference; required tool-isolation controls are never silently removed from a known gateway. Direct inference routes retain harmless tool-isolation headers while optional gateway performance/debug headers remain negotiated. Adaptive sampling repairs do not replay a dispatched request that may already have executed gateway-local tools.

## Saved conversations and gateway jobs

Gateway 16 exposes a scoped Jobs catalog and a browser of conversations stored on the gateway. Android can reopen the original user-facing transcript and reconnect to accepted work after its process closes. Ordinary generations and engineering jobs retain their existing executors, selected model and explicit stop/approval rules. See [GATEWAY_CONVERSATIONS.md](GATEWAY_CONVERSATIONS.md) for the admission receipts, API contract, private-chat behavior, queue/restart boundaries and regression coverage.

### Development workspace output

Open a saved engineering job in Android and select **Workspace → Tests**. Each retained APK or test report shows its path and SHA-256. Use **Save** to verify all its pages and copy it to a document you choose.

On the paired PC, the same retained file can be exported from the installed configuration directory. When several commands produced the same path, add `--revision`, `--patch` and `--command` from the job receipt.

```powershell
python export_workspace_output.py --root $GatewayConfigurationDirectory --job $EngineeringJobId --path app/build/outputs/apk/debug/app-debug.apk --output candidate.apk
```
