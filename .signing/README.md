# Android release signing

Android requires every update for an installed application to be signed by the same key.

## Required GitHub Actions secrets

Official release publication is intentionally **fail-closed**. Configure all four repository secrets before running the `Publish Signed Release` workflow:

- `KEYSTORE_BASE64` — base64-encoded persistent JKS/PKCS12 keystore
- `KEYSTORE_PASSWORD` — keystore password
- `KEY_ALIAS` — signing-key alias
- `KEY_PASSWORD` — signing-key password

The private keystore must not be committed to this repository. The release workflow validates the configured keystore and alias before building and refuses to publish if any required secret is missing.

## Signing continuity

Keep an offline backup of the release keystore and its credentials. Losing the signing key prevents Android from installing future updates over builds signed with that key.

Earlier CI revisions generated a new fallback key during each release when secrets were absent. Those artifacts do **not** establish a reliable long-term update-signing chain. After configuring the persistent key, users on an APK signed by a previous ephemeral key may need a one-time uninstall/reinstall before subsequent updates can install normally.

## Local files

If you temporarily place a keystore under `.signing/` for local work, it is ignored by Git. Do not add signing credentials, private keys, or passwords to source control.
