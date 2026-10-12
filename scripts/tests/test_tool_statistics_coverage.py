import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('statistics_coverage', Path(__file__).parents[1] / 'check_tool_statistics_coverage.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ToolStatisticsCoverageTest(unittest.TestCase):
    def test_every_definition_producer_has_explicit_policy(self):
        module.validate()
