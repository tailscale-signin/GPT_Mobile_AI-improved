"""Independent capability observations; readiness checks never execute a tool."""
import time


def component(state, *, configured=None, ready=None, reason=None, evidence='local_configuration', **fields):
    return dict(supported=True, configured=configured, ready=ready, state=state, reasonCode=reason,
                evidence=evidence, observedAtMs=int(time.time() * 1000), **fields)


def observations(runtime, service, backend, journal):
    engineering = getattr(runtime, 'gateway_engineering', None)
    settings = engineering.readiness() if engineering else {}
    checks = settings.get('checks', {})
    github_configured = checks.get('host_github_connection') == 'configured'
    catalogue = getattr(runtime, 'gateway_catalogue', None)
    tools = catalogue.readiness() if catalogue else {}
    debug = getattr(runtime, 'gateway_debug', None)
    diagnostic = debug.snapshot() if debug else {}
    diagnostic_ready = diagnostic.get('storageReady') if diagnostic.get('debugEnabled') else False
    available = max(0, service.get('maxWorkers', 0) - service.get('activeWorkers', 0))
    model_ready = backend.get('generationReady')
    if backend.get('modelAvailable') is False:
        model_ready = False
    elif backend.get('selectedModelDigest') and backend.get('modelAvailable') is not True:
        model_ready = None
    result = {
        'process': component('DRAINING' if service['draining'] else 'HEALTHY', configured=True,
                             ready=not service['draining'], evidence='process_liveness'),
        'selectedModel': component('LOADING' if backend.get('modelLoading') else 'READY' if model_ready is True else 'UNAVAILABLE' if model_ready is False else 'UNKNOWN',
                                   configured=bool(getattr(runtime, 'LLAMA_BASE', None)), ready=model_ready,
                                   reason=backend.get('reasonCode'),
                                   evidence='health_and_model_catalog', inferenceVerified=backend.get('inferenceVerified'),
                                   metadataAccess=backend.get('metadataAccess'), automaticSubstitution=False,
                                   modelAdvertised=backend.get('modelAvailable'), modelLoaded=backend.get('modelLoaded'),
                                   modelState=backend.get('modelState', 'UNKNOWN')),
        'githubAuthorization': component('NOT_MEASURED' if github_configured else 'NOT_CONFIGURED',
                                         configured=github_configured, ready=settings.get('repository_access'),
                                         reason=None if github_configured else 'HOST_GITHUB_CONNECTION_REQUIRED',
                                         authorizationVerified=settings.get('github_reachable')),
        'repositoryGraph': component('READY' if settings.get('graph_ready') else 'NOT_CONFIGURED' if not github_configured else 'UNAVAILABLE',
                                      configured=github_configured, ready=settings.get('graph_ready', False),
                                      reason=None if settings.get('graph_ready') else 'HOST_GITHUB_CONNECTION_REQUIRED' if not github_configured else 'GRAPH_STORAGE_UNAVAILABLE',
                                      repositoryIndexed=None, sourceLevelOnly=True),
        'mcpDiscovery': component(tools.get('toolsState', 'NOT_MEASURED'), configured=bool(tools.get('toolConnectionCount')),
                                 ready=tools.get('schemaReady'), evidence='catalog_observation', schemaCount=tools.get('toolCount'),
                                 executionVerified=tools.get('executionVerified'), dispatchAvailable=tools.get('toolsReady'),
                                 executionCoverage='Observed operations only; discovering schemas is not execution'),
        'workerCapacity': component('DRAINING' if service['draining'] else 'AVAILABLE' if available else 'WAITING_CAPACITY',
                                     configured=True, ready=not service['draining'] and available > 0,
                                     availableWorkers=available, maximumWorkers=service.get('maxWorkers'),
                                     executionOwner='GATEWAY', retryOwner='GATEWAY'),
        'diagnostics': component('DISABLED' if not diagnostic.get('debugEnabled') else 'NOT_MEASURED' if diagnostic_ready is None else 'READY' if diagnostic_ready else 'STORAGE_UNAVAILABLE',
                                 configured=debug is not None, ready=diagnostic_ready,
                                 evidence='capture_and_storage_state', **diagnostic),
        'durableStorage': component(journal.upper(), configured=journal != 'disabled', ready=journal == 'ready')}
    # Local process checks are measured now; selected-model evidence may come
    # from the bounded backend cache and must retain its original probe age.
    result['selectedModel'].update(observedAtMs=backend.get('observedAtMs'),
                                   observationAgeMs=backend.get('observationAgeMs'))
    scheduler = getattr(runtime, 'gateway_inference_scheduler', None)
    if scheduler and hasattr(scheduler, 'snapshot'):
        result['workerCapacity']['inference'] = scheduler.snapshot()
    return result
