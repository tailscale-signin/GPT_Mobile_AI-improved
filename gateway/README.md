# Local gateway 17.0.0

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


## Readiness, diagnostics and live status

`GET /gateway/ready?model=<selected-model>` (also under `/v1`) uses the normal configured transport identity. Its versioned response separates a reachable connection, an available selected model, generation readiness, and administrative metadata access. A model catalog returning HTTP 200 does not establish inference readiness; restricted `/health` or `/props` responses remain distinct from a failed model. Readiness never starts a model or sends a completion. It caches each exact backend/model probe for three seconds and bounds network waits. Responses include a process instance ID so Android can discard stale capability results after a gateway restart.

The app's Status tab can read `GET /gateway/dashboard` or `/v1/gateway/dashboard` using the same connection. This returns `schema: "gptmobile.gateway.dashboard.v1"`, `schemaVersion: 1`, measured usage, worker load, observed socket devices, recent activity, and authorized job history. Device/activity/history are scoped to the admitted identity; aggregate provider-reported usage and worker load are explicitly scoped to this installation. `availability` flags and nullable `openJobs` distinguish missing measurements. The open-job count comes from the active inventory, separately from the bounded history rows; inventories above the bounded inspection limit remain unmeasured. Status polling does not create fake connected-device activity. The management dashboard remains restricted to the desktop supervisor.

For an exportable local diagnostic history, enable **Settings → Advanced → Debug mode**, save, and restart when work finishes. **Export debug log** defaults to a ZIP bundle containing transport records, retained diagnostic events and a build/coverage manifest. JSONL remains available as a transport-only option. Manual installations can set `GATEWAY_DEBUG_MODE=true`; `GATEWAY_DEBUG_LOG` optionally chooses its path. The default is `gateway-debug.jsonl` beside the job database, and the desktop uses its managed `logs/` directory. Both transport memory and disk retain at most 5,000 lines. Logging uses a coalesced background writer; disk errors never replay or fail a model request. See [DIAGNOSTICS.md](DIAGNOSTICS.md) for backend failure codes, exact-model checks, timing and export coverage.

With Android Debug mode enabled outside a temporary conversation, the app sends a negotiated metadata header containing the Android SDK version, app version code, network revision, request role, and digests of the selected profile/model. The gateway acknowledges accepted metadata and sends its instance/request identity back; the phone records the acknowledgement when local log tracking is enabled. Physical dispatch rechecks the Debug and temporary-conversation gates, including for requests prepared earlier. The exported gateway log contains status codes, timings and byte counts, including restricted endpoint responses. It excludes prompt/response bodies, source text, API credentials, device identifiers and raw profile/model names. The app's full log is never uploaded. Turning gateway Debug mode off stops new records and retains the existing bounded history for export.

Capability discovery distinguishes a definite unsupported endpoint (HTTP 404/501) from a timeout, authentication failure or malformed response. A failed gateway-control check stops before inference; required tool-isolation controls are never silently removed from a known gateway. Direct inference routes retain harmless tool-isolation headers while optional gateway performance/debug headers remain negotiated. Adaptive sampling repairs do not replay a dispatched request that may already have executed gateway-local tools.

## Saved conversations and gateway jobs

Gateway 16 exposes a scoped Jobs catalog and a browser of conversations stored on the gateway. Android can reopen the original user-facing transcript and reconnect to accepted work after its process closes. Ordinary generations and engineering jobs retain their existing executors, selected model and explicit stop/approval rules. See [GATEWAY_CONVERSATIONS.md](GATEWAY_CONVERSATIONS.md) for the admission receipts, API contract, private-chat behavior, queue/restart boundaries and regression coverage.
