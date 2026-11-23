"""Optional fixture reranking through the configured local engineering encoder.

This adapter owns no endpoint, model download or generation route. It uses the
existing local transport and background inference admission with a frozen,
operator-declared encoder revision. Only the public selector's query and source
ranges enter the encoder; historical engineering records are never recalled.
"""
import math
import re
import time
from urllib.parse import urlsplit

from benchmarks.contracts import digest
from v14.evidence import canonical
from v14.execution_binding import ExecutionBindingError
from v14.memory import local_model_destination


QUEUE_SECONDS = .75


def configuration():
    from v14.engineering_memory import embedding_configuration
    return embedding_configuration()


def describe(request, provenance, engineering, *, configured=None):
    configured = configuration() if configured is None else configured
    endpoint, model, revision = (configured.get(key, '') for key in ('endpoint', 'model', 'revision'))
    routing = (provenance.get('selectedBinding') or {}).get('routing_policy', 'CONFIGURED_GATEWAY_ROUTE')
    privacy = request.get('memory_mode', 'HYBRID')
    try:
        parsed = urlsplit(endpoint)
        local = local_model_destination(endpoint) and parsed.username is None and parsed.password is None
    except ValueError:
        local = False
    status = ('POLICY_DISABLED' if routing == 'SELECTED_ONLY' or privacy == 'SESSION_ONLY' else
              'NOT_CONFIGURED' if not endpoint or not model else
              'LOCAL_DESTINATION_REQUIRED' if not local else
              'IMMUTABLE_REVISION_REQUIRED' if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', revision) else
              'TRANSPORT_UNAVAILABLE' if not callable(getattr(getattr(engineering, 'memory', None), 'semantic_scores', None)) else
              'ADMISSION_UNAVAILABLE' if not callable(getattr(getattr(engineering, 'scheduler', None), 'acquire', None)) else
              'READY')
    return dict(schema='benchmark.local-context-encoder.v1', status=status,
                endpointDigest=digest(endpoint), modelDigest=digest(model),
                revision=revision if re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', revision) else None,
                configurationDigest=digest(configured), routingPolicy=routing, memoryPolicy=privacy,
                identityScope='OPERATOR_CONFIGURED_IMMUTABLE_REVISION_NOT_ARTIFACT_MEASUREMENT',
                admission='EXISTING_BACKGROUND_INFERENCE_SCHEDULER', maximumQueueMs=int(QUEUE_SECONDS * 1000),
                sourceScope='PUBLIC_FIXTURE_QUERY_AND_SELECTED_RANGES',
                cacheScope='ENCRYPTED_OWNER_RUN_SOURCE_ENCODER')


class ContextSemanticRanker:
    def __init__(self, engineering, run, owner, cancelled, guard, *, now=time.monotonic):
        self.engineering, self.run, self.owner = engineering, run, owner
        self.cancelled, self.guard, self.now = cancelled, guard, now
        self.configuration = configuration()
        self.identity = describe(run['request'], run['provenance'], engineering, configured=self.configuration)
        admitted = run['provenance'].get('contextEncoderIdentity')
        if admitted is not None and admitted != self.identity:
            raise ValueError('BENCHMARK_CONTEXT_ENCODER_CHANGED_AFTER_ADMISSION')
        self.measurements = []

    def validate(self):
        if self.cancelled.is_set():
            raise InterruptedError('Benchmark stopped before local context ranking')
        self.guard()
        if (configuration() != self.configuration or
                describe(self.run['request'], self.run['provenance'], self.engineering,
                         configured=self.configuration) != self.identity):
            raise ValueError('BENCHMARK_CONTEXT_ENCODER_CHANGED_AFTER_FREEZE')

    def scores(self, query, rows, *, source_sha):
        self.validate()
        receipt = dict(status=self.identity['status'], encoderIdentityDigest=digest(self.identity),
                       queueMs=None, embeddingMs=None, scoredCandidates=0)
        self.measurements.append(receipt)
        if self.identity['status'] != 'READY':
            return None
        if not re.fullmatch(r'[0-9a-f]{64}', source_sha):
            raise ValueError('BENCHMARK_CONTEXT_SOURCE_IDENTITY_REQUIRED')
        # Match the transport's established bounds before reserving capacity.
        rows = rows[:24]
        texts = [query[:4000]] + [canonical(row[1])[:4000] for row in rows]
        byte_length = sum(len(text.encode()) for text in texts)
        scheduler = self.engineering.scheduler
        queued = self.now()
        try:
            ticket = scheduler.acquire('background', byte_length=byte_length, tokens=(byte_length + 2) // 3,
                                       memory_bytes=0, timeout=QUEUE_SECONDS, cancelled=self.cancelled.is_set,
                                       validate=self.validate)
        except (TimeoutError, ExecutionBindingError):
            self.validate()
            receipt.update(status='LOCAL_CAPACITY_UNAVAILABLE', queueMs=max(0, (self.now() - queued) * 1000))
            return None
        receipt['queueMs'] = max(0, (self.now() - queued) * 1000)
        began = self.now()
        try:
            self.validate()
            namespace = digest(['benchmark-context-v1', self.owner, self.run['id'],
                                self.run['provenance']['executionIdentity'], source_sha, digest(self.identity)])
            values = self.engineering.memory.semantic_scores(query, rows, namespace=namespace,
                configuration=self.configuration, validate=self.validate)
            self.validate()
            ids = {row[0] for row in rows}
            valid = {key: value for key, value in values.items() if key in ids and type(value) in (int, float) and math.isfinite(value)} if isinstance(values, dict) else {}
            if not valid or len(valid) != len(values):
                receipt['status'] = 'ENCODER_UNAVAILABLE'
                return None
            receipt.update(status='RANKED', scoredCandidates=len(valid))
            return valid
        finally:
            receipt['embeddingMs'] = max(0, (self.now() - began) * 1000)
            scheduler.release(ticket)
