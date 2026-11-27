# Windows and Android Gateway controls

Open **Connect phone** on Windows. If the Gateway uses Tailscale, copy the displayed PC address or enter the PC’s Tailscale IP in **Gateway settings** on Android, then tap **Connect**. Both devices must use the same Tailscale account. The PC verifies the phone’s identity through its local Tailscale daemon. The default Gateway port is 8090.

On private Wi-Fi, enter the PC's IP on Android. When both devices already use the same GitHub.com personal access token, they verify a short-lived proof tied to the installation, TLS certificate and Android device key. The token stays in each device’s credential store. Rotating the token on the PC invalidates existing account-based Wi-Fi credentials. Devices without an owner identity can view the shared settings overview.

If the devices use different credentials, **Other connection options** on Windows includes **Copy secure phone link**. Paste the full link into **Gateway settings** on Android and tap **Connect** within five minutes. If the PC has several network interfaces, **Advanced network choice** lets you select the one your phone can reach. Android verifies the certificate fingerprint in the link and signs its request over TLS. Future connections use the saved certificate and device key, including after the phone’s IP changes. Windows can revoke the device under **Settings → Devices**.

Engineering starts automatically for each authorized owner. Upgrades enable older owner connections that still have their original default. The PC can pause new jobs for an owner during maintenance. An explicit admission pause survives a restart. The PC’s model, GitHub connection and approved repositories determine which jobs can run. Existing owner and viewer grants retain their permissions.

## What a setting means

| Setting | Scope and activation |
| --- | --- |
| Engineering | Admission of new saved jobs for the selected owner. Turning it off retains accepted jobs and their original receipts. Missing host prerequisites remain visible as setup required. |
| Workers and active chats | Installation capacity; changes wait until existing work reaches the supervisor's safe boundary. |
| Batch mode and local validation workers | Future work uses the changed preference after the safe boundary. Accepted work retains its bound policy. |
| Repository validation profile | Windows first approves an immutable snapshot of an existing validated policy. A phone with an explicit grant can select an approved profile within its repository scope; activation waits for the safe boundary and rejects a policy changed on the host. |
| Model backend and model | A new route is probed before activation. Failure preserves the last effective route. A phone cannot approve a new backend origin. |
| Network and shutdown | Direct and private-address owners can edit the saved listener IP, port, hostnames and shutdown grace. Changes to the listener activate after a PC restart. The app also shows the selected MCP configuration file. |
| Public repository maps | Installation preference, applied through the runtime's guarded activation path. |
| Start at sign-in, updates and diagnostics | Installation preferences. OS operations that cannot be completed under the host account remain pending for local action. |

Each snapshot distinguishes **desired**, **effective** and **pending** values.
A successful settings command means the supervisor retained the request. It does
not mean a blocked change has already taken effect. Pending entries identify the
affected field, reason and any blocking jobs.

A separately granted phone can also request a local review of network access,
Engineering setup or a pending restart. These bounded notes have durable
**waiting for PC** and **reviewed** receipts. Marking a note reviewed does not
open a port, install software, change credentials or restart the Gateway; the
host performs any resulting action through the existing local controls.
Existing device grants do not automatically acquire these new permissions.

## Concurrency and uncertain delivery

A command contains the installation identity, observed revision, unique command
ID and requested changes. The supervisor commits the command and desired state
in one SQLite transaction. A stale revision returns a durable rejection with
the current snapshot. The client keeps the draft for review instead of silently
overwriting another editor.

Android retains the last confirmed snapshot and its confirmation time across
restart. If the original Gateway cannot be verified, the cached view is clearly
offline and read-only. Refreshing that view never sends a saved draft.

The clients retain the command reference before transmission. After a lost
response, refresh reads that command's receipt; it does not issue a replacement
mutation. Reusing an ID with different arguments is rejected. Receipts remain
bound to the original owner and installation. A later accepted edit may mark an
older command superseded while retaining its original revision and request.

If the Gateway explicitly reports that the original command is absent, review
the retained target and changes before choosing **Retry original change**. That
action reads the receipt again and resends only the original ID, revision and
payload if it is still absent. A timeout, unknown error, changed installation or
different owner cannot authorize a retry. The background refresh never resends
the command. Older pending references without a retained payload remain limited
to reading their original receipt.

The supervisor stores this additive state in the existing job journal. Restart
retains pending settings, paired grants, admission preferences and receipts.
Observed effective values are also written to the existing settings files for
compatibility with the established launcher and rollback path. Jobs, execution
bindings and conversation owners are not migrated to a new identity.

## Authentication and authority

The private-LAN control listener uses a pinned TLS certificate. Android retains its P-256
private key in Android Keystore and the grant in the encrypted, non-backup
credential vault. Requests bind the HTTP method, path, installation, timestamp,
nonce and exact body digest to a device signature. Replayed, expired, unsigned,
forwarded and revoked requests are rejected. The listener does not accept a
shared-network identity as an owner.

The supervisor bridges admitted requests to the local runtime with its private
management credential. That credential is not returned to the phone or native
window. The runtime resolves the paired principal again and rechecks grants for
new work. Allowed fields and repositories come from the PC-approved grant.
New repository access, credentials, backend origins, validation commands, image
trust and OS changes require their existing local approval paths.

The paired route retains each saved job's original principal and installation.
Pairing a phone does not transfer historical shared-network conversations to the
new owner. Selecting a different profile does not grant authority over an
existing job or change its original execution binding.

The unreleased alternative owner-certificate prototype was consolidated into
this single control plane during PR #1741. Its broad grants are not silently
converted to scoped owner permission. If that prototype was installed from an
intermediate development build, pair explicitly through the current controls.
Existing journal and credential files are left intact for deliberate recovery;
the host's read-only retained-work view preserves the recorded job owner.
Unconfirmed commands from that prototype are not replayed. Validated host
configuration that was already applied remains the initialization source for
the shared settings record.

## Additive API contract

| Endpoint | Purpose |
| --- | --- |
| `POST /gateway/pairing/claim` | Bind an unexpired invitation to the phone's public key and request PC confirmation. |
| `POST /gateway/pairing/finish` | Recover the approved device grant using the same key and invitation. |
| `GET /gateway/settings` | Read the paired owner's view of the shared revision. |
| `PATCH /gateway/settings` | Submit one revision-bound settings command. |
| `GET /gateway/settings/commands/{command_id}` | Reconcile the original command without resubmitting it. |
| `GET /gateway/devices/self` | Read the authenticated grant identity. |
| `DELETE /gateway/devices/self` | Revoke this device. |

The capability is `controlSettings.v1`. It is advertised only while the separate
listener is observed running. Ordinary chat and legacy clients do not require
this capability. The settings and command schemas are
`gptmobile.gateway.settings.v1` and `gptmobile.gateway.command.v1`.

## Verification boundary

Host regressions exercise revision conflicts, original-command recovery,
owner-specific admission, grant revocation, signatures and replay protection,
runtime deferral and restart recovery. Native compilation and Windows installer
smoke are separate gates. Neither host fixtures nor a successful build establish
physical phone pairing, firewall behavior on a particular PC, accessibility on
every Windows configuration, or a live provider/GitHub task outcome. Those
observations belong in the task's qualification record with the exact build.
