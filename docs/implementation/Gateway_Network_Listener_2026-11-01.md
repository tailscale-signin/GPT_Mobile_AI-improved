# Gateway key-free network listener

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base: main at 83afa7f113b29ad050c21d5bde5ea55da6dae602
Working branch: feat/gateway-network-listener-no-tailscale-2026-11-01
Starting branch head: ff34d32a52f7ffecdb8a678b4d9f60a66ab853b5

## Implemented behavior

- Desktop listener selects localhost, a specific IPv4/IPv6 interface, or all interfaces (`0.0.0.0` / `::`). Port and address persist across restarts. Supervisor maintenance and port-conflict checks use the selected interface.
- Network mode is deliberately key-free (`open_network`), with `apiKeyRequired=false`. Android connects by PC IP or hostname with an empty API key. Incoming identity/forwarding/API-key headers never choose a principal or grant administrative privileges.
- Network clients share `network:shared` job ownership; historical local/private jobs remain stored under their original principal. Network engineering requires the visible engineering option. Maintenance still uses an independent, process-local supervisor secret and rejects remote peers; users do not configure it.
- Installer and Settings replace the Tailscale identity controls with listen-address controls. Legacy settings retain backend, port, concurrency, update policy, startup preference, MCP path, and old identity fields for recovery, but migrate to localhost. Manual legacy proxy mode remains supported on loopback only.
- Windows Firewall is an explicit action, never part of startup or automatic updates. Its installation-specific rule allows the saved TCP port only on Private networks from LocalSubnet. Settings can reapply/remove it; uninstall removes the recorded rule through the current runtime. Administrator approval is requested by Windows only when this action is selected.
- Android connection forms explain direct addressing and the empty API-key field. Connection Doctor recognizes open-network capability metadata; unreachable-server guidance references listener/port/firewall rather than Tailscale.
- Exact destination IPs and configured hostname aliases are admitted. Browser origins, arbitrary DNS hosts and remote administrative/configuration/debug surfaces remain restricted.

## Validation performed locally

- `python3 gateway/check_gateway.py`: 304 Python tests discovered; 303 passed, one physical-LAN test skipped because this host exposes no bindable LAN IPv4 interface. Seven JavaScript browser tests passed.
- The socket integration test started the real gateway on `0.0.0.0`, connected without Authorization, restarted from persisted settings, and confirmed the shared-network capability and manager-only maintenance boundary.
- `bash scripts/validate-local.sh fast`: passed, including 285 host regression tests, dependency/CI/secret policy checks, Android regex checks, resources and diff whitespace.
- ktlint on all changed Kotlin files: passed.
- Targeted Android Gradle tests were attempted but could not start: Gradle 9.8.0 download failed with `Network is unreachable`. This host has JDK 17 and no Android SDK 37, so it cannot establish Android build success.

## Target-environment gates

The Windows workflow runs the network tests, builds the actual Inno Setup/PyInstaller package, then exercises the frozen key-free listener across restarts, firewall creation/scope/removal, repair install, rollback and uninstall. These Windows checks and the Android build must be verified on the resulting PR. Physical Android-to-Windows interoperability, interactive UAC/installer accessibility and corporate firewall behavior still require target-device acceptance. No installer binary or production release is claimed by this source implementation.

Anyone who can reach the open listener can use its enabled models/tools and shared network jobs. It is intended for a trusted LAN/private VPN. Public access requires a separately authenticated HTTPS proxy; TLS alone does not restrict access.


## Review repair checkpoint

Review of draft head `20c279acbfe6adf9ace5b78b437cd66fcb4d80a6` found and repaired two desktop lifecycle defects:

- Firewall removal no longer requires a readable settings file, so uninstall or recovery can remove the installation-specific rule after settings corruption. A regression test covers the settings-independent removal command.
- Repeated Settings saves retain the most recently persisted values instead of rebuilding later edits from the window's original snapshot.
- Gateway package-integrity hashes were refreshed after these scoped source and test repairs.

Fresh exact-revision CI is required for the resulting checkpoint. Older, cancelled, failed, pending, skipped-required or superseded runs do not count.
