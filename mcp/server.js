const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
const http = require('http');
const https = require('https');
const fs = require('fs').promises;
const path = require('path');
const vm = require('vm');
const { JSDOM } = require('jsdom');
const os = require('os'); // Added missing os import

// Initialize MCP server with capabilities
const server = new Server(
  {
    name: 'gpt-mobile-ai-improved',
    version: '1.0.0'
  },
  {
    capabilities: {
      tools: {},
      resources: {}
    }
  }
);

// Global state for database connections
const dbConnections = new Map();

// Helper function to format query results as text
function formatQueryResults(results) {
  if (!results || results.length === 0) {
    return 'No rows returned.';
  }

  // Get column names from first row
  const columns = results[0].map(col => col.name);

  // Create header row with proper alignment
  const headerRow = columns.map(col => col.padEnd(20)).join(' | ');

  // Calculate max width for each column
  const maxWidths = columns.map((col, i) => {
    const widths = [col.length, ...results.map(row => String(row[i]).length)];
    return Math.max(...widths);
  });

  // Create separator row
  const separatorRow = columns.map((_, i) => '-'.repeat(maxWidths[i])).join('-+-');

  // Format data rows
  const dataRows = results.map(row => {
    return columns.map((col, i) => String(row[i]).padEnd(maxWidths[i])).join(' | ');
  });

  // Combine all rows with newlines
  return [headerRow, separatorRow, ...dataRows].join('\n');
}

// Helper function to format schema as text
function formatSchema(schema) {
  if (!schema || !schema.tables) {
    return 'No tables found in database.';
  }

  const lines = [];
  lines.push('Database Schema:');
  lines.push('================');

  for (const table of schema.tables) {
    lines.push(`\nTable: ${table.name}`);
    lines.push(`Columns:`);
    for (const column of table.columns) {
      const type = column.type || 'TEXT';
      const nullable = column.nullable ? 'NULL' : 'NOT NULL';
      const pk = column.primaryKey ? ' PRIMARY KEY' : '';
      lines.push(`  - ${column.name}: ${type} ${nullable}${pk}`);
    }
    if (table.indexes && table.indexes.length > 0) {
      lines.push(`Indexes:`);
      for (const index of table.indexes) {
        lines.push(`  - ${index.name}: [${index.columns.join(', ')}]`);
      }
    }
  }

  return lines.join('\n');
}

// Helper function to get system info
function getSystemInfo() {
  const platform = process.platform;
  const memoryUsage = process.memoryUsage();
  const uptime = process.uptime();
  const hostname = os.hostname(); // Now works with os import

  return {
    platform,
    memory: {
      heapUsed: (memoryUsage.heapUsed / 1024 / 1024).toFixed(2) + ' MB',
      heapTotal: (memoryUsage.heapTotal / 1024 / 1024).toFixed(2) + ' MB',
      rss: (memoryUsage.rss / 1024 / 1024).toFixed(2) + ' MB'
    },
    cpu: {
      model: os.cpus()[0].model,
      speed: os.cpus()[0].speed ? `${os.cpus()[0].speed} MHz` : 'N/A',
      count: os.cpus().length
    },
    uptime: `${Math.floor(uptime / 3600)}h ${Math.floor((uptime % 3600) / 60)}m`,
    hostname
  };
}

// Helper function to get database schema
function getDatabaseSchema(dbPath) {
  return new Promise((resolve, reject) => {
    const db = require('better-sqlite3')(dbPath);
    try {
      const tables = [];
      const tableNames = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all();

      for (const table of tableNames) {
        const columns = db.prepare(`PRAGMA table_info("${table.name}")`).all();
        const indexes = db.prepare(`PRAGMA index_list("${table.name}")`).all();

        tables.push({
          name: table.name,
          columns: columns.map(col => ({
            name: col.name,
            type: col.type,
            nullable: col.notnull === 0,
            primaryKey: col.pk === 1
          })),
          indexes: indexes.map(idx => ({
            name: idx.name,
            columns: idx.columns.split(' ').filter(c => c !== 'unique')
          }))
        });
      }

      resolve({ version: 'v19', tables });
    } catch (error) {
      reject(error);
    } finally {
      db.close();
    }
  });
}

