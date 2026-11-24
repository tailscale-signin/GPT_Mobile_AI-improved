"""Exercise automatic release selection through its actual Git/Actions interface."""

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_release_trigger.py"
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

    def run_trigger(self, event="push", before=None, ref="refs/heads/main"):
        output = self.root / "outputs"
        env = {
            **os.environ,
            "GITHUB_EVENT_NAME": event,
            "GITHUB_REF": ref,
            "PUSH_BEFORE_SHA": self.before if before is None else before,
            "GITHUB_OUTPUT": str(output),
        }
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=self.root, env=env,
            capture_output=True, text=True
        )
        contents = output.read_text() if output.exists() else ""
        return result, contents

    def test_reviewed_version_pair_triggers_once_on_main_and_requires_both_versions(self):
        gateway = self.root / "gateway/v14/__init__.py"
        gateway.parent.mkdir(parents=True)
        gateway.write_text('VERSION = "17.0.1"\n')
        scope = self.root / "release-scope.json"
        request = dict(android="0.9.53.0", versionCode=122, gateway="17.0.1")
        manifest = dict(version="0.9.53.0", versionCode=122, publicationRequest=request)
        scope.write_text(json.dumps(manifest))
        result, output = self.run_trigger(ref="refs/heads/feature")
        self.assertEqual((0, "publish=false\n"), (result.returncode, output))
        (self.root / "outputs").unlink()
        result, output = self.run_trigger()
        self.assertEqual((0, "publish=true\npublish_gateway=true\n"), (result.returncode, output))
        self.git("add", "release-scope.json")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Reviewed pair")
        before = self.git("rev-parse", "HEAD").strip()
        (self.root / "outputs").unlink()
        scope.write_text(json.dumps(dict(manifest, description="Editor update")))
        result, output = self.run_trigger(before=before)
        self.assertEqual((0, "publish=false\n"), (result.returncode, output))
        (self.root / "outputs").unlink()
        request["gateway"] = "17.0.2"
        scope.write_text(json.dumps(manifest))
        result, output = self.run_trigger(before=before)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", output)

    def test_gpu_configuration_change_does_not_republish_existing_version(self):
        self.config.write_text(BASE_CONFIG + 'val ggufVulkan = true\n', encoding="utf-8")
        result, output = self.run_trigger()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("publish=false\n", output)

    def test_unsigned_pr_builds_version_or_joint_release_changes_on_any_branch(self):
        output = self.root / "pr-output"
        def selected():
            output.write_text("")
            subprocess.run([sys.executable, str(SCRIPT), "--unsigned-pr", self.before], cwd=self.root,
                           env={**os.environ, "GITHUB_OUTPUT": str(output)}, check=True)
            return output.read_text()
        self.assertEqual("build=false\n", selected())
        self.config.write_text(BASE_CONFIG + 'ndkVersion = "29.0.14206865"\n')
        self.assertEqual("build=true\n", selected())
        self.config.write_text(BASE_CONFIG)
        for path in ("scripts/check_apk_size.py", "local-kokoro-runtime/src/main/cpp/CMakeLists.txt",
                     "scripts/optimize_release_apks.py", "scripts/check_local_runtime_apk.py", "scripts/install_android_native_dependencies.sh"):
            with self.subTest(packaging_input=path):
                source = self.root / path
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("fixture\n")
                self.git("add", path)
                self.assertEqual("build=true\n", selected())
                self.git("rm", "-q", "-f", "--", path)
        self.config.write_text(BASE_CONFIG.replace("122", "123"))
        self.assertEqual("build=true\n", selected())
        self.config.write_text(BASE_CONFIG)
        (self.root / "release-scope.json").write_text(json.dumps({"publicationRequest": {"versionCode": 122}}))
        self.assertEqual("build=true\n", selected())

    def test_android_successor_requests_both_publications_with_unchanged_gateway(self):
        gateway = self.root / "gateway/v14/__init__.py"
        gateway.parent.mkdir(parents=True)
        gateway.write_text('VERSION = "18.0.0"\n', encoding="utf-8")
        previous_config = 'android {\n    versionCode = 143\n    versionName = "0.9.66.9"\n}\n'
        self.config.write_text(previous_config, encoding="utf-8")
        scope = self.root / "release-scope.json"
        manifest = dict(version="0.9.66.9", versionCode=143,
                        publicationRequest=dict(android="0.9.66.9", versionCode=143, gateway="18.0.0"))
        scope.write_text(json.dumps(manifest), encoding="utf-8")
        self.git("add", "app/build.gradle.kts", "gateway/v14/__init__.py", "release-scope.json")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "Previous coordinated request")
        before = self.git("rev-parse", "HEAD").strip()

        self.config.write_text(previous_config.replace("143", "144").replace("0.9.66.9", "0.9.66.10"),
                               encoding="utf-8")
        manifest.update(version="0.9.66.10", versionCode=144,
                        publicationRequest=dict(android="0.9.66.10", versionCode=144, gateway="18.0.0"))
        scope.write_text(json.dumps(manifest), encoding="utf-8")
        result, output = self.run_trigger(before=before)
        self.assertEqual((0, "publish=true\npublish_gateway=true\n"),
                         (result.returncode, output), result.stderr)

        self.git("add", "app/build.gradle.kts", "release-scope.json")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "Successor coordinated request")
        (self.root / "outputs").unlink()
        scope.write_text(json.dumps(dict(manifest, description="Release notes correction")), encoding="utf-8")
        result, output = self.run_trigger(before=self.git("rev-parse", "HEAD").strip())
        self.assertEqual((0, "publish=false\n"), (result.returncode, output), result.stderr)

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


class ReleaseWorkflowCoverageTests(unittest.TestCase):
    def test_each_publisher_input_triggers_every_required_main_workflow(self):
        def module(name, relative):
            spec = importlib.util.spec_from_file_location(name, ROOT / relative)
            value = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(value)
            return value

        workflows = module("release_workflow_filters", "gateway/v14/engineering_workflows.py")
        checks = module("release_required_checks", "scripts/await_release_checks.py")
        manifest = json.loads((ROOT / "release-scope.json").read_text(encoding="utf-8"))
        required = checks.required_workflows(manifest)
        sources = {}
        for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
            source = path.read_text(encoding="utf-8")
            match = re.search(r"^name:\s*(.+)$", source, re.MULTILINE)
            self.assertIsNotNone(match, str(path))
            name = workflows.literal(match[1])
            self.assertNotIn(name, sources, f"Ambiguous workflow name: {name}")
            sources[name] = source

        publisher = workflows.push_filters(sources["Publish Signed Release"])
        release_inputs = ["app/build.gradle.kts", "release-scope.json"]
        self.assertEqual(release_inputs, publisher["paths"])
        recovery = workflows.push_filters(sources["Recover verified Android 144 and Gateway 18 publication"])
        self.assertTrue(recovery["paths"], "The recovery publisher needs explicit main push inputs")
        for changed_path in release_inputs + recovery["paths"]:
            expected_publisher = publisher if changed_path in release_inputs else recovery
            self.assertTrue(workflows.applies(expected_publisher, "main", [changed_path]))
            for name in required:
                with self.subTest(workflow=name, changed_path=changed_path):
                    self.assertIn(name, sources)
                    filters = workflows.push_filters(sources[name])
                    self.assertTrue(workflows.applies(filters, "main", [changed_path]),
                                    f"{name} must run on the same main commit as the publisher")


if __name__ == "__main__":
    unittest.main()
