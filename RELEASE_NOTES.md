# GPT Mobile AI 0.9.43.0

## Local Health Connect history
- Add local Health Connect imports in Smartwatch settings, with summary ranges from today through five years, subject to the records actually available from Health Connect.
- Check extended-history support and request the separate Android history permission before multi-year imports. Existing AI health-tool queries remain limited to the last 30 days.
- Keep the imported snapshot in app-private storage. Importing or viewing health data locally does not grant AI sharing consent or enable profile access.

## Encrypted health backup and restore
- Add an optional Health data section to complete backup and restore. It is excluded from the default selection and requires encryption.
- Protect health backups with a password or a separate recovery key file. Keep the recovery key safe and separate from the backup; it is required to restore passwordless encrypted archives.
- Validate restored snapshots and preserve the original imported time range, including multi-year history. A saved snapshot is a summary of the selected range, not a complete raw-record export or a promise that every watch provides five years of data.
- Retain the existing independent consent controls for AI access and remote sharing.

## Release validation
- Correct the plaintext-backup ViewModel test to select non-sensitive settings rather than ALL sections, which includes health data, credentials and memory.
- Add ViewModel regression coverage for health backup with a recovery key, recovery-key cancellation without writing a backup, and password-encrypted health backup.
- Retain the five-year snapshot codec and archive round-trip coverage introduced by the Health Connect local-import change.
- The signed-release workflow validates the exact release commit, runs unit tests and Android lint, and checks native packaging, artifact identity and signing-certificate continuity before publication. No test or encryption gate has been disabled.

## Setup and scope
- Open Settings > Plugins/Tools > Smartwatch Health Connect > Configure to manage permissions and local imports.
- The watch companion app must share supported records with Health Connect. Availability depends on Android history support, permissions, companion-app sync and retained records.
- Physical-device multi-year import and clean-device restore testing remain required; this release does not claim watch certification, medical diagnosis or Google Play approval.
- This publication is a GitHub release, not a Google Play submission.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.43.0
- Version code: 112
