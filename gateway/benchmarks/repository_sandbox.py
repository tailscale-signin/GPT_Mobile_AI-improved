"""Compile/execute native fixtures with expected answers outside the container.

The trusted container driver transports observations only. The host compares
them with its frozen oracle; neither candidate output nor a test-report flag can
declare a pass. Immutable strings are the Kotlin input boundary. No caller-object
immutability, Android device, Room processor or hosted Actions claim is inferred.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import threading
import time
import uuid

from benchmarks import coding, sandbox
from benchmarks.contracts import CODING_WEIGHTS, digest
from v14.engineering_validation import run_bounded

START = 'GATEWAY_REPOSITORY_NATIVE_STARTED_V1'
IMAGE_PATTERN = r'[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}'
DRIVER = r"""import base64, json, os, pathlib, re, shutil, sqlite3, subprocess, sys
print('GATEWAY_REPOSITORY_NATIVE_STARTED_V1', flush=True)
root = pathlib.Path('/candidate')
spec = json.loads(pathlib.Path('/evaluator/inputs.json').read_text())
kind, cases = spec['language'], spec['vectors']

def sql_observe(source, case):
    connection = sqlite3.connect(':memory:')
    try:
        connection.execute('PRAGMA foreign_keys=ON')
        connection.executescript(case['setup'])
        error = False
        try:
            connection.executescript(source)
        except sqlite3.Error:
            error = True
            if connection.in_transaction:
                connection.rollback()
        tables = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        schemas, rows = {}, {}
        for table in tables:
            quoted = '"' + table.replace('"', '""') + '"'
            schemas[table] = {'columns': [list(row[1:]) for row in connection.execute('PRAGMA table_info(' + quoted + ')')],
                              'foreignKeys': [list(row[2:]) for row in connection.execute('PRAGMA foreign_key_list(' + quoted + ')')]}
            rows[table] = [list(row) for row in connection.execute('SELECT * FROM ' + quoted + ' ORDER BY 1')]
        checks = []
        for statement in case.get('probes', []):
            try:
                cursor = connection.execute(statement)
                checks.append({'ok': True, 'rows': [list(row) for row in cursor.fetchall()]})
            except sqlite3.Error:
                checks.append({'ok': False})
        return {'error': error, 'schema': schemas, 'rows': rows, 'checks': checks,
                'version': connection.execute('PRAGMA user_version').fetchone()[0]}
    finally:
        connection.close()

TOKEN = re.compile(r"\s*(?:('(?:[^']|'')*')|([A-Za-z_][A-Za-z0-9_-]*(?:\.(?:[A-Za-z0-9_-]+|\*))*)|(==|!=|&&|\|\||[!(),])|(-?\d+(?:\.\d+)?))")
def truthy(value):
    return not (value is None or value is False or type(value) in (int, float) and value == 0 or isinstance(value, str) and value == '')

def number(value):
    if value is None or value is False or value == '': return 0
    if value is True: return 1
    if type(value) in (int, float): return value
    if isinstance(value, str):
        try:
            converted = json.loads(value)
            if type(converted) in (int, float): return converted
        except ValueError: pass
    return float('nan')

def equal(left, right):
    if isinstance(left, str) and isinstance(right, str): return left.casefold() == right.casefold()
    if isinstance(left, (dict, list)) and type(left) is type(right): return left is right
    if type(left) is not type(right): return number(left) == number(right)
    return left == right

