"""Blind replay of the user-visible chat updates against independent event fixtures."""
from collections import Counter, defaultdict
import hashlib
import hmac
import math
from statistics import mean, median

from benchmarks.contracts import digest

ARMS = ('current', 'proposed')


def normal(text):
    return ' '.join(text.split())


def grade(events, updates):
    by_id = {}
    previous = -1
    for event in events:
        identifier = event['id']
        if (not isinstance(identifier, str) or not identifier or identifier in by_id
                or type(event['ms']) is not int or event['ms'] < previous or event['ms'] < 0
                or not isinstance(event.get('fact'), str) or not event['fact']
                or event['kind'] not in {'intent', 'status', 'finding', 'correction'}
                or not isinstance(event['accepted'], list) or not event['accepted']
                or not all(isinstance(line, str) and normal(line) for line in event['accepted'])
                or any(old not in by_id for old in event.get('invalidates', []))):
            raise ValueError('Invalid event, phrase authority or supersession order in preview fixture')
        previous = event['ms']
        by_id[identifier] = event
    seen_facts, published, invalid, needed_corrections = set(), set(), set(), set()
    event_index = 0
    def advance():
        nonlocal event_index
        event = events[event_index]
        event_index += 1
        old = set(event.get('invalidates', []))
        invalid.update(old)
        if old & published:
            needed_corrections.add(event['id'])

    unsupported = stale = duplicates = clauses = 0
    first_useful = None
    previous = -1
    for update in updates:
        ms, text, parts = update['ms'], update['text'], update['clauses']
        if (type(ms) is not int or ms < previous or ms < 0 or not isinstance(text, str)
                or not isinstance(parts, list) or not parts or any(not isinstance(p.get('text'), str) or not normal(p['text']) for p in parts)
                or normal(text) != normal(' '.join(p['text'] for p in parts))):
            raise ValueError('Every clause of the delivered preview must be retained in order')
        previous = ms
        while event_index < len(events) and events[event_index]['ms'] <= ms:
            advance()
        for part in parts:
            clauses += 1
            event = by_id.get(part.get('eventId'))
            if event and event['id'] in invalid:
                stale += 1
            elif (not event or event['ms'] > ms or normal(part['text']) not in
                  {normal(phrase) for phrase in event['accepted']}):
                unsupported += 1
            else:
                published.add(event['id'])
                if event['kind'] in {'finding', 'correction'}:
                    duplicates += int(event['fact'] in seen_facts)
                    seen_facts.add(event['fact'])
                    if first_useful is None:
                        first_useful = ms
    while event_index < len(events):
        advance()
    required = {event['id'] for event in events if event.get('required') is True and event['id'] not in invalid}
    return {'clauses': clauses, 'updates': len(updates), 'unsupported': unsupported, 'stale': stale,
            'duplicateFindings': duplicates, 'missingCorrections': len(needed_corrections - published),
            'missingRequired': len(required - published), 'firstUsefulMs': first_useful}


def packet(study, key):
    cases, readers = study['cases'], study['readers']
    if (len(key) != 32 or not isinstance(readers, list) or not readers or len(set(readers)) != len(readers)
            or not all(isinstance(reader, str) and reader.strip() for reader in readers)
            or not isinstance(cases, list) or not cases or len(cases) > 500 or len(readers) > 200):
        raise ValueError('The study needs a private blinding key, distinct cases and preregistered readers')
    public, mapping, stats = [], {}, {}
    def blind(case_id, purpose):
        return hmac.new(key, (purpose + '\0' + case_id).encode(), hashlib.sha256).digest()
    for case in cases:
        ident = case['id']
        if not isinstance(ident, str) or not ident or ident in mapping or not case.get('family') or not case.get('question'):
            raise ValueError('Invalid preview study case identity')
        mapping[ident] = dict(zip(('A', 'B'), ARMS if blind(ident, 'order')[0] & 1 else reversed(ARMS)))
        stats[ident] = {arm: grade(case['events'], case[arm]) for arm in ARMS}
        public.append({'id': ident, 'question': case['question'],
                       'transcripts': {label: [{'ms': row['ms'], 'text': row['text']} for row in case[arm]]
                                       for label, arm in mapping[ident].items()}})
    public.sort(key=lambda c: blind(c['id'], 'case'))
    output = {'schema': 'gateway.blind-preview-packet.v1', 'cases': public}
    # Bind the private grading plan, including readers and parallel references,
    # without publishing the arm assignment or a guessable unsalted plan hash.
    output['studyId'] = hmac.new(key, digest(study).encode(), hashlib.sha256).hexdigest()
    return output, mapping, stats


def score(study, key, ballots):
    public, mapping, stats = packet(study, key)
    expected = {(r, c['id']) for r in study['readers'] for c in study['cases']}
    per_family = defaultdict(list)
    readability = defaultdict(list)
    families = {c['id']: c['family'] for c in study['cases']}
    for row in ballots:
        ident = row['case']
        slot = (row['reader'], ident)
        if (slot not in expected or row.get('studyId') != public['studyId'] or row.get('prefer') not in {'A', 'B', 'tie'}
                or not isinstance(row.get('readability'), dict) or set(row['readability']) != {'A', 'B'}
                or any(type(n) is not int or not 1 <= n <= 5 for n in row['readability'].values())):
            raise ValueError('Missing or duplicate reader/case identity, invalid rating or stale blind packet')
        expected.remove(slot)
        choice = mapping[ident].get(row['prefer'])
        per_family[families[ident]].append(.5 if choice is None else float(choice == 'proposed'))
        for label, n in row['readability'].items():
            readability[mapping[ident][label]].append(n)
    measures = {}
    for arm in ARMS:
        rows = [by_arm[arm] for by_arm in stats.values()]
        metrics = Counter()
        for row in rows:
            metrics.update({k: n for k, n in row.items() if k != 'firstUsefulMs'})
        times = [row['firstUsefulMs'] for row in rows if row['firstUsefulMs'] is not None]
        measures[arm] = {**metrics, 'medianFirstUsefulMs': median(times) if times else None,
                         'casesWithUsefulFinding': len(times), 'readabilityMean': mean(readability[arm]) if readability[arm] else None}
    # Conditional on the preregistered reader panel; variants and repeated
    # ratings of one family never add independent samples.
    values = [mean(v) for v in per_family.values()]
    lower = max(0, mean(values) - math.sqrt(math.log(20) / (2 * len(values)))) if values and not expected else None
    truth = all(measures['proposed'][k] == 0 for k in ('unsupported', 'stale', 'duplicateFindings', 'missingCorrections', 'missingRequired'))
    return {'schema': 'gateway.blind-preview-score.v1', 'studyId': public['studyId'], 'arms': measures,
            'readers': len(study['readers']), 'familiesRated': len(values), 'missingBallots': len(expected),
            'proposedPreference': mean(values) if values and not expected else None, 'lowerOneSided95': lower,
            'truthGatePassed': truth, 'verdict': 'INCOMPLETE' if expected else 'TRUTH_GATE_FAILED' if not truth else
            'PREFERRED_ON_STUDY' if lower is not None and lower > .5 else 'INCONCLUSIVE'}
