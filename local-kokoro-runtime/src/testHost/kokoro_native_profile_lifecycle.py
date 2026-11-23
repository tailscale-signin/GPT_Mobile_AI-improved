#!/usr/bin/env python3
"""Run the actual native Model/JNI/Sherpa profile lifecycle on a QNN host emulator.

Requires the verified Kokoro model, local CMake dependency archives, JDK21,
ORT1.28.2 host headers/library and QNN2.7.0 host libraries. Nothing is downloaded.
The copied host build selects QNN's x86 CPU-emulator device instead of Android
HTP and adds its documented architecture settings. One test-only barrier throws
after actual ORT execution, before Sherpa ends profiling. Model/cache/JNI code,
the real graph, real outputs and production profile parsing are unchanged.
This is neither a physical device nor Android packaging qualification.
"""

import argparse
import base64
import csv
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise AssertionError("Host boundary port no longer matches the production source")
    return text.replace(before, after, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-source", type=Path, required=True)
    parser.add_argument("--dependency-cache", type=Path, required=True)
    parser.add_argument("--ort", type=Path, required=True)
    parser.add_argument("--qnn", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for key, value in vars(args).items():
        setattr(args, key, value.resolve())
    if args.output.exists():
        raise AssertionError("Use a fresh evidence directory to preserve earlier results")
    args.output.mkdir(parents=True)
    test_host = Path(__file__).resolve().parent
    test_java = test_host.parent / "test/java/dev/melo/kokoro/KokoroNativeProfileLifecycleIntegration.java"
    main_java = test_host.parent / "main/java/dev/melo/kokoro/TimedKokoro.java"
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
        return replace_once(text, 'if (provider == "qnn-htp") settings.emplace("htp_performance_mode", "balanced");',
                            'if (provider == "qnn-htp") { settings.emplace("htp_performance_mode", "balanced");\n'
                            '    settings.emplace("htp_arch", "79"); settings.emplace("soc_model", "69"); }')

    port(native / "kokoro_execution_provider.h", provider_port)
    for name in ["kokoro_profile_fault.h", "kokoro_profile_fault.cpp"]:
        shutil.copy2(test_host / name, native / name)
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
    qnn_runtime_libraries = args.qnn.parent / "onnxruntime_qnn.libs"
    env["LD_LIBRARY_PATH"] = ":".join(str(p) for p in [args.qnn, qnn_runtime_libraries, args.ort / "lib"])

    def run(label, command, timeout=300):
        with (args.output / (label + ".log")).open("w") as log:
            completed = subprocess.run([str(x) for x in command], stdout=log, stderr=subprocess.STDOUT,
                                       env=env, cwd=args.output, timeout=timeout)
        print(label + " exit=" + str(completed.returncode), flush=True)
        return completed.returncode

    assert run("configure", [args.cmake, "-S", native, "-B", build, "-DCMAKE_BUILD_TYPE=Release",
                             "-DBUILD_TESTING=ON", "-DORT_INCLUDE_DIR=" + str(args.ort / "include"),
                             "-DORT_LIBRARY=" + str(args.ort / "lib/libonnxruntime.so")]) == 0
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
    assert run("build", [args.cmake, "--build", build, "--parallel", "4"]) == 0
    assert run("ctest", [args.cmake.parent / "ctest", "--test-dir", build, "--output-on-failure"]) == 0
    provider_inputs = {}
    record = args.qnn.parent / "onnxruntime_qnn-2.7.0.dist-info/RECORD"
    package_hashes = {name: value for name, value, _ in csv.reader(record.open())}
    for library in sorted(args.qnn.glob("*.so")):
        if not (library.name.startswith("libQnn") or library.name in {"libHtpPrepare.so", "libonnxruntime_providers_qnn.so"}):
            continue
        expected = "sha256=" + base64.urlsafe_b64encode(bytes.fromhex(digest(library))).rstrip(b"=").decode()
        assert package_hashes.get("onnxruntime_qnn/" + library.name) == expected, "QNN package input differs: " + library.name
        provider_inputs[library.name] = digest(library)
        (build / library.name).symlink_to(library)
    for library in sorted(qnn_runtime_libraries.iterdir()):
        if not library.is_file():
            continue
        expected = "sha256=" + base64.urlsafe_b64encode(bytes.fromhex(digest(library))).rstrip(b"=").decode()
        relative = "onnxruntime_qnn.libs/" + library.name
        assert package_hashes.get(relative) == expected, "QNN host runtime input differs: " + library.name
        provider_inputs[relative] = digest(library)
    classes = args.output / "classes"
    classes.mkdir()
    assert run("javac", [args.jdk / "bin/javac", "-d", classes, main_java, test_java]) == 0
    results = {}
    for case in ["unqualified_cancel", "unqualified_failure", "qualified_cancel"]:
        results[case] = run(case, [args.jdk / "bin/java", "-Xcheck:jni", "-Djava.library.path=" + str(build),
                                  "-cp", classes, "dev.melo.kokoro.KokoroNativeProfileLifecycleIntegration",
                                  args.pack, build, case, args.output / (case + ".json")])
    source_inputs = {str(p): digest(p) for p in sorted(args.native_source.rglob("*")) if p.is_file()}
    source_inputs.update({str(p): digest(p) for p in [Path(__file__), test_java, main_java,
                                                    test_host / "kokoro_profile_fault.h", test_host / "kokoro_profile_fault.cpp"]})
    receipt = {
        "scope": "actual_native_model_sherpa_jni_with_explicit_host_emulator_and_fault_ports",
        "physical_device_qualified": False,
        "results": results,
        "source_sha256": source_inputs,
        "host_port_sha256": digest(args.output / "host-port.diff"),
        "sherpa_before_fault_port_sha256": sherpa_before,
        "sherpa_after_fault_port_sha256": digest(sherpa),
        "provider_libraries_sha256": provider_inputs,
        "ort_shared_library_sha256": digest(args.ort / "lib/libonnxruntime.so"),
        "model_sha256": model_pins,
        "built_jni_sha256": digest(build / "libtimed_kokoro.so"),
        "built_adapter_sha256": digest(build / "libkokoro_qnn_adapter.so"),
    }
    (args.output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    raise SystemExit(0 if all(code == 0 for code in results.values()) else 1)


if __name__ == "__main__":
    main()
