"""One bounded provider HTTP exchange, run in a killable trusted subprocess.

The parent owns authorization and the durable dispatch receipt. Credentials are
read from stdin, never command arguments; no provider body is used as an error.
This file is independently executable with Python's isolated import mode.
"""
import json
import sys
import time

import requests

ORIGINS = {'jules': 'https://jules.googleapis.com', 'cursor': 'https://api.cursor.com'}
MAX_RESPONSE = 4 * 1024 * 1024


class HttpFailure(Exception):
    def __init__(self, code):
        self.code = code


def perform(payload, *, request=requests.request, clock=time.monotonic):
    backend, path, method = payload['backend'], payload['path'], payload['method']
    if (backend not in ORIGINS or method not in {'GET', 'POST'} or not isinstance(path, str)
            or not path.startswith('/') or path.startswith('//') or '..' in path or '\\' in path
            or len(path) > 2048 or any(ord(c) < 32 for c in path)):
        raise HttpFailure('AGENT_ENDPOINT_INVALID')
    streaming = payload.get('kind') == 'events'
    started = clock()

    def within_deadline():
        if clock() - started >= (18 if streaming else 40):
            raise HttpFailure('AGENT_RESPONSE_DEADLINE')

    events, current, last, size = [], {}, payload.get('after'), 0
    try:
        within_deadline()
        with request(method, ORIGINS[backend] + path, headers=payload['headers'],
                     json=payload.get('body'), params=payload.get('params'), auth=lambda value: value,
                     timeout=(10, 3 if streaming else 30), allow_redirects=False, stream=True) as response:
            within_deadline()
            if response.status_code not in ({200} if streaming else {200, 201, 202, 204}):
                raise HttpFailure('AGENT_HTTP_' + str(response.status_code))
            if not streaming:
                raw = bytearray()
                for block in response.iter_content(8192):
                    within_deadline()
                    raw.extend(block)
                    if len(raw) > MAX_RESPONSE:
                        raise HttpFailure('AGENT_RESPONSE_TOO_LARGE')
                within_deadline()
                value = json.loads(raw) if raw else {}
                if not isinstance(value, dict):
                    raise HttpFailure('AGENT_RESPONSE_INVALID')
                return value
            stream_start = clock()
            for line in response.iter_lines(chunk_size=1024):
                within_deadline()
                size += len(line)
                if size > 512 * 1024 or len(events) >= 50 or clock() - stream_start > 5:
                    break
                if not line:
                    if current.get('data'):
                        events.append(dict(current, data=json.loads('\n'.join(current['data']))))
                        last = current.get('id', last)
                        if current.get('event') == 'done':
                            break
                    current = {}
                    continue
                if line.startswith(b':'):
                    continue
                key, _, value = line.decode('utf-8').partition(':')
                if key == 'data':
                    current.setdefault('data', []).append(value.lstrip(' '))
                elif key in {'id', 'event'}:
                    current[key] = value.lstrip(' ')
    except requests.RequestException:
        if not streaming:
            raise HttpFailure('AGENT_HTTP_UNCONFIRMED') from None
    except (ValueError, UnicodeError, OSError):
        raise HttpFailure('AGENT_EVENTS_INVALID' if streaming else 'AGENT_RESPONSE_INVALID') from None
    return {'events': events, 'next_cursor': last,
            'complete': any(value.get('event') == 'done' for value in events), 'waiting': not events}


def main():
    try:
        raw = sys.stdin.buffer.read(256 * 1024 + 1)
        if len(raw) > 256 * 1024:
            raise HttpFailure('AGENT_REQUEST_TOO_LARGE')
        result = {'result': perform(json.loads(raw))}
    except HttpFailure as error:
        result = {'error_code': error.code}
    except Exception:
        result = {'error_code': 'AGENT_HTTP_UNCONFIRMED'}
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(',', ':'), allow_nan=False))


if __name__ == '__main__':
    main()
