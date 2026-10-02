# GPT Mobile AI 0.9.27.0

## Delegation and GitHub reliability
- Fixes delegate selection for legacy configurations where cloud helpers had been allowed through the older local-only setting.
- Prevents delegation from selecting the active primary profile or repeatedly cycling through unavailable helpers.
- Keeps GitHub repository work on the native GitHub integration and removes POSIX/shell/terminal fallbacks for GitHub tasks.
- Adds repository write-capability inspection before diagnosing GitHub write failures.
- Stops treating every HTTP 403 as proof of a read-only token.
- Reports the exact GitHub permission layer involved, including repository push authority and endpoint-specific requirements such as Contents: write, Pull requests: write, and Actions: write.
- Preserves GitHub API error messages, rate-limit state, accepted permissions, OAuth scope hints, and SSO details when available.
- Returns structured GITHUB_WRITE_BLOCKED errors and stops repeated write retries after a confirmed denial.

## Tool Connections redesign
- Splits Tool Connections into a default Plugins tab and a separate Remote MCP tab.
- Moves built-in and native integrations, including GitHub API, web search, memory, calculator, file reading, URL reading, location, and delegation, under Plugins.
- Adds persistent per-plugin enable/disable controls and settings.
- Keeps actual MCP servers isolated under Remote MCP with their connection, health, authentication, resources/prompts, permissions, pairing, and marketplace controls.
- Ensures disabled plugins are actually removed from model tool catalogs rather than being cosmetic UI toggles.

## GitHub plugin UX
- Shows the permissions required for repository writes, pull requests, and Actions directly in GitHub Plugin settings.
- Keeps native GitHub and GitHub MCP clearly separated so the app does not duplicate or confuse the two tool paths.
- Prevents anonymous GitHub fallback from bypassing a disabled authenticated GitHub plugin.

## Regression coverage
- Covers legacy delegation migration, strict local-only behavior, plugin gating, GitHub write capability inspection, false read-only 403 diagnosis, and POSIX suppression for GitHub tasks.

## Version
- Version: 0.9.27.0
- Version code: 96

## Installation
- Install the signed Android APK from this release.
- No gateway script update is required specifically for these client-side delegation, GitHub, and Tool Connections fixes.
