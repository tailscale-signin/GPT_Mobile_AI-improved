"""Structural extraction without model calls, downloads, or execution of repository code."""
import ast
import hashlib
import importlib
import json
import re
from pathlib import PurePosixPath

from . import EXTRACTOR_VERSION

LANGUAGES = {'.py': 'python', '.kt': 'kotlin', '.kts': 'kotlin', '.java': 'java',
             '.js': 'javascript', '.jsx': 'javascript', '.ts': 'typescript', '.tsx': 'tsx'}
SUPPORTED = frozenset(LANGUAGES) | {'.json', '.md', '.mdx'}
MAX_FILE_BYTES = 256 * 1024
MAX_NODES = 4000


def identity(*parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()[:32]


def permitted(path):
    parts = PurePosixPath(path).parts
    return (bool(parts) and not path.startswith('/') and '\\' not in path and
            not any(part in {'.', '..', '.git', 'node_modules', 'build', 'dist', '.gradle', '__pycache__'} for part in parts) and
            not any(part.startswith('.env') or part.lower() in {'credentials.json', 'secrets.json', 'mcp_config.json'} for part in parts) and
            PurePosixPath(path).suffix.lower() in SUPPORTED)


def extract(path, text):
    """Return a cacheable file fragment. Unresolved names are retained, never guessed."""
    if not permitted(path) or len(text.encode()) > MAX_FILE_BYTES or '\0' in text:
        raise ValueError('Source is outside the graph inspection boundary')
    lines = text.splitlines()
    result = {'version': EXTRACTOR_VERSION, 'nodes': [], 'edges': [], 'references': [], 'imports': [],
              'fields': [], 'warnings': [], 'status': 'indexed', 'source_bytes': len(text.encode())}

    def node(name, kind, line, end, parent=None, signature=''):
        qualified = (parent['qualified'] + '.' if parent and parent['kind'] != 'file' else '') + name
        key = identity(path, qualified, kind, signature, line)
        value = {'id': key, 'name': name, 'qualified': qualified, 'kind': kind, 'path': path,
                 'line': line, 'end_line': end, 'signature': signature[:400],
                 'excerpt': '\n'.join(lines[max(0, line - 1):min(end, line + 5)])[:1600]}
        if len(result['nodes']) >= MAX_NODES:
            raise ValueError('File symbol limit exceeded')
        result['nodes'].append(value)
        if parent:
            result['edges'].append({'source': parent['id'], 'target': key, 'relation': 'contains',
                                    'confidence': 'EXTRACTED', 'line': line, 'path': path})
        return value

    root = node(path, 'file', 1, max(1, len(lines)))

    def reference(owner, target, relation, line, receiver=None):
        if target and len(result['references']) < 16000:
            result['references'].append({'source': owner['id'], 'name': target, 'relation': relation,
                                         'line': line, 'path': path, 'receiver': receiver})

    def field(owner, name, direction, line):
        if isinstance(name, str) and re.fullmatch(r'[A-Za-z_][A-Za-z_0-9.-]{0,79}', name) and len(result['fields']) < 2000:
            result['fields'].append({'name': name, 'direction': direction, 'node': owner['id'], 'path': path, 'line': line})

    extension = PurePosixPath(path).suffix.lower()
    try:
        if extension == '.py':
            tree = ast.parse(text)
            result['parser'] = 'python.ast'

            def walk(current, owner):
                if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    parent = owner
                    owner = node(current.name, 'class' if isinstance(current, ast.ClassDef) else 'function',
                                 current.lineno, current.end_lineno, parent, lines[current.lineno - 1].strip())
                    if isinstance(current, ast.ClassDef):
                        for base in current.bases:
                            reference(owner, ast.unparse(base), 'inherits', current.lineno)
                if isinstance(current, (ast.Import, ast.ImportFrom)):
                    for alias in current.names:
                        module = ((current.module or '') + '.' + alias.name).strip('.') if isinstance(current, ast.ImportFrom) else alias.name
                        result['imports'].append({'name': module, 'alias': alias.asname or alias.name.split('.')[0],
                                                  'line': current.lineno, 'source': root['id'], 'path': path})
                if isinstance(current, ast.Call):
                    called = current.func
                    if isinstance(called, ast.Name):
                        reference(owner, called.id, 'calls', current.lineno)
                    elif isinstance(called, ast.Attribute):
                        receiver = ast.unparse(called.value)
                        reference(owner, called.attr, 'calls', current.lineno, receiver)
                        if called.attr in {'get', 'pop', 'setdefault'} and current.args and isinstance(current.args[0], ast.Constant):
                            field(owner, current.args[0].value, 'reads', current.lineno)
                if isinstance(current, ast.Dict):
                    for key in current.keys:
                        if isinstance(key, ast.Constant):
                            field(owner, key.value, 'writes', key.lineno)
                if isinstance(current, ast.Subscript) and isinstance(current.slice, ast.Constant):
                    field(owner, current.slice.value, 'writes' if isinstance(current.ctx, ast.Store) else 'reads', current.lineno)
                for child in ast.iter_child_nodes(current):
                    walk(child, owner)
            walk(tree, root)
        elif extension in LANGUAGES:
            language_name = LANGUAGES[extension]
            from tree_sitter import Language, Parser
            module_name = 'typescript' if language_name == 'tsx' else language_name
            grammar = importlib.import_module('tree_sitter_' + module_name)
            factory = getattr(grammar, 'language_' + language_name) if module_name == 'typescript' else grammar.language
            tree = Parser(Language(factory())).parse(text.encode())
            result['parser'] = 'tree-sitter.' + language_name
            if tree.root_node.has_error:
                result['status'] = 'partial'
                result['warnings'].append('Syntax errors or unsupported grammar constructs; inspect source')
            declarations = {'function_declaration': 'function', 'method_declaration': 'function', 'method_definition': 'function',
                            'class_declaration': 'class', 'interface_declaration': 'interface', 'object_declaration': 'class',
                            'enum_declaration': 'class', 'constructor_declaration': 'function'}

            def value(item):
                return item.text.decode('utf-8') if item else ''

            def walk(current, owner):
                kind = current.type
                line = current.start_point[0] + 1
                if kind in declarations:
                    name = current.child_by_field_name('name')
                    if name:
                        owner = node(value(name), declarations[kind], line, current.end_point[0] + 1,
                                     owner, lines[line - 1].strip())
                        for child in current.named_children:
                            if child.type in {'superclass', 'super_interfaces', 'delegation_specifiers', 'extends_clause', 'class_heritage'}:
                                for match in re.finditer(r'\b[A-Z][\w.]*', value(child)):
                                    reference(owner, match.group(), 'inherits', line)
                if kind in {'import', 'import_header', 'import_declaration', 'import_statement'}:
                    content = value(current)
                    for match in re.finditer(r'(?:import\s+(?:static\s+)?|from\s+)["\']?([\w./]+)', content):
                        result['imports'].append({'name': match.group(1), 'source': root['id'], 'path': path, 'line': line})
                if kind in {'call_expression', 'method_invocation', 'object_creation_expression'}:
                    fn = current.child_by_field_name('function') or current.child_by_field_name('name')
                    if fn is None and current.named_children:
                        fn = current.named_children[0]
                    call = value(fn).replace('?.', '.')
                    receiver = value(current.child_by_field_name('object')) or (call.rsplit('.', 1)[0] if '.' in call else None)
                    target = call.rsplit('.', 1)[-1]
                    if re.fullmatch(r'[A-Za-z_]\w*', target):
                        reference(owner, target, 'calls', line, receiver)
                    if target in {'put', 'get', 'optString', 'getString'}:
                        match = re.search(r'\(\s*"([\w.-]+)"', value(current))
                        if match:
                            field(owner, match.group(1), 'writes' if target == 'put' else 'reads', line)
                if kind == 'index_expression':
                    match = re.search(r'\[\s*"([\w.-]+)"\s*\]', value(current))
                    if match:
                        field(owner, match.group(1), 'reads', line)
                for child in current.named_children:
                    walk(child, owner)
            walk(tree.root_node, root)
        elif extension == '.json':
            document = json.loads(text)
            result['parser'] = 'json'

            def schema(value, owner, prefix='', depth=0):
                if depth > 30:
                    result['warnings'].append('JSON nesting limit reached')
                    result['status'] = 'partial'
                    return
                if isinstance(value, dict):
                    properties = value.get('properties')
                    if isinstance(properties, dict):
                        for key, spec in properties.items():
                            child = node(prefix + key, 'schema_field', 1, 1, owner, str(spec.get('type', 'unknown')) if isinstance(spec, dict) else '')
                            field(child, key, 'declares', 1)
                    for key, item in value.items():
                        schema(item, owner, prefix + key + '.', depth + 1)
                elif isinstance(value, list):
                    for item in value[:1000]:
                        schema(item, owner, prefix, depth + 1)
            schema(document, root)
        else:
            result['parser'] = 'markdown.headings'
            for number, line in enumerate(lines, 1):
                if re.match(r'^#{1,6}\s+\S', line):
                    node(line.lstrip('# ').strip(), 'documentation', number, min(number + 5, len(lines)), root)
            result['warnings'].append('Documents indexed structurally; semantic extraction is not enabled')
    except ImportError:
        result['status'] = 'unsupported'
        result['parser'] = 'unavailable'
        result['warnings'].append('Install the bundled graph parser dependencies to index this language')
    except (SyntaxError, ValueError, RecursionError) as failure:
        result['status'] = 'failed'
        result['warnings'].append(type(failure).__name__ + ': source could not be fully parsed')
    return result
