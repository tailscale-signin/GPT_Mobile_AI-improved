"""Optional SDK bridge, executed only inside an isolated agent image.

The small ACP-shaped boundary keeps SDK dependencies out of Gateway's runtime.
Images must preinstall their selected SDK and native runtime; nothing is fetched
or upgraded by Gateway. Protocol stdout is separate from SDK diagnostics.
"""
import asyncio
from dataclasses import asdict, is_dataclass
import json
import os
from pathlib import Path
import stat
import sys
import uuid


class CheckoutTools:
    """File-only Copilot tools. No shell, network, imports or environment tool."""
    def __init__(self, root):
        self.root = Path(root).resolve()

    def path(self, relative):
        if (not isinstance(relative, str) or not 1 <= len(relative) <= 400 or relative.startswith('/') or '\\' in relative
                or any(ord(char) < 32 for char in relative) or any(part in {'', '.', '..'} or part.casefold() == '.git' for part in relative.split('/'))):
            raise ValueError('Use a safe checkout-relative source path')
        current = self.root
        for part in relative.split('/'):
            current /= part
            if current.is_symlink():
                raise ValueError('Checkout file links are unavailable')
        return current

    @staticmethod
    def read(path):
        # Nonblocking/no-follow also rejects a special file raced in after the
        # path walk; a FIFO must never hang a file-only tool handler.
        descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > 4 * 1024 * 1024:
                raise ValueError('Read a bounded regular text file')
            raw = stream.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024 or b'\0' in raw:
            raise ValueError('Read a bounded regular text file')
        return raw.decode('utf-8')

    def call(self, kind, args):
        if not isinstance(args, dict):
            raise ValueError('Use a structured checkout tool request')
        if kind == 'gateway_list_files':
            if set(args) != {'directory'}:
                raise ValueError('Supply one checkout directory')
            folder = self.root if args['directory'] == '.' else self.path(args['directory'])
            entries = []
            with os.scandir(folder) as paths:
                for row in paths:
                    if row.name.casefold() == '.git' or row.is_symlink():
                        continue
                    entries.append({'name': row.name, 'directory': row.is_dir(follow_symlinks=False)})
                    if len(entries) > 200:
                        break
            return {'entries': sorted(entries[:200], key=lambda row: row['name']), 'truncated': len(entries) > 200}
        if kind == 'gateway_read_file':
            if set(args) - {'path', 'start_line', 'line_count'} or 'path' not in args:
                raise ValueError('Supply a source path and bounded line range')
            start, count = args.get('start_line', 1), args.get('line_count', 100)
            if type(start) is not int or start < 1 or type(count) is not int or not 1 <= count <= 200:
                raise ValueError('Read one to 200 source lines')
            lines = self.read(self.path(args['path'])).splitlines(True)
            return {'path': args['path'], 'content': ''.join(lines[start - 1:start - 1 + count])[:16000], 'total_lines': len(lines)}
        if kind == 'gateway_edit_file':
            if set(args) != {'path', 'old', 'replacement'} or any(not isinstance(args[key], str) for key in args):
                raise ValueError('Supply a source path and exact old/replacement text')
            path, old, replacement = self.path(args['path']), args['old'], args['replacement']
            if len(old.encode()) + len(replacement.encode()) > 512 * 1024 or '\0' in replacement:
                raise ValueError('The exact text edit exceeds its allowance')
            if old == '':
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open('xb') as stream:
                    stream.write(replacement.encode())
            else:
                original = self.read(path)
                if original.count(old) != 1:
                    raise ValueError('The old source anchor must match exactly once')
                changed = original.replace(old, replacement)
                if len(changed.encode()) > 4 * 1024 * 1024:
                    raise ValueError('The edited file exceeds its allowance')
                path.write_bytes(changed.encode())
            return {'path': args['path'], 'edited': True}
        raise ValueError('This runtime exposes only its three bounded checkout tools')

    def copilot(self, session_id):
        from copilot.tools import Tool, ToolResult
        specs = {
            'gateway_list_files': ('List up to 200 checkout entries.', {'directory': {'type': 'string'}}, ['directory']),
            'gateway_read_file': ('Read a bounded source range.', {'path': {'type': 'string'}, 'start_line': {'type': 'integer', 'minimum': 1},
                                  'line_count': {'type': 'integer', 'minimum': 1, 'maximum': 200}}, ['path']),
            'gateway_edit_file': ('Replace one unique exact source anchor; empty old creates a new file.',
                                  {key: {'type': 'string'} for key in ('path', 'old', 'replacement')}, ['path', 'old', 'replacement'])}
        def handler(name):
            async def invoke(invocation):
                try:
                    if invocation.session_id != session_id:
                        raise ValueError('Tool session identity changed')
                    value = self.call(name, invocation.arguments)
                    return ToolResult(text_result_for_llm=json.dumps(value), result_type='success')
                except (ValueError, OSError):
                    return ToolResult(text_result_for_llm='Checkout tool rejected the bounded source operation.', result_type='failure')
            return invoke
        return [Tool(name=name, description=description, handler=handler(name), skip_permission=True, defer='never',
                     parameters={'type': 'object', 'properties': properties, 'required': required, 'additionalProperties': False})
                for name, (description, properties, required) in specs.items()]