def expression(text, context):
    if type(text) is bool:
        return text, False
    if not isinstance(text, str) or len(text) > 8192:
        raise ValueError('Unsupported expression')
    text = text.strip()
    if text.startswith('${{') and text.endswith('}}'):
        text = text[3:-2].strip()
    tokens, position = [], 0
    while position < len(text):
        matched = TOKEN.match(text, position)
        if not matched:
            raise ValueError('Unsupported expression token')
        tokens.append(next(value for value in matched.groups() if value is not None))
        position = matched.end()
    index, used_status = 0, False
    def path(name):
        current = context
        for part in name.split('.'):
            if part == '*':
                current = list(current.values()) if isinstance(current, dict) else []
            elif isinstance(current, list):
                current = [item.get(part, '') for item in current if isinstance(item, dict)]
            else:
                current = current.get(part, '') if isinstance(current, dict) else ''
        return current
    def take(value):
        nonlocal index
        if index < len(tokens) and tokens[index] == value:
            index += 1
            return True
        return False
    def atom():
        nonlocal index, used_status
        if take('!'):
            inner = atom()
            return lambda: not truthy(inner())
        if take('('):
            value = disjunction()
            if not take(')'): raise ValueError('Missing expression parenthesis')
            return value
        if index >= len(tokens): raise ValueError('Missing expression value')
        token = tokens[index]; index += 1
        if token.startswith("'"): return lambda: token[1:-1].replace("''", "'")
        if token in {'true', 'false', 'null'}: return lambda: {'true': True, 'false': False, 'null': None}[token]
        if re.fullmatch(r'-?\d+(?:\.\d+)?', token): return lambda: float(token) if '.' in token else int(token)
        if not take('('): return lambda: path(token)
        values = []
        if not take(')'):
            values.append(disjunction())
            while take(','): values.append(disjunction())
            if not take(')'): raise ValueError('Missing function parenthesis')
        if token in {'always', 'success', 'failure', 'cancelled'} and not values:
            used_status = True
        if token not in {'always', 'success', 'failure', 'cancelled', 'contains', 'toJSON', 'fromJSON'}:
            raise ValueError('Unsupported expression function')
        def call():
            arguments = [value() for value in values]
            states = [row['result'] for row in context.get('needs', {}).values()]
            if token in {'always', 'success', 'failure', 'cancelled'} and not arguments:
                return {'always': True, 'success': all(value == 'success' for value in states),
                        'failure': 'failure' in states, 'cancelled': 'cancelled' in states}[token]
            if token == 'contains' and len(arguments) == 2:
                search, item = arguments
                if isinstance(search, list): return any(equal(value, item) for value in search)
                if isinstance(search, str) and isinstance(item, str): return item.casefold() in search.casefold()
                raise ValueError('Unsupported contains arguments')
            if token == 'toJSON' and len(arguments) == 1: return json.dumps(arguments[0], separators=(',', ':'))
            if token == 'fromJSON' and len(arguments) == 1: return json.loads(arguments[0])
            raise ValueError('Invalid expression argument count')
        return call
    def equality():
        value = atom()
        while index < len(tokens) and tokens[index] in {'==', '!='}:
            operation = tokens[index]
            take(operation)
            left, right = value, atom()
            value = (lambda left=left, right=right: equal(left(), right())) if operation == '==' else (lambda left=left, right=right: not equal(left(), right()))
        return value
    def conjunction():
        value = equality()
        while take('&&'):
            left, right = value, equality()
            value = lambda left=left, right=right: right() if truthy(left()) else left()
        return value
    def disjunction():
        value = conjunction()
        while take('||'):
            left, right = value, conjunction()
            value = lambda left=left, right=right: left() if truthy(left()) else right()
        return value
    result = disjunction()
    if index != len(tokens): raise ValueError('Trailing expression')
    return result(), used_status

def expanded(value, context):
    if not isinstance(value, (str, int, bool)):
        raise ValueError('Unsupported environment value')
    def replace(matched):
        item, _ = expression(matched.group(0), context)
        if isinstance(item, (dict, list)): raise ValueError('Use toJSON for structured context')
        return str(item).lower() if isinstance(item, bool) else '' if item is None else str(item)
    return re.sub(r'\$\{\{.*?\}\}', replace, str(value), flags=re.DOTALL)

def permissions(value):
    if value is None: return {}
    if not isinstance(value, dict) or any(not isinstance(key, str) or item not in {'none', 'read', 'write'} for key, item in value.items()):
        raise ValueError('Use explicit permission mappings')
    return {key: item for key, item in value.items() if item != 'none'}

