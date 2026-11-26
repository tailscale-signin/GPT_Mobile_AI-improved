#!/usr/bin/env python3
"""Validate reviewed feature references and bind them to current Git blob bytes."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'docs/architecture/feature-map.source.json'
TARGET = 'docs/architecture/feature-map.json'
FIELDS = ('terms', 'paths', 'tests', 'commands', 'documents', 'invariants')
PATH_FIELDS = ('paths', 'tests', 'documents')
SOURCE_DIR = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data'
PLUGIN_SOURCE = SOURCE_DIR + '/model/AppFeatureSettings.kt'
ROUTE_TARGET = SOURCE_DIR + '/agent/ReviewedFeatureRoutes.kt'
INVENTORY_TARGET = SOURCE_DIR + '/agent/ReviewedAppFeatures.kt'
TOOL_AUDIT = 'docs/audits/tool-statistics-coverage.json'
TOOL_TARGET = SOURCE_DIR + '/agent/ReviewedToolSources.kt'
BUILTIN_SOURCE = SOURCE_DIR + '/database/entity/AgentToolBinding.kt'
ADDITIONAL_TOOL_SOURCES = tuple(SOURCE_DIR + '/' + name for name in (
    'agent/ProgressiveToolCatalog.kt', 'agent/ResponseRecoveryContext.kt',
    'assist/AssistTurnPlanningTool.kt', 'research/feature/ResearchEvidenceTool.kt',
))
FIXTURE_TOOL_SOURCES = tuple(SOURCE_DIR + '/' + name for name in (
    'assist/AssistTestFixture.kt', 'benchmark/BenchmarkToolScenario.kt',
    'benchmark/DelegationBenchmarkRunner.kt', 'benchmark/GitHubProfileAuditSuite.kt',
))
TOOL_DEFINITION = re.compile(r'override\s+val\s+definition(?:\s*:\s*AgentToolDefinition)?\s*=\s*AgentToolDefinition\(')
MAX_MANIFEST_BYTES = 512 * 1024
ROUTE_TEXT = ('proof', 'admission', 'decline')
ROUTE_LISTS = ('plugins', 'tools', 'decline_terms')


def validate_routing(root, row):
    route = row['routing']
    if not isinstance(route, dict) or set(route) != {'role', 'entry', 'positive_fixture', 'negative_fixture', *ROUTE_TEXT, *ROUTE_LISTS}:
        raise ValueError('Each feature needs a complete routing contract')
    if route['role'] not in {'evidence', 'executor', 'presentation', 'user_control'} or route['entry'] not in row['paths']:
        raise ValueError('Route needs a valid role and production entry point')
    for field in ROUTE_TEXT:
        value = route[field]
        if not isinstance(value, str) or not value.strip() or len(value) > 200 or not value.isprintable():
            raise ValueError('Invalid routing ' + field)
    for field in ROUTE_LISTS:
        values = route[field]
        pattern = r'[a-z][a-z0-9_:]{0,79}' if field != 'decline_terms' else r'[a-z][a-z0-9 ]{0,79}'
        if (not isinstance(values, list) or len(values) > 32 or
                any(not isinstance(v, str) or not re.fullmatch(pattern, v) for v in values) or len(values) != len(set(values))):
            raise ValueError('Invalid routing ' + field)
    if route['tools'] and (not route['plugins'] or route['role'] not in {'executor', 'evidence'}):
        raise ValueError('Automatic tool hints require a declared plugin and executable or evidence-producing role')
    for field in ('positive_fixture', 'negative_fixture'):
        value = route[field]
        if not isinstance(value, str) or '#' not in value:
            raise ValueError('Routing fixture must identify a test and case')
        name, case = value.rsplit('#', 1)
        if (name not in row['tests'] or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_ ]{0,199}', case) or
                not re.search(r'(?:fun|def|void)\s+`?' + re.escape(case) + r'`?\s*\(', source_path(root, name).read_text(encoding='utf-8'))):
            raise ValueError('Missing routing fixture: ' + value)
    if route['positive_fixture'] == route['negative_fixture']:
        raise ValueError('Routing requires distinct positive and negative fixtures')


def verify_plugin_coverage(root, rows):
    plugin_source = source_path(root, PLUGIN_SOURCE).read_text(encoding='utf-8')
    block = re.search(r'\bobject\s+ToolPluginId\s*\{(.*?)^\}', plugin_source, re.MULTILINE | re.DOTALL)
    if not block:
        raise ValueError('Built-in plugin declarations are unavailable')
    declarations = re.findall(r'^\s*const\s+val\s+\w+(?:\s*:\s*String)?\s*=\s*([^\n]+)', block.group(1), re.MULTILINE)
    literals = [re.fullmatch(r'"([a-z][a-z0-9_:]*)"(?:\s*//.*)?', value.strip()) for value in declarations]
    if any(match is None for match in literals):
        raise ValueError('Built-in plugin declaration needs a reviewed identity')
    declared = {match.group(1) for match in literals}
    classified = {plugin for row in rows for plugin in row['routing']['plugins']}
    if not declared or declared != classified:
        raise ValueError('Feature/plugin coverage differs: ' + ', '.join(sorted(declared ^ classified)))


def kotlin_literal(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def kotlin_strings(values):
    return 'listOf(' + ', '.join(kotlin_literal(v) for v in values) + ')' if values else 'emptyList()'


def compiled_tool_sources(root):
    """Publish fixed identities only. Wrappers and account-dependent aliases remain source records."""
    manifest = json.loads(source_path(root, TOOL_AUDIT).read_text(encoding='utf-8'))
    audited = sorted(row['source'] for row in manifest['producers'])
    if not audited or len(set(audited)) != len(audited):
        raise ValueError('Tool producer identities must be non-empty and unique')
    # A new production tool outside the statistics scanner's directories needs an explicit
    # inventory classification. Benchmark/fixture callbacks must never appear as product tools.
    classified = set(audited).union(ADDITIONAL_TOOL_SOURCES, FIXTURE_TOOL_SOURCES)
    unclassified = sorted(
        str(path.relative_to(root)) for path in (root / SOURCE_DIR).rglob('*.kt')
        if str(path.relative_to(root)) not in classified and not path.is_symlink()
        and TOOL_DEFINITION.search(path.read_text(encoding='utf-8'))
    )
    if unclassified:
        raise ValueError('Classify the new app tool definition producers: ' + ', '.join(unclassified))

    def constants(source):
        return dict(re.findall(r'^\s*const val (\w+) = "([a-z][a-z0-9_]{0,159})"', source, re.MULTILINE))
    builtins = constants(source_path(root, BUILTIN_SOURCE).read_text(encoding='utf-8'))
    products = {}
    for name in sorted(set(audited).union(ADDITIONAL_TOOL_SOURCES)):
        text = source_path(root, name).read_text(encoding='utf-8')
        declared = constants(text)
        # A model-visible definition's fixed first argument is a literal, builtin symbol or local constant.
        # Computed names need their owner's runtime registry, and discovered aliases need an admitted schema.
        definitions = re.finditer(r'override\s+val\s+definition(?:\s*:\s*AgentToolDefinition)?\s*=\s*'
                                  r'AgentToolDefinition\(\s*(?:name\s*=\s*)?'
                                  r'("[a-z][a-z0-9_]{0,159}"|BuiltInAgentTool\.\w+|[A-Z_]+)\s*,', text)
        for definition in definitions:
            token = definition.group(1)
            if token.startswith('"'):
                tool = token[1:-1]
            elif token.startswith('BuiltInAgentTool.'):
                tool = builtins[token.split('.')[-1]]
            else:
                tool = declared[token]
            products.setdefault(tool, []).append(name)
    lines = ['package dev.chungjungsoo.gptmobile.data.agent', '',
             '// Generated by scripts/generate_repository_reference.py from audited tool producers.',
             'internal val auditedToolProducerSources = listOf(',
             ',\n'.join('    ' + kotlin_literal(name) for name in audited), ')', '',
             'internal val fixedToolProducerSources = mapOf(',
             ',\n'.join('    ' + kotlin_literal(tool) + ' to ' + kotlin_strings(sorted(set(names)))
                         for tool, names in sorted(products.items())), ')', '']
    return '\n'.join(lines)


def compiled_inventory(rows):
    groups = {'evidence': 'Evidence', 'executor': 'Workflows', 'presentation': 'Presentation', 'user_control': 'Controls'}
    declarations = []
    for row in rows:
        route = row['routing']
        fields = (kotlin_literal(row['id']), kotlin_literal(row['title']), kotlin_literal(groups[route['role']]),
                  kotlin_literal(route['admission']), kotlin_strings(row['terms']), kotlin_strings(route['tools']),
                  kotlin_strings(route['plugins']))
        declarations.append('    AppFeature(' + ', '.join(fields) + ')')
    return ('package dev.chungjungsoo.gptmobile.data.agent\n\n'
            '// Generated by scripts/generate_repository_reference.py from the maintained feature map.\n'
            'internal val reviewedAppFeatures = listOf(\n' + ',\n'.join(declarations) + '\n)\n')


def compiled_tool_routes(rows):
    lines = ['package dev.chungjungsoo.gptmobile.data.agent', '',
             '// Generated by scripts/generate_repository_reference.py from reviewed app routing contracts.',
             'internal val reviewedFeatureRoutes = listOf(']
    for row in rows:
        route = row['routing']
        if route['tools']:
            parts = (kotlin_literal(row['id']), kotlin_strings(row['terms']), kotlin_strings(route['decline_terms']),
                     kotlin_strings(route['tools']), kotlin_literal(route['proof']))
            lines.append('    ReviewedFeatureRoute(' + ', '.join(parts) + '),')
    if lines[-1].endswith(','):
        lines[-1] = lines[-1][:-1]
    return '\n'.join([*lines, ')', ''])



def source_path(root, name):
    if (not isinstance(name, str) or not name or len(name) > 512 or '\\' in name or
            any(ord(c) < 32 for c in name)):
        raise ValueError('Reference path must be bounded plain repository text')
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {'', '.', '..', '.git'} for part in name.split('/')):
        raise ValueError('Reference path must stay inside the selected repository')
    result = root / name
    if any(part.is_symlink() for part in (result, *result.parents)) or not result.is_file():
        raise ValueError('Reference must name an existing regular file: ' + name)
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError('Reference escaped repository')
    return result


def blob_sha(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def is_git_root(root):
    result = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                            capture_output=True, text=True)
    return result.returncode == 0 and Path(result.stdout.strip()).resolve() == root.resolve()


def git_source_bytes(root, name, raw):
    """Apply only Git's observed text normalization, never a repository clean command."""
    attributes = subprocess.run(['git', '-C', str(root), 'check-attr', '-z',
                                 'filter', 'working-tree-encoding', '--', name],
                                capture_output=True, check=True).stdout.split(b'\0')
    if any(value not in {b'unspecified', b'unset'} for value in attributes[2::3]):
        raise ValueError('Reference source cannot use external filters or encoding transforms: ' + name)
    expected = subprocess.run(['git', '-C', str(root), 'hash-object', '--path=' + name, '--stdin'],
                              input=raw, capture_output=True, check=True).stdout.decode('ascii').strip()
    for candidate in (raw, raw.replace(b'\r\n', b'\n')):
        if blob_sha(candidate) == expected:
            return candidate
    raise ValueError('Reference source has an unsupported Git text transform: ' + name)


