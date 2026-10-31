#!/usr/bin/env python3
"""Prevent versioned module declarations from drifting out of the shared catalog."""
import re
import subprocess
from pathlib import Path

pattern = re.compile(r'''\b(?:implementation|api|debugImplementation|releaseImplementation|testImplementation|androidTestImplementation|ksp|kapt|runtimeOnly|compileOnly|classpath)\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']''')
errors = []
for filename in subprocess.check_output(["git", "ls-files", "--", "*.gradle.kts"], text=True).splitlines():
    for number, line in enumerate(Path(filename).read_text().splitlines(), 1):
        if pattern.search(line) and not line.lstrip().startswith("//"):
            errors.append(f"{filename}:{number}: move the versioned dependency to gradle/libs.versions.toml")
if errors:
    raise SystemExit("\n".join(errors))
print("Dependency declarations use the shared version catalog.")
