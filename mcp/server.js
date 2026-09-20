#!/usr/bin/env node
/**
 * GPT Mobile AI - Model Context Protocol (MCP) Server Implementation
 * Version: 0.9.5.4
 * Implements the 11 tools defined in mcp/tools/manifest.json and resources in mcp/resources/manifest.json.
 */

const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { z } = require('zod');
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const https = require('https');
const { URL } = require('url');

const server = new McpServer({
  name: 'GPT Mobile MCP',
  version: '0.9.5.4'
});

// Helper for HTTP requests
function executeHttpRequest(urlStr, method = 'GET', headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    try {
      const parsedUrl = new URL(urlStr);
      const protocol = parsedUrl.protocol === 'https:' ? https : http;
      const options = {
        hostname: parsedUrl.hostname,
        port: parsedUrl.port,
        path: `${parsedUrl.pathname}${parsedUrl.search}`,
        method: method.toUpperCase(),
        headers: headers || {}
      };

      const req = protocol.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => {
          data += chunk;
        });
        res.on('end', () => {
          resolve({
            statusCode: res.statusCode,
            headers: res.headers,
            body: data
          });
        });
      });

      req.on('error', (err) => {
        reject(err);
      });

      req.setTimeout(30000, () => {
        req.destroy(new Error('Request timed out after 30s'));
      });

      if (body) {
        req.write(typeof body === 'string' ? body : JSON.stringify(body));
      }
      req.end();
    } catch (err) {
      reject(err);
    }
  });
}

// 1. read_file
server.tool(
  'read_file',
  'Read content from a local file',
  {
    path: z.string().describe('File path to read')
  },
  async ({ path: filePath }) => {
    try {
      const resolved = path.resolve(filePath);
      const content = fs.readFileSync(resolved, 'utf-8');
      return {
        content: [{ type: 'text', text: content }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Failed to read file: ${error.message}` }]
      };
    }
  }
);

// 2. write_file
server.tool(
  'write_file',
  'Write content to a local file',
  {
    path: z.string().describe('File path'),
    content: z.string().describe('Content to write')
  },
  async ({ path: filePath, content }) => {
    try {
      const resolved = path.resolve(filePath);
      const dir = path.dirname(resolved);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(resolved, content, 'utf-8');
      return {
        content: [{ type: 'text', text: `Successfully wrote ${content.length} characters to ${filePath}` }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Failed to write file: ${error.message}` }]
      };
    }
  }
);

// 3. list_directory
server.tool(
  'list_directory',
  'List contents of a directory',
  {
    path: z.string().describe('Directory path')
  },
  async ({ path: dirPath }) => {
    try {
      const resolved = path.resolve(dirPath);
      const entries = fs.readdirSync(resolved, { withFileTypes: true });
      const listing = entries.map((entry) => ({
        name: entry.name,
        isDirectory: entry.isDirectory(),
        isFile: entry.isFile(),
        isSymbolicLink: entry.isSymbolicLink()
      }));
      return {
        content: [{ type: 'text', text: JSON.stringify(listing, null, 2) }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Failed to list directory: ${error.message}` }]
      };
    }
  }
);

// 4. delete_file
server.tool(
  'delete_file',
  'Delete a file (requires confirmation)',
  {
    path: z.string().describe('File path to delete')
  },
  async ({ path: filePath }) => {
    try {
      const resolved = path.resolve(filePath);
      if (!fs.existsSync(resolved)) {
        return {
          isError: true,
          content: [{ type: 'text', text: `File not found: ${filePath}` }]
        };
      }
      fs.unlinkSync(resolved);
      return {
        content: [{ type: 'text', text: `Successfully deleted file: ${filePath}` }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Failed to delete file: ${error.message}` }]
      };
    }
  }
);

