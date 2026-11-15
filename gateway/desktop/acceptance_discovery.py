"""Bounded static test discovery from a pinned mirror; repository code never runs here."""
import ast
from copy import deepcopy
import hashlib
import re

from desktop import repository_validation as setup
from v14.validation_host import command as host_command, environment as host_environment


def discover(options, query='', cancel=None, *, source_sha=None, approved_cases=None):
    mirror = setup.mirror_path(options['mirror'])
    if not isinstance(query, str) or len(query) > 200:
        raise ValueError('Use a test filename or path fragment of at most 200 characters.')

    def git(*args):
        setup.cancelled(cancel)
        with host_command(['git', '--git-dir=' + str(mirror), *args]) as argv:
            return setup.run(argv, timeout=15, cancel=cancel, env=host_environment(mirror), label='Test discovery')

    if source_sha is not None and (not isinstance(source_sha, str) or not re.fullmatch('[a-f0-9]{40}', source_sha)):
        raise ValueError('Enter the exact 40-character source commit SHA.')
    head = git('rev-parse', '--verify', (source_sha or 'HEAD') + '^{commit}')
    if not re.fullmatch('[a-f0-9]{40}', head) or source_sha is not None and head != source_sha:
        raise ValueError('Prepare the repository mirror before discovering tests.')
    paths = []
    for line in git('ls-tree', '-r', '--full-tree', head).splitlines():
        metadata, separator, path = line.partition('\t')
        fields = metadata.split()
        if (separator and len(fields) == 3 and fields[0] in {'100644', '100755'} and fields[1] == 'blob'
                and query.casefold() in path.casefold() and 'test' in path.casefold()
                and path.endswith(('.py', '.kt', '.java'))):
            paths.append((path, fields[2]))
    candidates = []
    for path, sha in paths[:80]:
        setup.cancelled(cancel)
        if not re.fullmatch('[a-f0-9]{40}', sha):
            raise ValueError('Invalid test blob identity')
        if int(git('cat-file', '-s', sha)) > 256 * 1024:
            continue
        text = git('cat-file', 'blob', sha)
        candidates.extend(dict(candidate, source_sha=head, blob_sha=sha) for candidate in parse_tests(path, text)
                          if approved_cases is None or candidate['case_id'] in approved_cases)
        if len(candidates) >= 256:
            break
    return {'head_sha': head, 'candidates': candidates[:256], 'matching_files': len(paths),
            'truncated': len(paths) > 80 or len(candidates) > 256, 'executed': False}


def parse_tests(path, text):
    candidates = []
    if path.endswith('.py'):
        try:
            tree = ast.parse(text)
        except (SyntaxError, ValueError, RecursionError):
            return []
        module = path.rsplit('/', 1)[-1][:-3]
        if not module.isidentifier():
            return []
        for node in tree.body:
            if not isinstance(node, ast.ClassDef) or not any(
                    isinstance(base, ast.Attribute) and base.attr == 'TestCase' or
                    isinstance(base, ast.Name) and base.id == 'TestCase' for base in node.bases):
                continue
            for method in node.body:
                if isinstance(method, ast.FunctionDef) and method.name.startswith('test'):
                    candidates.append({'path': path, 'case_id': module + '.' + node.name + '.' + method.name,
                                       'framework': 'unittest', 'class_name': module + '.' + node.name})
    else:
        package = re.search(r'^\s*package\s+([\w.]+)', text, re.M)
        classes = re.findall(r'^\s*(?:public\s+|internal\s+)?class\s+(\w+)', text, re.M)
        if not package or len(classes) != 1:
            return []
        class_name = package[1] + '.' + classes[0]
        pattern = r'@Test(?:\([^\n]*\))?\s+(?:public\s+)?(?:fun\s+|void\s+)(`[^`]+`|\w+)\s*\('
        for method in re.findall(pattern, text):
            candidates.append({'path': path, 'case_id': class_name + '.' + method.strip('`'),
                               'framework': 'junit', 'class_name': class_name})
    return [row for row in candidates if len(row['case_id']) <= 200]


def preset(candidate):
    """A reviewable command proposal. Only Save policy gives it host approval."""
    if candidate['framework'] == 'unittest':
        report = '.gateway-checks/' + hashlib.sha256(candidate['case_id'].encode()).hexdigest()[:16] + '.xml'
        # A single named case runs inside the existing isolated validator. The
        # wrapper emits fresh JUnit evidence, including missing/skipped/errors.
        script = ('import sys,unittest,xml.etree.ElementTree as E; from pathlib import Path; '
                  'sys.path.insert(0,sys.argv[1]); s=unittest.defaultTestLoader.loadTestsFromName(sys.argv[2]); '
                  'r=unittest.TextTestRunner().run(s); root=E.Element("testsuite"); '
                  'c=E.SubElement(root,"testcase",classname=sys.argv[2].rsplit(".",1)[0],name=sys.argv[2].rsplit(".",1)[1]); '
                  'ok=r.wasSuccessful() and r.testsRun==1 and not r.skipped; '
                  'None if ok else E.SubElement(c,"failure",message="Named behavior case did not pass exactly once"); '
                  'p=Path(sys.argv[3]); p.parent.mkdir(parents=True,exist_ok=True); E.ElementTree(root).write(p); '
                  'sys.exit(0 if ok else 1)')
        folder = candidate['path'].rsplit('/', 1)[0] if '/' in candidate['path'] else '.'
        return ["python3", '-c', script, folder, candidate['case_id'], report], report
    if candidate['framework'] == 'junit' and candidate['path'].startswith('app/src/test/'):
        return ['./gradlew', ':app:testDebugUnitTest', '--tests', candidate['case_id']], \
            'app/build/test-results/testDebugUnitTest/TEST-' + candidate['class_name'] + '.xml'
    raise ValueError('This test needs an existing approved command and report path; use Add task behavior check.')


def add_discovered(options, candidate, *, objective, requirement_id, source_path, ci_check):
    from desktop.acceptance_builder import add_behavior_check
    command, report = preset(candidate)
    result = deepcopy(options)
    if command not in result['commands']:
        if len(result['commands']) >= 10:
            raise ValueError('The policy already has ten approved commands. Edit or reuse an existing command.')
        result['commands'].append(command)
    return add_behavior_check(result, objective=objective, requirement_id=requirement_id, source_path=source_path,
                              ci_check=ci_check, case_id=candidate['case_id'], report_path=report,
                              command_index=result['commands'].index(command))
