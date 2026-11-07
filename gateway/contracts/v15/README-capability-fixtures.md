# Complete capability response fixtures

The current v15 capability route extends the v13 runtime response. Every entry
in `features` is a JSON boolean. Descriptive icon metadata is already exposed as
`ui_contract.gateway_tool_icon`; the disconnect grace interval is numeric
`singleflight.disconnect_grace_seconds`. Neither value is a feature flag.

## Captures and provenance

`gateway-v15-capabilities-full.json` is the entire JSON body obtained from
`GET /v1/gateway/capabilities` on a real local gateway process after the boolean
feature contract correction. The response has 27 top-level fields and 92 feature
flags in this revision.

`gateway-v15-capabilities-legacy-mixed.json` is the entire pre-fix response
captured from source `56de2367158efdee3be953bab9f78a813c24c8b6`, before modifying
the runtime. It has 26 top-level fields and 94 feature entries, including
`gateway_server_icon: "gateway_server"` and `disconnect_grace: 20`. Keep this
historical capture for clients that must tolerate already deployed gateways;
do not regenerate it from the fixed route or replace it with a small mock.

Both captures use a real Uvicorn process, the normal package validator,
application router, admission middleware and startup lifecycle. The fixture
configuration is explicitly loopback-only, with temporary journals, an empty MCP
inventory, disabled debug capture, no management token and a local simulated
model endpoint. Capability capture performs zero model inference requests.
The full bodies retain runtime facts such as `storageChecked: false`,
`storageReady: null`, disabled capabilities and supported protocol lists.

Only two nondeterministic identifiers are normalized:

- `build.sourceCommit` becomes forty zeroes after validating its SHA-1 shape.
- `instanceId` becomes thirty-two zeroes after validating its instance-ID shape.

No response field is omitted. All remaining values are actual route output.
The normalized identifiers are test sentinels, not build or transport evidence.
Private configuration and credentials are excluded by using isolated test
inputs, rather than by removing fields from a captured user response.

## Regeneration and drift checks

From the repository root, with the locked gateway dependencies installed and a
current validated source manifest, capture the corrected response with:

```bash
python gateway/tests/gateway_capability_fixture.py gateway/contracts/v15/gateway-v15-capabilities-full.json
```

Copy the captured file unchanged to `app/src/test/resources/` under the same
name. The canonical fixtures remain inside the gateway package so standalone
gateway contract tests do not depend on an Android source checkout.

`gateway/tests/test_gateway_capabilities.py` launches the actual gateway, checks
all six capability aliases, and compares the normalized complete response with
the canonical corrected fixture. `scripts/tests/test_gateway_capability_fixtures.py`
requires the Android copies to be byte-identical to the canonical fixtures.
Android decoding and negotiation tests consume those resource copies.
