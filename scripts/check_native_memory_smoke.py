#!/usr/bin/env python3
"""Require the real native-memory test to run and pass, not just a green task."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


TEST_CLASS = "dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemoryInstrumentedTest"
TEST_METHOD = "nativeIndexSurvivesReopenAndHonorsScopeAndDeletion"


def check_reports(directory):
    reports = list(Path(directory).rglob("TEST-*.xml"))
    if not reports:
        raise ValueError("Native memory smoke produced no JUnit XML reports")
    matched = []
    for path in reports:
        root = ET.parse(path).getroot()
        failed_counts = any(int(element.get(key, "0")) > 0 for element in root.iter() for key in ("failures", "errors", "skipped"))
        if failed_counts or any(list(root.iter(tag)) for tag in ("failure", "error", "skipped")):
            raise ValueError(f"{path}: native smoke failed or was skipped")
        for case in root.iter("testcase"):
            if case.get("classname") == TEST_CLASS and case.get("name") == TEST_METHOD:
                matched.append(path)
    if not matched:
        raise ValueError("Native encoder/index test did not execute; check minified test discovery")
    print(f"Native memory smoke: {TEST_CLASS}.{TEST_METHOD} passed on {len(matched)} device(s)")


def check_instrumentation(output):
    """Validate raw am instrument packets; adb's exit code is not a test result."""
    packet = {}
    started = False
    passed = False
    finished = False
    for line in Path(output).read_text().splitlines():
        if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            raise ValueError(f"Native instrumentation aborted: {line}")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                packet[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            if code < 0:
                raise ValueError(f"Native smoke failed or was skipped: {packet}")
            if packet.get("class") == TEST_CLASS and packet.get("test") == TEST_METHOD:
                if code == 1:
                    if started or finished:
                        raise ValueError("Native test has duplicate or out-of-order start packets")
                    started = True
                elif code == 0:
                    if not started or passed or finished:
                        raise ValueError("Native test completion has no matching start packet")
                    passed = True
            packet = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if int(line.removeprefix("INSTRUMENTATION_CODE: ")) != -1 or finished:
                raise ValueError(f"Native instrumentation did not finish successfully: {line}")
            finished = True
    if not (started and passed and finished):
        raise ValueError("Native encoder/index test did not execute and complete successfully")
    print(f"Native memory smoke: {TEST_CLASS}.{TEST_METHOD} executed and passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path, nargs="?")
    parser.add_argument("--instrumentation-output", type=Path)
    args = parser.parse_args()
    if (args.reports is None) == (args.instrumentation_output is None):
        parser.error("provide either a JUnit report directory or --instrumentation-output")
    if args.instrumentation_output:
        check_instrumentation(args.instrumentation_output)
    else:
        check_reports(args.reports)


if __name__ == "__main__":
    main()
