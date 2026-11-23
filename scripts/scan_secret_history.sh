#!/usr/bin/env bash
# Full-history PR gate. The Linux CI installer verifies the published archive.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
if [[ $(git rev-parse --is-shallow-repository) != false ]]; then
  echo 'Full history required. Fetch the complete repository before scanning.' >&2
  exit 2
fi
python3 scripts/scan_secrets.py --history-paths
version=8.30.1
if command -v gitleaks >/dev/null && [[ $(gitleaks version) == "$version" ]]; then
  scanner=$(command -v gitleaks)
else
  [[ $(uname -sm) == 'Linux x86_64' ]] || { echo "Install Gitleaks $version for this host." >&2; exit 2; }
  scratch=$(mktemp -d)
  trap 'rm -rf -- "$scratch"' EXIT
  curl --fail --silent --show-error --location --retry 3 --max-time 120 \
    "https://github.com/gitleaks/gitleaks/releases/download/v$version/gitleaks_${version}_linux_x64.tar.gz" -o "$scratch/scanner.tgz"
  echo "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb  $scratch/scanner.tgz" | sha256sum --check --status
  tar -xzf "$scratch/scanner.tgz" -C "$scratch" gitleaks
  scanner="$scratch/gitleaks"
fi
python3 scripts/test_gitleaks_model_pin.py --scanner "$scanner"
"$scanner" git --redact=100 --no-banner --no-color --log-opts='--all --full-history --diff-merges=first-parent --root' .