// Helper function to execute code safely
function executeCodeSafely(code, language = 'javascript') {
  if (language === 'python') {
    return Promise.resolve({ success: false, error: 'Python execution not implemented. JavaScript only.' });
  }

  try {
    const sandbox = {
      console: { log: (...args) => console.log(...args), error: (...args) => console.error(...args) },
      require: (module) => {
        if (module === 'fs') return fs;
        if (module === 'path') return path;
        throw new Error(`Module '${module}' is not allowed in sandbox`);
      }
    };

    const context = vm.createContext(sandbox);
    vm.runInContext(code, context, { timeout: 5000 });

    return Promise.resolve({ success: true, output: 'Code executed successfully.' });
  } catch (error) {
    return Promise.resolve({ success: false, error: error.message });
  }
}

// Helper function to translate text
function translateText(text, targetLanguage = 'en') {
  // Check for translation API key
  const apiKey = process.env.TRANSLATE_API_KEY;

  if (!apiKey) {
    return Promise.resolve({
      success: true,
      translatedText: `[Translation not available. API key required. Original: "${text.substring(0, 50)}${text.length > 50 ? '...' : ''}"]`
    });
  }

  // Use Google Translate API or similar
  const url = `https://translation.googleapis.com/language/translate/v2?key=${apiKey}`;
  const params = new URLSearchParams({
    q: text,
    target: targetLanguage,
    format: 'text'
  });

  return fetch(`${url}?${params.toString()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
  })
    .then(response => response.json())
    .then(data => {
      if (data.data && data.data.translations) {
        return Promise.resolve({ success: true, translatedText: data.data.translations[0].translatedText });
      } else {
        return Promise.resolve({ success: false, error: 'Translation API returned unexpected response' });
      }
    })
    .catch(error => {
      return Promise.resolve({
        success: true,
        translatedText: `[Translation failed. Original: "${text.substring(0, 50)}${text.length > 50 ? '...' : ''}"]`
      });
    });
}

// Helper function to geolocate IP address
function geolocateIP(ipAddress) {
  // Check for IP geolocation API key
  const apiKey = process.env.IP_GEO_API_KEY;

  if (!apiKey) {
    return Promise.resolve({
      success: true,
      location: {
        ip: ipAddress,
        country: 'Unknown',
        region: 'Unknown',
        city: 'Unknown',
        latitude: null,
        longitude: null,
        timezone: 'Unknown'
      }
    });
  }

  // Use IPinfo API or similar
  const url = `https://ipinfo.io/${ipAddress}/json`;
  const params = new URLSearchParams({ token: apiKey });

  return fetch(`${url}?${params.toString()}`, { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      if (data.ip) {
        return Promise.resolve({
          success: true,
          location: {
            ip: data.ip,
            country: data.country || 'Unknown',
            region: data.region || 'Unknown',
            city: data.city || 'Unknown',
            latitude: data.loc ? parseFloat(data.loc.split(',')[0]) : null,
            longitude: data.loc ? parseFloat(data.loc.split(',')[1]) : null,
            timezone: data.timezone || 'Unknown'
          }
        });
      } else {
        return Promise.resolve({
          success: true,
          location: {
            ip: ipAddress,
            country: 'Unknown',
            region: 'Unknown',
            city: 'Unknown',
            latitude: null,
            longitude: null,
            timezone: 'Unknown'
          }
        });
      }
    })
    .catch(error => {
      return Promise.resolve({
        success: true,
        location: {
          ip: ipAddress,
          country: 'Unknown',
          region: 'Unknown',
          city: 'Unknown',
          latitude: null,
          longitude: null,
          timezone: 'Unknown'
        }
      });
    });
}

// Helper function to reverse geocode coordinates
function reverseGeocode(latitude, longitude) {
  // Check for geocoding API key
  const apiKey = process.env.GEOCODE_API_KEY;

  if (!apiKey) {
    return Promise.resolve({
      success: true,
      location: {
        latitude,
        longitude,
        address: 'Unknown',
        city: 'Unknown',
        region: 'Unknown',
        country: 'Unknown'
      }
    });
  }

  // Use Nominatim (OpenStreetMap) or similar free API
  const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2`;
  const params = new URLSearchParams({ lat: latitude, lon: longitude });

  return fetch(`${url}?${params.toString()}`, { method: 'GET' })
    .then(response => response.json())
    .then(data => {
      if (data && data.display_name) {
        return Promise.resolve({
          success: true,
          location: {
            latitude,
            longitude,
            address: data.display_name,
            city: data.address.city || data.address.town || data.address.village || 'Unknown',
            region: data.address.state || data.address.county || 'Unknown',
            country: data.address.country || 'Unknown'
          }
        });
      } else {
        return Promise.resolve({
          success: true,
          location: {
            latitude,
            longitude,
            address: 'Unknown',
            city: 'Unknown',
            region: 'Unknown',
            country: 'Unknown'
          }
        });
      }
    })
    .catch(error => {
      return Promise.resolve({
        success: true,
        location: {
          latitude,
          longitude,
          address: 'Unknown',
          city: 'Unknown',
          region: 'Unknown',
          country: 'Unknown'
        }
      });
    });
}

// Helper function to get current location (mock implementation)
function getCurrentLocation() {
  // This would require native Android/iOS bridge for GPS access
  return Promise.resolve({
    success: true,
    location: {
      latitude: null,
      longitude: null,
      address: 'GPS not available - requires native bridge',
      city: 'Unknown',
      region: 'Unknown',
      country: 'Unknown'
    }
  });
}

// Helper function to execute HTTP request
function executeHttpRequest(method, url, headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    const client = method === 'https' ? https : http;

    const options = {
      hostname: url.hostname,
      port: url.port || (method === 'https' ? 443 : 80),
      path: url.pathname + url.search,
      method,
      headers: {
        ...headers,
        'Content-Type': body && !headers['Content-Type'] ? 'application/json' : headers['Content-Type']
      }
    };

    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });

    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// Helper function to fetch HTML and convert to markdown
function fetchHTML(url) {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https') ? https : http;

    const options = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname + url.search,
      method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0' }
    };

    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const dom = new JSDOM(data);
          const html = dom.window.document.documentElement.outerHTML;
          resolve({ success: true, markdown: html });
        } catch (error) {
          reject(error);
        }
      });
    });

    req.on('error', reject);
    req.setTimeout(10000, () => {
      req.destroy();
      reject(new Error('Request timeout'));
    });
    req.end();
  });
}

// Helper function to extract links from HTML
function extractLinks(html) {
  try {
    const dom = new JSDOM(html);
    const document = dom.window.document;
    const links = [];

    document.querySelectorAll('a[href]').forEach(anchor => {
      const href = anchor.getAttribute('href');
      if (href.startsWith('http')) {
        links.push({ url: href, text: anchor.textContent.trim() });
      }
    });

    resolve({ success: true, links });
  } catch (error) {
    reject(error);
  }
}

// Helper function to open a database connection
function openDatabase(dbPath) {
  return new Promise((resolve, reject) => {
    try {
      const db = require('better-sqlite3')(dbPath);
      dbConnections.set(dbPath, db);
      resolve({ success: true, path: dbPath });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to close a database connection
function closeDatabase(dbPath) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (db) {
        db.close();
        dbConnections.delete(dbPath);
        resolve({ success: true });
      } else {
        resolve({ success: false, error: 'Database not found' });
      }
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to execute a database query
function executeQuery(dbPath, sql, params = []) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (!db) {
        reject(new Error('Database not open. Call open_database first.'));
        return;
      }

      // Check for read-only mode
      if (process.env.READ_ONLY_MODE === 'true') {
        const isWriteQuery = sql.trim().toUpperCase().match(/^(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER)/i);
        if (isWriteQuery) {
          reject(new Error('Write operations are disabled in read-only mode'));
          return;
        }
      }

      // Limit results to 100 rows for safety
      const limitedSql = sql.trim().toUpperCase().startsWith('SELECT') ? `${sql} LIMIT 100` : sql;

      const results = db.prepare(limitedSql).all(...params);
      resolve({ success: true, results });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to execute a database write operation
function executeWrite(dbPath, sql, params = []) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (!db) {
        reject(new Error('Database not open. Call open_database first.'));
        return;
      }

      // Check for read-only mode
      if (process.env.READ_ONLY_MODE === 'true') {
        reject(new Error('Write operations are disabled in read-only mode'));
        return;
      }

      const result = db.prepare(sql).run(...params);
      resolve({ success: true, changes: result.changes });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to list tables in a database
function listTables(dbPath) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (!db) {
        reject(new Error('Database not open. Call open_database first.'));
        return;
      }

      const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all();
      resolve({ success: true, tables: tables.map(t => t.name) });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to describe a table schema
function describeTable(dbPath, tableName) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (!db) {
        reject(new Error('Database not open. Call open_database first.'));
        return;
      }

      const columns = db.prepare(`PRAGMA table_info("${tableName}")`).all();
      resolve({ success: true, columns });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to get database schema
function getSchema(dbPath) {
  return new Promise((resolve, reject) => {
    try {
      const db = dbConnections.get(dbPath);
      if (!db) {
        reject(new Error('Database not open. Call open_database first.'));
        return;
      }

      const schema = getDatabaseSchema(dbPath);
      resolve({ success: true, schema });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to read a file
function readFile(filePath) {
  return new Promise((resolve, reject) => {
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      resolve({ success: true, content });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to write a file
function writeFile(filePath, content) {
  return new Promise((resolve, reject) => {
    try {
      // Auto-create directories if they don't exist
      const dir = path.dirname(filePath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(filePath, content, 'utf-8');
      resolve({ success: true, path: filePath });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to list directory contents
function listDirectory(dirPath) {
  return new Promise((resolve, reject) => {
    try {
      const items = fs.readdirSync(dirPath);
      const entries = [];
      
      for (const item of items) {
        const fullPath = path.join(dirPath, item);
        const stat = fs.statSync(fullPath);
        entries.push({
          name: item,
          type: stat.isDirectory() ? 'directory' : 'file',
          size: stat.size
        });
      }
      
      resolve({ success: true, entries });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to delete a file
function deleteFile(filePath) {
  return new Promise((resolve, reject) => {
    try {
      if (!fs.existsSync(filePath)) {
        resolve({ success: false, error: 'File not found' });
        return;
      }
      fs.unlinkSync(filePath);
      resolve({ success: true, path: filePath });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to execute code
function executeCode(code, language = 'javascript') {
  return executeCodeSafely(code, language);
}

// Helper function to query database
function queryDatabase(dbPath, sql, readOnly = true) {
  return new Promise((resolve, reject) => {
    try {
      // Open database if not already open
      if (!dbConnections.has(dbPath)) {
        openDatabase(dbPath);
      }

      const results = executeQuery(dbPath, sql, []);
      
      if (readOnly && !sql.trim().toUpperCase().startsWith('SELECT')) {
        reject(new Error('Write operations are disabled in read-only mode'));
        return;
      }

      resolve({
        success: true,
        results: formatQueryResults(results.results)
      });
    } catch (error) {
      reject(error);
    }
  });
}

// Helper function to make HTTP request
function httpRequest(method, url, headers = {}, body = null) {
  return executeHttpRequest(method, url, headers, body);
}

// Helper function to handle tool calls
async function handleToolCall(name, args) {
  try {
    switch (name) {
      case 'read_file':
        return await readFile(args.path);
      case 'write_file':
        return await writeFile(args.path, args.content);
      case 'list_directory':
        return await listDirectory(args.path);
      case 'delete_file':
        return await deleteFile(args.path);
      case 'execute_code':
        return await executeCode(args.code, args.language);
      case 'query_database':
        return await queryDatabase(args.db_path, args.sql, args.read_only);
      case 'http_request':
        return await httpRequest(args.method, args.url, args.headers, args.body);
      case 'translate_text':
        return await translateText(args.text, args.target_language);
      case 'geolocate_ip':
        return await geolocateIP(args.ip_address);
      case 'reverse_geocode':
        return await reverseGeocode(args.latitude, args.longitude);
      case 'get_current_location':
        return await getCurrentLocation();
      case 'fetch':
        return await fetchHTML(args.url);
      case 'extract_links':
        return await extractLinks(args.html);
      default:
        return { content: [{ type: 'text', text: `Unknown tool: ${name}` }] };
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error: ${error.message}` }],
      isError: true
    };
  }
}

