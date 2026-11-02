# Local gateway 15.0.0

`gateway.py` is the stable launcher for Gateway 15 (the `v14/` module name is retained for compatibility). Install the complete reviewed package, including `manifest-v14.json`, the `v14/` package, the retained v13 runtime and transport modules, `gateway_multisearch.py`, `gateway_security.py`, and the pinned requirements. See [V14_UPGRADE.md](V14_UPGRADE.md) for installation, validation, upgrades and rollback. `gateway_v13.py` is a retained compatibility runtime, not the current launcher.

Use Python 3.12 or newer and an isolated environment:

```powershell
cd gateway
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests
```

The server defaults to `127.0.0.1:8090`. This branch proposes independent network binding (`127.0.0.1`, a selected interface, or `0.0.0.0`) with authenticated remote access. The current runtime still rejects non-loopback binding. Until runtime authorization, installer settings, firewall controls, and Android connection validation are implemented and tested, retain loopback-only access; do not expose key-free access over a LAN or public interface. Remote administration remains prohibited.

For Windows, use the bundled installer and [desktop setup and update guide](desktop/README.md). It exposes backend, performance, Tailscale identity, startup and update controls and preserves user data across updates. For a manual Python installation, follow the trusted-proxy configuration in [V14_UPGRADE.md](V14_UPGRADE.md). Legacy paired bearer access is opt-in compatibility behavior, not the default.


Legacy opt-in bearer setup (`GATEWAY_LEGACY_BEARER=true`): `python gateway_security.py issue --name "Phone"` prints a device ID and token once. Put the token in the profile API-key field. Do not place it in a URL or log. `python gateway_security.py list` lists devices without credentials. `python gateway_security.py revoke DEVICE_ID` invalidates subsequent requests immediately; an already authorized stream may finish.

Jobs and request coalescing are device-scoped. Historical jobs without an owner are not assigned automatically. Pairing and serving must use the same `GATEWAY_AUTH_DB` when overriding it.

Environment options: `GATEWAY_HOST`, `GATEWAY_PORT`, `GATEWAY_AUTH_DB`, `GATEWAY_JOB_DB`, `LLAMA_BASE` (default localhost:8080), `MEMORY_BASE` (default localhost:8765), and `MCP_CONFIG_PATH`. The gateway token is stripped from backend proxy requests. Configure backend authentication independently if needed.

Bounded context, explicit failover, output caps, cancellation, durable recovery, progress events and delegation diagnostics remain available. This server is optional; Android's local memory has no gateway dependency.

To update dependencies, edit `requirements.in`, resolve in a clean environment, test, and regenerate the pinned `requirements.txt`. CI imports the real application and tests the authenticated HTTP boundary with backend/MCP startup stubbed. Pure contract tests alone do not validate installation.

### Independent delegation retries and reviewer isolation

Install the complete Gateway 15 package on the server, validate its manifest, then restart using `gateway.py`. The historical `gateway_v12.1.py` launcher still loads v12. Each new delegated generation sends an `X-Gateway-Attempt-ID`; reconnects within that attempt retain it, while retries and output repairs receive new IDs. Singleflight now includes the attempt ID, completion budget, reasoning configuration, and tool policy, so a failed 768-token completion cannot be replayed as the next inference or a larger repair.

The app sends worker isolation headers even when reasoning is enabled. Reviewer requests have no tool catalog and explicitly disable gateway-local tools. Page crawling is app-owned; reviewers assess completed delegate evidence only. External Perplexity authentication failures and MCP initialization 404s still require correcting the configured key or endpoint; the app stops automatic repeated attempts and reports those configuration errors.

GitHub-focused routing, bounded immutable reads and new diagnostics are documented in [GITHUB_UPGRADE_14_3.md](GITHUB_UPGRADE_14_3.md).

App-launched engineering jobs that continue after Android closes, reconnectable updates, adaptive read batches, CI repair and exact-commit merge approval are documented in [INTELLIGENT_ENGINEERING.md](INTELLIGENT_ENGINEERING.md).
