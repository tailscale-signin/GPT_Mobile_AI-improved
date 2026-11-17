# Continuous engineering transport recovery — November 16, 2026

**Scope:** `fix/post-1299-incident-diagnostics-2026-11-15`, PR #1349.

## Cause and correction

The original Gateway supervisor transitioned a safe transient-transport failure to `NEEDS_INPUT` on the twelfth consecutive connection failure. This could terminate a long-running job despite a valid execution binding, retained checkpoint and available time budget.

The supervisor now records `TRANSIENT_TRANSPORT` plus the next retry deadline and counter in its durable job checkpoint. It retains the original phase and does not abort merely because of the number of safe retries. Timed waits release worker capacity and are observable through the existing `provider_retry_at` job property. The existing delay remains exponential and capped at 300 seconds. Requests that would exceed the accepted duration/finalization reserve are held for explicit review.

The change **does not** retry uncertain writes, bypass authentication or contract errors, alter selected-model bindings, extend user-approved run duration, or grant merge approval.

## Tests and remaining acceptance

The updated regression tests cover sixteen consecutive connection failures, reject premature direct ticks, recover the saved job across store restart and confirm progression only after connectivity returns. Existing tests continue to cover 429/403 cooldowns, unsafe mutations, deadline expiry and TLS/authorization failures.

Hosted CI, installed-Gateway fault injection and Android device acceptance must be repeated on the exact final PR head. Historical app build 141 reports alone cannot verify a fix or justify closing an issue. A multi-session renewable project budget and headless Windows Gateway are separate follow-ups requiring explicit limits and acceptance.
