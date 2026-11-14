"""Validate a complete versioned package before imports or installation."""
import hashlib
import json
from pathlib import Path
from v14 import VERSION

REQUIRED = {"gateway.py", "gateway_v14.py", "gateway_v13.py", "gateway_v13_runtime.py", "gateway_v13_transport.py", "gateway_security.py", "gateway_multisearch.py", "requirements.txt", "v14/__init__.py", "v14/config.py", "v14/identity.py", "v14/package.py", "v14/research.py", "v14/jobs.py", "v14/memory.py", "v14/tools.py", "v14/browser.py", "v14/browser-bootstrap.js"}
REQUIRED.update({'v14/journal.py', 'v14/service.py', 'v14/mcp.py', 'v14/health.py', 'v14/contracts.py', 'v14/actions.py', 'v14/catalog.py', 'v14/results.py', 'v14/operations.py', 'v14/github.py', 'v14/batch.py', 'v14/engineering.py', 'v14/engineering_backend.py', 'v14/engineering_routes.py'})
REQUIRED.update({'v14/evidence.py', 'v14/engineering_diagnostics.py'})
REQUIRED.update({'v14/engineering_memory.py', 'v14/engineering_crypto.py'})
REQUIRED.update({'v14/engineering_backup.py', 'v14/maintenance.py', 'v14/debug.py'})
REQUIRED.update({'v14/repository_lifecycle.py', 'v14/adaptive_sampling.py'})
REQUIRED.update({'v14/engineering_batch_plan.py', 'v14/engineering_batch_policy.py', 'v14/github_api_governor.py'})
REQUIRED.update({'v14/conversation_metadata.py', 'benchmarks/__init__.py', 'benchmarks/service.py', 'benchmarks/contracts.py', 'benchmarks/store.py', 'benchmarks/runner.py', 'benchmarks/fixtures.py', 'benchmarks/capstones.py', 'benchmarks/coding.py', 'benchmarks/sandbox.py', 'benchmarks/settings.py'})
REQUIRED.update({'v14/model_preparation.py', 'v14/model_preparation_routes.py', 'v14/inference_capabilities.py'})


def validate_package(root):
    root = Path(root).resolve()
    manifest = json.loads((root / "manifest-v14.json").read_text(encoding="utf-8"))
    hashes = manifest.get("files", {})
    if manifest.get("version") != VERSION or not REQUIRED <= hashes.keys():
        raise ValueError("Incomplete or incompatible v14 manifest")
    for relative, expected in hashes.items():
        path = root / relative
        if Path(relative).is_absolute() or ".." in Path(relative).parts or path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Unsafe package path")
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Package hash mismatch: " + relative)
    return manifest
