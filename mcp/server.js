const http = require('http');
const https = require('https');
const { URL } = require('url');
const { JSDOM } = require('jsdom');
const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
const { Server } = require('@modelcontextprotocol/sdk/server/index.js');

// Create MCP server instance
const server = new Server({
  name: 'gpt-mobile-mcp',
  version: '1.0.0'
}, {
  capabilities: {
    tools: {},
    resources: {}
  }
});

// HTTP server for SSE and message endpoints
let transport; // Declare at module level

const httpServer = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/sse') {
    // SSE endpoint
    const sseTransport = new SSEServerTransport('/message', req);
    server.connect(sseTransport);
    transport = sseTransport; // Store for POST handler
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.write('event: connected\ndata: {}\n\n');
  } else if (req.method === 'POST' && req.url === '/message') {
    // Message endpoint
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', async () => {
      if (transport) {
        try {
          await transport.handlePostMessage(req, res, JSON.parse(body));
        } catch (err) {
          if (!res.headersSent) {
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: err.message }));
          }
        }
      } else {
        res.writeHead(500);
        res.end('Transport not initialized');
      }
    });
  } else if (req.method === 'GET' && req.url === '/health') {
    // Health check
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', port: 3005 }));
  } else {
    res.writeHead(404);
    res.end('Not Found');
  }
});

httpServer.listen(3005, () => {
  console.log('MCP Server running on http://localhost:3005');
  console.log('SSE endpoint: http://localhost:3005/sse');
  console.log('Health check: http://localhost:3005/health');
});

// Helper: Fetch URL and return HTML (supports both HTTP and HTTPS)
function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    try {
      const parsedUrl = new URL(url);
      if (!parsedUrl.protocol.startsWith('http')) {
        reject(new Error('Only HTTP and HTTPS URLs are supported'));
        return;
      }
      
      const client = parsedUrl.protocol === 'https:' ? https : http;
      
      const request = client.get(url, { timeout: 10000 }, (response) => {
        let data = '';
        
        response.on('data', chunk => {
          data += chunk;
        });
        
        response.on('end', () => {
          if (response.statusCode >= 200 && response.statusCode < 300) {
            resolve(data);
          } else {
            reject(new Error(`HTTP ${response.statusCode}`));
          }
        });
      });
      
      request.on('error', reject);
      request.on('timeout', () => {
        request.destroy();
        reject(new Error('Request timeout'));
      });
    } catch (error) {
      reject(new Error(`Invalid URL: ${error.message}`));
    }
  });
}

