"""Integration against real javac/kotlinc output; set JVM_GRAPH_ASM to Gradle's asm-N.jar."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_jvm_call_index import create, write_index


class IndexWriterTest(unittest.TestCase):
    def test_size_boundary_publishes_only_complete_indexes(self):
        data = {'schema': 1, 'methods': [['example/Api.call()V', 0, 1, 'a' * 64]]}
        wire = json.dumps(data, separators=(',', ':')).encode()
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'nested' / 'jvm-call-index.json'
            with patch('build_jvm_call_index.JSON_LIMIT', len(wire)):
                self.assertEqual(len(wire), write_index(data, target))
                self.assertEqual(wire, target.read_bytes())
                with self.assertRaisesRegex(ValueError, 'Gateway limit'):
                    write_index(dict(data, extra='one byte over is enough'), target)
            self.assertEqual(wire, target.read_bytes())
            self.assertEqual([target], list(target.parent.iterdir()))


class RealCompilerTest(unittest.TestCase):
    def test_mixed_language_overload_call_chain_and_body_only_edit(self):
        asm = os.getenv('JVM_GRAPH_ASM')
        kotlin_cp = os.getenv('JVM_GRAPH_KOTLIN_CLASSPATH')
        if asm and not kotlin_cp and list(Path(asm).parent.glob('kotlin-compiler-embeddable-*.jar')):
            kotlin_cp = str(Path(asm).parent)
        kotlin = shutil.which('kotlinc')
        if not asm or not (kotlin or kotlin_cp):
            self.skipTest('Set JVM_GRAPH_ASM and provide kotlinc or JVM_GRAPH_KOTLIN_CLASSPATH for the real compiler fixture')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def run(*args):
                subprocess.run(args, cwd=root, check=True, stdout=subprocess.PIPE)
            def commit():
                run('git', 'add', 'Api.java', 'Client.kt', 'ClientTest.kt')
                run('git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'source')
            run('git', 'init', '-q')
            java_file = root / 'Api.java'
            java_file.write_text('package example;\npublic class Api {\n public static int send(int value) {return value + 1;}\n public static int send(String value) {return 2;}\n}\n')
            (root / 'Client.kt').write_text('package example\nfun relay() = Api.send(3)\n')
            (root / 'ClientTest.kt').write_text('package example\nfun checkRelay() = check(relay() == 4)\n')
            commit()
            classes = root / 'classes'
            classes.mkdir()
            javac = ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-g', '-d', str(classes), str(java_file)]
            run(*javac)
            if kotlin_cp:
                jars = os.pathsep.join(str(p) for p in Path(kotlin_cp).glob('*.jar'))
                stdlib = next(Path(kotlin_cp).glob('kotlin-stdlib-[0-9]*.jar'))
                run('java', '-cp', jars, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', os.pathsep.join((str(classes), str(stdlib))), '-d', str(classes), 'Client.kt', 'ClientTest.kt')
            else:
                run(kotlin, '-classpath', str(classes), '-d', str(classes), 'Client.kt', 'ClientTest.kt')
            before = create(root, [classes], Path(asm))
            methods = {method[0]: i for i, method in enumerate(before['methods'])}
            integer, string = 'example/Api.send(I)I', 'example/Api.send(Ljava/lang/String;)I'
            relay, test = 'example/ClientKt.relay()I', 'example/ClientTestKt.checkRelay()V'
            edges = {tuple(call) for call in before['calls']}
            self.assertIn((methods[relay], methods[integer], 2), edges)
            self.assertNotIn((methods[relay], methods[string], 2), edges)
            self.assertIn((methods[test], methods[relay], 2), edges)
            java_file.write_text(java_file.read_text().replace('value + 1', 'value + 2').replace('public class', '\npublic class'))
            with self.assertRaisesRegex(ValueError, 'differs'):
                create(root, [classes], Path(asm))
            commit()
            run(*javac)
            after = create(root, [classes], Path(asm), requested=before['commit_sha'])
            self.assertNotEqual(before['commit_sha'], after['commit_sha'])
            changed = [new[0] for old, new in zip(before['methods'], after['methods']) if old[3] != new[3]]
            self.assertEqual([integer], changed)