def action_observe(workflow, case):
    if set(workflow) - {'name', 'on', 'permissions', 'jobs', 'env'}:
        raise ValueError('Unsupported workflow feature')
    jobs = workflow.get('jobs')
    target_names = spec['jobs']
    if not isinstance(jobs, dict) or set(jobs) - set(target_names) - set(case['needs']) or any(name not in jobs for name in target_names):
        raise ValueError('Unknown or missing fixture job')
    outputs = {}
    for name in target_names:
        job = jobs[name]
        if not isinstance(job, dict) or set(job) - {'name', 'runs-on', 'needs', 'if', 'steps', 'permissions', 'env'}:
            raise ValueError('Unsupported job feature')
        needs = job.get('needs', [])
        if isinstance(needs, str): needs = [needs]
        if not isinstance(needs, list) or any(item not in case['needs'] for item in needs): raise ValueError('Unknown dependency')
        context = {'github': case['github'], 'needs': {key: {'result': case['needs'][key]} for key in needs}}
        ready, status_override = expression(job.get('if', 'success()'), context)
        if not status_override: ready = truthy(ready) and all(value['result'] == 'success' for value in context['needs'].values())
        state = 'skipped'
        if truthy(ready):
            state = 'success'
            steps = job.get('steps')
            if not isinstance(steps, list) or not 1 <= len(steps) <= 12: raise ValueError('Bounded run steps required')
            for step in steps:
                if not isinstance(step, dict) or set(step) - {'name', 'run', 'env', 'if', 'shell'} or step.get('shell', 'sh') != 'sh' or not isinstance(step.get('run'), str) or len(step['run']) > 16384:
                    raise ValueError('Only bounded run steps are supported')
                if 'if' in step:
                    run_step, _ = expression(step['if'], context)
                    if not truthy(run_step): continue
                environment = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': '/tmp', 'LANG': 'C.UTF-8'}
                for declarations in (workflow.get('env', {}), job.get('env', {}), step.get('env', {})):
                    if not isinstance(declarations, dict): raise ValueError('Invalid environment')
                    for key, value in declarations.items():
                        if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key): raise ValueError('Invalid environment name')
                        environment[key] = expanded(value, context)
                script = expanded(step['run'], context)
                process = subprocess.run(['/bin/sh', '-eu', '-c', script], env=environment, cwd='/tmp', timeout=3,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if process.returncode != 0:
                    state = 'failure'; break
        outputs[name] = {'result': state, 'permissions': permissions(job.get('permissions', workflow.get('permissions')))}
    return outputs

def run():
    if kind == 'kotlin':
        compiler, java = shutil.which('kotlinc'), shutil.which('java')
        if not compiler or not java: return {'runtimeError': 'KOTLIN_RUNTIME_UNAVAILABLE'}
        version = subprocess.run([compiler, '-version'], capture_output=True, timeout=10)
        if version.returncode: return {'runtimeError': 'KOTLIN_COMPILER_UNAVAILABLE'}
        main = pathlib.Path('/tmp/VerifierMain.kt')
        main.write_text('''import java.util.Base64
fun main() {
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        val input = String(Base64.getDecoder().decode(line), Charsets.UTF_8)
        val value = candidate.solve(input)
        println(Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))
    }
}
''')
        compiled = subprocess.run([compiler, str(root / 'Task.kt'), str(main), '-include-runtime', '-d', '/tmp/task.jar'],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45)
        runtime = {'kind': kind, 'compiler': (version.stdout + version.stderr).decode('utf-8', 'replace')[:1024], 'compiled': compiled.returncode == 0}
        if compiled.returncode: return {'runtime': runtime, 'candidateError': 'COMPILATION_FAILED', 'outputs': []}
        data = '\n'.join(base64.b64encode(row['events'].encode()).decode() for row in cases) + '\n'
        with open('/tmp/candidate-output', 'wb') as output:
            process = subprocess.run([java, '-Xmx192m', '-cp', '/tmp/task.jar', 'VerifierMainKt'], input=data.encode(),
                                     stdout=output, stderr=subprocess.DEVNULL, timeout=12)
        target = pathlib.Path('/tmp/candidate-output')
        if process.returncode or target.stat().st_size > 262144:
            return {'runtime': runtime, 'candidateError': 'EXECUTION_FAILED', 'outputs': []}
        try:
            lines = target.read_bytes().splitlines()
            if len(lines) != len(cases): raise ValueError('Unexpected output count')
            outputs = [base64.b64decode(line, validate=True).decode('utf-8') for line in lines]
        except (ValueError, UnicodeError):
            return {'runtime': runtime, 'candidateError': 'INVALID_OUTPUT_FRAMING', 'outputs': []}
        return {'runtime': runtime, 'outputs': outputs}
    if kind == 'sqlite':
        source = (root / 'migration.sql').read_text()
        return {'runtime': {'kind': kind, 'sqliteVersion': sqlite3.sqlite_version}, 'outputs': [sql_observe(source, case) for case in cases]}
    if kind == 'actions':
        try: import yaml
        except ImportError: return {'runtimeError': 'YAML_RUNTIME_UNAVAILABLE'}
        class Loader(yaml.SafeLoader): pass
        Loader.yaml_implicit_resolvers = {key: [(tag, pattern) for tag, pattern in rows if tag != 'tag:yaml.org,2002:bool'] for key, rows in Loader.yaml_implicit_resolvers.items()}
        Loader.add_implicit_resolver('tag:yaml.org,2002:bool', re.compile(r'^(?:true|false)$', re.I), list('tTfF'))
        def mapping(loader, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = loader.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result: raise ValueError('Duplicate or non-string YAML key')
                result[key] = loader.construct_object(value_node, deep=deep)
            return result
        Loader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
        try:
            workflow = yaml.load((root / 'workflow.yml').read_text(), Loader=Loader)
            if not isinstance(workflow, dict): raise ValueError('Expected workflow mapping')
            outputs = [action_observe(workflow, case) for case in cases]
        except Exception:
            return {'runtime': {'kind': kind, 'yamlVersion': yaml.__version__}, 'candidateError': 'WORKFLOW_CONTRACT_INVALID', 'outputs': []}
        return {'runtime': {'kind': kind, 'yamlVersion': yaml.__version__, 'execution': 'YAML_EXPRESSION_AND_SHELL_SIMULATION'}, 'outputs': outputs}
    return {'runtimeError': 'LANGUAGE_UNAVAILABLE'}

try:
    result = run()
except subprocess.TimeoutExpired:
    result = {'candidateError': 'NATIVE_STAGE_DEADLINE', 'outputs': []}
except Exception:
    result = {'runtimeError': 'NATIVE_RUNTIME_OBSERVATION_FAILED'}
encoded = json.dumps(result, ensure_ascii=True, allow_nan=False)
if len(encoded.encode()) > 262144:
    encoded = json.dumps({'candidateError': 'NATIVE_OUTPUT_LIMIT', 'outputs': []})
print(encoded, flush=True)
"""


def input_bundle(task):
    return {'language': task['language'], 'vectors': task['vectors'],
            'jobs': ['gate', 'publish'] if task['id'].endswith('fork-permissions') else ['gate']}


def parse_proposal(task, proposal):
    return coding.proposal(proposal, task)[1]


def _image(settings):
    image = settings.get('repository_image', '')
    if not isinstance(image, str) or re.fullmatch(IMAGE_PATTERN, image) is None:
        raise sandbox.SandboxUnavailable('Configure a locally installed repository_image pinned by sha256 with Kotlin/JVM, SQLite and PyYAML.', 'REPOSITORY_IMAGE_NOT_CONFIGURED')
    return image


def _arguments(image, name, candidate, evaluator):
    return ['run', '--rm', '--pull=never', '--name', name, '--network=none', '--cap-drop=ALL',
            '--security-opt=no-new-privileges', '--read-only', '--pids-limit=96', '--memory=1g', '--cpus=2',
            '--user=65534:65534', '--tmpfs=/tmp:rw,nosuid,nodev,size=256m',
            '--mount=type=bind,source=' + str(candidate) + ',target=/candidate,readonly',
            '--mount=type=bind,source=' + str(evaluator) + ',target=/evaluator,readonly',
            '--workdir=/tmp', '--env=HOME=/tmp', '--env=JAVA_OPTS=-Xmx512m',
            '--entrypoint=python3', image, '-I', '-B', '/evaluator/driver.py']


def execute(task, sources, settings, cancel=None, *, retain=None, release=None):
    image = _image(settings)
    docker = sandbox.preflight(dict(settings, sandbox_image=image))
    if cancel and cancel.is_set(): raise InterruptedError('Repository benchmark stopped')
    name = 'gateway-benchmark-' + uuid.uuid4().hex
    cleanup = {'kind': 'sandbox', 'outcome': 'unknown', 'container': name}
    with tempfile.TemporaryDirectory(prefix='gateway-native-') as directory:
        root = Path(directory); candidate = root / 'candidate'; evaluator = root / 'evaluator'
        candidate.mkdir(); evaluator.mkdir()
        for path, source in sources.items():
            if path not in task['sources'] or Path(path).name != path or len(source.encode()) > 65536:
                raise ValueError('Invalid native candidate source')
            (candidate / path).write_text(source, encoding='utf-8')
        (evaluator / 'driver.py').write_text(DRIVER, encoding='utf-8')
        (evaluator / 'inputs.json').write_text(json.dumps(input_bundle(task), ensure_ascii=True, allow_nan=False), encoding='utf-8')
        for path in (root, candidate, evaluator): path.chmod(0o755)
        for path in (*candidate.iterdir(), *evaluator.iterdir()): path.chmod(0o444)
        if retain: retain(name)
        finished = threading.Event(); removal = threading.Lock()
        def remove():
            with removal:
                if cleanup['outcome'] != 'confirmed': cleanup.update(sandbox.remove_container(name, docker=docker))
        def watch():
            while not finished.wait(.1):
                if cancel and cancel.is_set(): remove()
        watcher = threading.Thread(target=watch, name='repository-sandbox-cancel', daemon=True)
        watcher.start()
        output_path = root / 'output'; successful = False; start = time.perf_counter()
        observation = {'executed': False, 'outputs': [], 'error': 'NATIVE_OBSERVATION_UNAVAILABLE'}
        process_bound = None
        try:
            with sandbox.docker_command(docker, *_arguments(image, name, candidate, evaluator)) as argv, output_path.open('wb') as output:
                try:
                    measured = run_bounded(argv, root, min(180, max(60, settings.get('sandbox_seconds', 60))), 524288, destination=output)
                except TimeoutError:
                    process_bound = 'NATIVE_EXECUTION_DEADLINE'
                    raise
                except ValueError:
                    process_bound = 'NATIVE_OUTPUT_LIMIT'
                    raise
            successful = measured['exit_code'] == 0
            raw = output_path.read_bytes(); marker = (START + '\n').encode()
            started = raw.startswith(marker)
            observation = {'executed': started, 'outputs': [], 'exitCode': measured['exit_code'],
                           'durationMs': measured['elapsed_ms'], 'outputDigest': measured['output_sha256']}
            if started and successful:
                payload = json.loads(raw[len(marker):])
                if not isinstance(payload, dict) or set(payload) - {'runtime', 'runtimeError', 'candidateError', 'outputs'}:
                    raise ValueError('Invalid runtime receipt')
                observation.update(payload)
            elif started:
                observation['candidateError'] = 'NATIVE_PROCESS_FAILED'
            else:
                observation['runtimeError'] = 'NATIVE_EXECUTION_NOT_CONFIRMED'
        except (OSError, ValueError, TimeoutError):
            raw = output_path.read_bytes() if output_path.exists() else b''
            observation.update(executed=raw.startswith((START + '\n').encode()), durationMs=round((time.perf_counter() - start) * 1000),
                               error='NATIVE_OBSERVATION_UNAVAILABLE', outputDigest=hashlib.sha256(raw).hexdigest())
            # A started process that hits a measured resource bound is an
            # observed candidate failure once the independent controls pass.
            # Startup/transport failures and malformed runtime receipts remain
            # unqualified; their ValueErrors cannot masquerade as output limits.
            if observation['executed'] and process_bound is not None:
                observation.pop('error', None)
                observation['candidateError'] = process_bound
        finally:
            finished.set(); remove(); watcher.join(timeout=1)
        if successful and cleanup['outcome'] != 'confirmed': cleanup['outcome'] = 'terminal'
        elif cleanup['outcome'] != 'confirmed': cleanup.update(sandbox.confirm_container_absent(name, docker=docker))
        observation['cleanup'] = dict(cleanup)
        if cleanup['outcome'] in {'confirmed', 'terminal'} and release: release(name)
        if (cancel and cancel.is_set()) or cleanup['outcome'] == 'unknown':
            raise sandbox.SandboxInterrupted(cleanup, observation)
        return observation


def _matched(observation, expected):
    return (observation.get('executed') is True and not any(observation.get(key) for key in ('error', 'runtimeError', 'candidateError'))
            and isinstance(observation.get('runtime'), dict) and coding.same(observation.get('outputs'), expected))


def grade(task, proposal, settings, cancel, *, retain=None, release=None):
    result = dict(id=task['id'], problemId=task['problemId'], family=task['family'], title=task['title'], capstone=False,
                  taskDigest=task['taskDigest'], fixtureDigest=task['manifestDigest'], graderDigest=task['graderDigest'],
                  executionScope=task['executionScope'], taskPack='repository', status='BLOCKED', executed=False,
                  graded=False, qualified=False, resolved=False, outcomeKnown=False, failureDomain='HARNESS',
                  integrity='VERIFIED', score=None, dimensions={key: None for key in CODING_WEIGHTS})
    try:
        candidate = parse_proposal(task, proposal)
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        result.update(status='FAILED', graded=True, outcomeKnown=True, failureDomain='MODEL', error='NATIVE_PATCH_CONTRACT_INVALID')
        return result
    result['patchDigest'] = digest(candidate)
    try:
        reference = execute(task, task['referenceSources'], settings, cancel, retain=retain, release=release)
        bad = execute(task, task['sources'], settings, cancel, retain=retain, release=release)
    except sandbox.SandboxUnavailable as error:
        result.update(error=error.reason, recovery=str(error), failureDomain='SETUP')
        return result
    positive = _matched(reference, task['expected'])
    negative = (bad.get('executed') is True and isinstance(bad.get('runtime'), dict)
                and not any(bad.get(key) for key in ('runtimeError', 'error', 'candidateError'))
                and not _matched(bad, task['expected']))
    calibration = dict(status='PASSED' if positive and negative else 'FAILED',
                       controls=[{'id': 'native-reference', 'passed': positive}, {'id': 'known-broken-native', 'passed': negative}],
                       digest=digest([task['graderDigest'], reference.get('outputDigest'), bad.get('outputDigest')]))
    result['calibration'] = calibration
    if not (positive and negative):
        result.update(error='NATIVE_CALIBRATION_FAILED', runtime=reference.get('runtime'),
                      recovery='Verify the pinned compiler/runtime image and trusted native control cases before scoring models.')
        return result
    observed = execute(task, candidate, settings, cancel, retain=retain, release=release)
    result.update(executed=observed.get('executed') is True, runtime=observed.get('runtime'), runtimeObservationDigest=observed.get('outputDigest'),
                  validationDurationMs=sum(item.get('durationMs', 0) for item in (reference, bad, observed)),
                  inputBoundary='Immutable UTF-8 String values' if task['language'] == 'kotlin' else 'Fresh process/database scenario inputs',
                  inputObjectImmutability='NOT_CLAIMED', _candidate_sources=candidate)
    if observed.get('runtimeError') or observed.get('error') or not result['executed']:
        result.update(error=observed.get('runtimeError') or observed.get('error') or 'NATIVE_EXECUTION_NOT_CONFIRMED')
        return result
    actual = observed.get('outputs', [])
    checks = [index < len(actual) and coding.same(actual[index], expected) for index, expected in enumerate(task['expected'])] if isinstance(actual, list) else [False] * len(task['expected'])
    passed = _matched(observed, task['expected'])
    result.update(status='PASSED' if passed else 'FAILED', graded=True, qualified=True, resolved=passed,
                  outcomeKnown=True, failureDomain=None if passed else 'MODEL',
                  error=observed.get('candidateError') if not passed else None,
                  checks={'hiddenBehavior': {'passed': sum(checks), 'total': len(checks)},
                          'runtimeExecuted': True, 'calibrated': True},
                  secondaryRubricStatus='UNMEASURED_CRITERIA')
    result['dimensions']['functional'] = sum(checks) / len(checks)
    result['dimensions']['regression'] = 1.0 if passed else 0.0
    return result


def remote_bundle(task, candidate, image, run_id):
    """Return a self-hosted CI bundle; no hidden answer is mounted into Docker."""
    if not re.fullmatch(IMAGE_PATTERN, image) or not isinstance(run_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', run_id):
        raise ValueError('Freeze a pinned native image and bounded run identity.')
    if set(candidate) != set(task['sources']): raise ValueError('Keep the fixed native source paths.')
    base = 'benchmark-native/' + run_id
    files = {base + '/candidate/' + name: content for name, content in candidate.items()}
    for group, sources in (('reference', task['referenceSources']), ('broken', task['sources'])):
        files.update({base + '/' + group + '/' + name: content for name, content in sources.items()})
    files[base + '/evaluator/driver.py'] = DRIVER
    files[base + '/evaluator/inputs.json'] = json.dumps(input_bundle(task), ensure_ascii=True, allow_nan=False)
    # This file remains on the trusted CI host. Only evaluator/ and the selected
    # source directory are mounted, never the containing repository.
    files[base + '/expected.json'] = json.dumps(task['expected'], ensure_ascii=True, allow_nan=False)
    files[base + '/verify.py'] = _remote_verifier(image, task)
    workflow_path = '.github/workflows/benchmark-native-' + run_id + '.yml'
    files[workflow_path] = ('name: Independent coding verification\non:\n  push:\n    branches: ["benchmark/**"]\n    paths:\n'
        f'      - "{base}/**"\n      - "{workflow_path}"\n  workflow_dispatch:\n'
        'permissions:\n  contents: read\nconcurrency:\n  group: benchmark-native-' + run_id + '\n  cancel-in-progress: false\njobs:\n  native:\n'
        '    name: Independent coding verification\n    runs-on: [self-hosted, linux, gateway-native-benchmark]\n    timeout-minutes: 15\n    steps:\n'
        '      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\n'
        '        with:\n          persist-credentials: false\n          fetch-depth: 1\n'
        '      - name: Verify immutable artifact\n        run: python3 ' + base + '/verify.py\n')
    return files, workflow_path


def _remote_verifier(image, task):
    config = repr({'image': image, 'scope': task['executionScope'], 'taskDigest': task['taskDigest'], 'graderDigest': task['graderDigest']})
    return '''import json, pathlib, subprocess, sys, uuid
CONFIG = ''' + config + '''
root = pathlib.Path(__file__).resolve().parent
expected = json.loads((root / 'expected.json').read_text())
def observe(folder):
    name = 'gateway-benchmark-' + uuid.uuid4().hex
    argv = ['docker', '--context', 'default', 'run', '--rm', '--pull=never', '--name', name,
        '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--read-only',
        '--pids-limit=96', '--memory=1g', '--cpus=2', '--user=65534:65534', '--tmpfs=/tmp:rw,nosuid,nodev,size=256m',
        '--mount=type=bind,source=' + str(root / folder) + ',target=/candidate,readonly',
        '--mount=type=bind,source=' + str(root / 'evaluator') + ',target=/evaluator,readonly',
        '--workdir=/tmp', '--env=HOME=/tmp', '--env=JAVA_OPTS=-Xmx512m', '--entrypoint=python3', CONFIG['image'], '-I', '-B', '/evaluator/driver.py']
    try:
        process = subprocess.run(argv, capture_output=True, timeout=180)
        marker = b'GATEWAY_REPOSITORY_NATIVE_STARTED_V1\\n'
        if process.returncode or len(process.stdout) > 524288 or not process.stdout.startswith(marker):
            raise RuntimeError('Native process did not return bounded execution evidence')
        return json.loads(process.stdout[len(marker):])
    finally:
        subprocess.run(['docker', '--context', 'default', 'rm', '-f', name], capture_output=True, timeout=15)
def correct(value):
    return isinstance(value.get('runtime'), dict) and not any(value.get(key) for key in ('runtimeError', 'candidateError')) and value.get('outputs') == expected
reference, broken = observe('reference'), observe('broken')
if not correct(reference) or not isinstance(broken.get('runtime'), dict) or any(broken.get(key) for key in ('runtimeError', 'candidateError')) or correct(broken):
    raise SystemExit('Native verifier calibration failed; result unqualified')
candidate = observe('candidate')
passed = correct(candidate)
print(json.dumps({'qualified': True, 'resolved': passed, 'taskDigest': CONFIG['taskDigest'], 'graderDigest': CONFIG['graderDigest'], 'executionScope': CONFIG['scope']}))
raise SystemExit(0 if passed else 1)
'''