// Helper: Extract main content from HTML as Markdown
function htmlToMarkdown(html) {
  const dom = new JSDOM(html);
  const document = dom.window.document;
  
  let markdown = '';
  
  // Title
  const title = document.querySelector('title');
  if (title) {
    markdown += `# ${title.textContent.trim()}\n\n`;
  }
  
  // Main content - prefer article, main, or body
  const contentSelectors = ['article', 'main', '.content', '#content', 'body'];
  let contentElement = null;
  
  for (const selector of contentSelectors) {
    const el = document.querySelector(selector);
    if (el && el.textContent.trim()) {
      contentElement = el;
      break;
    }
  }
  
  // If no specific content found, use body but strip nav/header/footer
  if (!contentElement) {
    contentElement = document.body;
    
    // Remove common non-content elements
    const removeSelectors = [
      'nav', 'header:not([role="main"])', 'footer', 
      'aside', '.sidebar', '#sidebar', '.navigation',
      '.nav', '.menu', '.pagination'
    ];
    
    for (const selector of removeSelectors) {
      const elements = contentElement.querySelectorAll(selector);
      elements.forEach(el => el.remove());
    }
  }
  
  if (!contentElement || !contentElement.textContent.trim()) {
    return 'No content found on this page.\n';
  }
  
  // Convert headings
  const h1s = contentElement.querySelectorAll('h1');
  h1s.forEach(h => markdown += `# ${h.textContent.trim()}\n\n`);
  
  const h2s = contentElement.querySelectorAll('h2');
  h2s.forEach(h => markdown += `## ${h.textContent.trim()}\n\n`);
  
  const h3s = contentElement.querySelectorAll('h3');
  h3s.forEach(h => markdown += `### ${h.textContent.trim()}\n\n`);
  
  // Convert paragraphs
  const paras = contentElement.querySelectorAll('p');
  paras.forEach(p => {
    const text = p.textContent.trim();
    if (text) markdown += `${text}\n\n`;
  });
  
  // Convert lists
  const uls = contentElement.querySelectorAll('ul, ol');
  uls.forEach(list => {
    const items = list.querySelectorAll('li');
    items.forEach((item, index) => {
      const prefix = list.tagName === 'OL' ? `${index + 1}. ` : '- ';
      markdown += `${prefix}${item.textContent.trim()}\n`;
    });
    markdown += '\n';
  });
  
  // Convert links
  const links = contentElement.querySelectorAll('a[href]');
  links.forEach(link => {
    const text = link.textContent.trim();
    const href = link.getAttribute('href');
    if (text && href) {
      markdown += `[${text}](${href})\n`;
    }
  });
  
  // Convert images
  const images = contentElement.querySelectorAll('img[src]');
  images.forEach(img => {
    const alt = img.getAttribute('alt') || '(image)';
    const src = img.getAttribute('src');
    markdown += `![${alt}](${src})\n\n`;
  });
  
  // Convert code blocks
  const preBlocks = contentElement.querySelectorAll('pre, code');
  preBlocks.forEach(block => {
    const code = block.textContent;
    if (code.trim()) {
      markdown += '```\n';
      markdown += code.trim();
      markdown += '\n```\n\n';
    }
  });
  
  return markdown.trim() + '\n';
}

// Helper: Extract outbound links
function extractLinks(html, baseUrl) {
  const dom = new JSDOM(html);
  const document = dom.window.document;
  const links = [];
  
  const linkElements = document.querySelectorAll('a[href]');
  
  linkElements.forEach(link => {
    const href = link.getAttribute('href');
    if (href && !href.startsWith('#') && !href.startsWith('mailto:') && !href.startsWith('tel:')) {
      // Resolve relative URLs
      let absoluteUrl;
      try {
        absoluteUrl = new URL(href, baseUrl).href;
      } catch {
        return;
      }
      
      links.push({
        url: absoluteUrl,
        text: link.textContent.trim()
      });
    }
  });
  
  return links;
}

// Helper: Get system info
function getSystemInfo() {
  const os = require('os');
  return {
    platform: os.platform(),
    architecture: os.arch(),
    cpuCount: os.cpus().length,
    totalMemory: `${(os.totalmem() / 1024 / 1024 / 1024).toFixed(2)} GB`,
    freeMemory: `${(os.freemem() / 1024 / 1024 / 1024).toFixed(2)} GB`,
    uptime: os.uptime(),
    hostname: os.hostname()
  };
}

// Helper: Get database schema (placeholder)
function getDatabaseSchema() {
  return {
    version: '19',
    tables: [
      {
        name: 'conversations',
        columns: ['id TEXT PRIMARY KEY', 'title TEXT', 'created_at INTEGER', 'updated_at INTEGER']
      },
      {
        name: 'messages',
        columns: ['id TEXT PRIMARY KEY', 'conversation_id TEXT', 'role TEXT', 'content TEXT', 'created_at INTEGER']
      }
    ]
  };
}

// Helper: Translate text (placeholder - would need external API)
function translateText(text, targetLang) {
  return `[Translation to ${targetLang} not implemented. This is a placeholder.]`;
}

// Helper: Geolocate IP (placeholder - would need external API)
function geolocateIp(ip) {
  return `Geolocation for ${ip || 'auto-detected'} not implemented. This is a placeholder.`;
}

