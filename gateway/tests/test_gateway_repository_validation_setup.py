"""Managed settings regressions; external downloads/daemon are mocked, not claimed live."""
import copy
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import repository_validation as setup
from desktop.configuration import atomic_json, environment, validate
from desktop.validation_policy import (
    SETTING, PolicyConflictError, load_policy_snapshot, save_policy_path, save_policy_snapshot, selected_policy_path,
)
from v14.engineering_validation import load_policy, run_bounded
from v14.state_lock import StateDirectoryAlreadyOwned, StateDirectoryLock
from v14 import validation_host

PIN = 'python@sha256:' + 'a' * 64  # Test-only registry receipt, never a shipped default.


class ManagedValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {SETTING: ''})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)
        self.options = setup.defaults(self.root)
        self.options['mirror'] = str(self.root / 'mirror.git')

    def entry(self, **overrides):
        value = dict(mirror=self.options['mirror'], image=PIN, commands=copy.deepcopy(setup.SOURCE_COMMANDS),
                     timeout_seconds=600, acceptance_map=[], allowed_checks=['Required verification'])
        value.update(overrides)
        return value

    def existing(self, document):
        external = self.root / 'legacy.json'
        atomic_json(external, document)
        save_policy_path(self.root, str(external))
        return external

    def test_optional_workspace_tools_roundtrip_exact_host_permissions(self):
        options = copy.deepcopy(self.options)
        options['workspace_integrations'] = {'context7': {'enabled': True, 'allow_remote': True,
            'allow_mutations': False, 'tools': ['mcp_context7_query_docs']}}
        saved = setup.entry_from_options(options)
        self.assertEqual(options['workspace_integrations'], saved['workspace_integrations'])
        loaded = setup.options_for(self.root, options['repository'], {options['repository']: saved})
        self.assertEqual(saved['workspace_integrations'], loaded['workspace_integrations'])
        options['workspace_integrations'] = {}
        self.assertEqual({}, setup.entry_from_options(options, saved)['workspace_integrations'])
        for invalid in ({'unknown': {}}, {'context7': {'enabled': True, 'endpoint': 'https://unapproved.invalid'}},
                        {'playwright': {'tools': ['arbitrary tool'], 'allow_remote': True}}):
            with self.assertRaises(ValueError):
                setup.entry_from_options(dict(options, workspace_integrations=invalid))

    def prepare(self, options=None, revision=None, **kwargs):
        with ExitStack() as stack:
            for name, result in [('executable', '/fixture/tool'), ('check_docker', None),
                                 ('prepare_mirror', 'b' * 40), ('check_mirror', 'b' * 40), ('pin_image', PIN)]:
                stack.enter_context(patch.object(setup, name, return_value=result))
            return setup.setup(self.root, options or self.options,
                               revision or setup.read_settings(self.root)[1], prepare=kwargs.pop('prepare', True), **kwargs)

    def test_defaults_have_real_image_source_no_fake_digest_or_task_acceptance(self):
        value = setup.validate_options(self.options)
        self.assertEqual('docker.io/library/python:3.12-slim-bookworm', value['image_source'])
        self.assertEqual('', value['image'])
        self.assertEqual([], value['acceptance_map'])
        self.assertEqual(['Required verification', 'Analyze (java-kotlin)'], value['allowed_checks'])
        self.assertEqual(3, len(value['commands']))
        self.assertFalse(any('pip' in command or 'git' in command for command in value['commands']))

    def test_repository_url_and_case_are_normalized(self):
        for name in (' tailscale-signin/GPT_Mobile_AI-improved ',
                     'https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git'):
            self.assertEqual(setup.DEFAULT_REPOSITORY, setup.repository_name(name))
        for name in ('owner/../repo', 'https://github.com.evil.test/o/r', 'https://token@github.com/o/r',
                     'https://github.com/o/r?token=secret', '-option/repo', 'o/r\nx/y', None):
            with self.subTest(name=name), self.assertRaises(ValueError):
                setup.repository_name(name)

    def test_mirror_paths_are_absolute_and_never_links(self):
        for name in ('relative.git', str(self.root.anchor), str(self.root / '..' / 'escape'), ''):
            with self.subTest(name=name), self.assertRaises(ValueError):
                setup.mirror_path(name)
        self.assertEqual(self.root / 'safe ü.git', setup.mirror_path(str(self.root / 'safe ü.git')))

    def test_invalid_fields_stop_before_external_commands(self):
        for change in ({'timeout_seconds': True}, {'timeout_seconds': 1801}, {'commands': []},
                       {'commands': [['python', '\x00']]}, {'image_source': 'python'},
                       {'image_source': '--privileged'}, {'allowed_checks': ['']}, {'acceptance_map': {}}):
            options = dict(self.options, profile=setup.CUSTOM_PROFILE, **change)
            with self.subTest(change=change), patch.object(setup, 'executable') as execute, self.assertRaises(ValueError):
                setup.setup(self.root, options, setup.read_settings(self.root)[1], prepare=True)
            execute.assert_not_called()

    def test_default_profile_is_not_silently_used_for_another_project(self):
        with self.assertRaisesRegex(ValueError, 'Custom'):
            setup.validate_options(setup.defaults(self.root, 'another/project'))

    def test_prepare_publishes_a_managed_snapshot_and_survives_both_launchers(self):
        result = self.prepare()
        self.assertEqual('PREREQUISITES_READY', result['state'])
        self.assertFalse(result['tests_executed'])
        path = Path(selected_policy_path(self.root))
        self.assertTrue(path.resolve().is_relative_to((self.root / 'data' / 'validation-policies').resolve()))
        with patch.dict(os.environ, {}, clear=True):
            first = environment(self.root, validate({}))
        with patch.dict(os.environ, first, clear=True):
            second = environment(self.root, validate({}))
        with patch.dict(os.environ, second, clear=True):
            policy, mirror, image, commands, coverage = load_policy(setup.DEFAULT_REPOSITORY)
        self.assertEqual(PIN, image)
        self.assertEqual(self.options['commands'], commands)
        self.assertEqual([], coverage)
        self.assertEqual(path.name, hashlib.sha256(path.read_bytes()).hexdigest() + '.json')

    def test_new_snapshot_does_not_rewrite_running_jobs_previous_policy(self):
        self.prepare()
        previous = Path(selected_policy_path(self.root))
        raw = previous.read_bytes()
        changed = dict(self.options, timeout_seconds=900)
        self.prepare(changed)
        self.assertNotEqual(str(previous), selected_policy_path(self.root))
        self.assertEqual(raw, previous.read_bytes())

    def test_migration_retains_other_repositories_unknown_fields_and_behavior_maps(self):
        mapping = [{'requirement_id': 'fix', 'case_id': 'Case.behavior', 'command_index': 0,
                    'scope': 'behavior', 'report_path': 'reports/results.xml'}]
        original = self.entry(commands=[['python3', 'tests/run.py']], acceptance_map=mapping,
                              draft_repair_requirements=[{'id': 'fix', 'paths': ['app/feature.py'],
                                                          'checks': ['Required verification']}],
                              desktop_setup={'schema': 1, 'owner_annotation': 'preserved'}, extra_owner_setting='preserved')
        document = {setup.DEFAULT_REPOSITORY: original, 'other/repo': self.entry()}
        external = self.existing(document)
        raw = external.read_bytes()
        options = setup.options_for(self.root, setup.DEFAULT_REPOSITORY, document)
        self.assertEqual(setup.CUSTOM_PROFILE, options['profile'])
        self.prepare(options)
        migrated, _ = setup.read_settings(self.root)
        self.assertEqual(document['other/repo'], migrated['other/repo'])
        self.assertEqual(mapping, migrated[setup.DEFAULT_REPOSITORY]['acceptance_map'])
        self.assertEqual('preserved', migrated[setup.DEFAULT_REPOSITORY]['extra_owner_setting'])
        self.assertEqual('preserved', migrated[setup.DEFAULT_REPOSITORY]['desktop_setup']['owner_annotation'])
        self.assertEqual(original['draft_repair_requirements'], migrated[setup.DEFAULT_REPOSITORY]['draft_repair_requirements'])
        self.assertEqual(raw, external.read_bytes())
        external.unlink()
        self.assertEqual(migrated, setup.read_settings(self.root)[0])

    def test_mapped_commands_never_switch_silently_to_source_profile(self):
        options = dict(self.options, acceptance_map=[{'requirement_id': 'behavior'}])
        with self.assertRaisesRegex(ValueError, 'Custom profile'):
            setup.validate_options(options)

    def test_optimistic_revision_prevents_overwriting_another_saved_policy(self):
        old = setup.read_settings(self.root)[1]
        self.prepare()
        selected = selected_policy_path(self.root)
        with self.assertRaisesRegex(ValueError, 'changed'):
            self.prepare(revision=old)
        self.assertEqual(selected, selected_policy_path(self.root))

    def test_check_is_read_only_and_never_runs_repository_commands(self):
        self.prepare()
        selected = selected_policy_path(self.root)
        before = {p: p.read_bytes() for p in (self.root / 'data').rglob('*.json')}
        result = self.prepare(dict(self.options, image=PIN), prepare=False)
        self.assertFalse(result['saved'])
        self.assertFalse(result['tests_executed'])
        self.assertEqual(selected, selected_policy_path(self.root))
        self.assertEqual(before, {p: p.read_bytes() for p in (self.root / 'data').rglob('*.json')})

    def test_cancelled_or_failed_setup_does_not_activate_new_settings(self):
        self.prepare()
        selected = selected_policy_path(self.root)
        event = threading.Event()
        event.set()
        with self.assertRaises(setup.SetupCancelled):
            self.prepare(cancel=event)
        self.assertEqual(selected, selected_policy_path(self.root))
        with patch.object(setup, 'executable', side_effect=ValueError('Git missing')), self.assertRaises(ValueError):
            setup.setup(self.root, self.options, setup.read_settings(self.root)[1], prepare=True)
        self.assertEqual(selected, selected_policy_path(self.root))

    def test_actual_serialized_size_limit_and_parser_failure_preserve_selection(self):
        self.prepare()
        selected = selected_policy_path(self.root)
        _, revision = setup.read_settings(self.root)
        for entry in (self.entry(extra=['a'] * 10000), self.entry(acceptance_map=[{}])):
            with self.assertRaises(ValueError):
                setup.save_snapshot(self.root, setup.DEFAULT_REPOSITORY, entry, revision)
            self.assertEqual(selected, selected_policy_path(self.root))

    def test_corrupt_selection_never_reinherits_external_policy(self):
        external = self.existing({setup.DEFAULT_REPOSITORY: self.entry()})
        (self.root / 'data/validation-settings.json').write_text('{broken')
        with patch.dict(os.environ, {SETTING: str(external)}):
            with self.assertRaises(ValueError):
                setup.read_settings(self.root)
            setup.reset_settings(self.root, expected_revision=load_policy_snapshot(self.root)['revision'])
            self.assertEqual('', selected_policy_path(self.root))
            self.assertFalse(load_policy_snapshot(self.root)['enabled'])

    def test_setup_lock_prevents_second_prepare_but_explicit_disable_remains_available(self):
        lock = StateDirectoryLock(self.root / 'data/repository-validation-setup/owner')
        lock.acquire()
        try:
            with self.assertRaises(StateDirectoryAlreadyOwned):
                self.prepare()
            disabled = setup.reset_settings(self.root, expected_revision=load_policy_snapshot(self.root)['revision'])
            self.assertFalse(disabled['enabled'])
        finally:
            lock.release()

    def test_reset_does_not_remove_sources_or_images_or_old_snapshot(self):
        self.prepare()
        path = Path(selected_policy_path(self.root))
        setup.reset_settings(self.root, expected_revision=load_policy_snapshot(self.root)['revision'])
        self.assertTrue(path.is_file())
        self.assertEqual('', selected_policy_path(self.root))

    def test_nested_contract_errors_are_rejected_before_provisioning(self):
        cases = [
            {'acceptance_map': [{}]}, {'parallel_command_groups': [[0, 0]]},
            {'acceptance_templates': [{'objectives': ['Valid goal'], 'requirements': [{'id': 'incomplete'}]}]},
            {'acceptance_templates': [{'objectives': ['Valid goal'], 'requirements': []}] * 33},
            {'draft_repair_requirements': [{'id': 'incomplete'}]},
            {'draft_repair_requirements': [{'id': 'x', 'paths': ['../outside'], 'checks': ['Required verification']}]},
        ]
        for change in cases:
            options = dict(self.options, profile=setup.CUSTOM_PROFILE, **change)
            with self.subTest(field=list(change)), patch.object(setup, 'executable') as execute, self.assertRaises(ValueError):
                setup.setup(self.root, options, setup.read_settings(self.root)[1], prepare=True)
            execute.assert_not_called()

    def test_direct_save_uses_same_mapper_without_provisioning_and_keeps_unknown_fields(self):
        original = self.entry(extra_owner_setting='retained', desktop_setup={'schema': 1, 'owner_note': 'retained'})
        self.existing({setup.DEFAULT_REPOSITORY: original})
        snapshot = load_policy_snapshot(self.root)
        options = setup.options_for(self.root, setup.DEFAULT_REPOSITORY, snapshot['repositories'])
        options['timeout_seconds'] = 90
        with patch.object(setup, 'run') as external:
            entry = setup.entry_from_options(options, original)
            saved = setup.save_snapshot(self.root, setup.DEFAULT_REPOSITORY, entry, snapshot['revision'])
        external.assert_not_called()
        self.assertEqual(90, saved['repositories'][setup.DEFAULT_REPOSITORY]['timeout_seconds'])
        self.assertEqual('retained', saved['repositories'][setup.DEFAULT_REPOSITORY]['extra_owner_setting'])
        self.assertEqual('retained', saved['repositories'][setup.DEFAULT_REPOSITORY]['desktop_setup']['owner_note'])
        self.assertNotIn('repository', entry)
        self.assertNotIn('profile', entry)
        self.assertNotIn('image_source', entry)

    def test_legacy_selection_change_during_prepare_blocks_activation(self):
        _, revision = setup.read_settings(self.root)
        def disable(*_args, **_kwargs):
            setup.reset_settings(self.root, expected_revision=load_policy_snapshot(self.root)['revision'])
            return 'b' * 40
        with ExitStack() as stack:
            stack.enter_context(patch.object(setup, 'executable', return_value='/fixture/tool'))
            stack.enter_context(patch.object(setup, 'check_docker'))
            stack.enter_context(patch.object(setup, 'prepare_mirror', side_effect=disable))
            stack.enter_context(patch.object(setup, 'pin_image', return_value=PIN))
            with self.assertRaises(PolicyConflictError):
                setup.setup(self.root, self.options, revision, prepare=True)
        self.assertFalse(load_policy_snapshot(self.root)['enabled'])
        self.assertEqual('', selected_policy_path(self.root))

    def test_prepare_while_disabled_retains_reviewed_draft_without_enabling(self):
        result = self.prepare(enabled=False)
        self.assertTrue(result['saved'])
        self.assertFalse(result['snapshot']['enabled'])
        self.assertEqual(PIN, result['snapshot']['repositories'][setup.DEFAULT_REPOSITORY]['image'])
        self.assertNotIn(SETTING, environment(self.root, validate({})))

    def test_stale_reset_does_not_disable_a_newly_reviewed_policy(self):
        old = load_policy_snapshot(self.root)
        self.prepare()
        selected = selected_policy_path(self.root)
        with self.assertRaises(PolicyConflictError):
            setup.reset_settings(self.root, expected_revision=old['revision'])
        self.assertEqual(selected, selected_policy_path(self.root))

    def test_invalid_prepared_draft_cannot_report_saved_by_only_disabling(self):
        self.prepare()
        snapshot = load_policy_snapshot(self.root)
        original = snapshot['selection']
        with self.assertRaises(ValueError):
            setup.save_snapshot(self.root, setup.DEFAULT_REPOSITORY, self.entry(acceptance_map=[{}]),
                                snapshot['revision'], enabled=False)
        self.assertEqual(original, selected_policy_path(self.root))

    def test_later_incomplete_entry_is_editable_and_repaired_without_discarding_other_data(self):
        good = self.entry(extra_owner_setting='first repository remains')
        external = self.existing({setup.DEFAULT_REPOSITORY: good, 'other/repo': self.entry()})
        broken = {'mirror': str(self.root / 'other.git'), 'desktop_setup': 'broken metadata',
                  'allowed_checks': ['Required verification'], 'owner_custom': {'preserved': True}}
        atomic_json(external, {setup.DEFAULT_REPOSITORY: good, 'other/repo': broken})
        original_bytes = external.read_bytes()
        snapshot = load_policy_snapshot(self.root)
        self.assertEqual('INVALID', snapshot['state'])
        options = setup.options_for(self.root, 'other/repo', snapshot['repositories'])
        self.assertTrue(options['repair_notice'])
        self.assertEqual(setup.CUSTOM_PROFILE, options['profile'])
        self.assertEqual([], options['commands'])
        self.assertEqual('', options['image'])
        self.assertEqual(original_bytes, external.read_bytes())
        self.assertEqual(broken, snapshot['repositories']['other/repo'])
        options.update(image=PIN, image_source=PIN, commands=[['python3', '-m', 'unittest']])
        entry = setup.entry_from_options(options, broken)
        updated = copy.deepcopy(snapshot['repositories'])
        updated['other/repo'] = entry
        with patch.object(setup, 'run') as execute:
            saved = save_policy_snapshot(self.root, updated, enabled=True, expected_revision=snapshot['revision'])
        execute.assert_not_called()
        self.assertEqual('READY', saved['state'])
        self.assertEqual(good, saved['repositories'][setup.DEFAULT_REPOSITORY])
        self.assertEqual({'preserved': True}, saved['repositories']['other/repo']['owner_custom'])
        self.assertEqual(1, saved['repositories']['other/repo']['desktop_setup']['schema'])
        self.assertEqual(original_bytes, external.read_bytes())

    def test_metadata_only_repair_keeps_valid_commands_mirror_and_unconfigured_optional_fields(self):
        original = {'mirror': str(self.root / 'original.git'), 'image': PIN,
                    'commands': [['python3', '-m', 'unittest', 'actual_tests']], 'desktop_setup': []}
        before = copy.deepcopy(original)
        options = setup.options_for(self.root, setup.DEFAULT_REPOSITORY, {setup.DEFAULT_REPOSITORY: original})
        self.assertTrue(options['repair_notice'])
        self.assertEqual(setup.CUSTOM_PROFILE, options['profile'])
        self.assertEqual(original['commands'], options['commands'])
        self.assertEqual(original['mirror'], options['mirror'])
        self.assertEqual([], options['allowed_checks'])
        entry = setup.entry_from_options(options, original)
        self.assertEqual(original['commands'], entry['commands'])
        self.assertEqual(original['mirror'], entry['mirror'])
        self.assertIsInstance(entry['desktop_setup'], dict)
        self.assertEqual(before, original)