def document(root=ROOT):
    source = json.loads((root / SOURCE).read_text(encoding='utf-8'))
    if not isinstance(source, dict):
        raise ValueError('Reference root must be an object')
    rows = source.get('features')
    if source.get('schema_version') != 1 or not isinstance(rows, list) or not 1 <= len(rows) <= 96:
        raise ValueError('Reference requires schema 1 and 1–96 reviewed features')
    ids, files = set(), {}
    git_root = is_git_root(root)
    for row in rows:
        if not isinstance(row, dict) or set(row) != {'id', 'title', 'routing', *FIELDS}:
            raise ValueError('Feature fields differ from the reference contract')
        identity = row['id']
        if not isinstance(identity, str) or not re.fullmatch('[a-z][a-z0-9-]{1,63}', identity) or identity in ids:
            raise ValueError('Feature identities must be unique and stable')
        ids.add(identity)
        if not isinstance(row['title'], str) or not 1 <= len(row['title']) <= 160:
            raise ValueError('Feature title must be bounded')
        for field in FIELDS:
            values = row[field]
            if (not isinstance(values, list) or len(values) > 32 or
                    any(not isinstance(value, str) or not value.strip() or len(value) > 1000 or
                        any(ord(c) < 32 for c in value) for value in values) or len(set(values)) != len(values)):
                raise ValueError('Feature ' + field + ' must contain bounded unique strings')
        if not row['terms'] or not row['paths'] or not row['tests']:
            raise ValueError('Each reviewed feature needs terms, production paths and test candidates')
        validate_routing(root, row)
        for field in PATH_FIELDS:
            for name in row[field]:
                if name in files:
                    continue
                path = source_path(root, name)
                raw = path.read_bytes()
                if len(raw) > 2 * 1024 * 1024:
                    raise ValueError('Referenced file exceeds source limit: ' + name)
                raw.decode('utf-8')
                if git_root:
                    raw = git_source_bytes(root, name, raw)
                files[name] = {'blob_sha': blob_sha(raw), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    verify_plugin_coverage(root, rows)
    return {'schema_version': 1, 'generated_by': 'python3 scripts/generate_repository_reference.py',
            'source_binding': 'Resolve this manifest at the selected commit and verify referenced blob identities.',
            'coverage': 'Reviewed feature navigation and test candidates; not exhaustive semantic or runtime proof.',
            'commands_are_authority': False, 'features': rows, 'file_blobs': dict(sorted(files.items()))}


def synchronize(root=ROOT, check=False):
    manifest = document(root)
    serialized = json.dumps(manifest, indent=2, ensure_ascii=False) + '\n'
    if len(serialized.encode('utf-8')) > MAX_MANIFEST_BYTES:
        raise ValueError('Feature map exceeds the bounded runtime reader')
    changed = False
    for name, expected in ((TARGET, serialized), (ROUTE_TARGET, compiled_tool_routes(manifest['features'])),
                           (INVENTORY_TARGET, compiled_inventory(manifest['features'])),
                           (TOOL_TARGET, compiled_tool_sources(root))):
        path = root / name
        if not path.exists() or path.read_text(encoding='utf-8') != expected:
            changed = True
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(expected, encoding='utf-8')
    return changed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if synchronize(check=args.check) and args.check:
        raise SystemExit('Repository reference is stale: run python3 scripts/generate_repository_reference.py')
