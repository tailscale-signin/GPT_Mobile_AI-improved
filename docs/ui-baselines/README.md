# Reviewed UI baselines

`UI qualification pipeline` replaces the proposed three-stage UI workflow family
with one configurable entry point. Those three historical workflow files were
not present in this revision. Choose `requested`, `final`, or `production`, and
`legacy`, `geniex`, or `both`. Independent runtimes run in parallel after host
preflight. Compilation, rendering, comparison, packaging, and native/telemetry
audits retain their failure boundaries. Production prepares an unsigned release
candidate; the existing signed release identity and provenance gates govern
distribution.

Native Compose render tests already exercise light/dark, narrow/large-text, and
control reachability. They save actual PNGs under
`app/build/reports/reliability-ui`. Emulator acceptance additionally covers API 34
phone and API 36 tablet profiles. These are not physical QNN/GPU qualification.

To establish a baseline, inspect the rendered PNGs from a pinned source revision
and commit the approved files under a named directory here, together with:

```json
{
  "schema_version": 1,
  "commit_sha": "full 40-character source SHA",
  "reviewed_by": "reviewer identity",
  "renderer": "Robolectric native SDK 34; record CI image and font configuration",
  "channel_tolerance": 0,
  "max_changed_fraction": 0,
  "images": {"workspace-light.png": "full SHA-256 of approved PNG"}
}
```

Include every candidate image in the manifest. Compare only the same renderer,
fonts, dimensions, density and scale. Baseline review is a source change, not an
automatic consequence of a changed screenshot. The comparator checks digests,
dimensions, per-pixel changes and channel deltas, rejects missing/extra images,
and supports bounded, noninterlaced RGB/RGBA PNGs. It does not infer design intent.
Any renderer upgrade needs a new reviewed baseline.

No approved design images were supplied for this change. `requested` preserves
candidates and reports `NOT_QUALIFIED` when a manifest is absent; `final` and
`production` stop unless comparison passes. No baseline is silently accepted.
Use a manifest such as `docs/ui-baselines/workspace/manifest.json` when dispatching.
