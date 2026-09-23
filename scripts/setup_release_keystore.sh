#!/usr/bin/env bash
set -euo pipefail

required=(KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD)
missing=()
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "Release signing is not configured. Missing GitHub Actions secrets: ${missing[*]}" >&2
  echo "Refusing to create publishable Android artifacts with an ephemeral or repository-known key." >&2
  exit 1
fi

printf '%s' "$KEYSTORE_BASE64" | base64 --decode > release.keystore
chmod 600 release.keystore

# Validate credentials before spending time on a release build.
keytool -list \
  -keystore release.keystore \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS" >/dev/null

echo "KEYSTORE_PATH=$GITHUB_WORKSPACE/release.keystore" >> "$GITHUB_ENV"
echo "Release keystore configured from repository secrets."
