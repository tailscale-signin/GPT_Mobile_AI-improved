# Repository review: readiness and recovery clarity

## Intent and revision

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Parent pull request: `#2254`
- Base ref: `origin/review-pr-2254` (local read-only tracking ref for the parent PR)
- Base SHA: `f225c208e80393f0232ba7849340461fd23901b9`
- Working branch: `work/review-readiness-2026-11-22`
- Scope: existing profile, setup and settings surfaces; evidence-based readiness and processing-route presentation; recovery guidance.
- Intent: distinguish saved configuration, a currently loaded/connected runtime, and recorded successful execution. Keep profile selection independent of privacy-mode badges. Make first-response verification and backup/restore paths understandable without adding blocking prompts.

## Checkpoint

Read `AGENTS.md`, `.github/instructions/ai-engineering.md`, and the relevant subsystem entries in `docs/architecture/repository-index.json`. Implementation is complete in this isolated worktree. The parent worker owns integration, full Android CI and publication.

## Changes and evidence boundaries

1. Setup now says profiles are saved and asks for a first complete reply. Finishing a model download is no longer presented as proof that inference is ready.
2. Profile cards and settings distinguish phone inference, exact recognized cloud API hosts, observed Gateway identity, and unverified network services. An IP address or provider family never establishes PC-local or private processing. Engineering Gateway bindings do not relabel a separate cloud inference route. The helpers neither contact providers nor participate in profile admission.
3. Profile settings link directly to the existing response/performance checks. Local engine settings link directly to Diagnostics, including when opened through a filtered settings search.
4. Runtime settings show the model filename, requested processor/context and separately acknowledged native processor. Failed, recovering, released or unknown lifecycle states cannot promote an old receipt to current readiness. A loaded engine is explicitly distinct from measured speed/reliability.
5. Research Activity distinguishes implemented adapters from unavailable integrations and links to search/citation import. PDF guidance explains what to provide when extracted text is unavailable; downloading a PDF does not establish figure/table/scanned-page coverage.
6. Backup and Restore identify backups as snapshots and provide expandable transfer instructions, including omitted credentials/models and device-specific NPU packages. Existing selections, encryption and restore behavior are unchanged.

## Validation

- PASS: 12 JVM tests in `ConfiguredInferenceRouteTest` and `RuntimeReadinessPresentationTest`, compiled using Kotlin 2.4.20 and JDK 21. Tests cover misleading provider/address labels, exact host matching, embedded credentials, missing native acknowledgement, request/actual processor mismatch and retained evidence after failure/recovery/release.
- PASS: ktlint 1.3.1 for all changed Kotlin files; `git diff --check`.
- PASS: `python3 scripts/check_android_regex.py` (1,874 literal patterns, 84 dynamic patterns skipped, zero errors).
- PASS: `bash scripts/validate.sh resources` (36 XML files, icon and native provenance checks).
- BLOCKED AT GENERATED INVENTORY: `bash scripts/validate-local.sh fast` passed CI policy and the architecture, agent metadata and diagnostic schema checks, then stopped because `generate_app_audit_inventory.py --check` detected changed UI sources. The parent worker will regenerate the shared inventory after integrating all parallel work and rerun the canonical command. No generated global inventory was edited in this scoped branch.
- NOT RUN: full Android compilation, Compose/Robolectric/device tests, visual device QA and physical processor measurements; Android SDK is unavailable in this host. The parent worker owns full CI. UI status changes do not constitute device qualification.

The host JVM compile/test logs and formatting output are retained at `/workspace/scratch/dfab796cea9c/readiness-host-tests` for integration review.
