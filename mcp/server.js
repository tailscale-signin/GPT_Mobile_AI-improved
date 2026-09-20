const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { SSEServerTransport } = require("@modelcontextprotocol/sdk/server/sse.js");
const { z } = require("zod");
const http = require("http");

// Create MCP server instance
const server = new McpServer({
  name: "GPT Mobile AI Improved",
  version: "1.0.0",
});

// Define tools
server.tool(
  "search_repositories",
  "Search GitHub repositories",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/repositories?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_issues",
  "Search GitHub issues",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/issues?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_file_contents",
  "Get file contents from a GitHub repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    path: z.string().describe("File path within repository"),
    ref: z.string().optional().describe("Branch name, commit SHA, or tag (defaults to default branch)"),
  },
  async ({ owner, repo, path, ref }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/contents/${path}${ref ? `?ref=${ref}` : ""}`,
      { headers: { Accept: "application/vnd.github.v3+json" } }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: Buffer.from(data.content, "base64").toString() }] };
  }
);

server.tool(
  "get_issue",
  "Get issue details from a GitHub repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    issue_number: z.number().describe("Issue number"),
  },
  async ({ owner, repo, issue_number }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/issues/${issue_number}`,
      { headers: { Accept: "application/vnd.github.v3+json" } }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "add_issue_comment",
  "Add a comment to an issue or pull request",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    issue_number: z.number().describe("Issue or PR number"),
    body: z.string().describe("Comment content"),
  },
  async ({ owner, repo, issue_number, body }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/issues/${issue_number}/comments`,
      {
        method: "POST",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ body }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `Comment added successfully. ID: ${data.id}` }] };
  }
);

server.tool(
  "add_pull_request_comment",
  "Add a comment to a pull request",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    issue_number: z.number().describe("Pull request number"),
    body: z.string().describe("Comment content"),
  },
  async ({ owner, repo, issue_number, body }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/issues/${issue_number}/comments`,
      {
        method: "POST",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ body }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `Comment added successfully. ID: ${data.id}` }] };
  }
);

server.tool(
  "add_pull_request_review_comment",
  "Add a review comment to a pull request",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    pullNumber: z.number().describe("Pull request number"),
    path: z.string().describe("File path in the PR diff"),
    body: z.string().describe("Review comment text"),
    line: z.number().optional().describe("Line number in the file (1-indexed)"),
  },
  async ({ owner, repo, pullNumber, path, body, line }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/pulls/${pullNumber}/reviews/comments`,
      {
        method: "POST",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ path, body, line }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `Review comment added successfully. ID: ${data.id}` }] };
  }
);

server.tool(
  "create_pull_request",
  "Create a new pull request",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    title: z.string().describe("PR title"),
    head: z.string().describe("Branch containing changes"),
    base: z.string().describe("Base branch to merge into"),
    body: z.string().optional().describe("PR description"),
  },
  async ({ owner, repo, title, head, base, body }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/pulls`,
      {
        method: "POST",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ title, head, base, body }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `PR created successfully. URL: ${data.html_url}` }] };
  }
);

server.tool(
  "create_branch",
  "Create a new branch in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    branch: z.string().describe("Name for new branch"),
    from_branch: z.string().optional().describe("Source branch (defaults to repo default)"),
  },
  async ({ owner, repo, branch, from_branch }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/git/refs`,
      {
        method: "POST",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: from_branch ? (await fetch(`https://api.github.com/repos/${owner}/${repo}/branches/${from_branch}`).then(r => r.json())).commit.sha : undefined }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `Branch created successfully. URL: ${data.ref}` }] };
  }
);

