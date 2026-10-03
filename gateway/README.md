# Local gateway 12.2

`gateway_v12.py` is the canonical server. `gateway_v12.1.py` is a compatibility launcher; install it alongside the canonical script and `gateway_security.py`. The v10 file is retained for historical compatibility.

Use Python 3.12 or newer and an isolated environment:

```powershell
cd gateway
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests
```

The server defaults to `127.0.0.1:8090`. Every model, job, diagnostics and control endpoint requires a paired-device bearer token. Credentials are stored as hashes. Pairing codes expire after five minutes and can be redeemed once.

For phone access, explicitly select the host's private interface. Prefer your authenticated private network (for example Tailscale), or configured HTTPS. Android accepts HTTP pairing only for private destinations. Do not forward this port publicly.

```powershell
$env:GATEWAY_HOST="YOUR_PRIVATE_INTERFACE_IP"
python gateway_security.py pair --name "My gateway" --url "http://YOUR_PRIVATE_INTERFACE_IP:8090/v1" --model "YOUR_MODEL_ID"
python gateway_v12.py
```

Open the generated `gptmobile://pair` link on Android. Preview the destination, fetch and save the configuration. The app puts the credential in its vault. The imported profile starts disabled for review.

Manual setup: `python gateway_security.py issue --name "Phone"` prints a device ID and token once. Put the token in the profile API-key field. Do not place it in a URL or log. `python gateway_security.py list` lists devices without credentials. `python gateway_security.py revoke DEVICE_ID` invalidates subsequent requests immediately; an already authorized stream may finish.

Jobs and request coalescing are device-scoped. Historical jobs without an owner are not assigned automatically. Pairing and serving must use the same `GATEWAY_AUTH_DB` when overriding it.

Environment options: `GATEWAY_HOST`, `GATEWAY_PORT`, `GATEWAY_AUTH_DB`, `GATEWAY_JOB_DB`, `LLAMA_BASE` (default localhost:8080), `MEMORY_BASE` (default localhost:8765), and `MCP_CONFIG_PATH`. The gateway token is stripped from backend proxy requests. Configure backend authentication independently if needed.

Bounded context, explicit failover, output caps, cancellation, durable recovery, progress events and delegation diagnostics remain available. This server is optional; Android's local memory has no gateway dependency.

To update dependencies, edit `requirements.in`, resolve in a clean environment, test, and regenerate the pinned `requirements.txt`. CI imports the real application and tests the authenticated HTTP boundary with backend/MCP startup stubbed. Pure contract tests alone do not validate installation.