// Helper function to handle resource requests
async function handleResourceRequest(uri) {
  try {
    if (uri === 'mcp://system/info') {
      return { contents: [{ uri, mimeType: 'application/json', text: JSON.stringify(getSystemInfo(), null, 2) }] };
    } else if (uri === 'mcp://database/schema') {
      const dbPath = process.env.DATABASE_PATH || '/data/app.db';
      const schema = await getSchema(dbPath);
      return { contents: [{ uri, mimeType: 'application/json', text: JSON.stringify(schema, null, 2) }] };
    } else {
      return { contents: [] };
    }
  } catch (error) {
    return { contents: [], isError: true };
  }
}

// Helper function to handle incoming messages
async function handleMessage(message) {
  if (message.method === 'tools/call') {
    return await handleToolCall(message.params.name, message.params.arguments);
  } else if (message.method === 'resources/read') {
    return await handleResourceRequest(message.params.uri);
  } else {
    return { contents: [{ type: 'text', text: `Unknown method: ${message.method}` }] };
  }
}

// Helper function to start the server
async function startServer() {
  try {
    // Create HTTP server for SSE and message endpoints
    const httpServer = http.createServer(async (req, res) => {
      if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', port: 3005 }));
        return;
      }

      if (req.url === '/sse') {
        res.setHeader('Content-Type', 'text/event-stream');
        res.setHeader('Cache-Control', 'no-cache');
        res.setHeader('Connection', 'keep-alive');
        res.setHeader('Access-Control-Allow-Origin', '*');

        const transport = new SSEServerTransport('/message', req);
        await server.connect(transport);

        // Keep connection alive
        setInterval(() => {
          res.write('event: keepalive\ndata: {}\n\n');
        }, 30000);
      } else if (req.url === '/message' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
          try {
            const message = JSON.parse(body);
            const response = await handleMessage(message);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(response));
          } catch (error) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: error.message }));
          }
        });
      } else {
        res.writeHead(404);
        res.end('Not Found');
      }
    });

    httpServer.listen(3005, () => {
      console.log(`MCP Server running on http://localhost:3005`);
      console.log(`SSE endpoint: http://localhost:3005/sse`);
      console.log(`Health check: http://localhost:3005/health`);
    });

    // Start stdio transport for MCP protocol
    const stdioTransport = new StdioServerTransport();
    await server.connect(stdioTransport);
    console.log('MCP Server started successfully');
  } catch (error) {
    console.error('Failed to start server:', error);
    process.exit(1);
  }
}

// Start the server
startServer();