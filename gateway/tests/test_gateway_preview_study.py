from copy import deepcopy
from unittest import TestCase

from benchmarks import preview_study


def line(ms, text, event):
    return {'ms': ms, 'text': text, 'clauses': [{'text': text, 'eventId': event}]}


class PreviewStudyTests(TestCase):
    def setUp(self):
        self.events = [
            dict(id='a', fact='sha-a/ci', ms=5, kind='finding', accepted=['Old CI failed.', 'CI failed on old.']),
            dict(id='b', fact='sha-b/ci', ms=10, kind='correction', required=True,
                 accepted=['New CI is pending.'], invalidates=['a']),
        ]
        self.case = dict(id='case', question='What changed?', family='ci', events=self.events,
                         current=[line(5, 'Old CI failed.', 'a'), line(6, 'CI failed on old.', 'a'),
                                  line(11, 'Old CI failed.', 'a')],
                         proposed=[line(5, 'Old CI failed.', 'a'), line(11, 'New CI is pending.', 'b')])
        self.study = dict(readers=['r1', 'r2'], cases=[self.case])
        self.secret = bytes(range(32))

    def test_claim_support_coverage_supersession_and_useful_time_are_graded_separately(self):
        current = preview_study.grade(self.events, self.case['current'])
        self.assertEqual((1, 1, 1, 1, 5), tuple(current[k] for k in
                         ('duplicateFindings', 'stale', 'missingCorrections', 'missingRequired', 'firstUsefulMs')))
        self.assertEqual(0, preview_study.grade(self.events, self.case['proposed'])['missingCorrections'])
        self.assertEqual(2, preview_study.grade(self.events, [line(1, 'Old CI failed.', 'a'), line(5, 'All green.', 'a')])['unsupported'])
        hidden = line(5, 'Old CI failed.', 'a')
        hidden['text'] += ' Release published.'
        with self.assertRaises(ValueError):
            preview_study.grade(self.events, [hidden])

    def test_missing_and_duplicate_ballots_and_changed_study_cannot_win(self):
        packet, mapping, _ = preview_study.packet(self.study, self.secret)
        self.assertNotIn('proposed', str(packet))
        label = next(k for k, v in mapping['case'].items() if v == 'proposed')
        ballots = [dict(studyId=packet['studyId'], reader=r, case='case', prefer=label, readability={'A': 4, 'B': 3})
                   for r in self.study['readers']]
        result = preview_study.score(self.study, self.secret, ballots)
        self.assertEqual(('INCONCLUSIVE', 1, 1), (result['verdict'], result['proposedPreference'], result['familiesRated']))
        self.assertEqual('INCOMPLETE', preview_study.score(self.study, self.secret, ballots[:1])['verdict'])
        with self.assertRaises(ValueError):
            preview_study.score(self.study, self.secret, ballots * 2)
        changed = deepcopy(self.study)
        changed['cases'][0]['events'][0]['accepted'].append('Other wording.')
        with self.assertRaises(ValueError):
            preview_study.score(changed, self.secret, ballots)

    def test_quiet_replay_is_eligible_without_an_invented_finding(self):
        empty = preview_study.grade([], [])
        self.assertEqual(0, empty['unsupported'])
        self.assertIsNone(empty['firstUsefulMs'])
        premature = preview_study.grade([], [line(1, 'A repair was applied.', None)])
        self.assertEqual(1, premature['unsupported'])
