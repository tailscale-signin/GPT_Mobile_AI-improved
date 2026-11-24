import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('prepare_emulator_action', SCRIPTS / 'prepare_emulator_action.py')
emulator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(emulator)
FIXTURE = Path(__file__).parent / 'fixtures/android-emulator-runner-a421'

# Execute the actual pinned JavaScript module. Only its external command and
# timer boundaries are replaced; no Android readiness or device pass is claimed.
NODE_TRACE = r'''
const fs = require('node:fs');
const vm = require('node:vm');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const commands = [];
let polls = 0;
const actionExec = {exec: async (command, args, options) => {
  commands.push(command);
  if (command.includes('shell getprop sys.boot_completed')) {
    polls++;
    options.listeners.stdout(Buffer.from(input.bootNever ? '0' : polls > input.pendingPolls ? '1' : '0'));
  }
  if (input.rejectMenu && command.includes('shell input keyevent 82')) {
    throw new Error('Synthetic cold launcher input timeout');
  }
  return 0;
}};
const context = {
  exports: {},
  require: name => {
    if (name === '@actions/exec') return actionExec;
    if (name === 'fs') return {};
    throw new Error('Unexpected runtime dependency: ' + name);
  },
  console: {log: () => {}, warn: () => {}},
  process: {platform: 'linux', env: {ANDROID_HOME: '/verified/android-sdk'}},
  setTimeout: callback => callback(),
};
vm.runInNewContext(fs.readFileSync(input.module, 'utf8'), context, {timeout: 1000});
(async () => {
  let failure = null;
  try {
    await context.exports.launchEmulator('test', input.animations, false, input.spellchecker,
      input.bootNever ? 2 : 600, '-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot',
      input.keyboard, 5554);
    commands.push('custom-script-boundary');
  } catch (error) { failure = error.message; }
  await context.exports.killEmulator(5554);
  process.stdout.write(JSON.stringify({commands, polls, failure}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
'''


class PrepareEmulatorActionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.checkout = Path(self.temporary.name) / 'upstream'
        shutil.copytree(FIXTURE, self.checkout)
        (self.checkout / '.gitignore').write_text('ignored.tmp\n')
        self.git('init', '-q')
        self.commit()

    def git(self, *arguments):
        return subprocess.run(
            ['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
             '-c', 'user.name=Emulator fixture', '-c', 'user.email=emulator@example.invalid',
             '-C', str(self.checkout), *arguments],
            check=True, capture_output=True, text=True, timeout=10,
        ).stdout.strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-q', '-m', 'Synthetic checkout for Git boundary verification')
        self.commit_id = self.git('rev-parse', 'HEAD')
        self.tree_id = self.git('rev-parse', 'HEAD^{tree}')

    def bytes(self):
        return {relative: (self.checkout / relative).read_bytes() for relative, *_ in emulator.PATCHES}

    def qualified_checkout(self, checkout=None):
        # The fixture is a real clean Git checkout, but cannot reproduce the full
        # upstream commit graph. Only this Git identity boundary is substituted.
        with patch.object(emulator, 'UPSTREAM_COMMIT', self.commit_id), patch.object(emulator, 'UPSTREAM_TREE', self.tree_id):
            return emulator.patch_checkout(checkout or self.checkout)

    def trace(self, module, **options):
        payload = dict(module=str(module), pendingPolls=0, bootNever=False, rejectMenu=False,
                       animations=True, spellchecker=False, keyboard=False)
        payload.update(options)
        result = subprocess.run(['node', '-e', NODE_TRACE], input=json.dumps(payload),
                                text=True, capture_output=True, check=True, timeout=10)
        return json.loads(result.stdout)

    def test_pinned_fixture_digests_and_license_match_reviewed_upstream(self):
        provenance = json.loads((FIXTURE / 'PROVENANCE.json').read_text())
        self.assertEqual('a421e43855164a8197daf9d8d40fe71c6996bb0d', emulator.UPSTREAM_COMMIT)
        self.assertEqual('30f687b0945b18f77e56323c141e3646b6f246b0', emulator.UPSTREAM_TREE)
        self.assertEqual(emulator.UPSTREAM_COMMIT, provenance['commit'])
        self.assertEqual(emulator.UPSTREAM_TREE, provenance['tree'])
        for record in provenance['files']:
            self.assertEqual(record['sha256'], hashlib.sha256((FIXTURE / record['path']).read_bytes()).hexdigest())
        self.assertIn('Apache License', (FIXTURE / 'LICENSE').read_text())
        for relative, digest, _, _ in emulator.PATCHES:
            self.assertEqual(digest, hashlib.sha256((FIXTURE / relative).read_bytes()).hexdigest())

    def test_exact_patch_removes_only_one_line_and_leaves_other_upstream_bytes(self):
        before = self.bytes()
        retained = {name: (self.checkout / name).read_bytes() for name in ('LICENSE', 'action.yml', 'PROVENANCE.json')}
        report = self.qualified_checkout()
        self.assertEqual('PATCHED_SOURCE_ONLY', report['status'])
        self.assertFalse(report['emulator_execution_qualified'])
        for relative, _, after_digest, line in emulator.PATCHES:
            after = (self.checkout / relative).read_bytes()
            self.assertEqual(before[relative].replace(line, b'', 1), after)
            self.assertEqual(after_digest, hashlib.sha256(after).hexdigest())
        self.assertEqual(retained, {name: (self.checkout / name).read_bytes() for name in retained})
        self.assertEqual(['lib/emulator-manager.js', 'src/emulator-manager.ts'], self.git('diff', '--name-only').splitlines())

    def test_wrong_commit_refuses_without_writing_either_file(self):
        before = self.bytes()
        with self.assertRaisesRegex(ValueError, 'commit'):
            emulator.patch_checkout(self.checkout)
        self.assertEqual(before, self.bytes())

    def test_wrong_tree_refuses_without_writing_either_file(self):
        before = self.bytes()
        with patch.object(emulator, 'UPSTREAM_COMMIT', self.commit_id):
            with self.assertRaisesRegex(ValueError, 'tree'):
                emulator.patch_checkout(self.checkout)
        self.assertEqual(before, self.bytes())

    def test_dirty_tracked_checkout_refuses_without_writing_either_file(self):
        (self.checkout / 'LICENSE').write_text('unreviewed modification')
        before = self.bytes()
        with self.assertRaisesRegex(ValueError, 'clean'):
            self.qualified_checkout()
        self.assertEqual(before, self.bytes())

    def test_untracked_and_ignored_files_both_refuse_before_writes(self):
        for name in ('unexpected.tmp', 'ignored.tmp'):
            with self.subTest(name=name):
                extra = self.checkout / name
                extra.write_text('unexpected content')
                before = self.bytes()
                with self.assertRaisesRegex(ValueError, 'clean'):
                    self.qualified_checkout()
                self.assertEqual(before, self.bytes())
                extra.unlink()

    def test_missing_duplicate_and_changed_calls_refuse_even_in_a_clean_checkout(self):
        for index, replacement in enumerate((b'', b'duplicate', b'other')):
            with self.subTest(replacement=replacement):
                self.git('reset', '--hard', self.commit_id)
                path, _, _, line = emulator.PATCHES[1]
                target = self.checkout / path
                original = (FIXTURE / path).read_bytes()
                changed = original.replace(line, b'' if replacement == b'' else line * 2 if replacement == b'duplicate' else line.replace(b'82', b'83'))
                target.write_bytes(changed)
                self.commit()
                before = self.bytes()
                with self.assertRaisesRegex(ValueError, 'digest'):
                    self.qualified_checkout()
                self.assertEqual(before, self.bytes())

    def test_symlink_checkout_is_rejected_before_writes(self):
        alias = self.checkout.parent / 'alias'
        alias.symlink_to(self.checkout, target_is_directory=True)
        before = self.bytes()
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.qualified_checkout(alias)
        self.assertEqual(before, self.bytes())

    def test_nested_directory_cannot_patch_its_parent_checkout(self):
        before = self.bytes()
        with self.assertRaisesRegex(ValueError, 'own Git checkout'):
            self.qualified_checkout(self.checkout / 'src')
        self.assertEqual(before, self.bytes())

    def test_second_application_is_refused_without_additional_changes(self):
        self.qualified_checkout()
        after = self.bytes()
        with self.assertRaisesRegex(ValueError, 'clean'):
            self.qualified_checkout()
        self.assertEqual(after, self.bytes())

    def test_actual_runtime_avoids_the_captured_cold_launcher_input_failure(self):
        runtime = self.checkout / 'lib/emulator-manager.js'
        original = self.trace(runtime, rejectMenu=True)
        self.assertEqual('Synthetic cold launcher input timeout', original['failure'])
        self.assertNotIn('custom-script-boundary', original['commands'])
        self.qualified_checkout()
        patched = self.trace(runtime, rejectMenu=True, pendingPolls=2)
        self.assertIsNone(patched['failure'])
        self.assertEqual(3, patched['polls'])
        self.assertFalse(any('shell input' in command for command in patched['commands']))
        self.assertLess(patched['commands'].index('adb -s emulator-5554 shell settings put global animator_duration_scale 0.0'),
                        patched['commands'].index('custom-script-boundary'))
        self.assertEqual('adb -s emulator-5554 emu kill', patched['commands'][-1])

    def test_actual_runtime_retains_every_other_launch_setting_and_cleanup_command(self):
        runtime = self.checkout / 'lib/emulator-manager.js'
        original = self.trace(runtime, spellchecker=True, keyboard=True)
        self.qualified_checkout()
        patched = self.trace(runtime, spellchecker=True, keyboard=True)
        self.assertIsNone(original['failure'])
        self.assertIsNone(patched['failure'])
        self.assertEqual([command for command in original['commands'] if 'shell input keyevent 82' not in command], patched['commands'])
        self.assertIn('adb -s emulator-5554 shell settings put secure spell_checker_enabled 0', patched['commands'])
        self.assertIn('adb -s emulator-5554 shell settings put secure show_ime_with_hard_keyboard 0', patched['commands'])

    def test_actual_runtime_boot_timeout_still_refuses_custom_execution_and_cleans_up(self):
        self.qualified_checkout()
        observed = self.trace(self.checkout / 'lib/emulator-manager.js', bootNever=True)
        self.assertIn('Timeout', observed['failure'])
        self.assertNotIn('custom-script-boundary', observed['commands'])
        self.assertFalse(any('shell settings' in command or 'shell input' in command for command in observed['commands']))
        self.assertEqual('adb -s emulator-5554 emu kill', observed['commands'][-1])

    def test_actual_runtime_respects_disabled_optional_settings(self):
        self.qualified_checkout()
        observed = self.trace(self.checkout / 'lib/emulator-manager.js', animations=False)
        self.assertIsNone(observed['failure'])
        self.assertFalse(any('shell settings' in command or 'shell input' in command for command in observed['commands']))

    def test_workflow_verifies_ignored_dedicated_checkout_before_loading_local_action(self):
        root = SCRIPTS.parent
        workflow = (root / '.github/workflows/release-0950-acceptance.yml').read_text()
        steps = re.split(r'(?m)^      - ', workflow.split('\n    steps:\n', 1)[1])[1:]
        checkout = next(i for i, step in enumerate(steps) if 'repository: ReactiveCircus/android-emulator-runner' in step)
        prepare = next(i for i, step in enumerate(steps) if 'run: python3 scripts/prepare_emulator_action.py' in step)
        execute = next(i for i, step in enumerate(steps) if 'uses: ./build/emulator-action-upstream' in step)
        self.assertLess(checkout, prepare)
        self.assertLess(prepare, execute)
        self.assertIn('uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1', steps[checkout])
        self.assertIn('ref: ' + emulator.UPSTREAM_COMMIT, steps[checkout])
        self.assertIn('path: build/emulator-action-upstream', steps[checkout])
        self.assertIn('persist-credentials: false', steps[checkout])
        self.assertIn('/build', (root / '.gitignore').read_text().splitlines())
        for step in (steps[checkout], steps[prepare], steps[execute]):
            self.assertNotIn('continue-on-error:', step)
            self.assertNotIn('|| true', step)
        for text in ('api-level: ${{ matrix.api }}', 'profile: ${{ matrix.profile }}', 'cores: 4', 'ram-size: 4096M',
                     'arch: x86_64', 'target: ${{ matrix.target }}', 'disable-animations: true',
                     'emulator-options: -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot',
                     'script: ACCEPTANCE_EMULATOR_API=${{ matrix.api }} ACCEPTANCE_EMULATOR_TARGET=${{ matrix.target }} bash scripts/run_release0950_acceptance.sh'):
            self.assertIn(text, steps[execute])
        self.assertIn('    timeout-minutes: 60\n', workflow)
        self.assertRegex(workflow, r'(?m)^          - api: 36\n            profile: pixel_c\n            target: google_apis$')


if __name__ == '__main__':
    unittest.main()