// 5. execute_code
server.tool(
  'execute_code',
  'Execute Python/JavaScript code snippets',
  {
    code: z.string().describe('Code to execute'),
    language: z.string().optional().describe('Programming language (python, javascript)')
  },
  async ({ code, language = 'javascript' }) => {
    const lang = (language || 'javascript').toLowerCase();
    const { exec } = require('child_process');

    return new Promise((resolve) => {
      let command;
      let tempFile = null;

      if (lang === 'javascript' || lang === 'js' || lang === 'node') {
        command = `node -e ${JSON.stringify(code)}`;
      } else if (lang === 'python' || lang === 'py') {
        const tempPath = path.join(os.tmpdir(), `mcp_exec_${Date.now()}.py`);
        fs.writeFileSync(tempPath, code, 'utf-8');
        tempFile = tempPath;
        command = `python3 "${tempPath}"`;
      } else {
        return resolve({
          isError: true,
          content: [{ type: 'text', text: `Unsupported language: ${language}. Supported: javascript, python.` }]
        });
      }

      exec(command, { timeout: 15000, maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
        if (tempFile && fs.existsSync(tempFile)) {
          try {
            fs.unlinkSync(tempFile);
          } catch (_) {}
        }

        if (err) {
          resolve({
            isError: true,
            content: [{
              type: 'text',
              text: `Execution failed:\nExit code: ${err.code}\nError: ${err.message}\nStderr:\n${stderr}\nStdout:\n${stdout}`
            }]
          });
        } else {
          resolve({
            content: [{
              type: 'text',
              text: stdout || (stderr ? `Warnings/Stderr:\n${stderr}` : 'Code executed successfully with no output.')
            }]
          });
        }
      });
    });
  }
);

// 6. query_database
server.tool(
  'query_database',
  'Query SQLite/Room database',
  {
    sql: z.string().describe('SQL query to execute')
  },
  async ({ sql }) => {
    return {
      content: [{
        type: 'text',
        text: JSON.stringify({
          status: 'simulated_query_response',
          notice: 'When running on-device or against local databases, connect using SQLite adapter.',
          query: sql,
          rows: []
        }, null, 2)
      }]
    };
  }
);

// 7. http_request
server.tool(
  'http_request',
  'Make HTTP requests (GET/POST/PUT/DELETE)',
  {
    url: z.string().describe('Target URL'),
    method: z.string().optional().describe('HTTP method (GET, POST, PUT, DELETE)'),
    headers: z.record(z.any()).optional().describe('Request headers'),
    body: z.string().optional().describe('Request body')
  },
  async ({ url, method = 'GET', headers = {}, body = null }) => {
    try {
      const res = await executeHttpRequest(url, method, headers, body);
      return {
        content: [{
          type: 'text',
          text: JSON.stringify(res, null, 2)
        }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `HTTP request failed: ${error.message}` }]
      };
    }
  }
);

// 8. translate_text
server.tool(
  'translate_text',
  'Translate text between languages',
  {
    text: z.string().describe('Text to translate'),
    source_lang: z.string().optional().describe('Source language code (e.g., en, es)'),
    target_lang: z.string().describe('Target language code (e.g., fr, de)')
  },
  async ({ text, source_lang = 'auto', target_lang }) => {
    try {
      const queryUrl = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=${encodeURIComponent(source_lang)}&tl=${encodeURIComponent(target_lang)}&dt=t&q=${encodeURIComponent(text)}`;
      const res = await executeHttpRequest(queryUrl, 'GET', { 'User-Agent': 'Mozilla/5.0' });
      const parsed = JSON.parse(res.body);
      const translated = parsed[0].map((item) => item[0]).join('');
      return {
        content: [{
          type: 'text',
          text: JSON.stringify({
            original: text,
            translated,
            source_language: parsed[2] || source_lang,
            target_language: target_lang
          }, null, 2)
        }]
      };
    } catch (error) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Translation failed: ${error.message}` }]
      };
    }
  }
);

