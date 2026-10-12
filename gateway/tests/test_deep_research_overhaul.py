import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.academic import AcademicRouter, CORE, paper


class DeepResearchOverhaulTest(unittest.TestCase):
    def test_maximum_queries_every_eligible_engine_without_three_provider_cap(self):
        with tempfile.TemporaryDirectory() as folder:
            service = AcademicRouter(state_dir=folder)
            calls = []
            def fixture(provider, query, limit, page, args, cancelled):
                calls.append((provider, page))
                return [paper(provider, 'https://example.org/' + provider, provider=provider)]
            with patch.object(service, '_search_provider', side_effect=fixture):
                result = service.search({'query': 'research', 'depth': 100, 'maxProviderCalls': 1, 'maxResults': 50}, lambda: False)
            self.assertEqual(set(CORE), {provider for provider, _ in calls})
            self.assertEqual(5, len(result['providers']))

    def test_pagination_can_reach_tenth_page(self):
        with tempfile.TemporaryDirectory() as folder:
            service = AcademicRouter(state_dir=folder)
            with patch.object(service, '_search_provider', return_value=[]):
                result = service.search({'query': 'research', 'depth': 100, 'cursor': '10'}, lambda: False)
            self.assertIsNone(result['nextCursor'])

    def test_three_hundred_metadata_records_remain_resolvable(self):
        with tempfile.TemporaryDirectory() as folder:
            service = AcademicRouter(state_dir=folder)
            for index in range(300):
                service._remember(paper(str(index), 'https://example.org/' + str(index)))
            self.assertEqual(300, len(service.papers))
