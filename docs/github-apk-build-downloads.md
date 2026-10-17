# APK downloads in GitHub Workspace

The **Builds** tab in GitHub Workspace provides APK downloads for the selected repository. It uses the saved GitHub connection directly and does not require an AI profile, tool call, or repository write permission.

## User flow

1. Open **GitHub Workspace**, select a repository, and open **Builds**.
2. Choose **Releases** for published APK assets or **Workflow builds** for artifacts from successful runs on the selected branch.
3. Review the version or branch, date, file size, and device architecture. The variant matching the device is highlighted without hiding other APKs.
4. Select **Download**. Download progress and cancellation remain available in the workspace; the background worker continues when the workspace closes.
5. When the download finishes, choose an extracted APK and use **Save to Downloads** or **Share APK**. Saved files go to **Downloads/GPT Mobile**. The save confirmation offers **Open Downloads** so the APK can be opened from the system file interface. Installation remains an explicit Android user action.
6. Use **Remove local download** to reclaim the app's storage after a transfer finishes or is canceled. Copies already saved to Downloads remain available there.

Release listings exclude Android App Bundles, signature sidecars, checksums, and other non-APK assets. Workflow artifacts are ZIP archives; the app extracts their APK files so the user does not need to unzip them manually. An artifact can contain more than one APK variant.

## Sources and identity

Published releases are repository-wide. Workflow results use the currently selected repository and branch and only completed successful runs. Fork runs with a matching branch name are excluded by verifying the run's head repository. Each download is identified by connection, repository, source, and numeric GitHub asset or artifact ID. Changing the selected repository or branch does not retarget an existing download.

Release version tags and workflow commit SHAs are displayed as supplied by GitHub. An APK filename does not establish the app version, its signing identity, or its compatibility with an installed package. A successful workflow is evidence of that workflow's result, not proof that every repository check passed.

The catalog is paginated. Empty pages can still offer **Load more** because a release or workflow page may contain no APKs. Expired artifacts cannot be downloaded and are distinguished from connection failures.

Completed downloads have durable local records, so they remain in the workspace after Android clears finished WorkManager records. Download history retains the source repository, branch or release tag, and commit when GitHub supplies one. Normal Activity recreation preserves the current workspace selection.

## Authentication and transfer

Metadata calls use the existing GitHub credential. Private workflow artifacts require **Actions: read**; private release assets require the applicable repository read access. No Contents write permission is needed to download a build.

The download worker loads the credential from `SecretVault` when work starts. Tokens are not placed in WorkManager input data, saved build records, notifications, filenames, or download URLs. Download requests start from GitHub's asset/artifact API, using numeric IDs rather than a stored signed URL. Redirected storage requests do not receive the GitHub Authorization header.

Files are streamed to temporary storage and exposed only after completion and validation. ZIP extraction limits archive size, expanded output, and entry count; it rejects unsafe paths and only publishes APK files. A provided SHA-256 digest is checked against the downloaded asset or archive. This verifies transfer integrity; Android remains responsible for package installation and signature checks.

Interrupted transfers can resume from private partial files when the server supports byte ranges. A GitHub rate-limit response persists its retry deadline; Retry during that cooldown makes no network request. Removing a download is available only after its background work has stopped and is disabled while an APK is being saved.

Downloads are explicit user actions. The feature does not install packages automatically, merge pull requests, dispatch workflows, or publish releases.

## Implementation

- `GitHubBuildCatalog` discovers and normalizes release assets and workflow artifacts.
- `GitHubBuildsViewModel` isolates requests by the selected connection/repository/branch and ignores stale responses after selection changes.
- `GitHubBuildsPanel` presents source filters, metadata, progress, saved files, retry, and cancellation in the current theme.
- `GitHubApkDownloadManager` owns work identity, durable history, completed-file access, local removal, and Android save/share/downloads actions.
- `GitHubApkDownloadWorker` performs durable transfers using the existing WorkManager foreground-download infrastructure.

## Validation

Focused tests cover APK filtering, branch-specific discovery, pagination, authentication headers, permission failures, cancellation, archive extraction, invalid paths, multiple APKs, size limits, and transfer integrity. The **GitHub build downloads** workflow runs the two test classes with real Android/Gradle and Ktor dependencies and uploads their reports. It has a bounded runtime and runs independently of the full repository test suite.

To run the same checks locally with the Android SDK installed:

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  --tests dev.chungjungsoo.gptmobile.data.github.GitHubBuildCatalogTest \
  --tests dev.chungjungsoo.gptmobile.data.github.GitHubApkFilesTest
```

Kotlin formatting and Android resource validation apply to the new files. The normal PR validation pipeline retains the full unit suite, Android lint, and review APK build.

Manual Android verification should exercise a published ARM64 APK, a workflow ZIP containing multiple variants, a private repository, cancel/retry, navigation and rotation during a transfer, Save/Share/Open Downloads, and local removal. The in-app list displays the full workflow archive transfer size; a bundle containing several variants can be substantially larger than a single release APK.