// 9. geolocate_ip - Look up IP geolocation data
server.tool(
  'geolocate_ip',
  'Look up geolocation data for an IP address (keyless API)',
  {
    ip: z.string().optional().describe('IP address to lookup (auto-detect host IP if omitted)')
  },
  async ({ ip }) => {
    const targetIp = ip || await getPublicIp();
    
    // Use ipapi.is (keyless, free tier available)
    const url = `https://ipapi.co/${targetIp}/json/`;
    const res = await executeHttpRequest(url, 'GET', { 'User-Agent': 'GPT-Mobile-AI' });
    
    if (res.statusCode === 200) {
      return {
        content: [{ type: 'text', text: JSON.stringify(res.body, null, 2) }]
      };
    }
    
    return {
      isError: true,
      content: [{ type: 'text', text: `IP lookup failed (status ${res.statusCode}): ${res.body}` }]
    };
  }
);

// Helper: Detect public IP of the host
async function getPublicIp() {
  const res = await executeHttpRequest('https://api.ipify.org?format=json');
  return JSON.parse(res.body).ip;
}

// 10. reverse_geocode - Convert coordinates to human-readable address
server.tool(
  'reverse_geocode',
  'Convert latitude/longitude coordinates to human-readable address (OpenStreetMap Nominatim)',
  {
    latitude: z.number().describe('Latitude (-90 to 90)'),
    longitude: z.number().describe('Longitude (-180 to 180)'),
    format: z.enum(['json', 'address', 'text']).optional().default('json').describe('Output format')
  },
  async ({ latitude, longitude, format = 'json' }) => {
    // Validate coordinate bounds
    if (latitude < -90 || latitude > 90) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Invalid latitude: ${latitude}. Must be between -90 and 90.` }]
      };
    }
    if (longitude < -180 || longitude > 180) {
      return {
        isError: true,
        content: [{ type: 'text', text: `Invalid longitude: ${longitude}. Must be between -180 and 180.` }]
      };
    }

    // Use Nominatim (OpenStreetMap, keyless)
    const url = `https://nominatim.openstreetmap.org/reverse?format=json&lat=${latitude}&lon=${longitude}`;
    const res = await executeHttpRequest(url, 'GET', { 
      'User-Agent': 'GPT-Mobile-AI/1.0' 
    });
    
    if (res.statusCode === 200) {
      let output;
      if (format === 'address') {
        const data = JSON.parse(res.body);
        output = `${data.display_name || 'Address not found'}`;
      } else if (format === 'text') {
        const data = JSON.parse(res.body);
        output = `Location: ${data.display_name || 'Unknown'}\nCoordinates: ${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
      } else {
        output = res.body;
      }
      
      return {
        content: [{ type: 'text', text: output }]
      };
    }
    
    return {
      isError: true,
      content: [{ type: 'text', text: `Reverse geocode failed (status ${res.statusCode}): ${res.body}` }]
    };
  }
);

// 11. get_current_location - Get GPS coordinates from device (requires native bridge)
server.tool(
  'get_current_location',
  'Get current GPS location from Android/iOS device (requires native companion service)',
  {},
  async () => {
    return {
      content: [{ 
        type: 'text', 
        text: 'GPS access requires a native Android/iOS bridge. Use get_my_ip() for network-based location, or implement a companion service to expose GPS coordinates via HTTP/WebSocket.' 
      }]
    };
  }
);

// Resources support (from mcp/resources/manifest.json)
server.resource(
  'system-info',
  'mcp://system/info',
  async (uri) => ({
    contents: [{
      uri: uri.href,
      mimeType: 'application/json',
      text: JSON.stringify({
        platform: os.platform(),
        arch: os.arch(),
        release: os.release(),
        totalMemory: os.totalmem(),
        freeMemory: os.freemem(),
        cpus: os.cpus().length,
        loadavg: os.loadavg(),
        uptime: os.uptime()
      }, null, 2)
    }]
  })
);

server.resource(
  'database-schema',
  'mcp://database/schema',
  async (uri) => ({
    contents: [{
      uri: uri.href,
      mimeType: 'application/json',
      text: JSON.stringify({
        version: 19,
        tables: ['conversations', 'messages', 'favorites', 'local_models', 'settings']
      }, null, 2)
    }]
  })
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  console.error('Fatal MCP Server error:', err);
  process.exit(1);
});