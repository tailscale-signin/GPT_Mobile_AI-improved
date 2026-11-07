#!/usr/bin/env python3
"""Index checked-in runtime source and UI resources; never claim executed lines."""
import argparse
import hashlib
import json
import os
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = Path('app/src/main/assets/diagnostics/app-code-inventory.json')
KOTLIN = Path('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditSourceInventory.kt')
PRODUCTION_ROOTS = (
    'app/src/main', 'local-gguf-runtime/src/main',
    'core-domain/src/commonMain', 'core-domain/src/jvmMain',
    'gateway', 'authentication', 'mcp',
)
EXTENSIONS = {'.kt', '.java', '.c', '.cpp', '.h', '.hpp', '.py', '.js', '.mjs', '.ts', '.tsx', '.jsx', '.html', '.css', '.xml', '.sql', '.cmake'}
EXCLUDED_PARTS = {'tests', 'test', 'fixtures', 'node_modules', '__pycache__', '.venv', 'venv', 'build', 'dist', '.git'}
MAX_FILES = 10_000
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
AREAS = {
    'android_shell': 'Android application and lifecycle',
    'chat_ui': 'Chat, rendering and interaction',
    'accessibility_and_localization': 'Accessibility, themes and UI resources',
    'agent_execution': 'Agent execution and recovery',
    'agent_tools': 'Built-in tool routing and execution',
    'mcp_plugins': 'MCP connections and tool catalogues',
    'marketplace_plugins': 'Plugin marketplace',
    'model_providers': 'Model providers and adapters',
    'models_and_profiles': 'Model profiles and settings',
    'local_inference': 'Local inference and admission',
    'gateway_client': 'Android gateway client',
    'network': 'Networking and readiness',
    'github': 'GitHub and engineering workspace',
    'research': 'Research and source acquisition',
    'memory_and_rag': 'Memory, retrieval and indexing',
    'persistence': 'Databases, backup and storage',
    'diagnostics_and_export': 'Diagnostics, statistics and sharing',
    'benchmark': 'Benchmarks and app audit',
    'auth_and_security': 'Permissions, privacy and credentials',
    'voice_and_media': 'Voice and media',
    'portable_domain': 'Portable domain contracts',
    'native_bridge': 'Native runtime bridge and policies',
    'gateway_software': 'Companion gateway software',
    'authentication_service': 'Authentication broker software',
    'external_integrations': 'External integration servers',
    'other_production': 'Additional production source',
}


