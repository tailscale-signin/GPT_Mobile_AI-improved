import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qualify_github_auth import check_asset_links, check_manifest


class PackagedAuthContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app"><application>
        <activity android:name=".GitHubAuthCallbackActivity" android:exported="true" android:enabled="true">
        <intent-filter android:autoVerify="true"><action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="https" android:host="auth.example.org" android:path="/oauth/github/complete"/>
        </intent-filter></activity></application></manifest>'''
        self.links = [{"relation": ["delegate_permission/common.handle_all_urls"], "target": {
            "namespace": "android_app", "package_name": "example.app", "sha256_cert_fingerprints": ["ab" * 32]}}]

    def test_packaged_return_link_is_distinct_from_broker_callback(self):
        self.assertEqual("example.app.GitHubAuthCallbackActivity", check_manifest(self.manifest, "example.app", "auth.example.org"))
        for altered in [self.manifest.replace("/complete", "/callback"), self.manifest.replace('android:path=', 'android:pathPrefix='),
                        self.manifest.replace('android:enabled="true"', 'android:enabled="false"'), self.manifest.replace('android:autoVerify="true"', 'android:autoVerify="false"')]:
            with self.assertRaises(ValueError):
                check_manifest(altered, "example.app", "auth.example.org")

    def test_digital_asset_links_require_the_actual_package_and_certificate(self):
        check_asset_links(self.links, "example.app", ":".join(["AB"] * 32))
        for app, cert in [("another.app", "ab" * 32), ("example.app", "cd" * 32)]:
            with self.assertRaises(ValueError):
                check_asset_links(self.links, app, cert)
        unrelated = copy.deepcopy(self.links)
        unrelated[0]["relation"] = ["unrelated"]
        with self.assertRaises(ValueError):
            check_asset_links(unrelated, "example.app", "ab" * 32)


if __name__ == "__main__":
    unittest.main()
