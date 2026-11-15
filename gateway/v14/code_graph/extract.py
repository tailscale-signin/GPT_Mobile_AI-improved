"""Structural extraction without model calls, downloads, or execution of repository code."""
import ast
import hashlib
import importlib
import json
import re
import tomllib
import xml.parsers.expat
from pathlib import PurePosixPath

from . import EXTRACTOR_VERSION

LANGUAGES = {'.py': 'python', '.kt': 'kotlin', '.kts': 'kotlin', '.java': 'java',
             '.js': 'javascript', '.mjs': 'javascript', '.jsx': 'javascript', '.ts': 'typescript', '.tsx': 'tsx'}
SUPPORTED = frozenset(LANGUAGES) | {'.json', '.md', '.mdx', '.xml', '.yaml', '.yml', '.toml', '.c', '.cc', '.cpp', '.cxx', '.h', '.hpp'}
MAX_FILE_BYTES = 256 * 1024
MAX_SOURCE_BYTES = 2 * 1024 * 1024
MAX_NODES = 4000


def identity(*parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()[:32]


def permitted(path):
    parts = PurePosixPath(path).parts
    return (bool(parts) and not path.startswith('/') and '\\' not in path and
            not any(part in {'.', '..', '.git', 'node_modules', 'build', 'dist', '.gradle', '__pycache__'} for part in parts) and
            not any(part.lower().startswith('.env') or
                    PurePosixPath(part).stem.lower() in {'credentials', 'secrets', 'mcp_config'} and
                    PurePosixPath(part).suffix.lower() in {'.json', '.xml', '.yaml', '.yml', '.toml'} for part in parts) and
            PurePosixPath(path).suffix.lower() in SUPPORTED)


def metadata_fragment(path, warning, size=0):
    fragment = extract(path, '')
    fragment.update(status='unsupported', parser='file.metadata', warnings=[warning], source_bytes=0,
                    declared_bytes=size, retryable=False)
    return fragment


def compact_fragment(fragment):
    """The manifest owns each row's path; retain locations without repeating it."""
    for collection in ('nodes', 'edges', 'references', 'imports', 'fields'):
        for row in fragment.get(collection, []):
            row.pop('path', None)
            if collection == 'nodes':
                row['excerpt'] = row.get('excerpt', '')[:320]
            elif collection == 'references':
                receiver = row.get('receiver')
                if receiver and (len(receiver) > 200 or not re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*){0,8}', receiver)):
                    row['receiver'] = '<dynamic>'
                if len(row.get('name', '')) > 256:
                    row['name'] = '<dynamic>'
    return fragment


def extract(path, text):
    """Return a cacheable file fragment. Unresolved names are retained, never guessed."""
    if not permitted(path) or len(text.encode()) > MAX_SOURCE_BYTES or '\0' in text:
        raise ValueError('Source is outside the graph inspection boundary')
    lines = text.splitlines()
    result = {'version': EXTRACTOR_VERSION, 'nodes': [], 'edges': [], 'references': [], 'imports': [],
              'fields': [], 'warnings': [], 'status': 'indexed', 'source_bytes': len(text.encode()),
              'language': LANGUAGES.get(PurePosixPath(path).suffix.lower(), 'metadata'), 'package': '', 'retryable': False}
    large_source = result['source_bytes'] > MAX_FILE_BYTES
    if large_source:
        result['warnings'].append('Large source indexed structurally with bounded excerpts; fetch cited ranges for implementation')

    def node(name, kind, line, end, parent=None, signature=''):
        qualified = (parent['qualified'] + '.' if parent and parent['kind'] != 'file' else '') + name
        key = identity(path, qualified, kind, signature, line)
        value = {'id': key, 'name': name, 'qualified': qualified, 'kind': kind, 'path': path,
                 'line': line, 'end_line': end, 'signature': signature[:400],
                 'excerpt': '\n'.join(lines[max(0, line - 1):min(end, line + 5)])[:180 if large_source else 1600]}
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
                        module = '.' * current.level + '.'.join(filter(None, (current.module, alias.name))) if isinstance(current, ast.ImportFrom) else alias.name
                        result['imports'].append({'name': module, 'alias': alias.asname or alias.name.split('.')[0],
                                                  'module': '.' * current.level + (current.module or '') if isinstance(current, ast.ImportFrom) else alias.name,
                                                  'symbol': alias.name if isinstance(current, ast.ImportFrom) else None,
                                                  'namespace': isinstance(current, ast.Import),
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
                if kind in {'package_header', 'package_declaration'}:
                    match = re.search(r'\bpackage\s+([\w.]+)', value(current))
                    if match:
                        result['package'] = match.group(1)
                if kind == 'variable_declarator' and current.child_by_field_name('value') is not None and current.child_by_field_name('value').type in {'arrow_function', 'function_expression', 'generator_function'}:
                    name = current.child_by_field_name('name')
                    if name and name.type == 'identifier':
                        owner = node(value(name), 'function', line, current.end_point[0] + 1, owner, lines[line - 1].strip())
                if kind == 'property_declaration' and any(child.type == 'lambda_literal' for child in current.named_children):
                    declared = next((child for child in current.named_children if child.type == 'variable_declaration'), None)
                    name = next((child for child in declared.named_children if child.type == 'identifier'), None) if declared else None
                    if name:
                        owner = node(value(name), 'function', line, current.end_point[0] + 1, owner, lines[line - 1].strip())
                if kind in declarations:
                    name = current.child_by_field_name('name')
                    if name:
                        owner = node(value(name), declarations[kind], line, current.end_point[0] + 1,
                                     owner, lines[line - 1].strip())
                        if current.parent and current.parent.type == 'export_statement' and re.match(r'export\s+default\b', value(current.parent)):
                            owner['default_export'] = True
                        for child in current.named_children:
                            if child.type in {'superclass', 'super_interfaces', 'delegation_specifiers', 'extends_clause', 'class_heritage'}:
                                for match in re.finditer(r'\b[A-Z][\w.]*', value(child)):
                                    reference(owner, match.group(), 'inherits', line)
                if kind in {'import', 'import_header', 'import_declaration', 'import_statement'}:
                    content = value(current)
                    if language_name in {'javascript', 'typescript', 'tsx'}:
                        imported_source = current.child_by_field_name('source')
                        module = value(imported_source).strip('\"\'')
                        if module:
                            clause = next((c for c in current.named_children if c.type == 'import_clause'), None)
                            records = []
                            if clause:
                                for item in clause.named_children:
                                    if item.type == 'identifier':
                                        records.append({'alias': value(item), 'symbol': 'default'})
                                    elif item.type == 'namespace_import':
                                        records.append({'alias': value(item.named_children[-1]), 'namespace': True})
                                    elif item.type == 'named_imports':
                                        for spec in item.named_children:
                                            symbol = value(spec.child_by_field_name('name'))
                                            records.append({'alias': value(spec.child_by_field_name('alias')) or symbol, 'symbol': symbol})
                            for record in records or [{}]:
                                result['imports'].append(dict(record, name=module, module=module, source=root['id'], path=path, line=line))
                    else:
                        match = re.search(r'import\s+(?:static\s+)?([\w.]+(?:\*)?)(?:\s+as\s+(\w+))?', content)
                        if match:
                            imported = match.group(1)
                            result['imports'].append({'name': imported, 'alias': match.group(2) or imported.rsplit('.', 1)[-1],
                                                      'symbol': imported.rsplit('.', 1)[-1], 'wildcard': imported.endswith('.*'),
                                                      'source': root['id'], 'path': path, 'line': line})
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
        elif extension == '.xml':
            result['parser'] = 'xml.expat.structure'
            parser = xml.parsers.expat.ParserCreate()
            def deny(*args):
                raise ValueError('XML document type and entities are outside the graph inspection boundary')
            parser.StartDoctypeDeclHandler = deny
            parser.EntityDeclHandler = deny
            parser.ExternalEntityRefHandler = deny
            def element(tag, attrs):
                name = attrs.get('android:name') or attrs.get('name') or attrs.get('android:id')
                if name:
                    line = parser.CurrentLineNumber
                    child = node(name, 'resource', line, line, root, tag)
                    for key in attrs:
                        field(child, key, 'declares', line)
                    if tag in {'activity', 'service', 'receiver', 'provider', 'application'}:
                        reference(child, name, 'configures', line)
            parser.StartElementHandler = element
            parser.Parse(text, True)
        elif extension in {'.yaml', '.yml', '.toml', '.c', '.cc', '.cpp', '.cxx', '.h', '.hpp'}:
            result['status'] = 'partial'
            result['parser'] = 'bounded.config.structure' if extension in {'.yaml', '.yml', '.toml'} else 'bounded.native.structure'
            result['warnings'].append('Structural metadata only; types, macros, configuration expansion and runtime wiring require source review')
            if extension == '.toml':
                tomllib.loads(text)
            for number, line in enumerate(lines, 1):
                if extension in {'.yaml', '.yml', '.toml'}:
                    match = re.match(r'\s*([A-Za-z_][\w.-]*)\s*[:=]', line)
                    if match:
                        child = node(match.group(1), 'configuration', number, number, root, line.strip())
                        field(child, match.group(1), 'declares', number)
                else:
                    included = re.match(r'\s*#\s*include\s*[<"]([^>"]+)[>"]', line)
                    if included:
                        result['imports'].append({'name': included.group(1), 'source': root['id'], 'path': path, 'line': number})
                    match = re.match(r'\s*(?:[\w:*&<>]+\s+)+([A-Za-z_]\w*)\s*\([^;]*\)\s*(?:\{|;)', line)
                    if match:
                        node(match.group(1), 'function', number, number, root, line.strip())
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
        result['retryable'] = True
        result['parser'] = 'unavailable'
        result['warnings'].append('Install the bundled graph parser dependencies to index this language')
    except (SyntaxError, ValueError, RecursionError, xml.parsers.expat.ExpatError) as failure:
        result['status'] = 'partial' if len(result['nodes']) > 1 else 'failed'
        result['warnings'].append(type(failure).__name__ + ': source could not be fully parsed')
    return result
