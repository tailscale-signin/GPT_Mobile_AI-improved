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


def decode_event(line):
    if len(line) > 65536:
        raise ValueError('Benchmark SSE record exceeds the bound')
    if not line.startswith(b'data:'):
        return None
    raw = line[5:].strip()
    if raw == b'[DONE]':
        return 'DONE'
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Invalid stream envelope')
    return value


def measure(post, endpoint, model, messages, *, headers=None, output_tokens=256, clock=time.perf_counter, validate_output=None, gateway_options=None):
    started = clock()
    first, previous, largest_gap, events, output_bytes = None, None, 0.0, 0, 0
    output = bytearray()
    content_digest = hashlib.sha256()
    usage, timings, done, finish = None, None, False, None
    payload = {'model': model, 'messages': messages, 'max_tokens': output_tokens,
               'temperature': 0, 'stream': True, 'stream_options': {'include_usage': True},
               'tools': [], 'tool_choice': 'none'}
    if gateway_options is not None:
        payload['gateway'] = gateway_options
    with post(endpoint + '/v1/chat/completions', json=payload, headers=headers or {},
              timeout=(10, 120), stream=True, allow_redirects=False) as response:
        if response.status_code != 200:
            raise ValueError('Benchmark selected route rejected the request: HTTP ' + str(response.status_code))
        for line in response.iter_lines(chunk_size=1024):
            if clock() - started > 180:
                raise TimeoutError('Benchmark request exceeded its total deadline')
            event = decode_event(line)
            if event == 'DONE':
                done = True
                break
            if not isinstance(event, dict):
                continue
            if event.get('error'):
                raise ValueError('Selected route returned a stream error')
            reported = event.get('model')
            if reported is not None and reported != model:
                raise ValueError('Selected model receipt contradicted the requested identifier')
            for choice in event.get('choices', []):
                text = choice.get('delta', {}).get('content')
                finish = choice.get('finish_reason') or finish
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
                        if len(output) + len(encoded) > 16384:
                            raise ValueError('Benchmark output exceeded the validation limit')
                        output.extend(encoded)
            if isinstance(event.get('usage'), dict):
                usage = {key: value for key, value in event['usage'].items() if key in {'prompt_tokens', 'completion_tokens', 'total_tokens'} and type(value) is int and value >= 0}
            if isinstance(event.get('timings'), dict):
                timings = {key: value for key, value in event['timings'].items() if key in {'prompt_ms', 'predicted_ms', 'prompt_n', 'predicted_n'} and type(value) in (int, float) and math.isfinite(value) and value >= 0}
    if not done or first is None or finish is None:
        raise ValueError('Benchmark stream lacks first content or a verified terminal envelope')
    result = {'firstContentMs': (first - started) * 1000, 'totalMs': (clock() - started) * 1000,
            'maximumContentGapMs': largest_gap * 1000, 'contentEvents': events, 'outputBytes': output_bytes,
            'outputSha256': content_digest.hexdigest(), 'finishReason': finish, 'usage': usage,
            'backendTimings': timings, 'queueMs': None, 'coldLoadMs': None, 'offload': 'NOT_MEASURED',
            'clock': 'source_monotonic', 'transport': 'HTTP_SSE', 'requestedModel': model}
    if validate_output is not None:
        result['passed'] = bool(validate_output(output.decode()))
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
