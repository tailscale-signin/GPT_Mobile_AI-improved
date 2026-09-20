const http = require('http');
const https = require('https');
const { URL } = require('url');
const { JSDOM } = require('jsdom');
const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sseServerTransport.js');
const { Server } = require('@modelcontextprotocol/sdk/server/index.js');

// Create MCP server instance
const server = new Server({
  name: 'web-fetcher',
  version: '1.0.0'
}, {
  capabilities: {
    tools: {}
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
    req.on('end', () => {
      if (transport) {
        transport.handlePostMessage(JSON.parse(body));
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

// Helper: Fetch URL and return HTML
function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
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
    const prefix = list.tagName === 'OL' ? `${Array.from(items).indexOf(_) + 1}. ` : '- ';
    items.forEach(item => {
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

// Tool: Fetch web page and convert to Markdown
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

// Tool: Extract outbound links from URL
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
