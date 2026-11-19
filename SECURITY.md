# Reporting a security issue

Use the repository's [private vulnerability reporting page](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/security/advisories/new) when available. If private reporting is unavailable, request a private contact channel from the repository owner before sharing details. Public app issue reports can be read by other users.

Include the app version, commit or release, affected feature, expected behavior and observed impact. Omit tokens, signing keys, raw conversations, attachments and recovery codes. A dependency advisory can be identified by its GHSA, CVE or OSV identifier.

Revoke an exposed credential at its issuer and replace it in the affected deployment. An Android user can replace their own provider credential in the relevant connection settings. Deployment operators own OAuth client secrets, signing keys and public-service credentials. Removing a value from a commit cannot revoke it at its issuer.

A full checkout can run the history and locked-dependency checks locally.

```sh
bash scripts/scan_secret_history.sh
python3 scripts/audit_locked_dependencies.py
```

The history check covers locally fetched refs. Private configuration filenames also receive a history check, including binary signing containers. The dependency check queries OSV with public package names and versions from both Android runtime and processor graphs, the Gradle build plugin classpath, and the Python and npm lockfiles. The PR dependency-review job checks introduced vulnerabilities. Use `--verified-inventory` to include previously verified Maven versions and build/test tooling in a separate inventory review.
