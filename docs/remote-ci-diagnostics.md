# Remote CI diagnostics

This repository provides GitHub-hosted diagnostics for contributors and automation that cannot run the Android project locally. The workflow is designed to make build failures understandable through GitHub checks, pull-request comments, job summaries, and downloadable artifacts—including when changes are made remotely through GitHub MCP.

## Components

| Component | Purpose |
| --- | --- |
| `.github/workflows/remote-diagnostics.yml` | Runs resource, unit-test, lint, and build diagnostics on pull requests or by manual dispatch. |
| `scripts/validate.sh` | Stable entry point shared by local development and CI. |
| `scripts/check_android_resources.py` | Fast preflight for malformed values XML and duplicate Android resources. |

The existing repository checks remain authoritative. Remote diagnostics add focused output and do not replace release validation or device-based instrumented tests.

## Validation targets

Run the validation entry point from the repository root:

```bash
bash scripts/validate.sh TARGET
```

Supported targets are:

| Target | Checks performed |
| --- | --- |
| `all` | Resource preflight, debug unit tests, Android lint, and debug APK assembly. This is the default. |
| `resources` | Values XML and duplicate-resource preflight only. |
| `test` | `:app:testDebugUnitTest` |
| `lint` | `:app:lintDebug` |
| `build` | `assembleDebug` |

Examples:

```bash
# Run the complete validation set
bash scripts/validate.sh all

# Quickly check Android values resources
bash scripts/validate.sh resources

# Reproduce one CI job
bash scripts/validate.sh lint
```

An unknown target exits with status `2`. A failed check exits nonzero.

## Android resource preflight

`scripts/check_android_resources.py` scans `app/src/main/res/values*/*.xml` before Gradle packaging. It reports:

- malformed XML;
- files whose root element is not `<resources>`; and
- duplicate resource type/name pairs in the same values configuration directory.

The same resource name is allowed in different qualifier directories, such as `values/` and `values-es/`. It is rejected only when defined more than once in one configuration directory.

The checker emits GitHub workflow annotations in this form:

```text
::error file=app/src/main/res/values/example.xml::Duplicate string/example_name ...
```

This allows GitHub to associate a failure with the relevant file. Run it directly when developing the checker:

```bash
python3 scripts/check_android_resources.py
```

## Pull-request workflow

For pull requests targeting `main` or `master`, **Remote diagnostics** runs four visible checks:

1. **Resource and XML preflight**
2. **Unit tests**
3. **Android lint**
4. **Debug build**

The Gradle checks run independently with matrix fail-fast disabled, so one failure does not prevent the other diagnostics from completing. A final reporting job creates or updates one pull-request comment marked internally with:

```html
<!-- remote-diagnostics -->
```

Updating one bot comment avoids adding a new comment after every commit.

### Diagnostic output

Each job:

1. captures combined standard output and standard error with `tee`;
2. preserves the validation command's failure through `set -o pipefail`;
3. writes a concise result to the GitHub job summary;
4. uploads the complete captured log as an artifact; and
5. fails only after reporting and artifact upload have run.

Artifacts are retained for 14 days and use names such as:

- `resource-diagnostics-RUN_ID`
- `test-diagnostics-RUN_ID`
- `lint-diagnostics-RUN_ID`
- `build-diagnostics-RUN_ID`

The workflow uses a per-PR concurrency group and cancels an obsolete run when a newer commit is pushed.

## Manual diagnostics

A maintainer can open **Actions → Remote diagnostics → Run workflow** and select one of:

- `all`
- `resources`
- `test`
- `lint`
- `build`

A targeted dispatch executes only the selected validation path. Choose `all` for a complete remote check.

GitHub MCP currently may not expose workflow dispatch in every environment. In that case, push a commit to the pull-request branch to trigger the pull-request workflow, or ask a maintainer to start the manual run in GitHub Actions.

## Remote GitHub MCP workflow

A safe remote change cycle is:

1. Read the issue or pull request and its current checks.
2. Read only the files relevant to the failure.
3. Create or reuse a dedicated branch.
4. Make a narrow change and push it to that branch.
5. Open or update a pull request.
6. Wait for **Remote diagnostics** and existing repository checks.
7. Read check runs and the remote diagnostic PR comment.
8. If a check fails, inspect its summary, annotations, or linked artifact and iterate on the same branch.

Useful MCP operations include:

- reading PR details and changed files;
- retrieving check runs for the PR head commit;
- reading the bot's diagnostic PR comment;
- reading affected repository files;
- pushing related changes in one commit; and
- updating the PR description with validation results.

Prefer batched file reads and a single multi-file commit when the MCP environment imposes a tool-call limit.

## Troubleshooting

### Workflow does not appear

- Confirm the PR targets `main` or `master`.
- Check `.github/workflows/remote-diagnostics.yml` for YAML or expression errors.
- Remember that a matrix value is not available in a job-level `if` expression. Conditions involving `matrix.task` belong on steps after matrix expansion.
- Push a new commit after fixing workflow syntax.

### A command piped through `tee` appears to pass

Use `set -o pipefail` before the pipeline. Without it, the shell may return `tee`'s successful exit status instead of the validation command's failure.

### Resource preflight reports a duplicate

The message includes the resource type, name, configuration directory, and defining files. Remove or rename the redundant definition. Do not remove legitimate localized or qualified definitions from different `values-*` directories.

Example:

```text
Duplicate string/example_name in app/src/main/res/values:
app/src/main/res/values/a.xml, app/src/main/res/values/b.xml
```

### A job summary is too short

Open the workflow run and download the corresponding diagnostic artifact. Summaries intentionally contain only the tail of failed logs to remain readable.

### Manual target jobs show skipped steps

This is expected. The matrix is created once, and step conditions restrict execution to the selected manual target.

## Security and operational notes

- Workflow permissions are limited to `contents: read` and `pull-requests: write`.
- PR write access is used only for the compact diagnostic report.
- Validation commands must not print credentials, tokens, signing material, or other secrets.
- Do not add untrusted secret values to summaries, annotations, PR comments, or artifacts.
- Logs are retained for 14 days; treat them as repository-visible CI output.
- Pull-request code executes in CI, so changes to validation scripts and workflows require normal code review.
- These checks do not run instrumented tests and do not prove behavior on a physical device or emulator.

## Maintaining the diagnostics

When adding a new target:

1. Add a case to `scripts/validate.sh` and update its usage text.
2. Add or update the workflow matrix and manual input options.
3. Ensure the command writes a log, summary, and uniquely named artifact.
4. Keep `continue-on-error` only on the command step, followed by an explicit failure step after reporting.
5. Update this document and test both pull-request and manual-dispatch behavior.

Keep validation commands centralized in `scripts/validate.sh`; avoid duplicating Gradle task lists in workflow YAML. This keeps local and hosted validation reproducible.
