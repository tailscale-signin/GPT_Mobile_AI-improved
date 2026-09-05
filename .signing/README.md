# Persistent Signing Configuration

This directory supports persistent release signing for CI builds of GPT_Mobile_AI-improved.

### Why this exists:
Android enforces that updates to an installed application must be signed with the exact same cryptographic key.
When ephemeral/random keys were generated on every CI run, every build had a different certificate, causing Android to reject in-place updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and forcing users to uninstall before upgrading.

### Keystore details:
- **Default file path**: `.signing/release.jks`
- **Alias**: `gptmobile`
- **Storepass / Keypass**: `gptmobile_release_key`

If GitHub Actions repository secrets (`APP_KEYSTORE`, `KEY_ALIAS`, `KEY_PASSWORD`) are configured in repository Settings > Secrets and variables > Actions, the workflow uses those secrets with top priority. Otherwise, it will fall back to this consistent keystore so all releases can install seamlessly over each other without uninstallation.