// Helper: Reverse geocode (placeholder - would need external API)
function reverseGeocode(lat, lon) {
  return `Address for (${lat}, ${lon}) not implemented. This is a placeholder.`;
}

// Helper: Get current location (placeholder - requires native bridge)
function getCurrentLocation() {
  return 'Current GPS location not available without native Android/iOS bridge.';
}

// Tool: read_file - Read file contents from local filesystem
server.tool('read_file', {
  path: { type: 'string' }
}, async ({ path }) => {
  try {
    const fs = require('fs');
    const content = fs.readFileSync(path, 'utf-8');
    return {
      content: [{ type: 'text', text: content }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error reading file ${path}: ${error.message}` }]
    };
  }
});

// Tool: write_file - Write or update files
server.tool('write_file', {
  path: { type: 'string' },
  content: { type: 'string' }
}, async ({ path, content }) => {
  try {
    const fs = require('fs');
    
    // Create parent directories if they don't exist
    const dir = require('path').dirname(path);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    
    fs.writeFileSync(path, content, 'utf-8');
    return {
      content: [{ type: 'text', text: `Successfully wrote to ${path}` }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error writing file ${path}: ${error.message}` }]
    };
  }
});

// Tool: list_directory - List directory contents
server.tool('list_directory', {
  path: { type: 'string' }
}, async ({ path }) => {
  try {
    const fs = require('fs');
    const entries = fs.readdirSync(path, { withFileTypes: true });
    
    let output = `Contents of ${path}:\n\n`;
    
    entries.forEach(entry => {
      const type = entry.isDirectory() ? 'DIR' : 'FILE';
      output += `${type}: ${entry.name}\n`;
    });
    
    return {
      content: [{ type: 'text', text: output }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error listing directory ${path}: ${error.message}` }]
    };
  }
});

// Tool: delete_file - Delete a file
server.tool('delete_file', {
  path: { type: 'string' }
}, async ({ path }) => {
  try {
    const fs = require('fs');
    if (fs.existsSync(path)) {
      fs.unlinkSync(path);
      return {
        content: [{ type: 'text', text: `Successfully deleted ${path}` }]
      };
    } else {
      return {
        content: [{ type: 'text', text: `File not found: ${path}` }]
      };
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error deleting file ${path}: ${error.message}` }]
    };
  }
});

// Tool: execute_code - Execute JavaScript/Python code snippets
server.tool('execute_code', {
  code: { type: 'string' },
  language: { type: 'string' }
}, async ({ code, language = 'javascript' }) => {
  try {
    if (language === 'python') {
      return {
        content: [{ type: 'text', text: `Python execution not implemented. This is a placeholder.` }]
      };
    }
    
    // Execute JavaScript in a sandboxed way
    const vm = require('vm');
    const sandbox = { console, setTimeout, setInterval, clearTimeout, clearInterval };
    
    vm.createContext(sandbox);
    vm.runInContext(code, sandbox);
    
    return {
      content: [{ type: 'text', text: `Code executed successfully` }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error executing code: ${error.message}` }]
    };
  }
});

// Tool: query_database - Execute SQL queries against SQLite/Room database
server.tool('query_database', {
  sql: { type: 'string' }
}, async ({ sql }) => {
  try {
    return {
      content: [{ type: 'text', text: `Database query execution not implemented. This is a placeholder.` }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error executing database query: ${error.message}` }]
    };
  }
});

