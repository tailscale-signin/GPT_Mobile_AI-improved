#!/usr/bin/env bash
# Run after exact-source CI and Windows installer smoke pass on main.
set -euo pipefail

[[ "$GITHUB_REF" == refs/heads/main && "$(git rev-parse HEAD)" == "$GITHUB_SHA" ]]
version=$(PYTHONPATH=gateway python3 -c 'from v14 import VERSION; print(VERSION)')
[[ "$version" =~ ^17\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
tag="gateway-v$version"
zip="Gateway-$version-windows-x64.zip"
installer="Gateway-$version-windows-x64-setup.exe"
android_version=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"$/\1/p' app/build.gradle.kts)
android_code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)
[[ "$android_version" =~ ^[0-9]+(\.[0-9]+)+$ && "$android_code" =~ ^[0-9]+$ ]]
cd dist/windows

# The Windows runner writes its checksum file with CRLF on Windows.
tr -d '\r' < SHA256SUMS.txt > checksums.txt
sha256sum "$zip" "$installer" > expected-checksums.txt
cmp checksums.txt expected-checksums.txt
jq -e --arg version "$version" '.version == $version and (.passed | length > 0)' smoke-results.json > /dev/null
unzip -p "$zip" release.json |
    jq -e --arg version "$version" --arg sha "$GITHUB_SHA" '.version == $version and .sourceCommit == $sha' > /dev/null

# Prevent an existing Git ref from binding these artifacts to a different source.
existing_tag=$(git ls-remote --refs origin "refs/tags/$tag")
[[ -z "$existing_tag" ]]
release_tags=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases?per_page=100" --jq '.[].tag_name')
if grep -Fxq -- "$tag" <<< "$release_tags"; then
    echo "::error::Release $tag already exists. Published releases are immutable in this workflow."
    exit 1
fi

{
    printf 'Gateway source: %s\n\n' "$GITHUB_SHA"
    printf 'Android source at this revision: v%s (build %s). Android packaging and publication use the signed Android release workflow.\n\n' "$android_version" "$android_code"
    printf 'The Windows installer is unsigned. CI verified the installer and update smoke scenarios. Physical Android and Windows integration requires its own observed results.\n'
    if [[ -f "../../docs/repairs/post-1349-github-read-recovery-2026-11-17.md" ]]; then
        printf '\n[Feature delivery and qualification](https://github.com/%s/blob/%s/docs/repairs/post-1349-github-read-recovery-2026-11-17.md)\n' "$GITHUB_REPOSITORY" "$GITHUB_SHA"
    fi
} > release-notes.md

release_id=$(gh api --method POST "repos/$GITHUB_REPOSITORY/releases" \
    -f tag_name="$tag" -f target_commitish="$GITHUB_SHA" -f name="Gateway $version for Windows" \
    -F draft=true -f make_latest=false -F body=@release-notes.md --jq .id)
# Keep the release in draft if transfer or digest confirmation fails.
gh release upload "$tag" "$zip" "$installer" SHA256SUMS.txt smoke-results.json --repo "$GITHUB_REPOSITORY"
while read -r digest asset; do
    gh api "repos/$GITHUB_REPOSITORY/releases/$release_id" |
        jq -e --arg asset "$asset" --arg digest "sha256:$digest" \
            'any(.assets[]; .name == $asset and .digest == $digest and .state == "uploaded")' > /dev/null
done < expected-checksums.txt
gh api --method PATCH "repos/$GITHUB_REPOSITORY/releases/$release_id" \
    -F draft=false -f make_latest=false --jq .html_url
