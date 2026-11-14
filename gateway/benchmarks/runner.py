"""Actual selected-route measurements. No score is created from configuration."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import random
import re
import threading
import time

import requests

from benchmark_selected_model import BenchmarkInterrupted, measure
from benchmarks import coding, fixtures, sandbox
from benchmarks.contracts import coding_score, coverage, digest, paired_summary
from desktop.benchmark import CASES, client_session, get_json, matches, models
from v14 import VERSION


class BenchmarkRunner:
    def __init__(self, store, origin, cancelled, identity, settings):
        self.store, self.origin, self.cancelled = store, origin, cancelled
        self.identity, self.settings = identity, settings
        self.receipt_lock = threading.Lock()
        self.cancellation_receipts = []

    def cancel_request(self, job_id):
        receipt = {'jobId': job_id, 'outcome': 'unknown'}
        if not isinstance(job_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', job_id):
            return receipt
        with client_session() as client:
            url = self.origin + '/gateway/jobs/' + job_id
            with client.post(url + '/cancel', timeout=(1, 2), stream=True, allow_redirects=False) as response:
                body = bytearray()
                for chunk in response.iter_content(4096):
                    body.extend(chunk)
                    if len(body) > 16384:
                        return receipt
                if response.status_code == 200:
                    data = json.loads(body)
                    receipt['outcome'] = data.get('cancellationOutcome') if data.get('cancellationOutcome') in {'confirmed', 'requested', 'not_canceled'} else 'unknown'
            # A requested signal is not terminal evidence. Reconcile once with a
            # separately bounded observation; preserve unknown/requested if busy.
            with client.get(url, timeout=(1, 2), stream=True, allow_redirects=False) as response:
                body = bytearray()
                for chunk in response.iter_content(4096):
                    body.extend(chunk)
                    if len(body) > 16384:
                        return receipt
                if response.status_code == 200:
                    state = json.loads(body).get('status')
                    if state in {'cancelled', 'canceled', 'completed', 'failed', 'interrupted'}:
                        receipt.update(outcome='confirmed' if state in {'cancelled', 'canceled'} else 'terminal', status=state)
        return receipt

    def guard(self, run):
        if self.cancelled.is_set():
            raise InterruptedError('Benchmark stopped')
        if self.identity() != run['provenance']['executionIdentity']:
            raise ValueError('SELECTED_EXECUTION_CHANGED')

    def measure_case(self, run, name, turns, expected, *, validate=None, output_tokens=512):
        self.guard(run)
        try:
            with client_session() as client:
                measured = measure(client.post, self.origin, run['request']['model'], turns,
                                   output_tokens=output_tokens, validate_output=validate or (lambda text: matches(text, expected)),
                                   gateway_options={'store_conversation': False, 'budget': {'totalDeadlineMs': 120000}},
                                   cancel_event=self.cancelled, cancel_request=self.cancel_request)
        except BenchmarkInterrupted as error:
            with self.receipt_lock:
                self.cancellation_receipts.append(dict(error.cancellation, case=name))
            raise
        self.guard(run)
        timing = measured.get('backendTimings') or {}
        usage = measured.get('usage') or {}
        tokens, elapsed = timing.get('predicted_n'), timing.get('predicted_ms')
        return {'id': name, 'name': name, 'status': 'PASSED' if measured['passed'] else 'FAILED',
                'executed': True, 'graded': True, 'passed': measured['passed'],
                'firstMs': measured['firstContentMs'], 'totalMs': measured['totalMs'],
                'gapMs': measured['maximumContentGapMs'], 'inputTokens': usage.get('prompt_tokens'),
                'outputTokens': usage.get('completion_tokens'),
                'tokensPerSecond': 1000 * tokens / elapsed if tokens and elapsed else None,
                'outputDigest': measured['outputSha256'], 'finishReason': measured['finishReason'],
                'requestedModel': run['request']['model'], 'transport': 'HTTP_SSE',
                'observedModel': measured['observedModel'], 'modelReceipt': measured['modelReceipt'],
                'identityScope': measured['identityScope'], 'gatewayJobId': measured['gatewayJobId'],
                'actualGpu': None, 'nativeBatching': 'NOT_MEASURED'}

    def execute(self, run):
        self.guard(run)
        with client_session() as client:
            live = get_json(client, self.origin + '/gateway/live')
            if live.get('live') is not True or live.get('draining') or live.get('version') != VERSION:
                raise ValueError('GATEWAY_NOT_READY')
            if run['request']['model'] not in models(client, self.origin):
                raise ValueError('SELECTED_MODEL_NOT_ADVERTISED')
        method = {'quick_check': self.quick, 'batch_comparison': self.paired, 'coding': self.code}[run['request']['suite']]
        result = method(run)
        self.guard(run)
        with client_session() as client:
            if get_json(client, self.origin + '/gateway/live').get('instanceId') != live.get('instanceId'):
                raise ValueError('GATEWAY_INSTANCE_CHANGED')
        return result

    def checkpoint(self, run, ordinal, case):
        self.store.case(run['id'], ordinal, case)
        self.store.update(run['id'], completed=ordinal + 1)
        snapshot = self.store.get(run['id'])
        cases, planned = snapshot['cases'], snapshot['planned']
        suite = run['request']['suite']
        if suite == 'coding':
            partial = coding_score(cases, planned, run['request']['preset'])
        elif suite == 'batch_comparison':
            partial = paired_summary(cases, planned // 2)
        else:
            partial = {'score': None, 'qualified': False, 'maximum': 100, 'coverage': coverage(cases, planned)}
        self.store.update(run['id'], summary=partial)

    def quick(self, run):
        self.store.update(run['id'], planned=len(CASES))
        cases = []
        for ordinal, (name, turns, expected) in enumerate(CASES):
            self.guard(run)
            self.store.update(run['id'], progress=name)
            try:
                case = self.measure_case(run, name, turns, expected)
            except (requests.RequestException, TimeoutError, ValueError, TypeError) as error:
                case = {'id': name, 'name': name, 'status': 'ERROR', 'executed': None, 'graded': False,
                        'error': type(error).__name__}
            cases.append(case)
            self.checkpoint(run, ordinal, case)
        complete = all(c['graded'] for c in cases)
        return {'version': 'desktop-quick-v1', 'score': round(100 * sum(c.get('passed') is True for c in cases) / len(CASES), 1) if complete else None,
                'maximum': 100, 'qualified': complete, 'coverage': coverage(cases, len(CASES)),
                'scope': 'FIVE_TOOL_FREE_FIXTURES', 'label': 'Quick answer check; not a coding score'}

    def paired(self, run):
        count = {'quick': 3, 'standard': 10, 'deep': 30}[run['request']['preset']]
        width = run['request']['concurrency']
        self.store.update(run['id'], planned=count * 2,
                          progress='Warm-up (excluded from the comparison)')
        # One explicit warmup outside measured case population. Both arms then
        # receive identical fixture workload, output budget and selected route.
        self.measure_case(run, 'warmup', CASES[0][1], CASES[0][2])
        rng, cases = random.Random(run['request']['seed']), []
        orders = [('sequential', 'concurrent'), ('concurrent', 'sequential')]
        for pair in range(count):
            order = orders[pair % 2]
            if pair % 2 == 0 and rng.getrandbits(1):
                orders.reverse()
                order = orders[pair % 2]
            for arm in order:
                self.guard(run)
                self.store.update(run['id'], progress=f'Pair {pair + 1}/{count} · {arm}')
                started = time.perf_counter()
                samples = []
                def sample(entry):
                    name, turns, expected = entry
                    try:
                        return self.measure_case(run, name, turns, expected)
                    except (requests.RequestException, TimeoutError, ValueError, TypeError) as error:
                        return {'id': name, 'status': 'ERROR', 'executed': None, 'graded': False, 'error': type(error).__name__}
                if arm == 'sequential':
                    samples = [sample(entry) for entry in CASES]
                else:
                    with ThreadPoolExecutor(max_workers=width, thread_name_prefix='benchmark-request') as pool:
                        futures = {pool.submit(sample, entry): index for index, entry in enumerate(CASES)}
                        results = {}
                        for future in as_completed(futures):
                            results[futures[future]] = future.result()
                        samples = [results[index] for index in range(len(CASES))]
                elapsed = (time.perf_counter() - started) * 1000
                self.guard(run)
                correct = sum(s.get('passed') is True for s in samples)
                case = {'id': f'pair-{pair}-{arm}', 'pair': pair, 'arm': arm, 'order': list(order),
                        'status': 'PASSED' if correct == len(CASES) else 'FAILED', 'executed': True,
                        'graded': all(s.get('graded') is True for s in samples),
                        'correct': correct, 'planned': len(CASES), 'samples': samples,
                        'totalMs': elapsed, 'requestsPerSecond': len(CASES) * 1000 / elapsed,
                        'goodputPerSecond': correct * 1000 / elapsed,
                        'identityVerified': all(s.get('observedModel') == run['request']['model'] for s in samples),
                        'identityScope': 'STABLE_HTTP_ROUTE_AND_REPORTED_IDENTIFIER', 'modelArtifactDigest': None,
                        'requestedConcurrency': 1 if arm == 'sequential' else width,
                        'nativeInferenceBatching': 'NOT_VERIFIED', 'cacheCondition': 'UNKNOWN'}
                cases.append(case)
                self.checkpoint(run, len(cases) - 1, case)
        return paired_summary(cases, count)

    def code(self, run):
        tasks = fixtures.suite(run['request']['preset'], run['request']['seed'])
        self.store.update(run['id'], planned=len(tasks), progress='Checking isolated evaluator')
        settings = self.settings()
        docker = sandbox.preflight(settings)
        cases = []
        for ordinal, task in enumerate(tasks):
            self.guard(run)
            self.store.update(run['id'], progress=f'{ordinal + 1}/{len(tasks)} · {task["title"]}')
            # Pin a fresh, trusted reference evaluation before trusting this
            # task's environment; its expected answers remain outside Docker.
            baseline = sandbox.execute(task['referenceSources'], task['vectors'], settings, self.cancelled, docker=docker)
            if baseline.get('status') != 'COMPLETED' or len(baseline.get('outputs', [])) != len(task['vectors']) or any('output' not in row or not coding.same(row['output'], fixtures.expected(task, vector))
                    for row, vector in zip(baseline.get('outputs', []), task['vectors'])):
                raise sandbox.SandboxUnavailable('The trusted reference failed in this sandbox; no model score was recorded.')
            turns = [{'role': 'user', 'content': fixtures.prompt(task)}]
            attempts = []
            final = None
            for attempt in range(settings['repair_attempts'] + 1):
                answer = []
                def capture(text):
                    answer.append(text)
                    return True
                try:
                    timing = self.measure_case(run, task['id'], turns, None, validate=capture, output_tokens=4096)
                    final = coding.grade(task, answer[0], settings, self.cancelled, docker=docker)
                    final['timing'] = {key: timing.get(key) for key in ('firstMs', 'totalMs', 'inputTokens', 'outputTokens', 'finishReason')}
                    final.update({key: timing.get(key) for key in ('observedModel', 'modelReceipt', 'identityScope', 'gatewayJobId')})
                except (requests.RequestException, TimeoutError, ValueError, TypeError) as error:
                    if isinstance(error, sandbox.SandboxUnavailable):
                        raise
                    final = {'id': task['id'], 'family': task['family'], 'title': task['title'], 'capstone': task['capstone'],
                             'status': 'ERROR', 'executed': None, 'graded': False, 'resolved': False,
                             'integrity': 'VERIFIED', 'error': type(error).__name__, 'dimensions': {key: None for key in coding.CODING_WEIGHTS}}
                attempts.append({key: value for key, value in final.items() if key not in {'dimensionDefinitions'}})
                self.store.case(run['id'], ordinal, dict(final, status='RUNNING', graded=False, attempts=list(attempts), repairBudget=settings['repair_attempts']))
                if final.get('resolved') or final.get('integrity') == 'VIOLATION' or final['status'] == 'ERROR':
                    break
                turns = [{'role': 'user', 'content': fixtures.prompt(task)},
                         {'role': 'assistant', 'content': answer[0] if answer else '{}'},
                         {'role': 'user', 'content': 'The independent evaluator did not resolve the task. '
                          + json.dumps({key: final.get(key) for key in ('error', 'checks')})
                          + '. Return a full corrected proposal against the original displayed files. Hidden answers remain unavailable.'}]
            final['attempts'] = attempts
            final['firstSubmissionScore'] = attempts[0].get('score')
            final['firstSubmissionResolved'] = attempts[0].get('resolved', False)
            final['repairBudget'] = settings['repair_attempts']
            cases.append(final)
            self.checkpoint(run, ordinal, final)
        summary = coding_score(cases, len(tasks), run['request']['preset'])
        summary.update(fixtureVersion=fixtures.VERSION, fixtureManifest=digest([task['manifestDigest'] for task in tasks]),
                       uniqueProblemCount=len({task['problemId'] for task in tasks}), trialCount=len(tasks),
                       firstSubmissionResolved=sum(case['firstSubmissionResolved'] for case in cases),
                       repairBudget=settings['repair_attempts'],
                       limitations=['Original isolated Python repository tasks, not a general intelligence or public benchmark score.',
                                    'Workflow, safety and recovery dimensions cover the declared fixture contract; no live GitHub operations or device execution are claimed.',
                                    'Seeded variants reuse twelve task families; repeats are not independent new capabilities.'])
        return summary