// Tool: http_request - Perform outbound HTTP/HTTPS requests
server.tool('http_request', {
  url: { type: 'string' },
  method: { type: 'string' },
  headers: { type: 'object' },
  body: { type: 'string' }
}, async ({ url, method = 'GET', headers = {}, body }) => {
  try {
    const parsedUrl = new URL(url);
    const client = parsedUrl.protocol === 'https:' ? https : http;
    
    const options = {
      method,
      headers: { ...headers, 'User-Agent': 'GPT-Mobile-MCP/1.0' }
    };
    
    if (body) {
      options.headers['Content-Length'] = Buffer.byteLength(body);
    }
    
    return new Promise((resolve, reject) => {
      const request = client.request(url, options, (response) => {
        let data = '';
        response.on('data', chunk => { data += chunk; });
        response.on('end', () => {
          resolve({
            content: [{
              type: 'text',
              text: JSON.stringify({
                status: response.statusCode,
                headers: response.headers,
                body: data
              }, null, 2)
            }]
          });
        });
      });
      
      request.on('error', reject);
      request.on('timeout', () => {
        request.destroy();
        reject(new Error('Request timeout'));
      });
      
      if (body) {
        request.write(body);
      }
      request.end();
    });
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error making HTTP request to ${url}: ${error.message}` }]
    };
  }
});

// Tool: translate_text - Translate text between languages
server.tool('translate_text', {
  text: { type: 'string' },
  source_lang: { type: 'string' },
  target_lang: { type: 'string' }
}, async ({ text, source_lang, target_lang }) => {
  try {
    return {
      content: [{ type: 'text', text: translateText(text, target_lang) }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error translating text: ${error.message}` }]
    };
  }
});

// Tool: geolocate_ip - Look up geolocation data for an IP address
server.tool('geolocate_ip', {
  ip: { type: 'string' }
}, async ({ ip }) => {
  try {
    return {
      content: [{ type: 'text', text: geolocateIp(ip) }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error geolocating IP: ${error.message}` }]
    };
  }
});

// Tool: reverse_geocode - Convert coordinates to address
server.tool('reverse_geocode', {
  latitude: { type: 'number' },
  longitude: { type: 'number' }
}, async ({ latitude, longitude }) => {
  try {
    return {
      content: [{ type: 'text', text: reverseGeocode(latitude, longitude) }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error reverse geocoding: ${error.message}` }]
    };
  }
});

// Tool: get_current_location - Get current GPS location
server.tool('get_current_location', {}, async () => {
  try {
    return {
      content: [{ type: 'text', text: getCurrentLocation() }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error getting location: ${error.message}` }]
    };
  }
});

// Tool: fetch - Download web pages and parse as Markdown
server.tool('fetch', {
  url: { type: 'string' }
}, async ({ url }) => {
  try {
    const html = await fetchUrl(url);
    const markdown = htmlToMarkdown(html);
    
    return {
      content: [{ type: 'text', text: markdown }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error fetching ${url}: ${error.message}` }]
    };
  }
});

// Tool: extract_links - Extract outbound hyperlinks from URL
server.tool('extract_links', {
  url: { type: 'string' }
}, async ({ url }) => {
  try {
    const html = await fetchUrl(url);
    const links = extractLinks(html, url);
    
    let output = `Found ${links.length} outbound link(s):\n\n`;
    
    if (links.length === 0) {
      output += 'No outbound links found.\n';
    } else {
      links.forEach((link, i) => {
        output += `${i + 1}. [${link.text}](${link.url})\n`;
      });
    }
    
    return {
      content: [{ type: 'text', text: output }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error extracting links from ${url}: ${error.message}` }]
    };
  }
});

// Resource: mcp://system/info - System telemetry and diagnostics
server.resource('mcp://system/info', async () => {
  const info = getSystemInfo();
  return {
    contents: [{
      uri: 'mcp://system/info',
      mimeType: 'application/json',
      text: JSON.stringify(info, null, 2)
    }]
  };
});

// Resource: mcp://database/schema - Database schema definition
server.resource('mcp://database/schema', async () => {
  const schema = getDatabaseSchema();
  return {
    contents: [{
      uri: 'mcp://database/schema',
      mimeType: 'application/json',
      text: JSON.stringify(schema, null, 2)
    }]
  };
});
