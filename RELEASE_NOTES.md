# GPT Mobile AI 0.9.39.0

## Airbnb in the app
- Add public Airbnb search, listing details and photo cards that run directly on Android without an account, API key, OpenBnB or a computer-hosted MCP server.
- Use a free public search fallback when Airbnb blocks its search page. Indexed listings explicitly leave dates, availability and prices unconfirmed.
- Enable Airbnb globally and for your AI profile. Current listing data requires internet access; booking opens Airbnb.

## Downloaded plugins and backup
- Preserve downloaded plugin packages, installations, provider configuration and enabled switches in new Tools backups, and reload installed state after restore.
- Preserve Amazon Research global and profile enablement instead of switching it off during restore.
- Include Tools in default and previously saved Settings backup selections. Tools-only restores preserve unrelated current app preferences.
- Enabling a downloaded native plugin also enables its matching global service switch; profile permissions remain in effect.
- Include Credentials when backing up provider API keys. Older archives that omitted downloaded packages cannot recover those missing files.

## Included stability fixes
- Retain the 0.9.38.0 fixes for mixed tool-event completion timestamps and Android SQLite FTS5 detection.

## Validation
- Add coverage for account-free requests, Airbnb cards and details, blocked-page fallback, argument and permission checks, installed-package backup/restore, plugin preferences and every downloadable native adapter's tool/request wiring.
- Signed publication validates Android tests and lint, native packaging and signing-certificate continuity. Mock tests do not certify every live provider API or physical-device behavior.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.39.0
- Version code: 108
