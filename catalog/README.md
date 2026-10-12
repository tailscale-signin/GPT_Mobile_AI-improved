# Official GenieX registry capture — 2026-10-12

The `sources/geniex-2026-10-12` directory contains the unmodified official v0.64.0 registry manifest, platform metadata, 241 family info files and 200 release-asset manifests. The other 41 families expose no release-assets URL; their absence is recorded explicitly. Source metadata retrieval is complete for the pointers the registry supplies.

The generated v2 feed (`../model_catalog_v2.json`, also bundled in app assets) reconciles 241 families and 1,974 records. Coverage: 98 missing artifact metadata, 1,515 unsupported app workflow, 361 unsupported platform, zero newly represented normalized artifacts. The existing curated LiteRT entries remain available. The legacy `../model_catalog.json` is the schema-v1 projection.

Input SHA-256: `b57966c97a40f6a5489123985e425f263989ec1a15e3991d96b26a11a96949a3`.

`geniex-artifact-audit-2026-10-12.json` inventories all 170 Genie/GenieX artifact references. Bounded archive inspection succeeded for six; 152 requests were blocked by the network proxy (HTTP 403) and 12 timed out. CRCs and ZIP directory sizes are not cryptographic asset validation. No inspected archive is marked fully qualified. Complete SHA-256 inventory, redistribution review and exact-device execution are still required before exposing a normalized download.

Reproduce normalization with:

```sh
python3 scripts/update_geniex_catalog.py --capture catalog/sources/geniex-2026-10-12 --baseline model_catalog.json --revision 20261012 --output /tmp/catalog-review
```

The normalizer rejects inventory entries without source identity, pinned runtime, archive and asset hashes, bounded safe paths and explicit redistribution review. It does not infer a compatible phone SKU from a marketing chipset family, infer projector compatibility, or treat upstream metadata as successful native execution.
