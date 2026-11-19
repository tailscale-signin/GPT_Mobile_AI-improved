#!/usr/bin/env python3
"""Check an existing short plain-text Gateway answer across a real Android process restart.

Run on the paired Windows Gateway with adb on PATH, the screen unlocked, and an idle
test phone. Supply its Android chat-room ID, Gateway conversation ID, original command
ID and exact model. The command must already have completed under the accessible
Gateway principal. No new inference, repository action, or provider spend is triggered.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from qualify_device_performance import installed_apks


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def bound_answer(command, history, conversation_id, command_id, model):
    snapshot = history['conversation']
    if (command.get('command_id') != command_id or command.get('conversation_id') != conversation_id
            or not command.get('job_id') or command.get('model', model) != model or snapshot.get('conversation_id') != conversation_id
            or snapshot.get('history_complete') is not True or snapshot.get('model') != model):
        raise ValueError('COMMAND_OR_MODEL_MISMATCH')
    answers = [item for item in history['messages'] if item.get('role') == 'assistant' and item.get('command_id') == command_id]
    if len(answers) != 1 or answers[0].get('status') != 'completed':
        raise ValueError('COMPLETED_ANSWER_MISSING')
    answer = answers[0]
    content = answer['content'].strip()
    if not 12 <= len(content) <= 320 or '\n' in content or not answer.get('message_id'):
        raise ValueError('SHORT_PLAIN_TEXT_FIXTURE_REQUIRED')
    if sum(item.get('content', '').strip() == content for item in history['messages']) != 1:
        raise ValueError('ANSWER_NOT_DISTINCT_IN_TRANSCRIPT')
    return content, {'command': digest(command_id), 'job': digest(command['job_id']),
                     'message': digest(answer['message_id']), 'revision': answer['revision'],
                     'content_sha256': digest(answer['content']), 'conversationRevision': snapshot['revision']}


def screen_contains(raw, package, expected):
    if len(raw) > 4_000_000 or '<!DOCTYPE' in raw:
        return False
    start, end = raw.find('<hierarchy'), raw.rfind('</hierarchy>')
    if start < 0 or end < start:
        return False
    try:
        root = ET.fromstring(raw[start:end + len('</hierarchy>')])
    except ET.ParseError:
        return False
    return any(n.get('package') == package and expected in (n.get('text', '').strip(), n.get('content-desc', '').strip())
               and n.get('password') != 'true' and n.get('class') != 'android.widget.EditText'
               for n in root.iter('node'))


def unplugged_charge(raw):
    values = dict(re.findall(r'^\s*([^:\r\n]+?)\s*:\s*([^\r\n]+?)\s*$', raw, re.MULTILINE))
    if 'UPDATES STOPPED' in raw.upper() or values.get('status') != '3':
        return None
    if any(values.get(source) != 'false' for source in ('AC powered', 'USB powered', 'Wireless powered')):
        return None
    if any(value != 'false' for source, value in values.items() if source.endswith(' powered')):
        return None
    counter = values.get('Charge counter', '')
    return int(counter) if re.fullmatch(r'[1-9]\d{0,8}', counter) else None


def compare_reopens(baseline, candidate):
    """Only compare two builds on the same handset, OS, Gateway and original answer."""
    keys = ('deviceSerialHash', 'osBuildHash', 'scenarioHash', 'installationHash', 'principalHash')
    for row in (baseline, candidate):
        if (row.get('format') != 'gptmobile.connected-chat-reopen.v1' or row.get('status') != 'PASSED'
                or not all(re.fullmatch('[0-9a-f]{64}', str(row.get(k, ''))) for k in (*keys, 'apkSetSha256'))):
            raise ValueError('INCOMPLETE_COMPARISON_EVIDENCE')
    if any(baseline[k] != candidate[k] for k in keys) or not baseline.get('receipt') or baseline['receipt'] != candidate.get('receipt'):
        raise ValueError('COMPARISON_WORKLOAD_OR_DEVICE_CHANGED')
    if baseline['apkSetSha256'] == candidate['apkSetSha256']:
        raise ValueError('COMPARISON_REQUIRES_TWO_BUILDS')
    changes = {}
    for key in ('firstVisibleMs', 'reopenedVisibleMs', 'reopenObservationMs', 'wholeDeviceChargeChangeUah'):
        if key == 'wholeDeviceChargeChangeUah' and (key not in baseline or key not in candidate):
            continue
        if any(type(row.get(key)) is not int or row[key] < 0 for row in (candidate, baseline)):
            raise ValueError('INVALID_COMPARISON_MEASUREMENT')
        changes[key] = candidate[key] - baseline[key]
    return {'baselineApkSetSha256': baseline['apkSetSha256'], 'candidateMinusBaseline': changes}


def observe(args, report):
    if not args.serial.strip() or args.room < 1 or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', args.package):
        raise ValueError('INVALID_ANDROID_ROOM_OR_PACKAGE')
    def adb(*parts):
        return subprocess.check_output(['adb', '-s', args.serial, *parts], stderr=subprocess.DEVNULL,
                                       text=True, encoding='utf-8', errors='replace', timeout=30).strip()
    if adb('get-state') != 'device':
        raise ValueError('DEVICE_NOT_READY')
    if adb('shell', 'getprop ro.kernel.qemu') == '1' or adb('shell', 'getprop ro.boot.qemu') == '1':
        raise ValueError('EMULATOR_DETECTED')
    serial = adb('get-serialno')
    if serial != args.serial:
        raise ValueError('DEVICE_IDENTITY_MISMATCH')
    report['deviceSerialHash'] = digest(serial)
    fingerprint = adb('shell', 'getprop ro.build.fingerprint')
    if fingerprint:
        report['osBuildHash'] = digest(fingerprint)
    report['scenarioHash'] = digest(json.dumps([args.package, args.room, args.conversation, args.command, args.model]))
    artifact = installed_apks(adb, args.package)
    report['apkSetSha256'] = artifact['apkSetSha256']
    report['versionCode'] = artifact['versionCode']
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'gateway'))
    from desktop.conversation_client import Client
    client = Client(args.gateway_root)
    identity = client.identity()
    report['installationHash'] = digest(identity['gateway_id'])
    report['principalHash'] = digest(identity['principal_id'])
    def read():
        return bound_answer(client.command(args.command), client.read(args.conversation), args.conversation, args.command, args.model)
    expected, before = read()
    component = args.package + '/dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity'
    def open_and_observe():
        start = time.monotonic()
        adb('shell', 'am', 'start', '-W', '-n', component, '--ei', 'chatRoomId', str(args.room))
        while time.monotonic() - start < 45:
            if screen_contains(adb('exec-out', 'uiautomator', 'dump', '/dev/tty'), args.package, expected):
                return round((time.monotonic() - start) * 1000)
            time.sleep(.5)
        raise ValueError('SAVED_ANSWER_NOT_VISIBLE')
    def charge_counter():
        try:
            return unplugged_charge(adb('shell', 'dumpsys', 'battery'))
        except (OSError, subprocess.SubprocessError):
            return None
    start_charge = charge_counter()
    started = time.monotonic()
    report['phase'] = 'Observe original answer'
    report['firstVisibleMs'] = open_and_observe()
    report['phase'] = 'Restart and reconcile'
    adb('shell', 'am', 'force-stop', args.package)
    processes = adb('shell', 'ps', '-A').splitlines()
    if not processes or 'PID' not in processes[0].split() or any(line.split()[-1:] == [args.package] for line in processes[1:]):
        raise ValueError('PROCESS_STOP_NOT_OBSERVED')
    report['reopenedVisibleMs'] = open_and_observe()
    end_charge = charge_counter()
    report['reopenObservationMs'] = round((time.monotonic() - started) * 1000)
    if start_charge is not None and end_charge is not None and end_charge <= start_charge:
        report['wholeDeviceChargeChangeUah'] = start_charge - end_charge
    if read() != (expected, before) or installed_apks(adb, args.package) != artifact:
        raise ValueError('REOPEN_CHANGED_RECEIPT_OR_INSTALL')
    report['receipt'] = before
    report.update(status='PASSED', phase='Complete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='dev.melo.gptmobile.improved')
    parser.add_argument('--room', type=int, required=True)
    parser.add_argument('--command', required=True)
    parser.add_argument('--conversation', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--gateway-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, help='Compare with a previous build on the same handset and saved answer')
    args = parser.parse_args()
    if args.baseline and args.output.resolve() == args.baseline.resolve():
        parser.error('Choose a different output file so the baseline is preserved.')
    report = {'format': 'gptmobile.connected-chat-reopen.v1', 'status': 'BLOCKED'}
    try:
        observe(args, report)
        if args.baseline:
            with args.baseline.open('rb') as stream:
                raw = stream.read(65537)
            if len(raw) > 65536:
                raise ValueError('BASELINE_TOO_LARGE')
            report['comparison'] = compare_reopens(json.loads(raw), report)
    except Exception as error:
        reason = str(error)
        report['status'] = 'BLOCKED'
        if reason in {'SAVED_ANSWER_NOT_VISIBLE', 'REOPEN_CHANGED_RECEIPT_OR_INSTALL'} and report.get('phase') == 'Restart and reconcile':
            report['status'] = 'FAILED'
        report['reason'] = reason if re.fullmatch('[A-Z_]{1,80}', reason) else type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    sys.exit(main())
