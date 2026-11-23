"""Controlled same-model context arms. Outcome evidence comes from the real grader."""
import json
import math
import re
import time
from collections import defaultdict
from pathlib import Path
from statistics import mean, median

from benchmarks.context_fixture import VARIANTS, public_context_inputs
from benchmarks.contracts import digest, content_digest


def validate_context_request(request):
    variants = request.get('context_variants')
    keys = {'context_variants', 'context_repeats', 'context_budget_bytes'}
    if variants is None:
        if any(key in request for key in keys - {'context_variants'}):
            raise ValueError('Context settings require explicit experiment arms')
        return
    if (request.get('suite') != 'coding' or not isinstance(variants, list) or not 2 <= len(variants) <= 4
            or variants[0] != 'CURRENT' or any(not isinstance(arm, str) or arm not in VARIANTS for arm in variants)
            or len(set(variants)) != len(variants)):
        raise ValueError('Choose CURRENT and one to three context variants for executable coding')
    repeats, budget = request.get('context_repeats', 3), request.get('context_budget_bytes', 12000)
    if type(repeats) is not int or not 1 <= repeats <= 5 or type(budget) is not int or not 4096 <= budget <= 24000:
        raise ValueError('Choose one to five repeats and a bounded context budget')
    task_counts = {'quick': 4, 'standard': 8, 'deep': 12} if request.get('task_pack') == 'repository' else {'quick': 12, 'standard': 36, 'deep': 100}
    if task_counts[request['preset']] * len(variants) * repeats > 1000:
        raise ValueError('Context experiments support at most 1000 task episodes; reduce repeats or choose a smaller extent')
    request['context_repeats'], request['context_budget_bytes'] = repeats, budget


def trials(tasks, request):
    variants = request.get('context_variants')
    if not variants:
        return [(task, None, 0) for task in tasks]
    return [(task, arm, repeat) for repeat in range(request['context_repeats'])
            for arm in (list(reversed(variants)) if repeat % 2 else variants) for task in tasks]