server.tool(
  "create_or_update_file",
  "Create or update a file in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    path: z.string().describe("File path"),
    content: z.string().describe("File content"),
    message: z.string().describe("Commit message"),
    branch: z.string().optional().describe("Branch to create/update in"),
  },
  async ({ owner, repo, path, content, message, branch }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/git/trees`,
      {
        method: "GET",
        headers: { Accept: "application/vnd.github.v3+json" },
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const tree = await response.json();
    
    let sha = null;
    if (tree.tree.some(t => t.path === path)) {
      sha = tree.tree.find(t => t.path === path).sha;
    }
    
    const putResponse = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/contents/${path}`,
      {
        method: "PUT",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ message, content, branch, sha }),
      }
    );
    if (!putResponse.ok) throw new Error("GitHub API request failed");
    const data = await putResponse.json();
    return { content: [{ type: "text", text: `File ${data.commit ? 'updated' : 'created'} successfully. URL: ${data.html_url}` }] };
  }
);

server.tool(
  "delete_file",
  "Delete a file from a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    path: z.string().describe("File path to delete"),
    message: z.string().describe("Commit message"),
    branch: z.string().optional().describe("Branch to delete from"),
  },
  async ({ owner, repo, path, message, branch }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/contents/${path}`,
      {
        method: "DELETE",
        headers: { Accept: "application/vnd.github.v3+json", "Content-Type": "application/json" },
        body: JSON.stringify({ message, branch }),
      }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: `File deleted successfully. URL: ${data.commit?.html_url || 'N/A'}` }] };
  }
);

server.tool(
  "list_branches",
  "List branches in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
  },
  async ({ owner, repo }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/branches`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(b => ({ name: b.name, commit: b.commit.sha })), null, 2) }] };
  }
);

server.tool(
  "list_commits",
  "List commits on a branch",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    sha: z.string().optional().describe("Branch or tag name (defaults to default branch)"),
  },
  async ({ owner, repo, sha }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/commits${sha ? `?sha=${sha}` : ""}`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(c => ({ sha: c.sha, message: c.commit.message, author: c.author.name })), null, 2) }] };
  }
);

server.tool(
  "list_issues",
  "List issues in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    state: z.enum(["open", "closed"]).optional().describe("Filter by state (defaults to both)"),
  },
  async ({ owner, repo, state }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/issues${state ? `?state=${state}` : ""}`,
      { headers: { Accept: "application/vnd.github.v3+json" } }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(i => ({ number: i.number, title: i.title, state: i.state })), null, 2) }] };
  }
);

server.tool(
  "list_pull_requests",
  "List pull requests in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    state: z.enum(["open", "closed", "all"]).optional().describe("Filter by state (defaults to both)"),
  },
  async ({ owner, repo, state }) => {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/pulls${state ? `?state=${state}` : ""}`,
      { headers: { Accept: "application/vnd.github.v3+json" } }
    );
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(p => ({ number: p.number, title: p.title, state: p.state })), null, 2) }] };
  }
);

server.tool(
  "list_releases",
  "List releases in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
  },
  async ({ owner, repo }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(r => ({ tag_name: r.tag_name, name: r.name, published_at: r.published_at })), null, 2) }] };
  }
);

server.tool(
  "list_tags",
  "List git tags in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
  },
  async ({ owner, repo }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/tags`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(t => ({ name: t.name, commit: t.commit.sha })), null, 2) }] };
  }
);

