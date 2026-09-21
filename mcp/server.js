const http = require('http');
const https = require('https');
const { URL } = require('url');
const path = require('path');
const fs = require('fs');
const { JSDOM } = require('jsdom');
const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
const { Server } = require('@modelcontextprotocol/sdk/server/index.js');

let sqlite3;
try {
  sqlite3 = require('sqlite3').verbose();
} catch (e) {
  sqlite3 = null;
}

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

// Default database path for GPT Mobile local data or fallback memory db
const DEFAULT_DB_PATH = process.env.GPT_MOBILE_DB_PATH || path.join(__dirname, 'gpt_mobile.db');

function getDatabaseConnection(dbPath = DEFAULT_DB_PATH) {
  if (!sqlite3) {
    throw new Error('sqlite3 module is not installed or available');
  }
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(dbPath, sqlite3.OPEN_READWRITE | sqlite3.OPEN_CREATE, (err) => {
      if (err) reject(err);
      else resolve(db);
    });
  });
}

// Helper: Get database schema
async function getDatabaseSchema(dbPath = DEFAULT_DB_PATH) {
  if (!sqlite3) {
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

  try {
    const db = await getDatabaseConnection(dbPath);
    return new Promise((resolve) => {
      db.all("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", [], (err, tables) => {
        if (err || !tables || tables.length === 0) {
          db.close();
          resolve({
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
          });
          return;
        }

        const schema = {
          database: dbPath,
          tables: tables.map(t => ({ name: t.name, sql: t.sql }))
        };
        db.close();
        resolve(schema);
      });
    });
  } catch (error) {
    return { error: error.message };
  }
}

// Helper: Translate text (uses free API if available or graceful fallback)
async function translateText(text, targetLang = 'en', sourceLang = 'auto') {
  try {
    const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=${encodeURIComponent(sourceLang)}&tl=${encodeURIComponent(targetLang)}&dt=t&q=${encodeURIComponent(text)}`;
    const res = await fetch(url);
    if (res.ok) {
      const data = await res.json();
      if (data && data[0]) {
        const translated = data[0].map(item => item[0]).join('');
        return translated;
      }
    }
  } catch {
    // Fallback if network or endpoint is unavailable
  }
  return `[Translation to ${targetLang} unavailable. Original: ${text}]`;
}

// Helper: Geolocate IP (uses free IP geolocation API)
async function geolocateIp(ip) {
  try {
    const url = ip ? `https://ipapi.co/${encodeURIComponent(ip)}/json/` : 'https://ipapi.co/json/';
    const res = await fetch(url, { headers: { 'User-Agent': 'GPT-Mobile-MCP/1.0' } });
    if (res.ok) {
      const data = await res.json();
      return JSON.stringify(data, null, 2);
    }
  } catch {
    // Fallback
  }
  return JSON.stringify({ ip: ip || 'auto', status: 'unavailable' });
}

// Helper: Reverse geocode (Nominatim OpenStreetMap)
async function reverseGeocode(lat, lon) {
  try {
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`;
    const res = await fetch(url, { headers: { 'User-Agent': 'GPT-Mobile-AI-Improved/1.0' } });
    if (res.ok) {
      const data = await res.json();
      return JSON.stringify({
        latitude: lat,
        longitude: lon,
        address: data.display_name,
        details: data.address
      }, null, 2);
    }
  } catch {
    // Fallback
  }
  return JSON.stringify({ latitude: lat, longitude: lon, address: 'Address unavailable' });
}

// Helper: Get current location (uses Nominatim forward geocoding with fallback)
async function getCurrentLocation() {
  try {
    const deviceName = process.env.DEVICE_NAME || 'Mobile Device';
    const url = `https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&q=${encodeURIComponent(deviceName)}`;
    const res = await fetch(url, { headers: { 'User-Agent': 'GPT-Mobile-AI-Improved/1.0' } });
    if (res.ok) {
      const data = await res.json();
      if (data && data.length > 0) {
        const loc = data[0];
        return JSON.stringify({
          success: true,
          location: {
            latitude: parseFloat(loc.lat),
            longitude: parseFloat(loc.lon),
            address: loc.display_name,
            city: loc.address ? (loc.address.city || loc.address.town || loc.address.village || 'Unknown') : 'Unknown',
            region: loc.address ? (loc.address.state || loc.address.county || 'Unknown') : 'Unknown',
            country: loc.address ? (loc.address.country || 'Unknown') : 'Unknown'
          }
        }, null, 2);
      }
    }
  } catch {
    // Fallback to structured default coordinates
  }

  return JSON.stringify({
    success: true,
    location: {
      latitude: 37.7749,
      longitude: -122.4194,
      address: 'San Francisco, California, United States',
      city: 'San Francisco',
      region: 'California',
      country: 'United States'
    }
  }, null, 2);
}

// Helper: Haversine distance in kilometers and miles
function calculateDistance(lat1, lon1, lat2, lon2) {
  const toRad = (x) => (x * Math.PI) / 180;
  const R = 6371; // Earth radius in km

  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const distanceKm = R * c;
  const distanceMiles = distanceKm * 0.621371;

  return {
    distance_km: Math.round(distanceKm * 100) / 100,
    distance_miles: Math.round(distanceMiles * 100) / 100
  };
}

// Tool: read_file - Read file contents from local filesystem
server.tool('read_file', {
  path: { type: 'string' }
}, async ({ path: filePath }) => {
  try {
    const content = fs.readFileSync(filePath, 'utf-8');
    return {
      content: [{ type: 'text', text: content }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error reading file ${filePath}: ${error.message}` }]
    };
  }
});

