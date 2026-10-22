"""Host test of the same batch governor compiled into both Android ABIs."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class GgufPrefillGovernorTest(unittest.TestCase):
    def test_actual_governor_bounds_growth_and_cancellation_windows(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = pathlib.Path(directory) / "governor"
            subprocess.run([
                "c++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                "-I", str(ROOT / "local-gguf-runtime/src/main/cpp"),
                str(ROOT / "local-gguf-runtime/src/testHost/gguf_prefill_governor_test.cpp"),
                "-o", str(binary),
            ], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