def is_digest(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def execution_identity(value):
    return isinstance(value, str) and bool(value.strip())


def freeze(tasks, run, settings, *, semantic_identity=None):
    """Retain the controlled inputs before any arm dispatches the model."""
    request = run['request']
    inputs = {task['id']: public_context_inputs(task) for task in tasks}
    task_manifest = [{key: task.get(key) for key in ('id', 'taskDigest', 'graderDigest', 'family')} for task in tasks]
    if (len(inputs) != len(tasks) or not task_manifest
            or not execution_identity(run.get('provenance', {}).get('executionIdentity'))
            or any(not isinstance(row[key], str) or not row[key].strip() for row in task_manifest for key in ('id', 'family'))
            or any(not is_digest(row[key]) for row in task_manifest for key in ('taskDigest', 'graderDigest'))):
        raise ValueError('Context experiment task identity is ambiguous')
    identity = dict(protocol='public-fixture-context-v1', model=request['model'],
                    executionIdentity=run['provenance']['executionIdentity'],
                    settingsDigest=digest(settings), variants=list(request['context_variants']),
                    selectorDigest=selector_digest(),
                    encoderIdentity=semantic_identity, encoderIdentityDigest=digest(semantic_identity),
                    repeats=request['context_repeats'], budgetBytes=request['context_budget_bytes'],
                    ordering='COUNTERBALANCED',
                    contextManifestDigests={task: digest(value) for task, value in inputs.items()},
                    sourceDigests={task: value['sourceSha'] for task, value in inputs.items()},
                    referenceDigests={task: digest([row for row in value['files'] if row['path'].startswith('docs/')])
                                      for task, value in inputs.items()},
                    taskManifest=task_manifest, taskManifestDigest=digest(task_manifest))
    identity['digest'] = digest(identity)
    return identity


def selector_digest():
    root = Path(__file__).resolve().parents[1]
    return digest({name: content_digest((root / name).read_text(encoding='utf-8'))
                   for name in ('benchmarks/context_fixture.py', 'benchmarks/context_experiment.py',
                                'benchmarks/context_semantic.py', 'v14/engineering_memory.py', 'v14/task_context.py')})


def prepare(task, variant, budget, *, cancelled=lambda: False, now=time.monotonic, frozen=None, semantic_ranker=None):
    from v14.task_context import build_bundle
    inputs = public_context_inputs(task)
    expected_task = next((row for row in frozen.get('taskManifest', []) if row.get('id') == task['id']), None) if frozen is not None else None
    if frozen is not None and (frozen['selectorDigest'] != selector_digest() or frozen['contextManifestDigests'].get(task['id']) != digest(inputs)
                               or variant not in frozen['variants'] or budget != frozen['budgetBytes']
                               or expected_task is None or any(task.get(key) != expected_task.get(key) for key in ('taskDigest', 'graderDigest'))
                               or frozen.get('encoderIdentity') != (semantic_ranker.identity if semantic_ranker else None)):
        raise ValueError('BENCHMARK_CONTEXT_CHANGED_AFTER_FREEZE')
    if semantic_ranker is not None:
        semantic_ranker.validate()
    measurement_start = len(semantic_ranker.measurements) if semantic_ranker else 0
    files = {row['path']: row for row in inputs['files']}
    tree = {path: {'sha': row['blobSha'], 'mode': '100644', 'size': len(row['content'].encode())}
            for path, row in files.items()}
    cache, calls = {}, defaultdict(int)
    hits = 0
    def load(path):
        nonlocal hits
        calls[path] += 1
        if path in cache:
            hits += 1
            return dict(cache[path], cache_hit=True)
        row = files[path]
        cache[path] = dict(sha=row['blobSha'], content=row['content'], cache_hit=False)
        return cache[path]
    def build():
        return build_bundle(repository='benchmark/fixture', source_sha=inputs['sourceSha'],
                            source_identity_kind='FROZEN_FIXTURE_MANIFEST', tree=tree,
                            load_source=load, query=inputs['query'], variant=variant,
                            max_bytes=budget, cancel=cancelled,
                            semantic_scores=(lambda query, rows: semantic_ranker.scores(query, rows, source_sha=inputs['sourceSha']))
                                            if semantic_ranker is not None and variant == 'SEMANTIC' else None)
    began = now()
    cold = build()
    cold_ms = max(0, (now() - began) * 1000)
    cold_reads, repeated = sum(calls.values()), sum(max(0, n - 1) for n in calls.values())
    calls.clear()
    began = now()
    warm = build()
    warm_ms = max(0, (now() - began) * 1000)
    if semantic_ranker is not None:
        semantic_ranker.validate()
    if cold.get('source_sha') != inputs['sourceSha'] or warm.get('source_sha') != inputs['sourceSha']:
        raise ValueError('BENCHMARK_CONTEXT_REVISION_CHANGED')
    allowed = {'features', 'selected_ranges', 'documents', 'test_candidates', 'coverage', 'variant', 'source_sha', 'source_identity_kind'}
    selected = {key: value for key, value in warm.items() if key in allowed}
    supplement = '' if variant == 'CURRENT' else ('\n\nRepository reference for the same frozen public starting source. '
        'These are source observations, not executed test evidence.\n' + json.dumps(selected, sort_keys=True, separators=(',', ':'), ensure_ascii=True))
    def paths(key):
        return {row['path'] if isinstance(row, dict) else row for row in warm.get(key, [])
                if isinstance(row, str) or isinstance(row, dict) and isinstance(row.get('path'), str)}
    selected_paths = paths('selected_ranges') | paths('documents')
    selected_sources = selected_paths & set(inputs['declaredSourcePaths'])
    receipt = dict(variant=variant, manifestDigest=digest(inputs), sourceSha=inputs['sourceSha'],
                   sourceIdentityKind='FROZEN_FIXTURE_MANIFEST', referenceDigest=digest([row for row in inputs['files'] if row['path'].startswith('docs/')]),
                   indexDigest=inputs['sourceSha'], supplementDigest=content_digest(supplement),
                   indexIdentityScope='FROZEN_PUBLIC_FILE_MANIFEST_NO_PERSISTENT_SYMBOL_INDEX',
                   contextBytes=len(supplement.encode()), estimatedContextTokens=(len(supplement.encode()) + 2) // 3,
                   tokenMeasurement='UTF8_BYTES_DIVIDED_BY_THREE_ESTIMATE', coldPreparationMs=cold_ms, warmPreparationMs=warm_ms,
                   coldSourceReads=cold_reads, warmSourceReads=sum(calls.values()), warmCacheHits=hits,
                   repeatedSourceReads=repeated + sum(max(0, n - 1) for n in calls.values()),
                   selectedPathCount=len(selected_paths), selectedSourceCount=len(selected_sources),
                   declaredSourceCount=len(inputs['declaredSourcePaths']), selectedTestCount=len(paths('test_candidates')),
                   retrievalMode=warm.get('retrieval', 'UNKNOWN'),
                   encoderIdentityDigest=digest(semantic_ranker.identity if semantic_ranker else None),
                   semanticPreparation=semantic_ranker.measurements[measurement_start:] if semantic_ranker else [],
                   revisionVerified=True, dispatched=False, relevantFileRecall=None, testRecall=None,
                   recallScope='DECLARED_PUBLIC_SOURCE_COVERAGE_ONLY',
                   preparationScope='LOCAL_SELECTOR_CACHE_PROBES_NOT_MODEL_OR_NETWORK_COLD_START')
    if frozen is not None:
        receipt['experimentDigest'] = frozen['digest']
    return supplement, receipt


def frozen_task_families(frozen):
    """Family counts require the retained task identities, never case labels."""
    if not isinstance(frozen, dict):
        return None
    manifest = frozen.get('taskManifest')
    if (not isinstance(manifest, list) or not manifest or frozen.get('taskManifestDigest') != digest(manifest)
            or any(not isinstance(row, dict) or set(row) != {'id', 'taskDigest', 'graderDigest', 'family'}
                   or any(not isinstance(row[key], str) or not row[key].strip() for key in ('id', 'family'))
                   or any(not is_digest(row[key]) for key in ('taskDigest', 'graderDigest')) for row in manifest)):
        return None
    families = {row['id']: row['family'] for row in manifest}
    if len(families) != len(manifest):
        return None
    for key in ('contextManifestDigests', 'sourceDigests', 'referenceDigests'):
        identities = frozen.get(key)
        if (not isinstance(identities, dict) or identities.keys() != families.keys()
                or any(not is_digest(value) for value in identities.values())):
            return None
    return families


def requested_allowances(case):
    """Recorded runner caps precede the selected profile's additional clamp."""
    attempts = case.get('attempts')
    if not isinstance(attempts, list) or not attempts:
        return None
    values = [attempt.get('outputAllowance') for attempt in attempts]
    return sorted(set(values)) if all(type(value) is int and value > 0 for value in values) else None


def summary(cases, planned, request, frozen=None):
    variants = request['context_variants']
    task_families = frozen_task_families(frozen)
    frozen_valid = (task_families is not None and frozen.get('digest') == digest({key: value for key, value in frozen.items() if key != 'digest'})
                    and frozen.get('model') == request['model'] and frozen.get('variants') == variants
                    and execution_identity(frozen.get('executionIdentity'))
                    and is_digest(frozen.get('settingsDigest')) and is_digest(frozen.get('selectorDigest'))
                    and frozen.get('encoderIdentityDigest') == digest(frozen.get('encoderIdentity'))
                    and type(frozen.get('repeats')) is int and 1 <= frozen['repeats'] <= 5
                    and type(frozen.get('budgetBytes')) is int and 4096 <= frozen['budgetBytes'] <= 24000
                    and frozen.get('repeats') == request['context_repeats'] and frozen.get('budgetBytes') == request['context_budget_bytes'])
    task_identities = {row['id']: row for row in frozen['taskManifest']} if frozen_valid else {}
    eligible, grouped = {}, defaultdict(list)
    for case in cases:
        receipt = case.get('contextReceipt') or {}
        arm, repeat = case.get('contextVariant'), case.get('contextRepeat')
        if arm not in variants or type(repeat) is not int or not 0 <= repeat < request['context_repeats']:
            continue
        key = (case.get('taskId', case.get('id')), arm, repeat)
        grouped[arm].append(case)
        if key in eligible:
            eligible[key] = None
            continue
        task = key[0]
        delivered = (case.get('attempts') or [case])[-1]
        bound = (frozen_valid and task in task_families
                 and all(case.get(key) == delivered.get(key) == task_identities[task][key] for key in ('taskDigest', 'graderDigest'))
                 and receipt.get('experimentDigest') == frozen['digest']
                 and receipt.get('manifestDigest') == frozen['contextManifestDigests'].get(task)
                 and receipt.get('sourceSha') == frozen['sourceDigests'].get(task)
                 and receipt.get('referenceDigest') == frozen['referenceDigests'].get(task)
                 and receipt.get('indexDigest') == frozen['sourceDigests'].get(task)
                 and receipt.get('encoderIdentityDigest') == frozen.get('encoderIdentityDigest') == digest(frozen.get('encoderIdentity'))
                 and receipt.get('sourceIdentityKind') == 'FROZEN_FIXTURE_MANIFEST'
                 and is_digest(receipt.get('promptDigest')))
        verified = (delivered.get('executed') is True and delivered.get('graded') is True
                    and delivered.get('qualified') is True and delivered.get('outcomeKnown') is True
                    and delivered.get('integrity') == 'VERIFIED'
                    and ((delivered.get('status') == 'PASSED' and delivered.get('resolved') is True and delivered.get('failureDomain') is None)
                         or (delivered.get('status') == 'FAILED' and delivered.get('resolved') is False and delivered.get('failureDomain') == 'MODEL')))
        rejected = (delivered.get('graded') is True and delivered.get('outcomeKnown') is True
                    and delivered.get('qualified') is False and delivered.get('status') == 'FAILED'
                    and delivered.get('failureDomain') == 'MODEL' and delivered.get('resolved') is False)
        known = (bound and receipt.get('variant') == arm and receipt.get('revisionVerified') is True
                 and receipt.get('dispatched') is True and (verified or rejected)
                 and case.get('finalResolved') is (delivered.get('resolved') is True))
        eligible[key] = case if known else None
    metrics = {}
    def measured_total(rows, key):
        if not rows or any(not row.get('attempts') for row in rows):
            return None
        values = [(attempt.get('timing') or {}).get(key) for row in rows for attempt in row.get('attempts', [])]
        return sum(values) if values and all(type(v) in (int, float) and v >= 0 and math.isfinite(v) for v in values) else None
    for arm in variants:
        rows = grouped[arm]
        receipts = [row['contextReceipt'] for row in rows if row.get('contextReceipt')]
        known = [row for (task, variant, repeat), row in eligible.items() if variant == arm and row is not None]
        def mid(key):
            values = [r.get(key) for r in receipts]
            return median(values) if values and all(type(v) in (int, float) and math.isfinite(v) for v in values) else None
        metrics[arm] = dict(recorded=len(rows), evaluated=len(known), resolved=sum(row.get('finalResolved') is True for row in known),
                            medianContextBytes=mid('contextBytes'), medianColdPreparationMs=mid('coldPreparationMs'),
                            medianWarmPreparationMs=mid('warmPreparationMs'), inputTokens=measured_total(rows, 'inputTokens'),
                            outputTokens=measured_total(rows, 'outputTokens'), generationMs=measured_total(rows, 'totalMs'),
                            recordedOutputAllowances=sorted({attempt['outputAllowance'] for row in rows for attempt in row.get('attempts', [])
                                                            if type(attempt.get('outputAllowance')) is int and attempt['outputAllowance'] > 0}),
                            unobservedOutputAllowanceCases=sum(requested_allowances(row) is None for row in rows),
                            sourceReads=sum(r.get('coldSourceReads', 0) + r.get('warmSourceReads', 0) for r in receipts),
                            warmCacheHits=sum(r.get('warmCacheHits', 0) for r in receipts),
                            repeatedSourceReads=sum(r.get('repeatedSourceReads', 0) for r in receipts),
                            retrievalModes=sorted({r.get('retrievalMode', 'UNKNOWN') for r in receipts}),
                            semanticRankingStatuses=sorted({probe.get('status', 'UNKNOWN') for r in receipts for probe in r.get('semanticPreparation', [])}),
                            encoderIdentityDigest=frozen.get('encoderIdentityDigest') if frozen_valid else None,
                            staleOrUnboundReceipts=len(rows) - len(known),
                            declaredSourceCoverage=(sum(r.get('selectedSourceCount', 0) for r in receipts) /
                                                    sum(r.get('declaredSourceCount', 0) for r in receipts))
                                                    if sum(r.get('declaredSourceCount', 0) for r in receipts) else None,
                            medianTimeToFirstVerifiedPatchMs=median([row['firstVerifiedPatchMs'] for row in known if type(row.get('firstVerifiedPatchMs')) in (int, float)])
                                                    if any(type(row.get('firstVerifiedPatchMs')) in (int, float) for row in known) else None,
                            falseCompletionCount=None, completionClaimAssessment='NOT_MEASURED_STRUCTURED_PATCH_TASK',
                            relevantFileRecall=None, testRecall=None, apiCost=None)
    comparisons = []
    expected = len(task_families) * frozen['repeats'] if frozen_valid else None
    for arm in variants[1:]:
        families, matched = defaultdict(list), 0
        budget_matched = budget_mismatched = budget_unobserved = 0
        for (task, variant, repeat), baseline in eligible.items():
            other = eligible.get((task, arm, repeat))
            if variant != 'CURRENT' or baseline is None or other is None:
                continue
            if any(baseline['contextReceipt'].get(key) != other['contextReceipt'].get(key)
                   for key in ('manifestDigest', 'sourceSha', 'referenceDigest', 'indexDigest', 'encoderIdentityDigest')):
                continue
            matched += 1
            families[task_families[task]].append(int(other.get('finalResolved') is True) - int(baseline.get('finalResolved') is True))
            baseline_allowances, other_allowances = requested_allowances(baseline), requested_allowances(other)
            if baseline_allowances is None or other_allowances is None:
                budget_unobserved += 1
            elif len(baseline_allowances) != 1 or baseline_allowances != other_allowances:
                budget_mismatched += 1
            else:
                budget_matched += 1
        means = [mean(values) for values in families.values()]
        difference = mean(means) if means else None
        radius = math.sqrt(2 * math.log(40) / len(means)) if len(means) >= 2 else None
        interval = [max(-1, difference - radius), min(1, difference + radius)] if radius is not None else None
        budget_status = ('MISMATCH' if budget_mismatched else 'UNKNOWN' if budget_unobserved
                         else 'MATCHED_RECORDED_REQUESTS' if budget_matched else 'NO_PAIRED_EVIDENCE')
        state = ('IDENTITY_MISMATCH' if not frozen_valid else 'OUTPUT_BUDGET_MISMATCH' if budget_mismatched
                 else 'OUTPUT_BUDGET_UNKNOWN' if budget_unobserved else 'INCOMPLETE' if matched != expected
                 else 'INSUFFICIENT_EVIDENCE' if len(means) < 12 or request['context_repeats'] < 3 else 'DESCRIPTIVE_PAIRED_RESULT')
        if budget_mismatched or budget_unobserved:
            difference = interval = None
        if state in {'INSUFFICIENT_EVIDENCE', 'DESCRIPTIVE_PAIRED_RESULT'} and arm == 'SEMANTIC' and any(mode != 'LOCAL_SEMANTIC_AND_LEXICAL' for mode in metrics[arm]['retrievalModes']):
            state = 'SEMANTIC_FALLBACK_RECORDED'
        comparisons.append(dict(variant=arm, baseline='CURRENT', matchedPairs=matched, plannedPairs=expected,
                                budgetMatchedPairs=budget_matched, budgetMismatchedPairs=budget_mismatched,
                                budgetUnobservedPairs=budget_unobserved, outputBudgetStatus=budget_status,
                                independentFamilies=len(means), differencePercentagePoints=difference * 100 if difference is not None else None,
                                interval95=[v * 100 for v in interval] if interval else None, state=state))
    return dict(version='benchmark-context-comparison-v1', score=None, maximum=None, qualified=False,
                label='Same-model context experiment; paired completion and measured effort',
                contextComparisons=comparisons, contextArms=metrics, contextExperimentIdentity=frozen,
                planned=expected * len(variants) if expected is not None else None, reportedPlanned=planned,
                knownOutcomes=sum(row is not None for row in eligible.values()),
                resolved=sum(row is not None and row.get('finalResolved') is True for row in eligible.values()),
                scope='FROZEN_PUBLIC_FIXTURE_SUPPLEMENT_SAME_MODEL_SOURCE_TASKS',
                outputBudgetMeasurement='RECORDED_REQUESTED_CAP_BEFORE_SELECTED_BINDING_CLAMP',
                limitations=['Declared source coverage is not independent relevant-file or test recall.',
                             'Recorded output allowances precede the selected-profile clamp; provider effective limits are not measured.',
                             'No generated performance, cost, battery, or superiority score; incomplete usage stays unknown.'])


def invalidate_summary(value, state):
    """A failed final identity/cleanup gate cannot leave a completed comparison label."""
    if value.get('version') != 'benchmark-context-comparison-v1':
        return value
    return dict(value, score=None, qualified=False, invalidatedBy=state,
                contextComparisons=[dict(pair, state='INVALIDATED', invalidatedBy=state)
                                    for pair in value.get('contextComparisons', [])])
