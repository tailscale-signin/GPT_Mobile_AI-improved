"""Explicit modern metadata adapter; legacy negotiation remains a separate path."""
MODERN_VERSION = '2026-07-28'


def modern_params(params, version):
    result = dict(params or {})
    metadata = dict(result.get('_meta') or {})
    metadata.update({'io.modelcontextprotocol/protocolVersion': MODERN_VERSION,
                     'io.modelcontextprotocol/clientInfo': {'name': 'private-gateway', 'version': version},
                     'io.modelcontextprotocol/clientCapabilities': {}})
    result['_meta'] = metadata
    return result


def discovery(response):
    result = response.get('result')
    if not isinstance(result, dict) or result.get('resultType') != 'complete':
        raise ValueError('Modern MCP discovery did not return a complete result')
    versions = result.get('supportedVersions')
    if (not isinstance(versions, list) or not versions or len(versions) > 32
            or not all(isinstance(item, str) and len(item) <= 32 for item in versions)
            or MODERN_VERSION not in versions or not isinstance(result.get('capabilities'), dict)):
        raise ValueError('No supported modern MCP revision was observed')
    return result


def failure_category(error):
    """Classify the exception chain without persisting arbitrary exception text."""
    import socket
    seen = set()
    current = error
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, socket.gaierror):
            return 'DNS_FAILURE'
        if isinstance(current, (BrokenPipeError, ConnectionResetError)):
            return 'MCP_BROKEN_PIPE'
        current = current.__cause__ or current.__context__
    if isinstance(error, TimeoutError):
        return 'MCP_TIMEOUT'
    if isinstance(error, InterruptedError):
        return 'CANCELLED'
    if isinstance(error, (ValueError, TypeError)):
        return 'MCP_PROTOCOL_OR_INPUT'
    return 'MCP_TRANSPORT_FAILURE'
