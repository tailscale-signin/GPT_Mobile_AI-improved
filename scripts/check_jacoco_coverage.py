#!/usr/bin/env python3
"""Reject JaCoCo reports that did not analyze and execute application classes."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

APPLICATION_PREFIX = 'dev/chungjungsoo/gptmobile/'


def validate_report(path):
    try:
        report = ET.parse(Path(path)).getroot()
    except (OSError, ET.ParseError):
        raise ValueError('JaCoCo report is missing or invalid XML') from None
    if report.tag != 'report':
        raise ValueError('Expected a JaCoCo report root')
    names = set()
    executed = covered = missed = 0
    for entry in report.findall('./package/class'):
        name = entry.get('name', '')
        if not name.startswith(APPLICATION_PREFIX):
            continue
        if name in names:
            raise ValueError('JaCoCo report repeats an application class')
        names.add(name)
        counters = [counter for counter in entry.findall('counter')
                    if counter.get('type') == 'INSTRUCTION']
        if not counters:
            continue
        if len(counters) != 1:
            raise ValueError('JaCoCo application instruction counter is ambiguous')
        try:
            class_covered = int(counters[0].attrib['covered'])
            class_missed = int(counters[0].attrib['missed'])
        except (KeyError, ValueError):
            raise ValueError('JaCoCo application instruction counter is invalid') from None
        if class_covered < 0 or class_missed < 0:
            raise ValueError('JaCoCo application instruction counter is negative')
        covered += class_covered
        missed += class_missed
        executed += class_covered > 0
    if not names:
        raise ValueError('JaCoCo report has no application classes; verify production compiler outputs')
    if not executed or not covered:
        raise ValueError('JaCoCo report has no executed application instructions; verify class and execution-data inputs')
    return {
        'application_classes': len(names),
        'executed_application_classes': executed,
        'covered_instructions': covered,
        'missed_instructions': missed,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    try:
        result = validate_report(args.report)
    except ValueError as failure:
        parser.exit(1, f'{failure}\n')
    print(json.dumps({'report': str(args.report), **result}, sort_keys=True))


if __name__ == '__main__':
    main()
