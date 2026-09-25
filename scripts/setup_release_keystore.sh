#!/usr/bin/env bash
set -euo pipefail

required=(KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD)
missing=()
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done

if (( ${#missing[@]} == 0 )); then
  echo "Using release keystore configured from repository secrets."
  printf '%s' "$KEYSTORE_BASE64" | base64 --decode > release.keystore
  chmod 600 release.keystore

  # Validate credentials before spending time on a release build.
  keytool -list \
    -keystore release.keystore \
    -storepass "$KEYSTORE_PASSWORD" \
    -alias "$KEY_ALIAS" >/dev/null

  echo "KEYSTORE_PATH=$GITHUB_WORKSPACE/release.keystore" >> "$GITHUB_ENV"
  echo "KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD" >> "$GITHUB_ENV"
  echo "KEY_ALIAS=$KEY_ALIAS" >> "$GITHUB_ENV"
  echo "KEY_PASSWORD=$KEY_PASSWORD" >> "$GITHUB_ENV"
  echo "Release keystore validated and exported."
else
  echo "::error::Missing release signing secrets: ${missing[*]}. Configure the existing release key before publishing."
  echo "A replacement key is not generated because it would prevent upgrades of existing installations."
  exit 1
fi
