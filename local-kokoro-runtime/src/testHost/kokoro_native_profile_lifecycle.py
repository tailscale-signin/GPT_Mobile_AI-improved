#!/usr/bin/env python3
"""Run the actual native Model/JNI/Sherpa profile lifecycle on a QNN host emulator.

Requires the verified Kokoro model, local CMake dependency archives, JDK21,
ORT1.31.0 host headers/library and explicitly versioned QNN host libraries.
The historical default is QNN2.7.0/QAIRT2.51; use --qnn-version 2.8.0 for
the audited QNN2.8/QAIRT2.52 package. Host and application versions must match.
Nothing is downloaded.
The copied host build selects QNN's x86 CPU-emulator device instead of Android
HTP and adds its documented architecture settings. One test-only barrier throws
after actual ORT execution, before Sherpa ends profiling. A separate barrier
pauses after actual BASIC source preparation and returns normally after release,
so production cancellation must prevent compilation and uncached fallback.
Model/cache/JNI behavior, the real graph, real outputs and profile parsing remain
production code; every explicit test-only port is retained in the receipt.
This is neither a physical device nor Android packaging qualification.
"""

import argparse
import ast
import base64
import csv
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
QAIRT_VERSIONS = {"2.7.0": "2.51.0", "2.8.0": "2.52.0"}
HOST_MANIFESTS = {"2.8.0": ROOT / "docs/audits/qnn-host-probe-publisher-2.8.json"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise AssertionError("Host boundary port no longer matches the production source")
    return text.replace(before, after, 1)


def checked_provider_inputs(qnn, version, application_java):
    """Bind the actual package bytes/version before compiling or labeling evidence."""
    if version not in QAIRT_VERSIONS:
        raise ValueError("Unsupported QNN host package version")
    information = qnn / "build_and_package_info.py"
    libraries = {}
    publisher = qnn / "publisher-manifest.json"
    if publisher.is_file():
        audited = HOST_MANIFESTS.get(version)
        if audited is None or digest(publisher) != digest(audited):
            raise ValueError("Host publisher manifest differs from the audited package version")
        entries = json.loads(publisher.read_text())["files"]
        if any(Path(name).name != name or name in ("", ".", "..") for name in entries):
            raise ValueError("Host publisher entries must be local basenames")
        for name, expected in entries.items():
            path = qnn / name
            if path.stat().st_size != expected["bytes"] or digest(path) != expected["sha256"]:
                raise ValueError("Host publisher file hash or size differs: " + name)
            if name.startswith("lib"):
                libraries[name] = path
        if information.name not in entries:
            raise ValueError("Host package metadata is not covered by the publisher manifest")
        source_identity = {"kind": "AUDITED_PUBLISHER_MANIFEST", "sha256": digest(publisher)}
        runtime_directories = [qnn]
    else:
        distribution = qnn.parent / ("onnxruntime_qnn-" + version + ".dist-info")
        record = distribution / "RECORD"
        if not record.is_file():
            raise ValueError("QNN installed wheel does not match --qnn-version")
        with record.open() as stream:
            rows = list(csv.reader(stream))
        if any(len(row) != 3 for row in rows) or len({row[0] for row in rows}) != len(rows):
            raise ValueError("Invalid or ambiguous QNN wheel RECORD")
        entries = {name: (value, size) for name, value, size in rows}

        def verify(path, relative):
            actual = digest(path)
            encoded = "sha256=" + base64.urlsafe_b64encode(bytes.fromhex(actual)).rstrip(b"=").decode()
            if entries.get(relative) != (encoded, str(path.stat().st_size)):
                raise ValueError("QNN wheel input differs from RECORD: " + relative)

        verify(information, "onnxruntime_qnn/" + information.name)
        metadata = distribution / "METADATA"
        verify(metadata, distribution.name + "/METADATA")
        if re.findall(r"^Version: (.+)$", metadata.read_text(), re.MULTILINE) != [version]:
            raise ValueError("Installed QNN distribution version differs from the requested version")
        for library in sorted(qnn.glob("*.so")):
            if library.name.startswith("libQnn") or library.name in {"libHtpPrepare.so", "libonnxruntime_providers_qnn.so"}:
                verify(library, "onnxruntime_qnn/" + library.name)
                libraries[library.name] = library
        runtime = qnn.parent / "onnxruntime_qnn.libs"
        if not runtime.is_dir():
            raise ValueError("Verified QNN host C++ dependencies are missing")
        for library in sorted(runtime.iterdir()):
            if library.is_file():
                relative = "onnxruntime_qnn.libs/" + library.name
                verify(library, relative)
                libraries[relative] = library
        source_identity = {"kind": "INSTALLED_WHEEL_RECORD", "sha256": digest(record),
                           "metadataSha256": digest(metadata)}
        runtime_directories = [runtime]
    required = {"libonnxruntime_providers_qnn.so", "libQnnHtp.so", "libQnnSystem.so", "libHtpPrepare.so"}
    if not required.issubset(libraries):
        raise ValueError("Required QNN plugin or HTP host libraries are missing")
    assignments = {}
    for node in ast.parse(information.read_text()).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name in {"package_name", "__version__", "qnn_version"}:
                if name in assignments or not isinstance(node.value, ast.Constant) or not isinstance(node.value.value, str):
                    raise ValueError("Ambiguous QNN build metadata")
                assignments[name] = node.value.value
    if assignments != {"package_name": "onnxruntime-qnn", "__version__": version, "qnn_version": QAIRT_VERSIONS[version]}:
        raise ValueError("Actual QNN build metadata differs from the requested paired runtime")
    identifiers = re.findall(r'\bRUNTIME_ID\s*=\s*"([^"\n]+)"', application_java.read_text())
    if len(identifiers) != 1:
        raise ValueError("Expected exactly one application runtime identity")
    match = re.match(r"^ort[^-]+-qnn([0-9.]+)-qairt([0-9.]+)-", identifiers[0])

    def normalized(value):
        parts = value.split(".")
        if not 2 <= len(parts) <= 3 or any(not part.isdigit() for part in parts):
            raise ValueError("Invalid runtime version identity")
        return tuple(map(int, parts + ["0"] * (3 - len(parts))))

    if (not match or normalized(match[1]) != normalized(version) or
            normalized(match[2]) != normalized(QAIRT_VERSIONS[version])):
        raise ValueError("Application RUNTIME_ID and actual QNN/QAIRT host versions differ; use matching verified host libraries or the matching historical source")
    identity = {"applicationRuntimeId": identifiers[0], "hostQnnPluginVersion": version,
                "hostQairtVersion": QAIRT_VERSIONS[version], "packageBuildMetadataSha256": digest(information),
                "inputIdentity": source_identity, "validation": "APPLICATION_AND_HOST_VERSIONS_MATCH",
                "executionScope": "X86_CPU_EMULATOR_ONLY"}
    return identity, libraries, runtime_directories


def asr_memory_admission(fixture, meminfo=None):
    """An explicit host admission estimate, never measured model RSS or device qualification."""
    model_bytes = sum(record["bytes"] for record in fixture["files"] if not record["file"].startswith("test_wavs/"))
    # Embedded ONNX protobufs can coexist with initialized/prepacked weights.
    # Keep this distinct from GPU external weights and mapped HTP contexts.
    required = 3 * model_bytes + 512 * 1024**2
    if meminfo is None:
        try:
            meminfo = Path("/proc/meminfo").read_text()
        except OSError:
            meminfo = ""
    match = re.search(r"^MemAvailable:\s+(\d+)\s+kB$", meminfo, re.MULTILINE)
    available = int(match.group(1)) * 1024 if match else None
    return {"estimatedRequiredBytes": required, "availableHostBytes": available,
            "admitted": available is not None and available >= required,
            "isMeasuredModelRss": False, "physicalDeviceQualified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-source", type=Path, required=True)
    parser.add_argument("--dependency-cache", type=Path, required=True)
    parser.add_argument("--ort", type=Path, required=True)
    parser.add_argument("--qnn", type=Path, required=True)
    parser.add_argument("--qnn-version", choices=sorted(QAIRT_VERSIONS), default="2.7.0")
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--asr-packs", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-jobs", type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    if args.asr_packs is None:
        args.asr_packs = args.pack.parent / "asr-packs"
    for key, value in vars(args).items():
        if isinstance(value, Path):
            setattr(args, key, value.resolve())
    if args.output.exists():
        raise AssertionError("Use a fresh evidence directory to preserve earlier results")
    test_host = Path(__file__).resolve().parent
    test_java = test_host.parent / "test/java/dev/melo/kokoro/KokoroNativeProfileLifecycleIntegration.java"
    context_java = test_host.parent / "test/java/dev/melo/kokoro/KokoroNativeContextIntegration.java"
    prepared_java = context_java.with_name("KokoroPreparedPhraseIntegration.java")
    main_java = test_host.parent / "main/java/dev/melo/kokoro/TimedKokoro.java"
    asr_java = main_java.with_name("StreamingAsr.java")
    asr_test_java = test_java.with_name("StreamingAsrNativeIntegration.java")
    asr_fixture_manifest = test_host / "streaming_asr_fixtures.json"
    asr_fixtures = json.loads(asr_fixture_manifest.read_text())["fixtures"]
    asr_input_pins = {}
    for fixture in asr_fixtures:
        for record in fixture["files"]:
            relative = Path(fixture["id"]) / record["file"]
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unsafe ASR fixture path")
            source = args.asr_packs / relative
            if source.stat().st_size != record["bytes"] or digest(source) != record["sha256"]:
                raise ValueError("Pinned ASR model/audio fixture differs: " + relative.as_posix())
            asr_input_pins[relative.as_posix()] = record["sha256"]
    provider_identity, provider_files, runtime_directories = checked_provider_inputs(args.qnn, args.qnn_version, main_java)
    provider_inputs = {name: digest(path) for name, path in provider_files.items()}
    args.output.mkdir(parents=True)
    model_pins = {
        "model.int8.onnx": "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e",
        "voices.bin": "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
        "tokens.txt": "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
        "lexicon-us-en.txt": "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
    }
    for name, expected in model_pins.items():
        assert digest(args.pack / name) == expected, "Pinned model input differs: " + name
    native = args.output / "native"
    shutil.copytree(args.native_source, native)
    changes = []

    def port(path, changed):
        before = path.read_text()
        after = changed(before)
        changes.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                           fromfile=str(path) + ".production", tofile=str(path) + ".host"))
        path.write_text(after)

    def provider_port(text):
        text = replace_once(text, "#if defined(__ANDROID__) && defined(__aarch64__)", "#if 1  // Explicit x86 HTP emulator test boundary")
        text = replace_once(text, 'const auto requested_type = provider == "qnn-gpu" ? OrtHardwareDeviceType_GPU : OrtHardwareDeviceType_NPU;',
                            'const auto requested_type = OrtHardwareDeviceType_CPU;  // Official host emulator only')
        return replace_once(text, 'if (provider == "qnn-htp") settings.emplace("htp_performance_mode", htp_performance_mode);',
                            'if (provider == "qnn-htp") { settings.emplace("htp_performance_mode", htp_performance_mode);\n'
                            '    settings.emplace("htp_arch", "79"); settings.emplace("soc_model", "69"); }')

    port(native / "kokoro_execution_provider.h", provider_port)
    for name in ["kokoro_profile_fault.h", "kokoro_profile_fault.cpp"]:
        shutil.copy2(test_host / name, native / name)

    def context_port(text):
        text = replace_once(text, '#include "kokoro_execution_provider.h"',
                            '#include "kokoro_execution_provider.h"\n#include "kokoro_profile_fault.h"')
        text = replace_once(text, '  }  // Release the CPU preparation session before allocating QNN graphs.\n',
                            '  }  // Release the CPU preparation session before allocating QNN graphs.\n'
                            '  KokoroHostContextPreparedBoundary();  // Test-only barrier after the real save.\n')
        return replace_once(text, '    Ort::ThrowOnError(compile_api.CompileModel(env, compilation));',
                            '    KokoroHostContextCompileStarted();  // Count actual attempted compiler entries.\n'
                            '    Ort::ThrowOnError(compile_api.CompileModel(env, compilation));')

    port(native / "kokoro_context_export.h", context_port)
    port(native / "timed_kokoro.cpp", lambda text: replace_once(text,
         '        session->model = create(config.kokoro.model, "", "-uncached");',
         '        KokoroHostContextUncachedStarted();  // Test-only optional-cache retry counter.\n'
         '        session->model = create(config.kokoro.model, "", "-uncached");'))
    port(native / "CMakeLists.txt", lambda text: text +
         '\n# Test-only fault barrier, never an Android source.\n'
         'target_sources(timed_kokoro PRIVATE kokoro_profile_fault.cpp)\n'
         '# Keep the host GNU runtime private beside the QNN wheel libc++ runtime.\n'
         '# Android already uses its separate hidden static STL.\n'
         'target_link_options(timed_kokoro PRIVATE -static-libstdc++ -static-libgcc)\n')
    build = args.output / "build"
    env = os.environ.copy()
    env["GPTMOBILE_KOKORO_DEPENDENCY_CACHE"] = str(args.dependency_cache)
    env["JAVA_HOME"] = str(args.jdk)
    env["LD_LIBRARY_PATH"] = ":".join(dict.fromkeys(str(p) for p in [args.qnn, *runtime_directories, args.ort / "lib"]))

    def run(label, command, timeout=300):
        with (args.output / (label + ".log")).open("w") as log:
            completed = subprocess.run([str(x) for x in command], stdout=log, stderr=subprocess.STDOUT,
                                       env=env, cwd=args.output, timeout=timeout)
        print(label + " exit=" + str(completed.returncode), flush=True)
        return completed.returncode

    assert run("configure", [args.cmake, "-S", native, "-B", build, "-DCMAKE_BUILD_TYPE=Release",
                             "-DBUILD_TESTING=ON", "-DORT_INCLUDE_DIR=" + str(args.ort / "include"),
                             "-DORT_LIBRARY=" + str(args.ort / "lib/libonnxruntime.so"),
                             "-DASR_QNN_MANIFEST=" + str(args.native_source.parents[3] / "docs/audits/qnn-runtime-candidate.json")]) == 0
    sherpa = build / "_deps/sherpa-src/sherpa-onnx/csrc/offline-tts-kokoro-model.cc"
    sherpa_before = digest(sherpa)

    def fault_port(text):
        text = replace_once(text, '#include "kokoro_execution_provider.h"',
                            '#include "kokoro_execution_provider.h"\n#include "kokoro_profile_fault.h"')
        return replace_once(text, "    if (profiling_) {\n      profiling_ = false;",
                            "    KokoroHostProfileFaultBoundary();  // Actual kernels completed; deterministic test fault.\n"
                            "    if (profiling_) {\n      profiling_ = false;")

    port(sherpa, fault_port)
    (args.output / "host-port.diff").write_text("".join(changes))
    # The same library now also contains the streaming frontend and decoders.
    # Retain all native tests while allowing their additional compilation work.
    assert run("build", [args.cmake, "--build", build, "--parallel", str(args.build_jobs)], timeout=1800) == 0
    assert run("ctest", [args.cmake.parent / "ctest", "--test-dir", build, "--output-on-failure"]) == 0
    for name, library in provider_files.items():
        if digest(library) != provider_inputs[name]:
            raise ValueError("QNN package input changed during the native build: " + name)
        # Production cache identity rejects symlinks. Preserve the exact
        # verified publisher bytes in this isolated executable fixture too.
        shutil.copy2(library, build / library.name)
    classes = args.output / "classes"
    classes.mkdir()
    assert run("javac", [args.jdk / "bin/javac", "-d", classes, main_java, main_java.with_name("KokoroFrontend.java"), test_java, context_java, prepared_java,
                        asr_java, asr_test_java]) == 0
    results = {}
    for case in ["unqualified_cancel", "unqualified_failure", "qualified_cancel"]:
        results[case] = run(case, [args.jdk / "bin/java", "-Xcheck:jni", "-Djava.library.path=" + str(build),
                                  "-cp", classes, "dev.melo.kokoro.KokoroNativeProfileLifecycleIntegration",
                                  args.pack, build, case, args.output / (case + ".json")])
    results["prepared_phrase_cpu"] = run("prepared_phrase_cpu", [args.jdk / "bin/java", "-Xcheck:jni",
        "-Djava.library.path=" + str(build), "-cp", classes, "dev.melo.kokoro.KokoroPreparedPhraseIntegration",
        args.pack / "model.int8.onnx", args.pack])
    results["context_roundtrip"] = run("context_roundtrip", [args.jdk / "bin/java", "-Xcheck:jni",
        "-Djava.library.path=" + str(build), "-cp", classes, "dev.melo.kokoro.KokoroNativeContextIntegration",
        args.pack, build, args.output / "context_roundtrip.json"])
    asr_memory = {}
    for fixture in asr_fixtures:
        label = "asr_" + fixture["id"]
        admission = asr_memory_admission(fixture)
        asr_memory[fixture["id"]] = admission
        if not admission["admitted"]:
            # Resource refusal fails the required job; it never becomes a
            # successful skipped model or an inferred CPU execution result.
            results[label] = 78
            (args.output / (label + ".json")).write_text(json.dumps({
                "scope": "CPU_HOST_ADMISSION_FAILURE_MODEL_NOT_EXECUTED",
                "error": "Insufficient or unknown host memory for mandatory ASR JNI regression",
                **admission,
            }, indent=2) + "\n")
            print(label + " failed: insufficient or unknown host memory", flush=True)
            continue
        results[label] = run(label, [args.jdk / "bin/java", "-Xcheck:jni", "-Xmx256m", "-Djava.library.path=" + str(build),
            "-cp", classes, "dev.melo.kokoro.StreamingAsrNativeIntegration", args.asr_packs / fixture["id"],
            fixture["encoder"], fixture["decoder"], fixture["joiner"], fixture["tokens"], fixture["modelType"],
            fixture["language"], str(fixture["chunkMillis"]), fixture["wav"], fixture["expectedPrefix"], args.output / (label + ".json")])
    source_inputs = {str(p): digest(p) for p in sorted(args.native_source.rglob("*")) if p.is_file()}
    source_inputs.update({str(p): digest(p) for p in [Path(__file__), test_java, context_java, prepared_java, main_java, main_java.with_name("KokoroFrontend.java"),
                                                    test_host / "kokoro_profile_fault.h", test_host / "kokoro_profile_fault.cpp",
                                                    asr_java, asr_test_java, asr_fixture_manifest]})
    receipt = {
        "scope": "actual_native_model_sherpa_jni_with_explicit_host_emulator_and_fault_ports",
        "physical_device_qualified": False,
        "provider_identity": provider_identity,
        "results": results,
        "source_sha256": source_inputs,
        "host_port_sha256": digest(args.output / "host-port.diff"),
        "sherpa_before_fault_port_sha256": sherpa_before,
        "sherpa_after_fault_port_sha256": digest(sherpa),
        "provider_libraries_sha256": provider_inputs,
        "ort_shared_library_sha256": digest(args.ort / "lib/libonnxruntime.so"),
        "model_sha256": model_pins,
        "asr_fixture_sha256": asr_input_pins,
        "asr_host_memory_admission": asr_memory,
        "asr_execution_scope": "CPU_HOST_PRODUCTION_JNI_ONLY_NO_GPU_NPU_OR_PHYSICAL_QUALIFICATION",
        "built_jni_sha256": digest(build / "libtimed_kokoro.so"),
        "built_adapter_sha256": digest(build / "libkokoro_qnn_adapter.so"),
    }
    (args.output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    raise SystemExit(0 if all(code == 0 for code in results.values()) else 1)


if __name__ == "__main__":
    main()
