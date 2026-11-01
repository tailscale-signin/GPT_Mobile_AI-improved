"""Build a reproducible complete distribution; run from a reviewed checkout."""
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path
from v14 import VERSION
import sys

root = Path(__file__).resolve().parent
source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, encoding="utf-8").strip()
# Include the characterized runtime, tests, fixtures, dependency lock and guides;
# never include databases, local configurations, credentials or generated logs.
# Restrict discovery to Git's source inventory. Generated distributions/configuration
# can live inside the checkout without entering a source or installer package.
tracked = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "gateway"], cwd=root.parent, encoding="utf-8").splitlines()
paths = sorted({root.parent / name for name in tracked if (root.parent / name).is_file()
    and ((root.parent / name).suffix in {".py", ".md", ".ps1", ".json", ".js", ".mjs", ".iss"} or (root.parent / name).name in {"requirements.txt", "requirements.in", "requirements-build.txt"})
    and (root.parent / name).name not in {"manifest-v14.json", "gateway_settings.json", "settings.json", "mcp_config.json"}})

base_commit = "a66155dfdc0cf647dd368019f605d91689d48583"
manifest = {"baseCommit": base_commit, "version": VERSION, "schemaVersion": 1, "sourceCommit": source_commit,
            "files": {str(p.relative_to(root)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}}
(root / "manifest-v14.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
from v14.package import validate_package
validate_package(root)
if '--manifest-only' in sys.argv:
    print('Validated source manifest for ' + VERSION)
    sys.exit(0)
out = root.parent / ("Gateway_v" + VERSION.replace('.', '_') + "_Implementation.zip")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(paths) + [root / "manifest-v14.json"]:
        archive.write(p, "gateway/" + str(p.relative_to(root)).replace('\\', '/'))
    android_patch = subprocess.check_output(["git", "diff", base_commit, source_commit, "--", "app"], cwd=root.parent, encoding="utf-8")
    archive.writestr("android/Gateway_v14_Android_Compatibility.patch", android_patch)
print(out)
