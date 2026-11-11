"""The Android decoder and live gateway route must use identical full fixtures."""
import unittest
from pathlib import Path


class GatewayCapabilityFixtureTests(unittest.TestCase):
    def test_android_resources_are_exact_copies_of_the_gateway_contract_fixtures(self):
        root = Path(__file__).resolve().parents[2]
        for name in ('gateway-v15-capabilities-full.json', 'gateway-v15-capabilities-legacy-mixed.json', 'gateway-v16-capabilities-full.json'):
            with self.subTest(fixture=name):
                self.assertEqual((root / 'gateway/contracts' / ('v16' if 'v16' in name else 'v15') / name).read_bytes(),
                                 (root / 'app/src/test/resources' / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
