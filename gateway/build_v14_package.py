"""Build a reproducible complete distribution; run from a reviewed checkout."""
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
# Include the characterized runtime, tests, fixtures, dependency lock and guides;
# never include databases, local configurations, credentials or generated logs.
paths = [p for p in root.rglob("*") if p.is_file() and (p.suffix in {".py", ".md", ".ps1", ".json", ".js", ".mjs"} or p.name in {"requirements.txt", "requirements.in"}) and "__pycache__" not in p.parts and p.name not in {"manifest-v14.json", "gateway_settings.json", "mcp_config.json"}]
base_commit = "a66155dfdc0cf647dd368019f605d91689d48583"
manifest = {"baseCommit": base_commit, "version": "14.0.0", "schemaVersion": 1, "sourceCommit": source_commit,
            "files": {str(p.relative_to(root)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}}
(root / "manifest-v14.json").write_text(json.dumps(manifest, indent=2) + "\n")
from v14.package import validate_package
validate_package(root)
out = root.parent / "Gateway_v14_Implementation.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(paths) + [root / "manifest-v14.json"]:
        archive.write(p, "gateway/" + str(p.relative_to(root)).replace('\\', '/'))
    android_patch = subprocess.check_output(["git", "diff", base_commit, source_commit, "--", "app"], cwd=root.parent, text=True)
    archive.writestr("android/Gateway_v14_Android_Compatibility.patch", android_patch)
print(out)
