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

# A lost create/upload response can leave an unpublished release on GitHub.
# Continue only when its tag and immutable source match this validated package.
releases="repos/$GITHUB_REPOSITORY/releases"
lookup() {
    gh api --paginate --slurp "$releases?per_page=100" |
        jq -ce --arg tag "$tag" '[.[][] | select(.tag_name == $tag)] | if length > 1 then error("Duplicate release") else . end'
}
tag_commit() {
    git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}" |
        awk -v ref="refs/tags/$tag" '$2 == ref {direct=$1} $2 == ref "^{}" {peeled=$1} END {print (peeled ? peeled : direct)}'
}
matches=$(lookup)
existing_tag=$(tag_commit)
if [[ -n "$existing_tag" && "$existing_tag" != "$GITHUB_SHA" ]]; then
    echo "::error::The existing Windows tag points to another commit."
    exit 1
fi
if [[ "$matches" == '[]' && -n "$existing_tag" ]]; then
    echo "::error::A tag already exists without a matching release."
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

if [[ "$matches" == '[]' ]]; then
    if ! gh api --method POST "$releases" -f tag_name="$tag" -f target_commitish="$GITHUB_SHA" \
        -f name="Gateway $version for Windows" -F draft=true -f make_latest=false -F body=@release-notes.md --silent; then
        echo 'Checking the result of the Windows draft creation.' >&2
    fi
    matches=$(lookup)
fi
release_id=$(jq -er --arg sha "$GITHUB_SHA" '.[0] | select(.target_commitish == $sha and .prerelease == false) | .id | numbers' <<< "$matches")
[[ "$release_id" =~ ^[1-9][0-9]*$ ]]
state="$releases/$release_id"
assets=("$zip" "$installer" SHA256SUMS.txt smoke-results.json)
receipt=$(gh api "$state")
verify_asset() {
    local name="$1" digest size
    digest=$(sha256sum "$name" | cut -d' ' -f1)
    size=$(stat -c %s "$name")
    jq -e --arg name "$name" --arg digest "sha256:$digest" --argjson size "$size" '
        [.assets[] | select(.name == $name)] | length == 1 and .[0].state == "uploaded" and
        .[0].digest == $digest and .[0].size == $size' <<< "$receipt" > /dev/null
}
# Adding an asset is safe only on this same-source draft. Never replace a file.
for name in "${assets[@]}"; do
    if ! jq -e --arg name "$name" 'any(.assets[]; .name == $name)' <<< "$receipt" > /dev/null; then
        jq -e '.draft == true' <<< "$receipt" > /dev/null
        if ! gh release upload "$tag" "$name" --repo "$GITHUB_REPOSITORY"; then
            echo "Checking whether GitHub received $name." >&2
        fi
        receipt=$(gh api "$state")
    fi
    verify_asset "$name"
done
jq -e '(.assets | length) == 4' <<< "$receipt" > /dev/null
if jq -e '.draft == true' <<< "$receipt" > /dev/null; then
    if ! gh api --method PATCH "$state" -F draft=false -f make_latest=false --silent; then
        echo 'Checking the result of Windows publication.' >&2
    fi
fi
receipt=$(gh api "$state")
jq -er --arg tag "$tag" --arg sha "$GITHUB_SHA" '
    select(.tag_name == $tag and .target_commitish == $sha and .draft == false and .prerelease == false
        and .published_at != null and (.assets | length) == 4) | .html_url' <<< "$receipt"
[[ "$(tag_commit)" == "$GITHUB_SHA" ]]
for name in "${assets[@]}"; do verify_asset "$name"; done
# Re-fetch the published files and compare bytes, including the smoke receipt.
downloads=$(mktemp -d)
trap 'rm -rf -- "$downloads"' EXIT
gh release download "$tag" --repo "$GITHUB_REPOSITORY" --dir "$downloads" --pattern "$zip" --pattern "$installer" \
    --pattern SHA256SUMS.txt --pattern smoke-results.json
for name in "${assets[@]}"; do cmp -- "$name" "$downloads/$name"; done