class ImageAndProcessTests(unittest.TestCase):
    def test_image_tag_registry_port_and_digest_validation(self):
        for value in ('registry.example:5000/team/checks:v1', setup.DEFAULT_IMAGE, PIN):
            self.assertEqual(value, setup.image_source(value))
        self.assertEqual('library/python', setup.image_repository(setup.DEFAULT_IMAGE))
        self.assertEqual('library/python', setup.image_repository(PIN))
        for value in ('python@sha256:' + 'a' * 63, 'python:latest; rm', 'python', '-x:y'):
            with self.assertRaises(ValueError):
                setup.image_source(value)

    def test_prepare_resolves_one_real_registry_receipt_and_confirms_local_pin(self):
        with patch.object(setup, 'docker_run', side_effect=['', json.dumps([PIN]), '{}']) as call:
            self.assertEqual(PIN, setup.pin_image('/docker', setup.DEFAULT_IMAGE, prepare=True))
        self.assertEqual(['pull', '--quiet', setup.DEFAULT_IMAGE], call.call_args_list[0].args[0])
        self.assertEqual(['image', 'inspect', PIN], call.call_args_list[-1].args[0])

    def test_existing_digest_is_kept_until_explicit_refresh(self):
        with patch.object(setup, 'docker_run', side_effect=['', json.dumps([PIN]), '{}']) as call:
            self.assertEqual(PIN, setup.pin_image('/docker', setup.DEFAULT_IMAGE, PIN, prepare=True))
        self.assertEqual(PIN, call.call_args_list[0].args[0][-1])
        with patch.object(setup, 'docker_run') as call, self.assertRaises(ValueError):
            setup.pin_image('/docker', 'python@sha256:' + 'b' * 64, PIN, prepare=True)
        call.assert_not_called()

    def test_read_only_image_check_never_pulls_and_unprepared_is_blocked(self):
        with patch.object(setup, 'docker_run', side_effect=[json.dumps([PIN]), '{}']) as call:
            setup.pin_image('/docker', setup.DEFAULT_IMAGE, PIN)
        self.assertTrue(all(c.args[0][0] != 'pull' for c in call.call_args_list))
        with patch.object(setup, 'docker_run') as call, self.assertRaises(ValueError):
            setup.pin_image('/docker', setup.DEFAULT_IMAGE)
        call.assert_not_called()

    def test_fake_foreign_ambiguous_and_missing_digests_are_rejected(self):
        for payload in (None, [], ['sha256:' + 'a' * 64], ['other@sha256:' + 'a' * 64],
                        [PIN, 'python@sha256:' + 'b' * 64], {'digest': PIN}):
            with self.subTest(payload=payload), patch.object(setup, 'docker_run', side_effect=['', json.dumps(payload)]), self.assertRaises(ValueError):
                setup.pin_image('/docker', setup.DEFAULT_IMAGE, prepare=True)

    def test_linux_daemon_is_required(self):
        with patch.object(setup, 'docker_run', return_value='windows'), self.assertRaisesRegex(ValueError, 'Linux'):
            setup.check_docker('/docker')

    def test_local_daemon_configuration_is_shared_by_setup_and_execution(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(validation_host, 'executable', return_value='/local/docker'), \
                patch.dict(os.environ, {'DOCKER_HOST': 'tcp://untrusted:2375', 'DOCKER_CONTEXT': 'remote', 'SECRET': 'hidden'}):
            with validation_host.command(['docker', 'info']) as args:
                self.assertEqual(['/local/docker', '--config'], args[:2])
                self.assertEqual(['--context', 'default', 'info'], args[3:])
                path = Path(args[2])
                self.assertTrue(path.is_dir())
                self.assertEqual([], list(path.iterdir()))
            self.assertFalse(path.exists())
            env = validation_host.environment(temp)
            self.assertNotIn('DOCKER_HOST', env)
            self.assertNotIn('DOCKER_CONTEXT', env)
            self.assertNotIn('SECRET', env)

    def test_setup_passes_isolated_environment_to_docker(self):
        with patch.object(validation_host, 'executable', return_value='/local/docker'), patch.object(setup, 'run', return_value='linux') as call:
            setup.check_docker('/local/docker')
        args = call.call_args.args[0]
        self.assertEqual(['/local/docker', '--config'], args[:2])
        self.assertEqual(['--context', 'default'], args[3:5])
        self.assertNotIn('DOCKER_HOST', call.call_args.kwargs['env'])

    def test_token_only_enters_the_git_child_environment(self):
        token = 'fixture-token-not-real'
        with patch.dict(os.environ, {'GIT_CONFIG_VALUE_77': 'leaked', 'GIT_DIR': '/wrong'}):
            env = setup.git_environment(token)
        self.assertNotIn('GIT_DIR', env)
        self.assertNotIn('GIT_CONFIG_VALUE_77', env)
        self.assertEqual('http.https://github.com/.extraheader', env['GIT_CONFIG_KEY_0'])
        self.assertIn('Authorization: Basic ', env['GIT_CONFIG_VALUE_0'])
        self.assertNotIn(token, repr(setup.git_prefix('/git')))
        self.assertNotIn('GIT_CONFIG_VALUE_0', setup.git_environment())

    def test_subprocess_success_timeout_cancel_output_bound_and_redaction(self):
        self.assertEqual('ready', setup.run([sys.executable, '-c', 'print("ready")']))
        for script, options, expected in (
                ('import time; time.sleep(5)', {'timeout': .05}, 'timed out'),
                ('print("x"*1100000)', {}, 'output budget'),
                ('import sys; print("secret-token"); sys.exit(7)', {}, 'exit 7')):
            with self.subTest(expected=expected), self.assertRaisesRegex(ValueError, expected) as result:
                setup.run([sys.executable, '-c', script], **options)
            self.assertNotIn('secret-token', str(result.exception))
        event = threading.Event()
        timer = threading.Timer(.15, event.set)
        timer.start()
        try:
            with self.assertRaises(setup.SetupCancelled):
                setup.run([sys.executable, '-c', 'import time; time.sleep(5)'], cancel=event)
        finally:
            timer.join()

    def test_output_capture_stops_an_overbudget_writer_before_it_finishes(self):
        with tempfile.TemporaryDirectory() as temp:
            completed = Path(temp) / 'completed'
            script = ('import os, pathlib, sys; data=b"x"*8192; '
                      '[os.write(1,data) for _ in range(1024)]; pathlib.Path(sys.argv[1]).write_text("finished")')
            with self.assertRaisesRegex(ValueError, 'output budget'):
                setup.run([sys.executable, '-c', script, str(completed)])
            self.assertFalse(completed.exists(), 'The byte cap must stop the writer before its full 8 MiB completes')

    def test_exact_output_limit_is_accepted_and_invalid_bytes_are_redacted(self):
        output = setup.run([sys.executable, '-c', 'import sys; sys.stdout.write("x"*(1024*1024))'])
        self.assertEqual(1024 * 1024, len(output))
        with self.assertRaisesRegex(ValueError, 'invalid text') as failure:
            setup.run([sys.executable, '-c', 'import os; os.write(1,b"private-output-"+bytes([255]))'])
        self.assertNotIn('private-output', str(failure.exception))

    def test_closed_stdout_child_remains_subject_to_deadline_and_cancellation(self):
        script = 'import os,time; os.close(1); os.close(2); time.sleep(10)'
        started = time.monotonic()
        with self.assertRaisesRegex(ValueError, 'timed out'):
            setup.run([sys.executable, '-c', script], timeout=.15)
        self.assertLess(time.monotonic() - started, 5)
        cancel = threading.Event()
        timer = threading.Timer(.15, cancel.set)
        timer.start()
        started = time.monotonic()
        try:
            with self.assertRaises(setup.SetupCancelled):
                setup.run([sys.executable, '-c', script], timeout=10, cancel=cancel)
        finally:
            timer.join()
        self.assertLess(time.monotonic() - started, 5)

    def test_runtime_resolves_git_before_restricting_the_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            result = run_bounded(['git', '--version'], temp, 10)
        self.assertEqual(0, result['exit_code'])
        self.assertIn('git version', result['output_tail'])


class ActualMirrorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mirror = self.root / 'approved.git'
        self.git = setup.executable('git')
        env = setup.git_environment()
        source = self.root / 'source'
        source.mkdir()
        (source / 'hello.txt').write_text('test')
        def git(*args):
            return subprocess.check_output([self.git, *args], env=env, stderr=subprocess.DEVNULL, text=True).strip()
        git('init', '-q', str(source))
        git('-C', str(source), 'add', '.')
        git('-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
            '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture')
        git('clone', '--quiet', '--mirror', str(source), str(self.mirror))
        git('--git-dir=' + str(self.mirror), 'remote', 'set-url', 'origin', 'https://github.com/' + setup.DEFAULT_REPOSITORY + '.git')
        self.git_call = git

    def test_existing_bare_mirror_is_verified_without_any_network(self):
        head = setup.check_mirror(self.git, setup.DEFAULT_REPOSITORY, self.mirror)
        self.assertRegex(head, '[a-f0-9]{40}')

    def test_different_origin_and_custom_includes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'different repository'):
            setup.check_mirror(self.git, 'other/repo', self.mirror)
        self.git_call('--git-dir=' + str(self.mirror), 'config', 'include.path', str(self.root / 'other'))
        with self.assertRaisesRegex(ValueError, 'custom redirect'):
            setup.check_mirror(self.git, setup.DEFAULT_REPOSITORY, self.mirror)

    def test_worktree_configuration_cannot_redirect_a_credential_bearing_fetch(self):
        self.git_call('--git-dir=' + str(self.mirror), 'config', 'extensions.worktreeConfig', 'true')
        self.git_call('--git-dir=' + str(self.mirror), 'config', '--worktree',
                      'url.https://different-origin.example/.insteadOf', 'https://github.com/')
        # The real Git configuration resolves the advertised raw URL elsewhere.
        actual = self.git_call('--git-dir=' + str(self.mirror), 'remote', 'get-url', 'origin')
        self.assertTrue(actual.startswith('https://different-origin.example/'), actual)
        calls = []
        original = setup.run
        def observe(argv, **kwargs):
            calls.append((argv, kwargs))
            if 'fetch' in argv:
                self.fail('The redirected mirror must be rejected before fetch')
            return original(argv, **kwargs)
        with patch.object(setup, 'run', side_effect=observe), self.assertRaisesRegex(ValueError, 'worktree configuration'):
            setup.prepare_mirror(self.git, setup.DEFAULT_REPOSITORY, self.mirror, token='fixture-token')
        self.assertFalse(any('GIT_CONFIG_VALUE_0' in kwargs.get('env', {}) for _, kwargs in calls))

    def test_existing_foreign_directory_is_never_overwritten_or_removed(self):
        folder = self.root / 'not-a-repository'
        folder.mkdir()
        marker = folder / 'keep.txt'
        marker.write_text('keep')
        with self.assertRaises(ValueError):
            setup.prepare_mirror(self.git, setup.DEFAULT_REPOSITORY, folder)
        self.assertEqual('keep', marker.read_text())

    def test_refresh_uses_explicit_verified_https_origin_without_hooks_or_secret_url(self):
        calls = []
        original = setup.run
        def run(args, **kwargs):
            if 'fetch' in args:
                calls.append((args, kwargs))
                return ''
            return original(args, **kwargs)
        with patch.object(setup, 'run', side_effect=run):
            setup.prepare_mirror(self.git, setup.DEFAULT_REPOSITORY, self.mirror, token='fixture-token')
        self.assertEqual(1, len(calls))
        args, kw = calls[0]
        self.assertIn('https://github.com/' + setup.DEFAULT_REPOSITORY + '.git', args)
        self.assertIn('core.hooksPath=' + os.devnull, args)
        self.assertIn('credential.helper=', args)
        self.assertNotIn('fixture-token', repr(args))
        self.assertIn('GIT_CONFIG_VALUE_0', kw['env'])


if __name__ == '__main__':
    unittest.main()