class Driver:
    def __init__(self, backend, output):
        self.backend, self.output = backend, output
        self.session_id, self.session, self.client, self.options = None, None, None, None
        self.busy = False

    def write(self, value):
        raw = json.dumps(value, default=str, separators=(',', ':'))
        if len(raw.encode()) > 60000:
            value = {'jsonrpc': '2.0', 'method': 'session/update',
                     'params': {'sessionId': self.session_id, 'update': {'sessionUpdate': 'gateway_event', 'omitted': True}}}
            raw = json.dumps(value)
        self.output.write(raw + '\n')
        self.output.flush()

    def event(self, value):
        if is_dataclass(value):
            value = asdict(value)
        elif hasattr(value, 'model_dump'):
            value = value.model_dump(mode='json')
        elif not isinstance(value, (str, dict, list, int, float, bool, type(None))):
            value = {'type': type(value).__name__}
        self.write({'jsonrpc': '2.0', 'method': 'session/update',
                    'params': {'sessionId': self.session_id, 'update': {'sessionUpdate': 'gateway_event', 'event': value}}})

    async def create(self, params):
        if self.session_id:
            raise ValueError('One session per isolated runtime')
        root = Path(params['cwd']).resolve()
        if root != Path.cwd().resolve():
            raise ValueError('Session must use the sealed checkout')
        self.options = params.get('_meta', {}).get('gateway', {})
        model = self.options['model_id']
        self.session_id = str(uuid.uuid4())
        if self.backend == 'claude':
            from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions
            options = ClaudeAgentOptions(model=model, cwd=str(root), session_id=self.session_id,
                max_turns=self.options['max_iterations'], max_budget_usd=self.options['max_budget_usd'],
                permission_mode='acceptEdits', tools=['Read', 'Edit', 'Write', 'Glob', 'Grep'],
                allowed_tools=['Read', 'Edit', 'Write', 'Glob', 'Grep'],
                disallowed_tools=['Bash', 'WebFetch', 'WebSearch'], strict_mcp_config=True, mcp_servers={},
                setting_sources=[], enable_file_checkpointing=True)
            self.client = ClaudeSDKClient(options=options)
            await self.client.connect()
        elif self.backend == 'openhands':
            from openhands.sdk import LLM, Agent, Conversation, Tool
            from openhands.tools.file_editor import FileEditorTool
            from openhands.tools.terminal import TerminalTool
            from openhands.tools.task_tracker import TaskTrackerTool
            llm = LLM(model=model, api_key=os.getenv('LLM_API_KEY'), base_url=os.getenv('LLM_BASE_URL') or None)
            agent = Agent(llm=llm, tools=[Tool(name=TerminalTool.name), Tool(name=FileEditorTool.name), Tool(name=TaskTrackerTool.name)])
            self.session = Conversation(agent=agent, workspace=str(root), conversation_id=uuid.UUID(self.session_id),
                persistence_dir=str(root.parent / 'home' / 'openhands'), callbacks=[self.event],
                max_iteration_per_run=self.options['max_iterations'], max_budget_per_run=self.options['max_budget_usd'],
                visualizer=None)
        elif self.backend == 'copilot_sdk':
            from copilot import CopilotClient, RuntimeConnection
            from copilot.rpc import PermissionDecisionReject
            os.environ['COPILOT_SKIP_CLI_DOWNLOAD'] = '1'
            token = os.environ.pop('GH_TOKEN', None)
            if not token:
                raise ValueError('An explicit Copilot inference credential is required')
            environment = {key: value for key, value in os.environ.items()
                           if key in {'PATH', 'HOME', 'LANG', 'COPILOT_SKIP_CLI_DOWNLOAD', 'COPILOT_CLI_PATH', 'COPILOT_CLI_EXTRACT_DIR'}}
            runtime = RuntimeConnection.for_spawned_stdio(env=environment, working_directory=str(root),
                        base_directory=str(root.parent / 'home' / 'copilot'))
            self.client = CopilotClient(mode='empty', runtime=runtime, github={'token': token, 'use_logged_in_user': False})
            await self.client.start()
            tools = CheckoutTools(root).copilot(self.session_id)
            self.session = await self.client.create_session(model=model, session_id=self.session_id,
                working_directory=str(root), permissions={'on_request': lambda *_: PermissionDecisionReject(feedback='Only the three configured checkout tools are available.')},
                tools={'definitions': tools, 'available_tools': ['custom:' + tool.name for tool in tools]},
                enable_config_discovery=False,
                state={'memory': {'enabled': False}, 'enable_cross_session_store': False},
                execution={'autopilot': {'max_continues': 0}})
            self.session.on(self.event)
        elif self.backend != 'aider':
            raise ValueError('Unsupported optional SDK')
        return {'sessionId': self.session_id}

    async def prompt(self, params):
        if params.get('sessionId') != self.session_id or self.busy:
            raise ValueError('Session identity changed or has an active turn')
        text = '\n'.join(v['text'] for v in params.get('prompt', []) if v.get('type') == 'text')
        if not 1 <= len(text) <= 24000:
            raise ValueError('Bounded text prompt required')
        self.busy = True
        try:
            if self.backend == 'claude':
                await self.client.query(text)
                async for message in self.client.receive_response():
                    self.event(message)
                    if type(message).__name__ == 'ResultMessage' and (
                            getattr(message, 'is_error', False) or str(getattr(message, 'subtype', '')).startswith('error')):
                        raise ValueError('Claude did not complete the turn')
            elif self.backend == 'openhands':
                self.session.send_message(text)
                await asyncio.to_thread(self.session.run)
                if str(self.session.state.execution_status).upper().split('.')[-1] not in {'FINISHED', 'IDLE'}:
                    raise ValueError('OpenHands did not complete the turn')
            elif self.backend == 'copilot_sdk':
                result = await self.session.send_and_wait(text)
                self.event(result)
            elif self.backend == 'aider':
                process = await asyncio.create_subprocess_exec('aider', '--model', self.options['model_id'], '--message', text,
                    '--yes-always', '--no-auto-commits', '--no-dirty-commits', '--no-stream',
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
                while block := await process.stdout.read(8000):
                    self.event({'text': block.decode('utf-8', 'replace')})
                if await process.wait() != 0:
                    raise ValueError('Aider did not complete the turn')
            return {'stopReason': 'end_turn'}
        finally:
            self.busy = False

    async def handle(self, frame):
        identity = frame.get('id')
        try:
            method, params = frame.get('method'), frame.get('params', {})
            if method == 'initialize':
                if params.get('protocolVersion') != 1:
                    raise ValueError('Unsupported protocol')
                result = {'protocolVersion': 1, 'agentCapabilities': {'loadSession': False, 'gatewayFollowupWhenIdle': True},
                          'agentInfo': {'name': 'gateway-' + self.backend, 'version': '1'}}
            elif method == 'session/new':
                result = await self.create(params)
            elif method == 'session/prompt':
                result = await self.prompt(params)
            else:
                raise ValueError('Unsupported SDK operation')
            if identity is not None:
                self.write({'jsonrpc': '2.0', 'id': identity, 'result': result})
        except Exception:
            if identity is not None:
                self.write({'jsonrpc': '2.0', 'id': identity,
                            'error': {'code': -32000, 'message': 'Optional agent runtime rejected the operation; inspect its installation and account configuration.'}})


async def main():
    output = sys.stdout
    sys.stdout = sys.stderr
    driver = Driver(sys.argv[1], output)
    pending = set()
    while raw := await asyncio.to_thread(sys.stdin.buffer.readline, 256 * 1024 + 1):
        if len(raw) > 256 * 1024:
            break
        try:
            frame = json.loads(raw)
            if not isinstance(frame, dict):
                break
        except ValueError:
            break
        task = asyncio.create_task(driver.handle(frame))
        pending.add(task)
        task.add_done_callback(pending.discard)
    if pending:
        await asyncio.gather(*pending)


if __name__ == '__main__':
    asyncio.run(main())
