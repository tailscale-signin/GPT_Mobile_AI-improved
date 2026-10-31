import unittest

from scripts.check_ci_policy import check_text


class AndroidModelProvisioningPolicyTest(unittest.TestCase):
    def workflow(self, steps, runner='ubuntu-latest'):
        return f'''permissions:
  contents: read
concurrency:
  group: test
jobs:
  build:
    runs-on: {runner}
    timeout-minutes: 30
    steps:
{steps}
'''

    def check(self, steps, runner='ubuntu-latest'):
        return check_text(self.workflow(steps, runner), '.github/workflows/test.yml')

    def test_manual_android_job_requires_provisioning_before_build(self):
        setup = '      - uses: android-actions/setup-android@' + 'a' * 40 + '\n'
        build = '      - run: ./gradlew :app:testDebugUnitTest\n'
        provision = '      - run: python3 scripts/provision_memory_model.py\n'
        self.assertTrue(self.check(setup + build))
        self.assertTrue(self.check(setup + build + provision))
        self.assertEqual([], self.check(setup + provision + build))

    def test_physical_device_and_composite_setup_paths(self):
        build = '      - run: ./gradlew :app:connectedDebugAndroidTest\n'
        self.assertTrue(self.check(build, '[self-hosted, android-device]'))
        self.assertEqual([], self.check('      - run: python3 scripts/provision_memory_model.py\n' + build, '[self-hosted, android-device]'))
        self.assertEqual([], self.check('      - uses: ./.github/actions/setup-android\n' + build))

    def test_provisioning_in_another_job_does_not_mask_missing_asset(self):
        first = '      - run: python3 scripts/provision_memory_model.py\n'
        workflow = self.workflow(first) + '''  tests:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: android-actions/setup-android@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
      - run: ./gradlew :app:testDebugUnitTest
'''
        self.assertTrue(check_text(workflow, '.github/workflows/test.yml'))
