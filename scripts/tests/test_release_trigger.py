"""Exercise automatic release selection through its actual Git/Actions interface."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_release_trigger.py"
BASE_CONFIG = 'android {\n    versionCode = 122\n    versionName = "0.9.53.0"\n}\n'


class ReleaseTriggerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / "app/build.gradle.kts"
        self.config.parent.mkdir()
        self.config.write_text(BASE_CONFIG, encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "app/build.gradle.kts")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "Initial version")
        self.before = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.root, text=True, stderr=subprocess.STDOUT
        )

    def run_trigger(self, event="push", before=None):
        output = self.root / "outputs"
        env = {
            **os.environ,
            "GITHUB_EVENT_NAME": event,
            "PUSH_BEFORE_SHA": self.before if before is None else before,
            "GITHUB_OUTPUT": str(output),
        }
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=self.root, env=env,
            capture_output=True, text=True
        )
        contents = output.read_text() if output.exists() else ""
        return result, contents

    def test_gpu_configuration_change_does_not_republish_existing_version(self):
        self.config.write_text(BASE_CONFIG + 'val ggufVulkan = true\n', encoding="utf-8")
        result, output = self.run_trigger()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("publish=false\n", output)

    def test_either_version_field_change_requires_full_release_validation(self):
        for config in (
            BASE_CONFIG.replace("0.9.53.0", "0.9.54.0"),
            BASE_CONFIG.replace("122", "123"),
        ):
            with self.subTest(config=config):
                self.config.write_text(config, encoding="utf-8")
                (self.root / "outputs").unlink(missing_ok=True)
                result, output = self.run_trigger()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("publish=true\n", output)

    def test_manual_dispatch_keeps_existing_duplicate_release_validation(self):
        result, output = self.run_trigger(event="workflow_dispatch", before="")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("publish=true\n", output)

    def test_initial_branch_push_requires_release_validation(self):
        result, output = self.run_trigger(before="0" * 40)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("publish=true\n", output)

    def test_missing_or_invalid_base_fails_without_suppressing_validation(self):
        for before in ("", "--help", "f" * 40):
            with self.subTest(before=before):
                result, output = self.run_trigger(before=before)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", output)

    def test_malformed_or_ambiguous_version_fails_closed(self):
        for config in (
            BASE_CONFIG.replace('versionName = "0.9.53.0"', "versionName = versionProvider.get()"),
            BASE_CONFIG + 'versionCode = 123\n',
        ):
            with self.subTest(config=config):
                self.config.write_text(config, encoding="utf-8")
                result, output = self.run_trigger()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", output)

    def test_unsupported_event_fails_closed(self):
        result, output = self.run_trigger(event="pull_request")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
