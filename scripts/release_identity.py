"""Helpers for reading identity values from the Android Gradle build script."""
import re


def release_application_id(build_script):
    """Resolve the application ID used by the regular release variant."""
    conditional = re.search(
        r'applicationId\s*=\s*if\s*\([^)]*\)\s*"[^"]+"\s*else\s*"([^"]+)"',
        build_script,
    )
    if conditional:
        return conditional.group(1)
    literal = re.search(r'applicationId\s*=\s*"([^"]+)"', build_script)
    if literal:
        return literal.group(1)
    raise ValueError("Could not resolve applicationId from app/build.gradle.kts")
