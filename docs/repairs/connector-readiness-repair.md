# Connector and gateway recovery

This repair separates protocol failures, local authorization rejection, transport failures, and unmeasured behavior. Successful discovery establishes metadata availability; a successful capability read does not establish selected-model readiness or tool execution.

## MCP connection repair

| Observed response | Retained classification | Concrete repair |
| --- | --- | --- |
| HTTP 200 with missing, empty, or malformed `supportedVersions` | `MCP_DISCOVERY_SCHEMA_INVALID`, with the exact version-list validation rule | Update the server or bridge to return a nonempty array of protocol revisions it actually implements. A modern discovery result must also provide its required capability metadata. Reconnect after repairing the response and verify `tools/list`. |
| HTTP 404 with JSON-RPC `-32601` | `MCP_DISCOVERY_CONFIGURATION`, `DISCOVERY_METHOD_SUPPORTED_REQUIRED` | The endpoint answered JSON-RPC and rejected `server/discover`. Verify the bridge's implemented protocol and repair negotiation or choose its documented MCP endpoint. This response does not establish that the URL path is absent. |
| HTTP 404 without a recognized MCP method response | `MCP_DISCOVERY_CONFIGURATION`, `ENDPOINT_ROUTE_REQUIRED` | Check the configured path and the process serving it. The implemented protocol is still unknown. |
| Supported legacy versions advertised through valid negotiation, or a supported non-modern compatibility response | Existing SDK initialization path | Complete `initialize` and `tools/list`; neither a successful HTTP status nor a legacy selection alone establishes a usable catalogue. |
| Local `CREDENTIAL_REAUTHORIZATION_REQUIRED` | Local validation, `NOT_DISPATCHED`, no invented HTTP status | Open the exact saved connection, sign in again, and reconnect. An unchanged credential cannot be repaired by repeating discovery. |
| Temporary DNS or connection failure | Transport failure and existing bounded recovery gate | Retain the saved catalogue, keep unrelated connections usable, and retry according to the network recovery/backoff state. |

The current MCP Streamable HTTP specification identifies HTTP 404 plus JSON-RPC `-32601` as a modern unknown-method response. Its compatibility guidance prohibits silently interpreting recognized modern errors as evidence for a legacy protocol. Likewise, a malformed HTTP 200 discovery result is a server/bridge defect, not permission to guess another protocol. See the [official HTTP error responses and compatibility guidance](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).

The app preserves its tested legacy initialization path. It does not have a persisted user-selected protocol override. An override would require a connection schema migration, backup and identity handling, and a reviewed settings path. It must not be hidden in the endpoint, credential, or authorization policy fields. The services captured on ports 8101 and 8102 run outside this Android repository; their deployment and response configuration must be repaired at that service before they can be verified live.

Discovery logs now identify the requesting trigger (automatic resolution, automatic health check, user reconnect, audit refresh, or OAuth refresh), a safe connection hash, the saved configuration revision, and the gate/cache/acquisition decision. Response diagnostics retain only allowlisted field names, version-list shape/count, bounded date-formatted protocol revisions, and the count of unrecognized fields. Server instructions, arbitrary keys, endpoint credentials, and rejected values are omitted.

## Gateway observations and retry identity

Capability and selected-model readiness checks now complete independently. A timeout from `/gateway/ready` no longer discards a successful capability response. The retained state includes both check timestamps and failure codes, the selected failed phase, the last reachable time, and the observed instance identity. Partial metadata never grants `isOnline` or generation readiness.

The availability probe is metadata-only. It does not cancel remote jobs or rewrite the pending queue. Existing scheduling retains bounded backoff; the next successful exact-model readiness observation can restore online status. A fresh metadata observation is still subject to route, model, network generation, instance, and freshness checks.

Each bounded local metadata read receives an opaque logical ID. Its initial attempt and single retry retain distinct physical spans but share `operation_id` and `incident_id`; `retry_attempt` and `network_epoch` identify their context. Socket aborts remain failed transport attempts. A successful parsed retry emits `operation_reconciliation` with `error_code=NONE`, `response_body_completed=true`, and the same incident ID. The app log additionally emits `METADATA_READ_OUTCOME` with `outcome=COMPLETED`. This establishes read recovery, not successful model generation. Two failed attempts remain visible without representing two unrelated incidents.

## Audit qualification

`AppAuditHarnessCatalog.kt` maps every current source-inventory area to concrete existing candidate suites. The exported worklist retains exact-build, fixture, permission, device, service identity, and residual-source requirements. The mapping is not a coverage report and does not promote any audit result or measured score. The repository check in `scripts/tests/test_app_audit_harness_catalog.py` rejects missing paths and unmapped inventory areas.

Optional connections are never enabled automatically. Missing targets receive a configure-target action; disabled targets receive a review-permission action; local authorization, discovery schema, unsupported method, and missing route failures receive distinct repairs. Device measurements, real external service repair, and named authorized tool fixtures still need execution in their applicable environment.
