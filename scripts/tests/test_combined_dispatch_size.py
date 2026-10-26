import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('dispatch_size', Path(__file__).parents[1] / 'check_research_dispatch_size.py')
SIZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIZE)


class CombinedDispatchSizeTest(unittest.TestCase):
    def test_both_real_suspend_stages_are_guarded(self):
        for method in ('prepare', 'freezeAndPersist', 'access$freezeAndPersist'):
            self.assertTrue(SIZE.is_dispatch_method(SIZE.COMBINED_PLANNER + ';', method))

    def test_generated_state_machines_and_nested_lambdas_are_guarded(self):
        for stage in ('prepare', 'freezeAndPersist'):
            self.assertTrue(SIZE.is_dispatch_method(SIZE.COMBINED_PLANNER + '$' + stage + '$1;', 'invokeSuspend'))
            self.assertTrue(SIZE.is_dispatch_method(SIZE.COMBINED_PLANNER + '$' + stage + '$assembled$1;', 'invokeSuspend'))

    def test_guard_uses_art_code_units_and_does_not_match_unrelated_names(self):
        self.assertEqual(16383, SIZE.ART_CODE_UNIT_LIMIT)
        self.assertFalse(SIZE.is_dispatch_method(SIZE.COMBINED_PLANNER + ';', 'prepareFallback'))
        self.assertFalse(SIZE.is_dispatch_method('Lother/Planner;', 'prepare'))