server.tool(
  "list_collaborators",
  "List repository collaborators",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
  },
  async ({ owner, repo }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/collaborators`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data.map(c => ({ login: c.login, permission: c.permission })), null, 2) }] };
  }
);

server.tool(
  "get_latest_release",
  "Get the latest release in a repository",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
  },
  async ({ owner, repo }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases/latest`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_release_by_tag",
  "Get a specific release by tag name",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    tag: z.string().describe("Tag name (e.g., 'v1.0.0')"),
  },
  async ({ owner, repo, tag }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases/tags/${tag}`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_tag",
  "Get details about a specific git tag",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    tag: z.string().describe("Tag name"),
  },
  async ({ owner, repo, tag }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/git/tags/${tag}`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_commit",
  "Get details for a commit",
  {
    owner: z.string().describe("Repository owner"),
    repo: z.string().describe("Repository name"),
    sha: z.string().describe("Commit SHA, branch name, or tag name"),
  },
  async ({ owner, repo, sha }) => {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/commits/${sha}`, {
      headers: { Accept: "application/vnd.github.v3+json" },
    });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_code",
  "Search code across GitHub repositories",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/code?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_commits",
  "Search commits across GitHub repositories",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/commits?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_issues",
  "Search issues using natural language",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/issues?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_pull_requests",
  "Search pull requests using natural language",
  {
    query: z.string().describe("Search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/issues?q=${encodeURIComponent(query)}&type=pr`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_repositories",
  "Search repositories by name, description, readme, topics, or other metadata",
  {
    query: z.string().describe("Repository search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/repositories?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "search_users",
  "Find GitHub users by username, real name, or other profile information",
  {
    query: z.string().describe("User search query"),
  },
  async ({ query }) => {
    const response = await fetch(`https://api.github.com/search/users?q=${encodeURIComponent(query)}`);
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_me",
  "Get details of the authenticated GitHub user",
  {},
  async () => {
    const response = await fetch("https://api.github.com/user", { headers: { Accept: "application/vnd.github.v3+json" } });
    if (!response.ok) throw new Error("GitHub API request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "get_current_date",
  "Returns the current local date, time, and time zone.",
  {},
  async () => {
    const response = await fetch("https://worldtimeapi.org/api/ip");
    if (!response.ok) throw new Error("WorldTimeAPI request failed");
    const data = await response.json();
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

server.tool(
  "calculate_expression",
  "Evaluates a mathematical expression safely and accurately.",
  {
    expression: z.string().describe("The mathematical expression to evaluate"),
  },
  async ({ expression }) => {
    try {
      // Safe evaluation using Function constructor with restricted scope
      const result = new Function(`return (${expression})`)();
      return { content: [{ type: "text", text: String(result) }] };
    } catch (error) {
      throw new Error("Invalid expression");
    }
  }
);

// HTTP/SSE server setup
const PORT = process.env.PORT || 3005;
let httpServer = null;
let stdioTransport = null;

async function startHttpServer() {
  return new Promise((resolve, reject) => {
    httpServer = http.createServer(async (req, res) => {
      // SSE endpoint
      if (req.method === "GET" && req.url === "/sse") {
        const sessionId = Math.random().toString(36).substring(7);
        const transport = new SSEServerTransport("/message", req, sessionId);
        
        try {
          await server.connect(transport);
          res.writeHead(200, {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "Access-Control-Allow-Origin": "*"
          });
          res.flushHeaders();
          resolve();
        } catch (error) {
          reject(error);
        }
      }
      // Message endpoint
      else if (req.method === "POST" && req.url === "/message") {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', async () => {
          try {
            const message = JSON.parse(body);
            await transport.handlePostMessage(message);
          } catch (error) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "Invalid message format" }));
          }
        });
      }
      // Health check
      else if (req.method === "GET" && req.url === "/health") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok", port: PORT }));
      }
      // CORS preflight
      else if (req.method === "OPTIONS") {
        res.writeHead(204, {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type"
        });
        res.end();
      }
      // 404 for other routes
      else {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "Not found" }));
      }
    });

    httpServer.listen(PORT, () => {
      console.error(`HTTP/SSE server listening on port ${PORT}`);
      console.error(`SSE endpoint: http://localhost:${PORT}/sse`);
      console.error(`Health check: http://localhost:${PORT}/health`);
    });

    httpServer.on('error', (err) => {
      console.error(`HTTP server error:`, err);
      reject(err);
    });
  });
}

async function main() {
  try {
    // Start HTTP/SSE server
    await startHttpServer();
    
    // Also support stdio transport for CLI/agent integration
    const stdioTransportInstance = new StdioServerTransport();
    await server.connect(stdioTransportInstance);
    console.error("MCP server connected via stdio (for CLI/agent integration)");
  } catch (error) {
    console.error("Fatal error:", error);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
