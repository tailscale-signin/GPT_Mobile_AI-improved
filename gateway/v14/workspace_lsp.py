"""One compiler/language-server query against the live isolated checkout."""
import json
from pathlib import Path
import subprocess
import sys

SERVERS = {'kotlin': ['kotlin-lsp', '--stdio'], 'python': ['pylsp'], 'typescript': ['typescript-language-server', '--stdio']}
KINDS = {'definition': ('definitionProvider', 'textDocument/definition'),
         'references': ('referencesProvider', 'textDocument/references'),
         'diagnostics': ('diagnosticProvider', 'textDocument/diagnostic'),
         'rename_preview': ('renameProvider', 'textDocument/rename'),
         'callers': ('callHierarchyProvider', 'textDocument/prepareCallHierarchy'),
         'supertypes': ('typeHierarchyProvider', 'textDocument/prepareTypeHierarchy')}


class Client:
    def __init__(self, process):
        self.process = process
        self.next_id = 0
        self.diagnostics = {}

    def send(self, message):
        raw = json.dumps(dict(jsonrpc='2.0', **message), separators=(',', ':')).encode()
        self.process.stdin.write(b'Content-Length: ' + str(len(raw)).encode() + b'\r\n\r\n' + raw)
        self.process.stdin.flush()

    def request(self, method, params):
        self.next_id += 1
        ident = self.next_id
        self.send(dict(id=ident, method=method, params=params))
        while True:
            size = None
            for _ in range(16):
                header = self.process.stdout.readline(1024)
                if not header:
                    raise ValueError('Language server closed its connection')
                if header in (b'\r\n', b'\n'):
                    break
                key, _, value = header.partition(b':')
                if key.strip().lower() == b'content-length':
                    size = int(value)
            if size is None or not 1 <= size <= 4 * 1024 * 1024:
                raise ValueError('Invalid language-server frame')
            message = json.loads(self.process.stdout.read(size))
            if 'method' in message:
                if 'id' in message:
                    if message['method'] == 'workspace/configuration':
                        self.send(dict(id=message['id'], result=[None] * len(message.get('params', {}).get('items', []))))
                    elif message['method'] in {'client/registerCapability', 'client/unregisterCapability', 'window/workDoneProgress/create'}:
                        self.send(dict(id=message['id'], result=None))
                    else:
                        self.send(dict(id=message['id'], error={'code': -32601, 'message': 'Unsupported client request'}))
                elif message['method'] == 'textDocument/publishDiagnostics':
                    p = message['params']
                    self.diagnostics[p['uri']] = p.get('diagnostics', [])
            elif message.get('id') == ident:
                if 'error' in message:
                    raise ValueError('Language server rejected the query: ' + str(message['error'].get('code')))
                return message.get('result')


def query(root, action, servers=None):
    language, kind = action['language'], action['operation']
    servers = SERVERS if servers is None else servers
    if language not in servers or kind not in KINDS:
        raise ValueError('Choose a supported language and code-intelligence operation')
    file = (root / action['path']).resolve(strict=True)
    if not file.is_relative_to(root) or not file.is_file() or file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Select a bounded file in the live checkout')
    line, character = action.get('line', 1), action.get('column', 1)
    if type(line) is not int or type(character) is not int or min(line, character) < 1:
        raise ValueError('Positions begin at line 1, UTF-16 column 1')
    text = file.read_text(encoding='utf-8')
    uri = file.as_uri()
    proc = subprocess.Popen(servers[language], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    client = Client(proc)
    try:
        capabilities = client.request('initialize', {
            'processId': None, 'rootUri': root.as_uri(), 'workspaceFolders': [{'name': root.name, 'uri': root.as_uri()}],
            'capabilities': {'workspace': {'configuration': True}, 'general': {'positionEncodings': ['utf-16']},
                             'textDocument': {'diagnostic': {}, 'callHierarchy': {}, 'typeHierarchy': {}}}})['capabilities']
        client.send({'method': 'initialized', 'params': {}})
        client.send({'method': 'textDocument/didOpen', 'params': {'textDocument': {
            'uri': uri, 'version': 1, 'languageId': language, 'text': text}}})
        provider, method = KINDS[kind]
        supported = capabilities.get(provider)
        if supported is None or supported is False:
            # Some servers push diagnostics after opening or resolving the file.
            if kind == 'diagnostics' and capabilities.get('documentSymbolProvider'):
                client.request('textDocument/documentSymbol', {'textDocument': {'uri': uri}})
            if kind == 'diagnostics' and uri in client.diagnostics:
                return {'resolution': 'language_server', 'operation': kind, 'result': client.diagnostics[uri]}
            return {'resolution': 'unavailable', 'operation': kind, 'next_action': 'Use lexical exploration or run the project compiler'}
        params = {'textDocument': {'uri': uri}}
        if kind != 'diagnostics':
            params['position'] = {'line': line - 1, 'character': character - 1}
        if kind == 'references':
            params['context'] = {'includeDeclaration': True}
        if kind == 'rename_preview':
            name = action.get('new_name')
            if not isinstance(name, str) or not 1 <= len(name) <= 200:
                raise ValueError('Supply a bounded proposed name')
            params['newName'] = name
        result = client.request(method, params)
        if kind in {'callers', 'supertypes'}:
            method = 'callHierarchy/incomingCalls' if kind == 'callers' else 'typeHierarchy/supertypes'
            result = [{'item': item, 'relationships': client.request(method, {'item': item})} for item in (result or [])[:8]]
        return {'resolution': 'language_server', 'operation': kind, 'result': result, 'applied': False}
    finally:
        proc.kill()
        proc.wait(timeout=5)
        proc.stdin.close()
        proc.stdout.close()


if __name__ == '__main__':
    try:
        print(json.dumps(query(Path.cwd().resolve(), json.loads(sys.argv[1]))))
    except (OSError, ValueError, KeyError) as error:
        print(json.dumps({'resolution': 'unavailable', 'error': str(error)[:300], 'next_action': 'Inspect lexical coverage or run the compiler'}))
        sys.exit(1)