def source_area(path):
    """Association is a coverage inventory, not evidence a file was exercised."""
    value = str(path)
    for prefix, area in (
        ('local-gguf-runtime/', 'native_bridge'), ('core-domain/', 'portable_domain'),
        ('gateway/', 'gateway_software'), ('authentication/', 'authentication_service'),
        ('mcp/', 'external_integrations'), ('app/src/main/res/', 'accessibility_and_localization'),
        ('app/src/main/assets/', 'chat_ui'), ('app/src/main/java/', 'native_bridge'),
    ):
        if value.startswith(prefix):
            return area
    if value == 'app/src/main/AndroidManifest.xml':
        return 'android_shell'
    prefix = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/'
    if not value.startswith(prefix):
        return 'other_production'
    relative = value[len(prefix):]
    name = Path(relative).name
    if relative.startswith('data/agent/tool/'):
        return 'mcp_plugins' if name.startswith(('Mcp', 'ModernMcp')) else 'agent_tools'
    for route, area in (
        ('data/agent/provider/', 'model_providers'), ('data/agent/', 'agent_execution'),
        ('data/network/gateway/', 'gateway_client'), ('data/network/', 'network'),
        ('data/repository/ChatRepository', 'agent_execution'),
    ):
        if relative.startswith(route):
            return area
    groups = {
        'accounting': 'agent_execution', 'delegation': 'agent_execution', 'orchestration': 'agent_execution',
        'mcp': 'mcp_plugins', 'marketplace': 'marketplace_plugins', 'universalmarketplace': 'marketplace_plugins',
        'github': 'github', 'workspace': 'github',
        'audit': 'benchmark', 'benchmark': 'benchmark',
        'research': 'research', 'crawl': 'research',
        'memory': 'memory_and_rag', 'rag': 'memory_and_rag', 'knowledge': 'memory_and_rag',
        'database': 'persistence', 'datastore': 'persistence', 'backup': 'persistence', 'repository': 'persistence',
        'diagnostics': 'diagnostics_and_export', 'telemetry': 'diagnostics_and_export', 'sharing': 'diagnostics_and_export',
        'security': 'auth_and_security', 'permissions': 'auth_and_security', 'privacy': 'auth_and_security',
        'voice': 'voice_and_media', 'localruntime': 'local_inference', 'localmodel': 'local_inference', 'llama': 'local_inference',
        'model': 'models_and_profiles', 'catalog': 'models_and_profiles',
        'free': 'model_providers', 'huggingface': 'model_providers', 'nvidia': 'model_providers',
        'ollama': 'model_providers', 'openrouter': 'model_providers', 'pricing': 'model_providers', 'provider': 'model_providers',
        'readiness': 'network', 'health': 'network',
        'airbnb': 'external_integrations', 'amazon': 'external_integrations', 'assist': 'external_integrations',
        'chat': 'chat_ui', 'context': 'chat_ui', 'conversation': 'chat_ui', 'parser': 'chat_ui', 'label': 'chat_ui',
        'service': 'android_shell', 'worker': 'android_shell', 'queue': 'android_shell', 'pairing': 'android_shell',
        'unified': 'agent_execution', 'home': 'chat_ui',
    }
    if relative.startswith('data/'):
        return groups.get(relative.split('/')[1], 'other_production')
    if 'Benchmark' in name or 'AppAudit' in name:
        return 'benchmark'
    if relative.startswith(('presentation/theme/', 'presentation/icons/', 'presentation/designsystem/')):
        return 'accessibility_and_localization'
    if relative.startswith('presentation/ui/workspace/'):
        return 'github'
    if relative.startswith(('presentation/service/', 'presentation/navigation/', 'di/')):
        return 'android_shell'
    if relative.startswith(('presentation/', 'ui/')):
        return 'chat_ui'
    if relative.startswith('domain/model/'):
        return 'models_and_profiles'
    if relative.startswith(('domain/', 'util/')):
        return 'android_shell'
    return 'other_production'


def is_production_source(relative):
    if relative in (ASSET, KOTLIN):
        return False
    name = relative.name
    if relative.parts[0] in {'gateway', 'authentication', 'mcp'}:
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            return False
        if name.startswith('test_') or name.endswith(('.test.mjs', '.test.js', '.test.ts')):
            return False
    return relative.suffix in EXTENSIONS or name == 'CMakeLists.txt'


def production_paths(root):
    settings = root / 'settings.gradle.kts'
    if settings.exists():
        modules = {name for group in re.findall(r'\binclude\s*\(([^)]*)\)', settings.read_text())
                   for name in re.findall(r'"(:[A-Za-z0-9_:-]+)"', group)}
        if modules - {':app', ':local-gguf-runtime', ':core-domain', ':macrobenchmark'}:
            raise ValueError('A new Gradle module requires explicit production inventory roots')
    paths = set()
    for folder in PRODUCTION_ROOTS:
        for directory, dirs, names in os.walk(root / folder, followlinks=False):
            external = Path(folder).parts[0] in {'gateway', 'authentication', 'mcp'}
            if external:
                dirs[:] = [name for name in dirs if name not in EXCLUDED_PARTS]
            for name in dirs:
                if (Path(directory) / name).is_symlink():
                    raise ValueError('Source inventory refuses linked source directories')
            for name in names:
                path = Path(directory) / name
                if is_production_source(path.relative_to(root)):
                    paths.add(path)
                    if len(paths) > MAX_FILES:
                        raise ValueError('Production source inventory exceeds the explicit file bound')
    return sorted(paths)


