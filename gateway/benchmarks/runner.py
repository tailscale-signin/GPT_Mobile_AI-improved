"""Actual selected-route measurements. No score is created from configuration."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import random
import re
import threading
import time
import traceback

import requests

from benchmark_selected_model import BenchmarkInterrupted, BenchmarkInvocationError, OUTPUT_LIMIT, measure
from benchmarks import coding, fixtures, sandbox
from benchmarks.contracts import coding_score, content_digest, coverage, digest, paired_summary, repository_score
from desktop.benchmark import CASES, client_session, get_json, matches, models
from v14 import VERSION


def benchmark_error_receipt(error, stage):
    """Retain a bounded failure category and phase, never raw provider text.

    Unknown model/transport outcomes must not be relabelled as invalid model
    patches or treated as permission to replay an uncertain HTTP request.
    """
    known = {'SELECTED_EXECUTION_CHANGED', 'GATEWAY_NOT_READY', 'BENCHMARK_ENVIRONMENT_CHANGED',
             'TASK_REVISION_CHANGED', 'GRADER_REVISION_CHANGED', 'PROPOSAL_IDENTITY_CHANGED',
             'SELECTED_MODEL_NOT_ADVERTISED', 'GATEWAY_INSTANCE_CHANGED'}
    if isinstance(error, BenchmarkInvocationError):
        code = error.code
    elif isinstance(error, requests.Timeout):
        code = 'BENCHMARK_TRANSPORT_TIMEOUT'
    elif isinstance(error, requests.ConnectionError):
        code = 'BENCHMARK_CONNECTION_FAILED'
    elif isinstance(error, requests.HTTPError):
        code = 'BENCHMARK_HTTP_ERROR'
    elif isinstance(error, requests.RequestException):
        code = 'BENCHMARK_TRANSPORT_ERROR'
    elif isinstance(error, TimeoutError):
        code = 'BENCHMARK_LOCAL_TIMEOUT'
    elif isinstance(error, TypeError):
        code = 'BENCHMARK_RESPONSE_TYPE_ERROR'
    elif isinstance(error, ValueError):
        code = str(error) if str(error) in known else 'BENCHMARK_VALUE_ERROR'
    else:
        code = 'BENCHMARK_UNCLASSIFIED_ERROR'
    result = {'error': type(error).__name__, 'errorCode': code, 'failureStage': stage}
    frames = traceback.extract_tb(error.__traceback__, limit=-8)
    if frames:
        # Only a digest of code coordinates, never a path, source line or message.
        result['failureFingerprint'] = digest([(frame.name, frame.lineno) for frame in frames])
    if isinstance(error, BenchmarkInvocationError):
        if error.admission_code is not None:
            result['admissionCode'] = error.admission_code
        if error.status is not None:
            result['httpStatus'] = error.status
        if error.job_id is not None:
            result.update(gatewayJobId=error.job_id, requestOutcome='OBSERVATION_FAILED')
        elif error.pre_dispatch or error.status in {400, 401, 403, 404, 409, 413, 422}:
            result['requestOutcome'] = 'REJECTED'
        else:
            result['requestOutcome'] = 'UNKNOWN'
        if error.settlement is not None:
            result['jobSettlement'] = error.settlement
        if error.pre_dispatch and error.job_id is None:
            result['dispatchState'] = 'NOT_DISPATCHED'
        if error.retry_after is not None:
            result['retryAfterSeconds'] = error.retry_after
    if isinstance(error, requests.RequestException):
        # The server may have accepted the request before the client failed.
        result['requestOutcome'] = 'UNKNOWN'
        response = getattr(error, 'response', None)
        status = getattr(response, 'status_code', None)
        if type(status) is int and 100 <= status <= 599:
            result['httpStatus'] = status
    return result


def best_verified_attempt_index(attempts):
    """Retain the strongest measured repair without hiding unsafe attempts.

    Every attempted candidate remains in the evidence journal. Never promote an
    unexecuted/unknown observation or roll back a terminal integrity violation.
    """
    last = len(attempts) - 1
    if last < 1 or attempts[last].get('status') not in {'FAILED', 'BLOCKED'} or any(
            row.get('integrity') == 'VIOLATION' for row in attempts):
        return last

    def rank(case):
        if (case.get('status') not in {'FAILED', 'BLOCKED'} or case.get('integrity') != 'VERIFIED'
                or case.get('executed') is not True or case.get('graded') is not True):
            return None
        checks = case.get('checks')
        if not isinstance(checks, dict):
            return None
        native = checks.get('hiddenBehavior')
        if case.get('qualified') is True and isinstance(native, dict):
            passed, total = native.get('passed'), native.get('total')
            if type(passed) is int and type(total) is int and 0 <= passed <= total and total > 0:
                return (passed / total, 0, 0, 0)
        hidden, total = checks.get('hiddenPassed'), checks.get('hiddenTotal')
        replay, replay_total = checks.get('replayPassed'), checks.get('replayTotal')
        if (type(hidden) is not int or type(total) is not int or not 0 <= hidden <= total
                or total == 0 or type(replay) is not int or type(replay_total) is not int
                or not 0 <= replay <= replay_total or replay_total == 0):
            return None
        earned = case.get('earned', 0)
        earned = float(earned) if type(earned) in (int, float) else 0.0
        detected = checks.get('independentDefectsCaught', 0)
        return (hidden / total, replay / replay_total, detected if type(detected) is int else 0, earned)

    if attempts[last].get('status') == 'BLOCKED' and rank(attempts[last]) is None:
        return last
    candidates = [(rank(row), index) for index, row in enumerate(attempts)]
    candidates = [(quality, index) for quality, index in candidates if quality is not None]
    # Prefer a physical measurement over a reused evaluation at equal quality.
    return max(candidates, default=(None, last), key=lambda item: (item[0] is not None, item[0] or (), not attempts[item[1]].get('evaluationReused'), item[1]))[1]


class BenchmarkRunner:
    def __init__(self, store, origin, cancelled, identity, settings, *, external_qualifier=None, context_ranker=None):
        self.store, self.origin, self.cancelled = store, origin, cancelled
        self.identity, self.settings = identity, settings
        self.receipt_lock = threading.Lock()
        self.cancellation_receipts = []
        self.external_qualifier = external_qualifier
        self.context_ranker = context_ranker

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
        frozen = run['provenance'].get('benchmarkSettingsDigest')
        if frozen is not None and digest(self.settings()) != frozen:
            raise sandbox.SandboxUnavailable('The frozen benchmark runtime settings changed.', 'BENCHMARK_ENVIRONMENT_CHANGED')

    def measure_case(self, run, name, turns, expected, *, validate=None, output_tokens=512, output_format=None, on_admitted=None):
        self.guard(run)
        try:
            with client_session() as client:
                def selected_post(url, **kwargs):
                    binding = run.get('provenance', {}).get('selectedBinding')
                    if binding:
                        payload = dict(kwargs['json'])
                        selected = binding.get('generation_parameters', {})
                        payload.update({key: value for key, value in selected.items() if key not in {'context_tokens', 'max_tokens', 'reasoning'}})
                        if 'reasoning' in selected:
                            payload['chat_template_kwargs'] = {'enable_thinking': selected['reasoning']}
                        payload['max_tokens'] = min(output_tokens, selected.get('max_tokens', output_tokens))
                        kwargs['json'] = payload
                    return client.post(url, **kwargs)
                measured = measure(selected_post, self.origin, run['request']['model'], turns,
                                   output_tokens=output_tokens, validate_output=validate or (lambda text: matches(text, expected)),
                                   gateway_options={'store_conversation': False, 'budget': {'totalDeadlineMs': 120000}},
                                   cancel_event=self.cancelled, cancel_request=self.cancel_request, output_format=output_format, on_admitted=on_admitted)
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
                'outputDigest': measured['outputSha256'], 'outputBytes': measured['outputBytes'],
                'finishReason': measured['finishReason'],
                'requestedModel': run['request']['model'], 'transport': 'HTTP_SSE',
                'observedModel': measured['observedModel'], 'modelReceipt': measured['modelReceipt'],
                'identityScope': measured['identityScope'], 'gatewayJobId': measured['gatewayJobId'],
                'actualGpu': None, 'nativeBatching': 'NOT_MEASURED'}

    def execute(self, run):
        self.guard(run)
        if run['request']['suite'] == 'coding_submission':
            return self.external_code(run)
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
        if suite == 'coding' and run['request'].get('context_variants'):
            from benchmarks.context_experiment import summary
            partial = summary(cases, planned, run['request'], (snapshot.get('summary') or {}).get('contextExperimentIdentity'))
        elif suite == 'coding':
            score = repository_score if run['request'].get('task_pack') == 'repository' else coding_score
            partial = score(cases, planned, run['request']['preset'])
        elif suite == 'coding_submission':
            from benchmarks.external import task_summary
            partial = task_summary(cases[0]) if cases else {'score': None, 'qualified': False}
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
                        **benchmark_error_receipt(error, 'MODEL_INVOCATION')}
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
                    except InterruptedError:
                        # Fence the worker before it can dequeue another trial.
                        # Running observers settle their own admitted requests.
                        self.cancelled.set()
                        raise
                    except (requests.RequestException, TimeoutError, ValueError, TypeError) as error:
                        return {'id': name, 'status': 'ERROR', 'executed': None, 'graded': False,
                                **benchmark_error_receipt(error, 'MODEL_INVOCATION')}
                if arm == 'sequential':
                    samples = [sample(entry) for entry in CASES]
                else:
                    with ThreadPoolExecutor(max_workers=width, thread_name_prefix='benchmark-request') as pool:
                        # Submit only the admitted wave. Previously every trial
                        # was queued at once, making cancellation race with
                        # already-scheduled work beyond the requested width.
                        futures, results = {}, {}
                        next_index = 0

                        def admit_one():
                            nonlocal next_index
                            if next_index >= len(CASES) or self.cancelled.is_set():
                                return
                            future = pool.submit(sample, CASES[next_index])
                            futures[future] = next_index
                            next_index += 1

                        for _ in range(min(width, len(CASES))):
                            admit_one()
                        try:
                            while futures:
                                future = next(as_completed(tuple(futures)))
                                index = futures.pop(future)
                                results[index] = future.result()
                                admit_one()
                        except InterruptedError:
                            # Wait for already-admitted observations to settle;
                            # no additional trial may be dispatched.
                            self.cancelled.set()
                            for pending in futures:
                                pending.cancel()
                            raise
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

    def execute_sandbox(self, run, *args, **kwargs):
        return sandbox.execute(*args, **kwargs,
                               retain=lambda name: self.store.claim_sandbox(run['id'], name),
                               release=lambda name: self.store.release_sandbox(run['id'], name))

    def sandbox_interrupted(self, run, ordinal, task, error, stage, attempts=(), timing=None):
        receipt = dict(error.cancellation, case=task['id'], stage=stage)
        with self.receipt_lock:
            self.cancellation_receipts.append(dict(receipt))
        observed = error.observation or {}
        case = {key: task[key] for key in ('id', 'family', 'title', 'capstone')}
        case.update({key: task[key] for key in ('taskId', 'contextVariant', 'contextRepeat', 'contextReceipt') if key in task})
        previous = self.store.get(run['id'])
        checkpoint = next((item for item in previous.get('activeCases', []) if item.get('id') == task['id']), {})
        case.update(startedAt=checkpoint.get('startedAt'), endedAt=time.time(), phase=stage,
                    status='INTERRUPTED', executed=observed.get('executed') if stage == 'CANDIDATE' else False, graded=False, resolved=False,
                    score=None, integrity='VERIFIED', error='SANDBOX_CLEANUP_UNCONFIRMED' if receipt['outcome'] == 'unknown' else 'BENCHMARK_STOPPED',
                    dimensions={key: None for key in coding.CODING_WEIGHTS}, cancellation=receipt,
                    sandboxObservation={key: observed[key] for key in ('status', 'executed', 'error', 'exitCode', 'outputDigest', 'durationMs') if key in observed})
        if timing is not None:
            case['timing'] = {key: timing.get(key) for key in ('firstMs', 'totalMs', 'inputTokens', 'outputTokens', 'finishReason')}
            case.update({key: timing.get(key) for key in ('observedModel', 'modelReceipt', 'identityScope', 'gatewayJobId')})
        if stage == 'CANDIDATE':
            case['attempts'] = [*attempts, dict(case)]
        self.store.case(run['id'], ordinal, case)

    def code(self, run):
        from benchmarks.calibration import self_check, task_check
        from benchmarks import context_experiment
        native = run['request'].get('task_pack') == 'repository'
        calibration = {'status': 'PER_TASK_NATIVE_RUNTIME'} if native else self_check()
        if not native and calibration['status'] != 'PASSED':
            raise sandbox.SandboxUnavailable('The independent grader calibration failed.', 'GRADER_CALIBRATION_FAILED')
        if native:
            from benchmarks import repository_tasks, repository_sandbox
            tasks = repository_tasks.task_manifest(run['request']['preset'], run['request']['seed'])
        else:
            tasks = fixtures.suite(run['request']['preset'], run['request']['seed'])
        trial_plan = context_experiment.trials(tasks, run['request'])
        if len(trial_plan) > 1000:
            raise ValueError('BENCHMARK_CONTEXT_TRIAL_LIMIT')
        def trial_id(task, arm, repeat):
            return task['id'] if arm is None else f'{task["id"]}:{arm}:{repeat}'
        self.store.update(run['id'], planned=len(trial_plan), casePlan=[trial_id(*trial) for trial in trial_plan],
                          progress='Checking isolated evaluator')
        settings = self.settings()
        context_identity = context_experiment.freeze(tasks, run, settings,
            semantic_identity=self.context_ranker.identity if self.context_ranker else None) if run['request'].get('context_variants') else None
        if context_identity is not None:
            self.store.update(run['id'], summary=context_experiment.summary([], len(trial_plan), run['request'], context_identity))
        docker = sandbox.preflight(dict(settings, sandbox_image=settings.get('repository_image', '')) if native else settings)
        cases = []
        for ordinal, (task, context_variant, context_repeat) in enumerate(trial_plan):
            self.guard(run)
            calibrated = {} if native else task_check(task)
            if not native and calibrated['status'] != 'PASSED':
                raise sandbox.SandboxUnavailable('A hidden grader control failed.', 'GRADER_NEGATIVE_CONTROL_FAILED')
            self.store.update(run['id'], progress=f'{ordinal + 1}/{len(trial_plan)} · {context_variant or "Coding"} · {task["title"]}')
            started_at = time.time()
            monotonic_started = time.monotonic()
            first_verified_ms = None
            active = {key: task[key] for key in ('id', 'family', 'title', 'capstone')}
            journal_task = dict(task, id=trial_id(task, context_variant, context_repeat))
            active.update(status='RUNNING', startedAt=started_at, phase='REFERENCE', executed=False, graded=False, id=journal_task['id'])
            if context_variant is not None:
                active.update(taskId=task['id'], contextVariant=context_variant, contextRepeat=context_repeat)
                journal_task.update(taskId=task['id'], contextVariant=context_variant, contextRepeat=context_repeat)
            self.store.case(run['id'], ordinal, active)
            # Pin a fresh, trusted reference evaluation before trusting this
            # task's environment; its expected answers remain outside Docker.
            if not native:
                try:
                    baseline = self.execute_sandbox(run, task['referenceSources'], task['vectors'], settings, self.cancelled, docker=docker)
                except sandbox.SandboxInterrupted as error:
                    self.sandbox_interrupted(run, ordinal, journal_task, error, 'REFERENCE')
                    raise
                if baseline.get('status') != 'COMPLETED' or len(baseline.get('outputs', [])) != len(task['vectors']) or any('output' not in row or not coding.same(row['output'], fixtures.expected(task, vector))
                        for row, vector in zip(baseline.get('outputs', []), task['vectors'])):
                    raise sandbox.SandboxUnavailable('The trusted reference failed in this sandbox; no model score was recorded.')
            task_prompt = task['prompt'] if native else fixtures.prompt(task)
            context_receipt = None
            if context_variant is not None:
                supplement, context_receipt = context_experiment.prepare(task, context_variant, run['request']['context_budget_bytes'],
                                                                         cancelled=self.cancelled.is_set, frozen=context_identity,
                                                                         semantic_ranker=self.context_ranker)
                task_prompt += supplement
                context_receipt['promptDigest'] = content_digest(task_prompt)
                active['contextReceipt'] = context_receipt
                journal_task['contextReceipt'] = context_receipt
            turns = [{'role': 'user', 'content': task_prompt}]
            attempts = []
            evaluated_attempts = []
            verified_candidates = {}
            final = None
            allowance, output_format = 4096, {'type': 'json_object'}
            for attempt in range(settings['repair_attempts'] + 1):
                answer = []
                admission_rejections = []
                retried_transient = False
                timing = None
                phase = 'MODEL_INVOCATION'
                def capture(text):
                    answer.append(text)
                    return True
                def record_invocation(**fields):
                    self.store.case(run['id'], ordinal, dict(active, phase='MODEL_INVOCATION', attempt=attempt + 1,
                                                            attempts=attempts, admissionRejections=admission_rejections, **fields))
                def admitted(job_id):
                    if context_receipt is not None:
                        context_receipt['dispatched'] = True
                    record_invocation(gatewayJobId=job_id)
                try:
                    record_invocation()
                    # Reduce the requested cap only after typed context rejection.
                    # An admitted Gateway job must first be confirmed settled.
                    while True:
                        try:
                            timing = self.measure_case(run, task['id'], turns, None, validate=capture,
                                                       output_tokens=allowance, output_format=output_format,
                                                       on_admitted=admitted)
                            if context_receipt is not None:
                                context_receipt['dispatched'] = True
                            break
                        except BenchmarkInvocationError as error:
                            rejected_before_admission = error.job_id is None and error.status in {400, 409, 413, 422}
                            settled_context_guard = (error.code == 'BENCHMARK_STREAM_REJECTED' and error.job_id is not None
                                                     and error.settlement in {'confirmed', 'terminal'}
                                                     and error.admission_code == 'context_budget_exceeded')
                            if error.job_id is not None and not settled_context_guard:
                                raise
                            next_allowance, next_format, delay = allowance, output_format, 0
                            context_rejected = error.admission_code in {
                                'EXECUTION_CONTEXT_EXHAUSTED', 'EXECUTION_OUTPUT_LIMIT', 'CONTEXT_LIMIT_EXCEEDED',
                                'context_length_exceeded', 'context_budget_exceeded',
                            }
                            if allowance > 1024 and (settled_context_guard or rejected_before_admission and context_rejected):
                                next_allowance = allowance // 2
                            elif error.status in {400, 422} and error.admission_code == 'unsupported_response_format' and output_format:
                                next_format = None
                            elif (error.pre_dispatch and error.status in {429, 503} and not retried_transient and
                                  (error.retry_after is None or error.retry_after <= 10)):
                                retried_transient = True
                                delay = max(1, error.retry_after or 0)
                            else:
                                raise
                            admission_rejections.append(dict(benchmark_error_receipt(error, phase), outputAllowance=allowance))
                            record_invocation()
                            allowance, output_format = next_allowance, next_format
                            if delay and self.cancelled.wait(delay):
                                raise InterruptedError('Benchmark stopped during retry wait')
                    # A terminated-at-max-tokens response is not an anchor error.
                    # Never pass a truncated JSON proposal into the evaluator.
                    finish = timing.get('finishReason')
                    if finish != 'stop':
                        code = 'PATCH_OUTPUT_TRUNCATED' if finish == 'length' else 'PATCH_GENERATION_INCOMPLETE'
                        final = coding.rejected_submission(task, code)
                    elif not answer:
                        code = 'PATCH_OUTPUT_TOO_LARGE' if timing.get('outputBytes', 0) > OUTPUT_LIMIT else 'PATCH_JSON_INVALID'
                        final = coding.rejected_submission(task, code)
                    else:
                        phase = 'CANDIDATE_EVALUATION'
                        self.store.case(run['id'], ordinal, dict(active, phase=phase, attempt=attempt + 1,
                                                                attempts=attempts, gatewayJobId=timing.get('gatewayJobId')))
                        if native:
                            final = repository_sandbox.grade(task, answer[0], settings, self.cancelled,
                                retain=lambda name: self.store.claim_sandbox(run['id'], name),
                                release=lambda name: self.store.release_sandbox(run['id'], name))
                            final.pop('_candidate_sources', None)
                            calibrated = final.get('calibration', {})
                        else:
                            final = coding.grade(task, answer[0], settings, self.cancelled, docker=docker, prior=verified_candidates,
                                                 execute=lambda *args, **kwargs: self.execute_sandbox(run, *args, **kwargs))
                    final = dict(final, outputAllowance=allowance, admissionRejections=admission_rejections,
                                 outputFormat='JSON_OBJECT' if output_format else 'PROMPT_JSON')
                    final['timing'] = {key: timing.get(key) for key in ('firstMs', 'totalMs', 'inputTokens', 'outputTokens', 'finishReason')}
                    # Case-level evidence is sufficient for later issue triage
                    # without storing unapproved model-generated patch content.
                    final.update({key: timing.get(key) for key in (
                        'observedModel', 'modelReceipt', 'identityScope', 'gatewayJobId')})
                    # The sandbox's outputDigest measures executed candidate
                    # outputs; a model submission has a separate digest.
                    final['submissionDigest'] = timing.get('outputDigest')
                    final['submissionBytes'] = timing.get('outputBytes')
                    final['executionScope'] = task.get('executionScope', 'ISOLATED_PYTHON_CONTRACT')
                except sandbox.SandboxInterrupted as error:
                    self.sandbox_interrupted(run, ordinal, journal_task, error, 'CANDIDATE', attempts, timing)
                    raise
                except (requests.RequestException, TimeoutError, ValueError, TypeError) as error:
                    if isinstance(error, sandbox.SandboxUnavailable):
                        raise
                    final = {'id': task['id'], 'family': task['family'], 'title': task['title'], 'capstone': task['capstone'],
                             'status': 'ERROR', 'executed': None, 'graded': False, 'resolved': False,
                             'integrity': 'VERIFIED', **benchmark_error_receipt(error, phase),
                             'admissionRejections': admission_rejections,
                             'dimensions': {key: None for key in coding.CODING_WEIGHTS}}
                if context_variant is not None:
                    if final.get('resolved') is True and final.get('graded') is True and first_verified_ms is None:
                        first_verified_ms = max(0, (time.monotonic() - monotonic_started) * 1000)
                    final.update(id=journal_task['id'], taskId=task['id'], contextVariant=context_variant, contextRepeat=context_repeat,
                                 contextReceipt=dict(context_receipt), firstVerifiedPatchMs=first_verified_ms)
                evaluated_attempts.append(final)
                attempts.append({key: value for key, value in final.items() if key not in {'dimensionDefinitions'}})
                self.store.case(run['id'], ordinal, dict(final, status='RUNNING', startedAt=started_at, phase='ATTEMPT_RECORDED', graded=False,
                                                             attempts=list(attempts), repairBudget=settings['repair_attempts']))
                # A model repair can improve regression vectors, but cannot
                # manufacture missing grader authority over input preservation.
                evaluator_only = final['status'] == 'BLOCKED' and not final.get('uncoveredDefectModules')
                if final.get('resolved') or final.get('integrity') == 'VIOLATION' or final['status'] == 'ERROR' or evaluator_only:
                    break
                # Do not reintroduce a full, possibly truncated assistant answer:
                # that wastes scarce local context and can repeat malformed data.
                feedback = {key: final[key] for key in ('error', 'failureStage', 'repairHint', 'parserLocation', 'checks', 'uncoveredDefectModules') if final.get(key) is not None}
                if final.get('evaluationReused'):
                    feedback['next'] = 'The previous proposal was already evaluated. Change the repair or supply a different regression vector.'
                turns = [{'role': 'user', 'content': task_prompt +
                          '\nPrevious submission failed independent validation: ' +
                          json.dumps(feedback, sort_keys=True, ensure_ascii=True) +
                          '\nReturn ONE concise complete JSON proposal for the ORIGINAL displayed files. '
                          'Use their exact BLOB hashes and unique anchors. No Markdown; do not claim tests ran.'}]
            selected_index = best_verified_attempt_index(attempts)
            final = dict(evaluated_attempts[selected_index])
            final['selectedAttempt'] = selected_index + 1
            final['taskCalibration'] = calibrated
            final['deliveredAttempt'] = len(attempts)
            final['initialResolved'] = attempts[0].get('resolved') is True
            final['finalResolved'] = attempts[-1].get('resolved') is True
            final['bestResolved'] = any(attempt.get('resolved') is True for attempt in attempts)
            final['attemptSelection'] = 'BEST_VERIFIED_MEASURED' if selected_index != len(attempts) - 1 else 'LATEST'
            final['attempts'] = attempts
            final['startedAt'] = started_at
            final['endedAt'] = time.time()
            final['firstSubmissionScore'] = attempts[0].get('score')
            final['firstSubmissionResolved'] = attempts[0].get('resolved', False)
            final['repairBudget'] = settings['repair_attempts']
            if context_variant is not None:
                final.update(id=journal_task['id'], taskId=task['id'], contextVariant=context_variant, contextRepeat=context_repeat,
                             contextReceipt=dict(context_receipt), firstVerifiedPatchMs=first_verified_ms)
            cases.append(final)
            self.checkpoint(run, ordinal, final)
        if run['request'].get('context_variants'):
            return context_experiment.summary(cases, len(trial_plan), run['request'], context_identity)
        summary = (repository_score if native else coding_score)(cases, len(tasks), run['request']['preset'])
        if native:
            calibration = {'status': 'PASSED' if all(case.get('taskCalibration', {}).get('status') == 'PASSED' for case in cases) else 'INCOMPLETE',
                           'scope': 'PER_TASK_NATIVE_RUNTIME', 'tasks': len(cases)}
        summary.update(fixtureVersion=repository_tasks.VERSION if native else fixtures.VERSION, fixtureManifest=digest([task['manifestDigest'] for task in tasks]),
                       uniqueProblemCount=len({task['problemId'] for task in tasks}), trialCount=len(tasks),
                       firstSubmissionResolved=sum(case['firstSubmissionResolved'] for case in cases),
                       repairBudget=settings['repair_attempts'],
                       graderDigest=tasks[0]['graderDigest'], calibration=calibration,
                       executionScope='ISOLATED_NATIVE_REPOSITORY_CONTRACT' if native else 'ISOLATED_PYTHON_CONTRACT',
                       limitations=['Native Kotlin/JVM state, SQLite migrations and Actions YAML/shell contract fixtures; no full Android, Room processor or hosted Actions certification.',
                                    'Only measured task outcomes qualify; unmeasured secondary rubric dimensions remain unavailable.'] if native else
                                   ['Original isolated Python repository tasks, not a general intelligence or public benchmark score.',
                                    'Workflow, safety and recovery dimensions cover the declared fixture contract; no live GitHub operations or device execution are claimed.',
                                    'Input preservation is verified for the declared bounded Python subset; out-of-contract syntax and measured candidate exhaustion count as model failures.',
                                    'Seeded variants reuse twelve task families; repeats are not independent new capabilities.'])
        return summary

    def external_code(self, run):
        """Grade one task produced by the caller's model; never invoke another model."""
        from benchmarks.calibration import self_check, task_check
        from benchmarks.external import environment_receipt, task_summary, select_task
        request = run['request']
        repository_pack = request.get('task_pack') == 'repository'
        task = select_task(request['task_id'], request['seed'], request.get('task_pack', 'python'))
        if task['manifestDigest'] != request['fixture_digest']:
            raise sandbox.SandboxUnavailable('The frozen task or grader changed before execution.', 'TASK_REVISION_CHANGED')
        proposal = self.store.private_submission(run['id'])
        if content_digest(proposal) != request['proposal_digest']:
            raise ValueError('PROPOSAL_IDENTITY_CHANGED')
        calibration, calibrated = {}, {}
        if not repository_pack:
            calibration = self_check()
            if calibration['status'] != 'PASSED':
                raise sandbox.SandboxUnavailable('The independent grader calibration failed.', 'GRADER_CALIBRATION_FAILED')
            calibrated = task_check(task)
            if calibrated['status'] != 'PASSED':
                raise sandbox.SandboxUnavailable('A hidden grader control failed.', 'GRADER_NEGATIVE_CONTROL_FAILED')
        self.store.update(run['id'], planned=1, casePlan=[task['id']], progress='Verifying one immutable coding task')
        active = {key: task[key] for key in ('id', 'family', 'title', 'capstone')}
        self.store.case(run['id'], 0, dict(active, status='RUNNING', phase='REFERENCE', graded=False, executed=False))
        settings = self.settings()
        environment = environment_receipt(settings, request.get('task_pack', 'python'), task['graderDigest'])
        if request.get('environment_digest', environment['environmentDigest']) != environment['environmentDigest']:
            raise sandbox.SandboxUnavailable('The frozen benchmark runtime settings changed.', 'BENCHMARK_ENVIRONMENT_CHANGED')
        candidate = None
        try:
            if repository_pack:
                from benchmarks.repository_sandbox import grade
                result = grade(task, proposal, settings, self.cancelled,
                               retain=lambda name: self.store.claim_sandbox(run['id'], name),
                               release=lambda name: self.store.release_sandbox(run['id'], name))
                candidate = result.pop('_candidate_sources', None)
                calibration = result.get('calibration', {})
                calibrated = result.get('taskCalibration', calibration)
            else:
                try:
                    coding.proposal(proposal, task)
                except (ValueError, KeyError, TypeError, RecursionError, OverflowError):
                    # A completed malformed model answer is observable without a
                    # compiler/container. Missing setup must not erase this failure.
                    result = coding.grade(task, proposal, settings, self.cancelled)
                else:
                    docker = sandbox.preflight(settings)
                    baseline = self.execute_sandbox(run, task['referenceSources'], task['vectors'], settings, self.cancelled, docker=docker)
                    if (baseline.get('status') != 'COMPLETED' or len(baseline.get('outputs', [])) != len(task['vectors'])
                            or any('output' not in row or not coding.same(row['output'], fixtures.expected(task, vector))
                                   for row, vector in zip(baseline.get('outputs', []), task['vectors']))):
                        raise sandbox.SandboxUnavailable('The trusted reference did not pass this evaluator.', 'GRADER_REFERENCE_FAILED')
                    self.guard(run)
                    self.store.case(run['id'], 0, dict(active, status='RUNNING', phase='CANDIDATE_EVALUATION', graded=False, executed=False))
                    result = coding.grade(task, proposal, settings, self.cancelled, docker=docker,
                                          execute=lambda *args, **kwargs: self.execute_sandbox(run, *args, **kwargs))
        except sandbox.SandboxInterrupted as error:
            self.sandbox_interrupted(run, 0, task, error, 'CANDIDATE')
            raise
        receipt = request['generation_receipt']
        result.update(problemId=task['problemId'], taskDigest=task['taskDigest'], graderDigest=task['graderDigest'],
                      **environment,
                      proposalDigest=request['proposal_digest'], receiptId=run['id'],
                      selectedAttempt=1, deliveredAttempt=1, repairBudget=0,
                      generationReceipt=receipt, modelIdentityScope='AUTHENTICATED_CLIENT_OBSERVATION',
                      independentProviderIdentityVerified=False, requestedModel=receipt['model'],
                      observedModel=receipt.get('observed_model'),
                      timing={'firstMs': None, 'totalMs': receipt.get('duration_ms'),
                              'inputTokens': receipt.get('input_tokens'), 'outputTokens': receipt.get('output_tokens')},
                      calibration=calibration, taskCalibration=calibrated)
        if request.get('evidence_lane') == 'LIVE_GITHUB':
            if result.get('qualified') is True and result.get('resolved') is True and self.external_qualifier is not None:
                if candidate is None and not repository_pack:
                    _, candidate, _ = coding.proposal(proposal, task)
                if candidate is None:
                    raise ValueError('CANDIDATE_RECEIPT_UNAVAILABLE')
                result = self.external_qualifier(run, task, candidate, result, self.cancelled)
            elif result.get('status') == 'FAILED' and result.get('outcomeKnown') is True:
                result.update(liveGitHub='NOT_EXECUTED', liveQualification='LOCAL_MODEL_FAILURE')
            else:
                result.update(qualified=False, resolved=False, score=None, status='BLOCKED',
                              liveGitHub='NOT_EXECUTED', qualificationBlockers=['LIVE_GITHUB_REQUIRES_VERIFIED_LOCAL_TASK'])
        self.guard(run)
        result['attempts'] = [{key: value for key, value in result.items() if key not in {'attempts', 'dimensionDefinitions'}}]
        self.checkpoint(run, 0, result)
        summary = task_summary(result)
        summary.update(calibration=calibration, fixtureManifest=task['taskDigest'])
        return summary
