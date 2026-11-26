"""Grade source retrieval against frozen controller labels, independently of answer wording."""
import json
import math
import random
import re
from collections import defaultdict
from pathlib import Path

ASSETS = Path(__file__).parent / 'campaign_assets' / 'scenarios'
STAGES = ('candidate', 'prepared', 'dispatched', 'cited')
CONTROLS = ('product', 'build', 'model', 'capabilities_digest', 'memory_mode', 'index_state', 'budget_bytes')


def evaluate(capture):
    gold = json.loads((ASSETS / 'controller' / 'source-qa.json').read_text(encoding='utf-8'))
    public = json.loads((ASSETS / 'public' / 'source-qa.json').read_text(encoding='utf-8'))
    labels = {case['id']: case for case in gold['cases']}
    families = {case['id']: case['family'] for case in public['cases']}
    if (not isinstance(capture, dict) or capture.get('schema') != 'github.source-qa-capture.v1'
            or capture.get('repository') != gold['repository'] or capture.get('source_sha') != gold['source_sha']):
        raise ValueError('Select an original source-QA capture bound to the frozen repository revision')
    controls, records = capture.get('controls'), capture.get('trials')
    if (not isinstance(controls, dict) or controls.get('budget_bytes') != gold['budget_bytes']
            or any(not isinstance(controls.get(k), str) or not controls[k].strip() or len(controls[k]) > 256 for k in CONTROLS[:-1])
            or not re.fullmatch(r'[a-f0-9]{64}', controls['capabilities_digest'])
            or not isinstance(records, list) or len(records) > 1200):
        raise ValueError('Supply the frozen build, model, capability digest, memory, index and packet budget')
    rows, seen = [], set()
    groups = defaultdict(list)
    for trial in records:
        if not isinstance(trial, dict):
            raise ValueError('Invalid source-QA trial')
        ident, arm, attempt = trial.get('id'), trial.get('arm'), trial.get('attempt')
        if ident not in labels or arm not in ('baseline', 'candidate') or type(attempt) is not int or not 1 <= attempt <= 20:
            raise ValueError('Unknown source-QA task, arm or attempt')
        key = (ident, arm, attempt)
        if key in seen:
            raise ValueError('Duplicate source-QA observation')
        seen.add(key)
        label, measures = labels[ident], {}
        for stage in STAGES:
            received = trial.get(stage)
            if received is None:
                measures[stage] = None
                continue
            if not isinstance(received, list) or len(received) > 100:
                raise ValueError('A stage must contain bounded original range identities')
            hits = []
            for row in received[:5] if stage == 'candidate' else received:
                if not isinstance(row, dict):
                    raise ValueError('Invalid source-QA source identity')
                hit = row.get('path') == label['path'] and row.get('blob_sha') == label['blob_sha']
                if stage != 'candidate':
                    lo, hi = row.get('start_line'), row.get('end_line')
                    if type(lo) is not int or type(hi) is not int or not 1 <= lo <= hi:
                        raise ValueError('Invalid exact range')
                    hit = hit and any(lo <= item['line'] <= hi for item in label['lines'])
                hits.append(hit)
            measures[stage] = int(any(hits))
        for metric in ('first_source_ms', 'index_ms', 'bytes', 'cost'):
            value = trial.get(metric)
            if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or value < 0):
                raise ValueError('Supply a nonnegative measured value, or null')
            measures[metric] = value
        if measures['bytes'] is not None and measures['bytes'] > controls['budget_bytes']:
            raise ValueError('The supplied trial exceeds the frozen packet allowance')
        row = dict(id=ident, family=families[ident], arm=arm, attempt=attempt, **measures)
        rows.append(row)
        groups[(row['family'], arm, attempt)].append(row)
    summaries, paired = [], []
    for (family, arm, attempt), samples in sorted(groups.items()):
        metrics = {}
        for key in (*STAGES, 'first_source_ms', 'index_ms', 'bytes', 'cost'):
            values = [r[key] for r in samples if r[key] is not None]
            metrics[key] = {'observed': len(values), 'mean': sum(values) / len(values) if values else None}
        summaries.append(dict(family=family, arm=arm, attempt=attempt, planned=sum(f == family for f in families.values()), metrics=metrics))
    indexed = {(r['id'], r['arm'], r['attempt']): r for r in rows}
    for family, attempt in sorted({(r['family'], r['attempt']) for r in rows}):
        for stage in STAGES:
            deltas = []
            for ident in sorted(labels):
                left, right = (indexed.get((ident, arm, attempt), {}) for arm in ('baseline', 'candidate'))
                if families[ident] == family and left.get(stage) is not None and right.get(stage) is not None:
                    deltas.append(right[stage] - left[stage])
            interval = None
            if len(deltas) >= 2:
                rng = random.Random(f'{family}/{attempt}/{stage}')
                draws = sorted(sum(rng.choices(deltas, k=len(deltas))) / len(deltas) for _ in range(2000))
                interval = [draws[49], draws[1949]]
            paired.append(dict(family=family, attempt=attempt, stage=stage, pairs=len(deltas),
                               mean_difference=sum(deltas) / len(deltas) if deltas else None, bootstrap_95=interval))
    return dict(schema='github.source-qa-report.v1', source_sha=gold['source_sha'], controls=controls,
                scope='SUPPLIED_RETRIEVAL_TRACES', production_score_eligible=False, planned_tasks=len(labels),
                observed_trials=len(rows), metrics=summaries, paired=paired)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    args = parser.parse_args()
    from benchmarks.campaign import read_json
    print(json.dumps(evaluate(read_json(args.capture)), indent=2))
