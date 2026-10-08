import { createInterface } from 'node:readline';

const names = ['search_products', 'get_product', 'get_price_history', 'get_deals', 'get_buy_link', 'compare_marketplaces', 'add_price_watch', 'list_price_watches', 'remove_price_watch'];
const input = createInterface({ input: process.stdin });
input.on('line', line => {
  const request = JSON.parse(line);
  if (request.id === undefined) return;
  let result;
  if (request.method === 'initialize') result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'amazon-mcp', version: 'fixture' } };
  else if (request.method === 'tools/list') result = { tools: names.map(name => ({ name, description: name, inputSchema: { type: 'object', properties: {} } })) };
  else if (request.method === 'tools/call') result = { content: [{ type: 'text', text: JSON.stringify(request.params) }] };
  else if (request.method === 'ping') result = {};
  else throw new Error('Unexpected fixture method');
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n');
});
