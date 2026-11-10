/** Advertise only the session protocol actually implemented by the bridge SDK. */
export function replyToLegacyDiscovery(response, request, supportedVersion) {
  const requested = request?.params?._meta?.['io.modelcontextprotocol/protocolVersion'];
  if (request?.jsonrpc !== '2.0' || request.method !== 'server/discover' ||
      !(typeof request.id === 'string' && request.id.length <= 128 || Number.isSafeInteger(request.id)) ||
      typeof requested !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(requested) || requested === supportedVersion) {
    return false;
  }
  response.writeHead(400, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify({
    jsonrpc: '2.0',
    id: request.id,
    error: {
      code: -32022,
      message: 'Use the supported session protocol.',
      data: { requested, supported: [supportedVersion] }
    }
  }));
  return true;
}
