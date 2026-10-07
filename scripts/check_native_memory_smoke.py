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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    check_reports(parser.parse_args().reports)


if __name__ == "__main__":
    main()
