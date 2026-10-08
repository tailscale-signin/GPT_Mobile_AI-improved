"""Guard the offline font packaging that MathJax v4 needs on Android."""
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class MathJaxAssetsTest(unittest.TestCase):
    def test_dynamic_font_data_is_bundled_and_rendering_is_async(self):
        fonts = ROOT / "app/src/main/assets/mathjax/fonts/newcm"
        version = json.loads((fonts / "package.json").read_text())["version"]
        self.assertEqual("4.1.2", version)
        self.assertTrue((fonts / "LICENSE").is_file())
        for name in ("shapes", "arrows", "calligraphic", "symbols", "variants"):
            self.assertGreater((fonts / "svg/dynamic" / f"{name}.js").stat().st_size, 100)
        view = (ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/MathJaxView.kt").read_text()
        self.assertIn("dynamicPrefix: 'file:///android_asset/mathjax/fonts/newcm/svg/dynamic'", view)
        self.assertIn("MathJax.tex2svgPromise(expression", view)
        self.assertNotIn("MathJax.tex2svg(expression", view)
