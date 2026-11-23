# Gateway status refresh regression repair

## Intent and source

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`, parent PR 2254.
- Base branch: `origin/review-pr-2254`.
- Base SHA: `85389f14ab1159d7d9d1c2c5b8ff6dfb84102e31`.
- Working branch: `work/review-ci-gateway-viewmodel-2026-11-22`.
- Scope: Gateway status ViewModel test fixture and selected-connection refresh assertions; production changes only if reproduction identifies a production defect.

Full PR Validation run `45059996467`, unit job `137323925528`, on source `50131133f01caf05f4467815b4d677b746e8b38f` reported a `MockKException` in `GitHubGatewayStatusViewModelTest.selectingAnotherGatewayCancelsTheOldRequestAndClearsItsMeasurements`. The fixture registered only `GatewayDashboardClient.refresh(null)`, while the explicit UI refresh correctly supplies the selected gateway's request configuration. Both the relevant production method and test are unchanged at this task's base.

The fix must preserve explicit service rechecking, cancellation of the old request, removal of old measurements, and the separation between inference and engineering connections. It must verify the selected refresh configuration, including behavior after connection changes and removal.

## Resolution

No production defect was found. The fixture now registers exact request configurations for the selected and alternate gateway, plus an explicit null configuration for cache-only refresh when no service is selected. Editing a connection registers its exact updated configuration in that scenario. No unrestricted refresh mock was introduced.

The existing three behavior tests additionally verify that:

- Without a selected gateway, refresh receives null and never rechecks a configured connection.
- An explicit refresh receives the selected gateway's address, token, profile UID and owner-control setting. After switching, refresh receives the new gateway's configuration and does not refresh the old gateway again or target the inference profile.
- After editing a gateway, refresh uses the edited configuration. Removing the selected gateway permits only cache-only refresh, preserves the prior edited-route refresh count and never switches to the remaining alternate gateway.

The original old-request cancellation, cleared measurements, reconnecting/live state, and absence of inference polling assertions remain in place.

## Validation

- Before the change, the focused host harness reproduced exactly one failure among the three tests: the same `MockKException` for the selected configuration at `GitHubGatewayStatusViewModel.refresh`.
- After the change, all three tests pass with the real production ViewModel, dashboard monitor, Gateway client class, engineering connection selection and local destination policy. AndroidX Lifecycle 2.11.0, MockK 1.14.11, coroutines 1.11.0 and Ktor 3.6.0 are the actual libraries. AndroidX artifacts were verified against this base's existing Gradle dependency checksums.
- The client and diagnostic source remain mocked at the same seams as the repository test. Unrelated Android DI, persistence/settings DTOs and diagnostic-export boundaries are narrow host fixtures. This does not substitute for Android, Hilt or device validation.
- `bash scripts/validate-local.sh fast`: passed all 608 host checks, metadata/inventory checks, 1,889 literal regex checks (90 dynamic patterns skipped, zero errors), 36 XML resources, icons and dependency provenance.
- ktlint 1.3.1 and `git diff --check`: passed.

The reproduction and passing logs are retained under the session's `gateway-viewmodel-host-tests` directory. Full exact-head Android CI remains required and is owned by the integration parent.