def document(root=ROOT):
    root = root.resolve()
    paths = production_paths(root)
    files, total_bytes = [], 0
    for path in paths:
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError('Source inventory refuses symlinks or paths outside the repository')
        size = path.stat().st_size
        total_bytes += size
        if size > MAX_FILE_BYTES or total_bytes > MAX_TOTAL_BYTES:
            raise ValueError('Production source inventory exceeds the explicit byte bound')
        data = path.read_bytes()
        if len(data) != size:
            raise ValueError('Source changed while generating its inventory')
        data.decode('utf-8')
        relative = path.relative_to(root)
        kind = 'ui_resource' if '/res/' in str(relative) else 'source'
        files.append({'path': relative.as_posix(), 'area_id': source_area(relative), 'kind': kind,
                      'sha256': hashlib.sha256(data).hexdigest(),
                      'indexed_lines': data.count(b'\n') + int(bool(data) and not data.endswith(b'\n'))})
    canonical = json.dumps(files, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode()
    counts = Counter(f['area_id'] for f in files)
    lines = Counter()
    for file in files:
        lines[file['area_id']] += file['indexed_lines']
    return {
        'schema_version': 1, 'generated_by': 'python3 scripts/generate_app_audit_inventory.py',
        'source_digest': hashlib.sha256(canonical).hexdigest(), 'file_count': len(files),
        'indexed_line_count': sum(lines.values()), 'line_coverage_available': False,
        'coverage_meaning': 'Source association only. No file, line, branch or UI interaction is marked executed by this inventory.',
        'scope': {'production_roots': list(PRODUCTION_ROOTS),
                  'excluded': ['test and fixture sources', 'generated inventory outputs', 'build outputs and downloaded dependency internals',
                               'binary model/runtime internals and non-text assets', 'developer tooling and CI code outside production roots'],
                  'external_software': ['gateway', 'authentication', 'mcp']},
        'areas': [{'id': area, 'label': label, 'file_count': counts[area], 'indexed_line_count': lines[area]}
                  for area, label in AREAS.items()],
        'files': files,
    }


def kotlin_document(data):
    lines = ['// Generated by scripts/generate_app_audit_inventory.py. Source association is not executed-line coverage.',
             'package dev.chungjungsoo.gptmobile.data.audit', '', 'import kotlinx.serialization.Serializable', '',
             '@Serializable', 'data class AppAuditSourceArea(val id: String, val label: String, val fileCount: Int, val lineCount: Int)', '',
             'object AppAuditSourceInventory {', '    val schemaVersion: Int = 1',
             '    val assetPath: String = "diagnostics/app-code-inventory.json"',
             f'    val sourceDigest: String = "{data["source_digest"]}"',
             f'    val fileCount: Int = {data["file_count"]}', f'    val lineCount: Int = {data["indexed_line_count"]}',
             '    val lineCoverageAvailable: Boolean = false', '    val areas: List<AppAuditSourceArea> = listOf(']
    for index, area in enumerate(data['areas']):
        suffix = ',' if index < len(data['areas']) - 1 else ''
        lines.append(f'        AppAuditSourceArea("{area["id"]}", "{area["label"]}", {area["file_count"]}, {area["indexed_line_count"]}){suffix}')
    return '\n'.join(lines + ['    )', '}', ''])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = document()
    outputs = {ASSET: json.dumps(data, indent=2, ensure_ascii=True) + '\n', KOTLIN: kotlin_document(data)}
    for relative, expected in outputs.items():
        path = ROOT / relative
        if args.check:
            if not path.exists() or path.read_text() != expected:
                raise SystemExit('App audit source inventory is stale: run python3 scripts/generate_app_audit_inventory.py')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected)
    print(f'App audit inventory: {data["file_count"]} files, {len(data["areas"])} areas; executed-line coverage unavailable.')


if __name__ == '__main__':
    main()