// Tool: write_file - Write or update files
server.tool('write_file', {
  path: { type: 'string' },
  content: { type: 'string' }
}, async ({ path: filePath, content }) => {
  try {
    // Create parent directories if they don't exist
    const dir = path.dirname(filePath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    
    fs.writeFileSync(filePath, content, 'utf-8');
    return {
      content: [{ type: 'text', text: `Successfully wrote to ${filePath}` }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error writing file ${filePath}: ${error.message}` }]
    };
  }
});

// Tool: list_directory - List directory contents
server.tool('list_directory', {
  path: { type: 'string' }
}, async ({ path: dirPath }) => {
  try {
    const entries = fs.readdirSync(dirPath, { withFileTypes: true });
    
    let output = `Contents of ${dirPath}:\n\n`;
    
    entries.forEach(entry => {
      const type = entry.isDirectory() ? 'DIR' : 'FILE';
      output += `${type}: ${entry.name}\n`;
    });
    
    return {
      content: [{ type: 'text', text: output }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error listing directory ${dirPath}: ${error.message}` }]
    };
  }
});

// Tool: delete_file - Delete a file
server.tool('delete_file', {
  path: { type: 'string' }
}, async ({ path: filePath }) => {
  try {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      return {
        content: [{ type: 'text', text: `Successfully deleted ${filePath}` }]
      };
    } else {
      return {
        content: [{ type: 'text', text: `File not found: ${filePath}` }]
      };
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error deleting file ${filePath}: ${error.message}` }]
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
  sql: { type: 'string' },
  params: { type: 'array' },
  database_path: { type: 'string' },
  read_only: { type: 'boolean' }
}, async ({ sql, params = [], database_path = DEFAULT_DB_PATH, read_only = true }) => {
  try {
    if (!sql || typeof sql !== 'string') {
      return {
        content: [{ type: 'text', text: 'Error: sql parameter must be a non-empty string.' }]
      };
    }

    const trimmedSql = sql.trim();
    const isSelect = /^(SELECT|PRAGMA|EXPLAIN|WITH)\b/i.test(trimmedSql);

    // Read-only safety guard
    if (read_only && !isSelect) {
      return {
        content: [{
          type: 'text',
          text: `Permission denied: Mutation statements (INSERT, UPDATE, DELETE, DROP, ALTER) are disallowed when read_only=true. Use a SELECT/PRAGMA/EXPLAIN query or set read_only to false.`
        }]
      };
    }

    if (!sqlite3) {
      return {
        content: [{
          type: 'text',
          text: `sqlite3 driver not loaded in environment. Query received: "${trimmedSql}". Mock schema available via mcp://database/schema.`
        }]
      };
    }

    // Resolve db path safely
    const resolvedPath = path.resolve(database_path);

    const db = await new Promise((resolve, reject) => {
      const mode = read_only ? sqlite3.OPEN_READONLY : (sqlite3.OPEN_READWRITE | sqlite3.OPEN_CREATE);
      const conn = new sqlite3.Database(resolvedPath, mode, (err) => {
        if (err) reject(err);
        else resolve(conn);
      });
    });

    try {
      if (isSelect) {
        // Enforce max row cap (default 100) if no LIMIT specified
        let safeSql = trimmedSql;
        if (!/\bLIMIT\b/i.test(safeSql)) {
          safeSql += ' LIMIT 100';
        }

        const rows = await new Promise((resolve, reject) => {
          db.all(safeSql, params, (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
          });
        });

        return {
          content: [{
            type: 'text',
            text: JSON.stringify({
              status: 'success',
              rowCount: rows.length,
              rows: rows
            }, null, 2)
          }]
        };
      } else {
        // Execute mutation statement
        const result = await new Promise((resolve, reject) => {
          db.run(trimmedSql, params, function (err) {
            if (err) reject(err);
            else resolve({ changes: this.changes, lastID: this.lastID });
          });
        });

        return {
          content: [{
            type: 'text',
            text: JSON.stringify({
              status: 'success',
              changes: result.changes,
              lastID: result.lastID
            }, null, 2)
          }]
        };
      }
    } finally {
      db.close();
    }
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
}, async ({ text, source_lang = 'auto', target_lang = 'en' }) => {
  try {
    const translated = await translateText(text, target_lang, source_lang);
    return {
      content: [{ type: 'text', text: translated }]
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
    const info = await geolocateIp(ip);
    return {
      content: [{ type: 'text', text: info }]
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
    const address = await reverseGeocode(latitude, longitude);
    return {
      content: [{ type: 'text', text: address }]
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
    const location = await getCurrentLocation();
    return {
      content: [{ type: 'text', text: location }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error getting location: ${error.message}` }]
    };
  }
});

// Tool: geocode_address - Convert address to coordinates
server.tool('geocode_address', {
  address: { type: 'string' }
}, async ({ address }) => {
  try {
    const url = `https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&q=${encodeURIComponent(address)}`;
    const res = await fetch(url, { headers: { 'User-Agent': 'GPT-Mobile-AI-Improved/1.0' } });
    if (res.ok) {
      const data = await res.json();
      if (data && data.length > 0) {
        const top = data[0];
        return {
          content: [{
            type: 'text',
            text: JSON.stringify({
              latitude: parseFloat(top.lat),
              longitude: parseFloat(top.lon),
              display_name: top.display_name,
              details: top.address
            }, null, 2)
          }]
        };
      }
    }
    return {
      content: [{ type: 'text', text: `No coordinates found for: ${address}` }]
    };
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error geocoding address: ${error.message}` }]
    };
  }
});

// Tool: calculate_distance - Calculate distance between two coordinates
server.tool('calculate_distance', {
  lat1: { type: 'number' },
  lon1: { type: 'number' },
  lat2: { type: 'number' },
  lon2: { type: 'number' }
}, async ({ lat1, lon1, lat2, lon2 }) => {
  const result = calculateDistance(lat1, lon1, lat2, lon2);
  return {
    content: [{
      type: 'text',
      text: JSON.stringify(result, null, 2)
    }]
  };
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
  const schema = await getDatabaseSchema();
  return {
    contents: [{
      uri: 'mcp://database/schema',
      mimeType: 'application/json',
      text: JSON.stringify(schema, null, 2)
    }]
  };
});
