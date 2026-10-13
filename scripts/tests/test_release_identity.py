from pathlib import Path
import importlib.util
import unittest

spec = importlib.util.spec_from_file_location(
    "release_identity", Path(__file__).resolve().parents[1] / "release_identity.py"
)
release_identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_identity)


class ReleaseIdentityTest(unittest.TestCase):
    def test_build_tools_version_follows_the_application(self):
        for version in ("37.0.0", "38.0.0"):
            with self.subTest(version=version):
                self.assertEqual(version, release_identity.release_build_tools_version(f'buildToolsVersion = "{version}"'))

    def test_missing_build_tools_version_fails_clearly(self):
        with self.assertRaisesRegex(ValueError, "Could not resolve buildToolsVersion"):
            release_identity.release_build_tools_version("android { }")

    def test_conditional_uses_regular_release_application_id(self):
        build = 'applicationId = if (geniexRuntime) "dev.example.preview" else "dev.example"'
        self.assertEqual("dev.example", release_identity.release_application_id(build))

    def test_literal_application_id_is_supported(self):
        self.assertEqual("dev.example", release_identity.release_application_id('applicationId = "dev.example"'))

    def test_missing_application_id_fails_clearly(self):
        with self.assertRaisesRegex(ValueError, "Could not resolve applicationId"):
            release_identity.release_application_id("defaultConfig { } ")


if __name__ == "__main__":
    unittest.main()
