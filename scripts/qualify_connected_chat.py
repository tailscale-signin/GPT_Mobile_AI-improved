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
            or not command.get('job_id') or snapshot.get('conversation_id') != conversation_id
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
    report['phase'] = 'Observe original answer'
    report['firstVisibleMs'] = open_and_observe()
    report['phase'] = 'Restart and reconcile'
    adb('shell', 'am', 'force-stop', args.package)
    processes = adb('shell', 'ps', '-A').splitlines()
    if not processes or 'PID' not in processes[0].split() or any(line.split()[-1:] == [args.package] for line in processes[1:]):
        raise ValueError('PROCESS_STOP_NOT_OBSERVED')
    report['reopenedVisibleMs'] = open_and_observe()
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
    args = parser.parse_args()
    report = {'format': 'gptmobile.connected-chat-reopen.v1', 'status': 'BLOCKED'}
    try:
        observe(args, report)
    except Exception as error:
        reason = str(error)
        if reason in {'SAVED_ANSWER_NOT_VISIBLE', 'REOPEN_CHANGED_RECEIPT_OR_INSTALL'} and report.get('phase') == 'Restart and reconcile':
            report['status'] = 'FAILED'
        report['reason'] = reason if re.fullmatch('[A-Z_]{1,80}', reason) else type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    sys.exit(main())
