#!/usr/bin/env python3
"""Measure a fixed selected route. Settings are observations, never automatic tuning.

Run each server configuration separately. Do not infer first-token/user latency
from llama-bench; this harness measures streaming HTTP including queue/prefill.
No prompt, generated text, token, local model path or response body is exported.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import urlsplit

import requests


CASES = {
    'short': [{'role': 'user', 'content': 'Explain a binary search in three short paragraphs.'}],
    'long': [{'role': 'user', 'content': 'Summarize these deterministic fixture records and check the final record.\n' + '\n'.join(f'Record {i}: item {i % 31}; revision {i % 7}.' for i in range(1000))}],
    'tool_continuation': [{'role': 'user', 'content': 'Summarize the verified fixture evidence and identify unverified items.'},
                          {'role': 'assistant', 'content': 'Fixture evidence: source inspected; tests not run; no remote write performed.'},
                          {'role': 'user', 'content': 'Provide the final status with the evidence gaps.'}],
}


def digest_file(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


OUTPUT_LIMIT = 65536
ADMISSION_CODES = frozenset({
    'EXECUTION_CONTEXT_EXHAUSTED', 'EXECUTION_OUTPUT_LIMIT', 'EXECUTION_CONTEXT_UNAVAILABLE',
    'EXECUTION_OUTPUT_INVALID', 'EXECUTION_TOKEN_COUNT_INVALID', 'EXECUTION_MODEL_NOT_ADVERTISED',
    'EXECUTION_MODEL_NOT_LOADED', 'EXECUTION_RUNTIME_UNREACHABLE', 'EXECUTION_BINDING_REQUIRED',
    'EXECUTION_REASONING_UNSUPPORTED', 'CONTEXT_LIMIT_EXCEEDED', 'context_length_exceeded',
    'unsupported_response_format', 'PROVIDER_RATE_LIMITED', 'PROVIDER_UNAVAILABLE',
    'context_budget_exceeded', 'context_capacity_unverified', 'backend_timeout', 'backend_stream_interrupted',
})


class BenchmarkInvocationError(ValueError):
    def __init__(self, code, *, status=None, job_id=None, admission_code=None, pre_dispatch=False, retry_after=None):
        super().__init__(code)
        self.code, self.status, self.job_id, self.admission_code = code, status, job_id, admission_code
        self.pre_dispatch, self.retry_after = pre_dispatch, retry_after
        self.settlement = None


def rejection_code(value):
    error = value.get('error') if isinstance(value, dict) else None
    code = error.get('code') if isinstance(error, dict) else None
    return code if isinstance(code, str) and code in ADMISSION_CODES else None


def response_lines(response):
    # Buffer bytes to preserve split UTF-8 and CRLF, with a bound even when no
    # newline arrives. Test doubles and the standalone harness can supply lines.
    if not hasattr(response, 'iter_content'):
        yield from response.iter_lines(chunk_size=1024)
        return
    pending = bytearray()
    for chunk in response.iter_content(chunk_size=1024):
        pending.extend(chunk)
        while (end := pending.find(b'\n')) >= 0:
            if end > OUTPUT_LIMIT:
                raise BenchmarkInvocationError('BENCHMARK_SSE_FRAME_LIMIT')
            yield bytes(pending[:end]).removesuffix(b'\r')
            del pending[:end + 1]
        if len(pending) > OUTPUT_LIMIT:
            raise BenchmarkInvocationError('BENCHMARK_SSE_FRAME_LIMIT')
    if pending:
        yield bytes(pending)


def decode_event(line):
    if len(line) > OUTPUT_LIMIT:
        raise BenchmarkInvocationError('BENCHMARK_SSE_FRAME_LIMIT')
    if not line.startswith(b'data:'):
        return None
    raw = line[5:].strip()
    if raw == b'[DONE]':
        return 'DONE'
    try:
        value = json.loads(raw)
    except (ValueError, RecursionError) as error:
        raise BenchmarkInvocationError('BENCHMARK_SSE_JSON_INVALID') from error
    if not isinstance(value, dict):
        raise BenchmarkInvocationError('BENCHMARK_SSE_ENVELOPE_INVALID')
    return value


class BenchmarkInterrupted(InterruptedError):
    def __init__(self, receipt):
        super().__init__('Benchmark stopped')
        self.cancellation = receipt


def measure(post, endpoint, model, messages, *, headers=None, output_tokens=256, clock=time.perf_counter, validate_output=None, gateway_options=None, cancel_event=None, cancel_request=None, on_admitted=None, output_format=None):
    if cancel_event is not None and cancel_event.is_set():
        raise InterruptedError('Benchmark cancelled before dispatch')
    started = clock()
    first, previous, largest_gap, events, output_bytes = None, None, 0.0, 0, 0
    output = bytearray()
    content_digest = hashlib.sha256()
    usage, timings, done, finish, observed_model = None, None, False, None, None
    cancellation = {'outcome': 'unknown', 'jobId': None}
    payload = {'model': model, 'messages': messages, 'max_tokens': output_tokens,
               'temperature': 0, 'stream': True, 'stream_options': {'include_usage': True},
               'tools': [], 'tool_choice': 'none'}
    if gateway_options is not None:
        payload['gateway'] = gateway_options
    if output_format is not None:
        payload['response_format'] = output_format
    response = None
    finished = threading.Event()
    watcher = None
    def watch():
        while not finished.wait(.05):
            if cancel_event.is_set():
                if cancel_request is not None:
                    try:
                        cancellation.update(cancel_request(cancellation['jobId']))
                    except Exception:
                        cancellation['outcome'] = 'unknown'
                # The Gateway cancellation endpoint stops the admitted job;
                # closing the observer alone would not prove model cancellation.
                response.close()
                return
    try:
        # Gateway emits a heartbeat every five seconds. A bounded read timeout
        # also closes silent/broken streams during shutdown before the runtime
        # lifecycle ends. Non-Gateway CLI measurements retain their old bound.
        response = post(endpoint + '/v1/chat/completions', json=payload, headers=headers or {},
                        timeout=(5, 20) if cancel_event is not None else (10, 120), stream=True, allow_redirects=False)
        received_headers = getattr(response, 'headers', {})
        admitted = received_headers.get('X-Gateway-Job-ID') or received_headers.get('x-gateway-job-id')
        if isinstance(admitted, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', admitted):
            cancellation['jobId'] = admitted
            if on_admitted is not None:
                on_admitted(admitted)
        if cancel_event is not None:
            watcher = threading.Thread(target=watch, name='benchmark-stream-cancel', daemon=True)
            watcher.start()
        if response.status_code != 200:
            raw = bytearray()
            if hasattr(response, 'iter_content'):
                for chunk in response.iter_content(chunk_size=1024):
                    raw.extend(chunk)
                    if len(raw) > 8192 or clock() - started > 20:
                        break
            try:
                envelope = json.loads(raw) if len(raw) <= 8192 else None
            except (ValueError, RecursionError):
                envelope = None
            detail = envelope.get('error') if isinstance(envelope, dict) else None
            not_dispatched = isinstance(detail, dict) and detail.get('dispatch_state') == 'not_dispatched'
            retry_after = received_headers.get('Retry-After')
            retry_after = int(retry_after) if isinstance(retry_after, str) and re.fullmatch(r'[0-9]{1,4}', retry_after) else None
            raise BenchmarkInvocationError('BENCHMARK_HTTP_REJECTED', status=response.status_code,
                                           job_id=cancellation['jobId'], admission_code=rejection_code(envelope),
                                           pre_dispatch=not_dispatched, retry_after=retry_after)
        for line in response_lines(response):
            if cancel_event is not None and cancel_event.is_set():
                raise InterruptedError('Benchmark cancelled during response')
            if clock() - started > 180:
                raise TimeoutError('Benchmark request exceeded its total deadline')
            event = decode_event(line)
            if event == 'DONE':
                done = True
                break
            if not isinstance(event, dict):
                continue
            if event.get('error'):
                raise BenchmarkInvocationError('BENCHMARK_STREAM_REJECTED', admission_code=rejection_code(event))
            reported = event.get('model')
            if reported is not None and reported != model:
                raise BenchmarkInvocationError('BENCHMARK_MODEL_MISMATCH')
            if isinstance(reported, str):
                observed_model = reported
            choices = event.get('choices', [])
            if not isinstance(choices, list):
                raise BenchmarkInvocationError('BENCHMARK_SSE_ENVELOPE_INVALID')
            for choice in choices:
                delta = choice.get('delta', {}) if isinstance(choice, dict) else None
                if not isinstance(delta, dict):
                    raise BenchmarkInvocationError('BENCHMARK_SSE_ENVELOPE_INVALID')
                text = delta.get('content')
                reason = choice.get('finish_reason')
                if reason is not None and (not isinstance(reason, str) or not re.fullmatch(r'[a-z_]{1,32}', reason)):
                    raise BenchmarkInvocationError('BENCHMARK_SSE_ENVELOPE_INVALID')
                finish = reason or finish
                if isinstance(text, str) and text:
                    instant = clock()
                    first = instant if first is None else first
                    largest_gap = max(largest_gap, instant - previous) if previous is not None else largest_gap
                    previous = instant
                    events += 1
                    encoded = text.encode()
                    output_bytes += len(encoded)
                    content_digest.update(encoded)
                    if validate_output is not None:
                        if len(output) + len(encoded) <= OUTPUT_LIMIT:
                            output.extend(encoded)
            if isinstance(event.get('usage'), dict):
                usage = {key: value for key, value in event['usage'].items() if key in {'prompt_tokens', 'completion_tokens', 'total_tokens'} and type(value) is int and value >= 0}
            if isinstance(event.get('timings'), dict):
                timings = {key: value for key, value in event['timings'].items() if key in {'prompt_ms', 'predicted_ms', 'prompt_n', 'predicted_n'} and type(value) in (int, float) and math.isfinite(value) and value >= 0}
        if not done or finish is None:
            raise BenchmarkInvocationError('BENCHMARK_STREAM_INCOMPLETE')
        if first is None and output_format is None:
            raise BenchmarkInvocationError('BENCHMARK_NO_VISIBLE_OUTPUT')
    except Exception as error:
        if isinstance(error, BenchmarkInvocationError):
            error.job_id = cancellation['jobId']
        if cancel_event is not None and cancel_event.is_set():
            # The watcher records a requested/unknown outcome rather than
            # treating a closed response as proof of stopped inference.
            if watcher is not None:
                watcher.join(25)
            raise BenchmarkInterrupted(dict(cancellation)) from None
        if cancel_request is not None and (response is None or response.status_code == 200 or cancellation['jobId'] is not None):
            # A lost stream may leave an admitted worker running. Stop/reconcile
            # that exact observed job before the coordinator dispatches another
            # trial. Unconfirmed admission or cancellation ends this run.
            try:
                cancellation.update(cancel_request(cancellation['jobId']))
            except Exception:
                cancellation['outcome'] = 'unknown'
            if isinstance(error, BenchmarkInvocationError):
                error.settlement = cancellation['outcome']
            if cancellation['outcome'] not in {'confirmed', 'terminal', 'not_canceled'}:
                if isinstance(error, BenchmarkInvocationError):
                    cancellation.update(observationError=error.code, admissionCode=error.admission_code)
                raise BenchmarkInterrupted(dict(cancellation)) from None
        raise
    finally:
        finished.set()
        if response is not None:
            close = getattr(response, 'close', None)
            if close is not None:
                close()
        if watcher is not None:
            watcher.join(25)
    if cancel_event is not None and cancel_event.is_set():
        if cancellation['outcome'] == 'unknown' and cancel_request is not None:
            cancellation.update(cancel_request(cancellation['jobId']))
        raise BenchmarkInterrupted(dict(cancellation))
    result = {'firstContentMs': (first - started) * 1000 if first is not None else None, 'totalMs': (clock() - started) * 1000,
            'maximumContentGapMs': largest_gap * 1000, 'contentEvents': events, 'outputBytes': output_bytes,
            'outputSha256': content_digest.hexdigest(), 'finishReason': finish, 'usage': usage,
            'backendTimings': timings, 'queueMs': None, 'coldLoadMs': None, 'offload': 'NOT_MEASURED',
            'clock': 'source_monotonic', 'transport': 'HTTP_SSE', 'requestedModel': model,
            'observedModel': observed_model, 'modelReceipt': 'REPORTED_IDENTIFIER' if observed_model else 'MISSING',
            'identityScope': 'HTTP_ROUTE_AND_REPORTED_IDENTIFIER' if observed_model else 'HTTP_ROUTE_ONLY',
            'gatewayJobId': cancellation['jobId'], 'modelArtifactDigest': None}
    if validate_output is not None:
        result['passed'] = output_bytes <= OUTPUT_LIMIT and bool(validate_output(output.decode()))
    return result


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--model-file', type=Path, required=True)
    parser.add_argument('--runtime-build', required=True)
    parser.add_argument('--hardware', required=True)
    parser.add_argument('--batch', type=int, required=True)
    parser.add_argument('--microbatch', type=int, required=True)
    parser.add_argument('--slots', type=int, choices=(1, 2), required=True)
    parser.add_argument('--cache-condition', choices=('hot', 'cold'), required=True)
    parser.add_argument('--repeats', type=int, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    endpoint = args.endpoint.rstrip('/')
    uri = urlsplit(endpoint)
    if uri.scheme not in {'http', 'https'} or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or uri.path not in ('', '/'):
        parser.error('Use the exact fixed inference origin without credentials or paths')
    if args.batch not in (256, 512, 1024) or args.microbatch not in (128, 256, 512) or args.microbatch > args.batch or not 3 <= args.repeats <= 100:
        parser.error('Unsupported candidate or repetition bound')
    headers = {'Authorization': 'Bearer ' + os.environ['GATEWAY_BENCHMARK_TOKEN']} if os.getenv('GATEWAY_BENCHMARK_TOKEN') else {}
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    report = {'schemaVersion': 1, 'sourceSha': sha, 'modelSha256': digest_file(args.model_file),
              'model': args.model, 'runtimeBuild': args.runtime_build, 'hardware': args.hardware,
              'routeDigest': hashlib.sha256(endpoint.encode()).hexdigest(), 'cacheCondition': args.cache_condition,
              'declaredSettings': {'batch': args.batch, 'microbatch': args.microbatch, 'slots': args.slots},
              'settingsVerified': False, 'samples': [], 'qualification': 'NOT_QUALIFIED'}
    try:
        # Observe only allowlisted runtime controls. An operator-supplied flag is not proof.
        with requests.get(endpoint + '/props', headers=headers, timeout=(10, 10), stream=True, allow_redirects=False) as response:
            data = bytearray()
            for block in response.iter_content(8192):
                data.extend(block)
                if len(data) > 262144:
                    raise ValueError('Runtime metadata exceeds its bound')
            if response.status_code == 200:
                props = json.loads(data)
                controls = props.get('default_generation_settings', {})
                if not isinstance(controls, dict):
                    raise ValueError('Runtime controls are malformed')
                report['observedSettings'] = {key: value if type(value) is int and 0 < value <= 2097152 else None
                                              for key in ('n_batch', 'n_ubatch', 'n_parallel', 'n_ctx')
                                              for value in [controls.get(key, props.get(key))]}
                observed = report['observedSettings']
                report['settingsVerified'] = observed['n_batch'] == args.batch and observed['n_ubatch'] == args.microbatch and observed['n_parallel'] == args.slots
        for case, messages in CASES.items():
            for iteration in range(args.repeats):
                report['samples'].append(dict(case=case, iteration=iteration,
                    **measure(requests.post, endpoint, args.model, messages, headers=headers)))
        report['summary'] = {case: {metric + 'P95': percentile([sample[metric] for sample in report['samples'] if sample['case'] == case], .95)
                                    for metric in ('firstContentMs', 'totalMs', 'maximumContentGapMs')} for case in CASES}
        report['qualification'] = 'MEASURED_HTTP_ONLY'
    finally:
        report['limitations'] = ['Declared cold/hot and batch settings require host receipts.', 'No Android frame, GPU, queue or cold-load evidence is inferred.', 'No route/model tuning was applied.']
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Measured samples:', len(report['samples']), '; runtime settings verified:', report['settingsVerified'])


if __name__ == '__main__':
    main()
