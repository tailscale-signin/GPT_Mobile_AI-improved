#!/usr/bin/env python3
"""Check configuration-cache reuse for our model-packaging and portable-domain tasks.

Full Android builds retain their documented ObjectBox plugin cache restriction.
"""
from pathlib import Path
import subprocess

command = ["./gradlew", ":app:prepareMemoryModel", ":core-domain:jvmTest", "--configuration-cache", "--configuration-cache-problems=fail", "--console=plain", "--no-daemon"]
destination = Path("build/reports/configuration-cache-verification")
destination.mkdir(parents=True, exist_ok=True)
for attempt in (1, 2):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (destination / f"attempt-{attempt}.log").write_text(result.stdout)
    print(result.stdout)
    if result.returncode:
        raise SystemExit(result.returncode)
    if attempt == 2 and "Reusing configuration cache" not in result.stdout and "Configuration cache entry reused" not in result.stdout:
        raise SystemExit("The second packaging/domain build did not reuse its configuration cache.")
