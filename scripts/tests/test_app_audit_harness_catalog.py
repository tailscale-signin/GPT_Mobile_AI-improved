"""Keep exported qualification instructions tied to existing, concrete suites."""
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AppAuditHarnessCatalogTest(unittest.TestCase):
    def test_every_inventoried_area_maps_to_existing_harness_files(self):
        catalog = (ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditHarnessCatalog.kt").read_text()
        entries = re.findall(r'"([a-z_]+)" to listOf\((.*?)\n    \)', catalog, re.S)
        mapping = {area: re.findall(r'"([^"\n]+)"', paths) for area, paths in entries}
        inventory = json.loads((ROOT / "app/src/main/assets/diagnostics/app-code-inventory.json").read_text())
        self.assertEqual({area["id"] for area in inventory["areas"]}, set(mapping))
        for area, paths in mapping.items():
            with self.subTest(area=area):
                self.assertTrue(paths)
                for path in paths:
                    self.assertFalse(Path(path).is_absolute())
                    self.assertNotIn("..", Path(path).parts)
                    self.assertTrue((ROOT / path).is_file(), path)
                    self.assertTrue(path.endswith("Test.kt") or path.endswith(".py"), path)


if __name__ == "__main__":
    unittest.main()
