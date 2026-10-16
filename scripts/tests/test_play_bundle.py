"""Offline fixtures for Google Play AAB compatibility checks."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location(
    "check_play_bundle", Path(__file__).resolve().parents[1] / "check_play_bundle.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def fake_elf(machine, align=16384):
    image = bytearray(128)
    image[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", image, 18, machine)
    struct.pack_into("<Q", image, 32, 64)
    struct.pack_into("<HH", image, 54, 56, 1)
    struct.pack_into("<I", image, 64, 1)
    struct.pack_into("<Q", image, 64 + 48, align)
    return bytes(image)


class PlayBundlePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / "app.aab"

    def write_bundle(self, arm=None, x86=None):
        with ZipFile(self.bundle, "w") as archive:
            archive.writestr("BundleConfig.pb", b"bundle")
            archive.writestr("base/manifest/AndroidManifest.xml", b"manifest")
            if arm is not None:
                archive.writestr("base/lib/arm64-v8a/libtest.so", arm)
            if x86 is not None:
                archive.writestr("base/lib/x86_64/libtest.so", x86)

    def test_accepts_both_64_bit_abis(self):
        self.write_bundle(fake_elf(183), fake_elf(62))
        checker.check_bundle(self.bundle)

    def test_rejects_4kb_native_library(self):
        self.write_bundle(fake_elf(183, 4096), fake_elf(62))
        with self.assertRaisesRegex(ValueError, "less than 16 KB"):
            checker.check_bundle(self.bundle)

    def test_rejects_missing_architecture(self):
        self.write_bundle(fake_elf(183))
        with self.assertRaisesRegex(ValueError, "expected arm64-v8a"):
            checker.check_bundle(self.bundle)

    def test_skips_only_qualcomm_dsp_skeletons(self):
        self.assertFalse(checker.check_library(
            "base/lib/arm64-v8a/libQnnHtpV79Skel.so", fake_elf(164), "arm64-v8a"
        ))
        with self.assertRaisesRegex(ValueError, "unexpected host machine"):
            checker.check_library(
                "base/lib/arm64-v8a/libwrong.so", fake_elf(164), "arm64-v8a"
            )

    def test_checks_target_and_identity(self):
        content = (
            "compileSdk = 37\nminSdk = 31\ntargetSdk = 37\n"
            'versionCode = 122\nversionName = "0.9.53.0"\n'
            'applicationId = if (geniexRuntime) "dev.melo.gptmobile.improved.geniex" '
            'else "dev.melo.gptmobile.improved"\nuseLegacyPackaging = true\n'
        )
        build = Path(self.temp.name) / "build.gradle.kts"
        build.write_text(content)
        checker.check_gradle_config(build)
        build.write_text(content.replace("targetSdk = 37", "targetSdk = 35"))
        with self.assertRaisesRegex(ValueError, "Google Play requires"):
            checker.check_gradle_config(build)


if __name__ == "__main__":
    unittest.main()
