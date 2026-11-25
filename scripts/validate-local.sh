#!/usr/bin/env bash
# Fast host-only checks, or full Android validation with JDK 21 + Android SDK 37.
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-full}"
case "$mode" in fast|full) ;; *) echo 'Usage: bash scripts/validate-local.sh [fast|full]' >&2; exit 2 ;; esac
python3 scripts/check_ci_policy.py
python3 scripts/generate_architecture_index.py --check
python3 scripts/sync_agent_metadata.py --check
python3 scripts/generate_repository_reference.py --check
python3 scripts/generate_diagnostic_schema.py --check
python3 scripts/generate_app_audit_inventory.py --check
python3 scripts/check_dependency_catalog.py
PYTHONPATH=gateway python3 -c "from pathlib import Path; from v14.package import validate_package; validate_package(Path('gateway'))"
python3 scripts/scan_secrets.py
python3 -m unittest discover -s scripts/tests
python3 scripts/check_android_regex.py
bash scripts/validate.sh resources
git diff --check
if [[ "$mode" == full ]]; then
    python3 -m pip install --require-hashes -r gateway/requirements.txt
    python3 gateway/check_gateway.py
    python3 -m unittest discover -s authentication -t . -p 'test_*.py' -v
    bash scripts/validate.sh all
fi
