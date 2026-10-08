# GPT Mobile AI 0.9.35.0

## Amazon research, local history and manual watches

- Add Amazon Research Free: optional native Canada/US public-page search and product details without an API key or desktop server. It starts disabled globally and per AI profile.
- Show product cards with retrieval time, marketplace/currency context, canonical links and a shortcut to local history and manual watches.
- Store profile-owned price observations and explicit manual targets in Room schema 34. Temporary-chat lookups do not automatically persist history.
- Add native save/edit/pause/delete controls and Check now, plus read-only AI tools for local history and saved watches. Local tools can remain available when online tools are disabled.
- Preserve installation request usage, pacing and blocking cooldowns across restarts and migration. Permission revocation cancels acquisition and rolls back observation writes.
- Offer an optional history/manual-watch backup section. Restored watches pause or become orphaned, plugin grants reset, and the installation request budget is retained.
- Keep Amazon SerpApi search and the pinned JanNafta MCP bridge separately configurable. Desktop bridge setup is documented in mcp/amazon/README.md.

Canada/US public pages remain previews. History consists of observations captured by the app. Incomplete seller, condition, variant or destination information cannot confirm a target match. Manual watches start no background checks or notifications. Live Amazon reliability and physical-device visual review remain pending.

## Toolkit and AI profile controls

- Group tools by service with themed logos, search, filters, sorting and collapsible settings.
- Give each AI profile independent service, native-capability and remote MCP tool selections while honoring global switches and missing-setup requirements.
- Highlight required settings in the error colour and make enable/disable controls available after supported installation.
- Combine OpenStreetMap geocoding and restroom search under one installation, retaining prior endpoints, exclusions, limits and disabled capabilities.
- Offer Restore once during onboarding and improve the alignment and appearance of the new-chat icon.

## Marketplace installation and reliability

- Add separate Plugins/MCP tabs and a catalogue of commit-pinned optional packages with integrity-checked downloads and clear runtime/setup requirements.
- Install supported Android-native provider registrations in the app, with enable/disable controls, encrypted credentials and uninstall cleanup. Required API fields have a red outline; keyless providers have appropriate setup controls.
- Preserve download and tab state, isolate optional provider failures, enforce shared provider quotas and block stale tool calls after revocation.
- Redact provider credentials echoed in responses while retaining useful results. Keep location/event queries bounded and require explicit provider configuration.
- Document the optional computer-hosted companion and distinguish installable native adapters, hosted connections and setup guides.

## Release integrity and validation

- PR #617 passed 1,816 local unit tests, Android lint with zero errors, and runtime/JNI/16 KB alignment/telemetry checks on all three debug APKs.
- All applicable PR checks passed before merge, including hosted unit tests, Android lint, packaged runtime checks, marketplace contracts, Kotlin lint, CodeQL and the Android 16 minified native-memory smoke test.
- Include the independently verified Ktor encoding POM checksums required by strict dependency verification.
- Publish this version from a new release commit. The signed workflow independently validates that exact commit, verifies APK/AAB identity and signing-certificate continuity, and publishes checksums and provenance.

## Version and installation

- Version: 0.9.35.0
- Version code: 104 (previous release: 103)
- Package: dev.melo.gptmobile.improved
- Install the signed arm64-v8a APK for an ARM64 Android phone. Keep the existing app installed when updating with the same signing certificate.
- Amazon research and Toolkit controls: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/617
- Native marketplace installation: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/616
- Marketplace catalogue and downloads: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/615
- Initial Amazon product search: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/613
