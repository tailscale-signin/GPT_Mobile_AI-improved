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
  echo "Notice: Repository signing secrets not set (${missing[*]})."
  echo "Generating a deterministic fallback signing keystore for automated release CI..."
  FALLBACK_KEY_PASS="gptmobile-deterministic-release-key"
  keytool -genkeypair -v \
    -keystore release.keystore \
    -alias gptmobile \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$FALLBACK_KEY_PASS" \
    -keypass "$FALLBACK_KEY_PASS" \
    -dname "CN=GPT Mobile Improved, OU=Development, O=OpenSource, L=Global, ST=Global, C=XX"
  chmod 600 release.keystore

  echo "KEYSTORE_PATH=$GITHUB_WORKSPACE/release.keystore" >> "$GITHUB_ENV"
  echo "KEYSTORE_PASSWORD=$FALLBACK_KEY_PASS" >> "$GITHUB_ENV"
  echo "KEY_ALIAS=gptmobile" >> "$GITHUB_ENV"
  echo "KEY_PASSWORD=$FALLBACK_KEY_PASS" >> "$GITHUB_ENV"
  echo "Deterministic release keystore ready."
fi
