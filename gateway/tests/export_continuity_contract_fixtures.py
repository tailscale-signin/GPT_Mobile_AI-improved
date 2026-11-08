"""Generate Android wire fixtures through the production SQLite projection.

The admitted provider metadata and repository are synthetic test boundaries;
these files prove serialization compatibility, never live account execution.
"""
import json
import sys
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from test_gateway_v14_engineering import DurableEngineeringTests
from gateway_security import DeviceStore
from v14.config import Config
from v14.continuity_routes import install_continuity_routes
from v14.identity import Admission
from v14.service import ServiceState


def export(destination):
    destination.mkdir(parents=True, exist_ok=True)
    fixture = DurableEngineeringTests()
    fixture.setUp()
    try:
        fixture.time = 1794052800.0
        request = dict(fixture.request, branch='feature/source', base='feature/source', source_branch='feature/source',
                       source_sha='a' * 40, scope='read', profile_name='Original profile 原始', request_id='fixture-command')
        job = fixture.store.create('owner', request)
        continuity = fixture.store.continuity
        conversation_id = job['conversation_id']
        ordinary = {'job_id': 'gwjob_fixture_ordinary', 'principal_id': 'owner', 'model': 'original-chat-model',
                    'profile_id': 'chat_profile', 'profile_name': 'Chat profile 🌍', 'task_text': 'Continue the conversation',
                    'status': 'completed', 'phase': 'complete', 'scheduling_state': 'completed',
                    'created_at': fixture.time - 20, 'updated_at': fixture.time, 'finished_at': fixture.time,
                    'started_at': fixture.time - 10, 'last_sequence': 3, 'result_available': True}
        result = {'choices': [{'message': {'role': 'assistant', 'content': 'The retained ordinary answer'}, 'finish_reason': 'stop'}]}
        fixture.store.tx(lambda db: continuity.record_ordinary(db, ordinary, result, transcript=[
            {'role': 'user', 'content': 'Original question'}, {'role': 'assistant', 'content': 'Earlier answer'},
            {'role': 'user', 'content': 'Continue the conversation'}]))
        catalog = continuity.catalog('owner')
        ordinary_id = next(item['conversation_id'] for item in catalog['items'] if item['kind'] == 'chat')
        values = {
            'identity': continuity.identity('owner', 'fixture_process_instance', 'user'),
            'catalog': catalog,
            'engineering-snapshot': continuity.conversation('owner', conversation_id),
            'ordinary-snapshot': continuity.conversation('owner', ordinary_id),
            'command': continuity.command('owner', 'fixture-command'),
            'replay': continuity.messages('owner', ordinary_id),
        }
        values['replay-empty-tail'] = continuity.messages('owner', ordinary_id, values['replay']['next_cursor'])
        # Compact fixtures use a small physical-part budget; the >2 MiB behavior
        # is independently exercised with the normal production byte limits.
        with patch('v14.continuity.MESSAGE_PART_BYTES', 64):
            fixture.store.tx(lambda db: continuity.append(db, conversation_id, job['id'], 'fixture-part-final',
                'assistant', 'final', 'Retained answer with Unicode 🌍. ' * 12, fixture.time))
        first = continuity.messages('owner', conversation_id, limit=3)
        values['replay-parts-first'] = first
        values['replay-parts-duplicate'] = continuity.messages('owner', conversation_id, limit=3)
        values['replay-parts-resume'] = continuity.messages('owner', conversation_id, first['next_cursor'])
        for name, value in values.items():
            (destination / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    finally:
        fixture.tearDown()


def export_gaps(destination):
    """Capture exact authenticated HTTP bodies, with bounded retention faults."""
    destination.mkdir(parents=True, exist_ok=True)
    fixture = DurableEngineeringTests()
    fixture.setUp()
    try:
        fixture.time = 1794052800.0
        fixture.admit('local:single-user')
        config = Config(mode='loopback')
        app = FastAPI()
        app.add_middleware(Admission, config=config, store=DeviceStore(fixture.temp.name + '/auth.db'))
        install_continuity_routes(app, fixture.service, ServiceState(), config)
        with TestClient(app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 1234)) as client:
            for name, missing in [('interior', [2]), ('trailing', [4]), ('interior-trailing', [2, 4])]:
                job = fixture.store.create('local:single-user', dict(fixture.request, request_id='gap-' + name, scope='read'))
                cid = job['conversation_id']
                for index in (2, 3):
                    fixture.store.tx(lambda db: fixture.store.continuity.append(db, cid, job['id'], 'fixture-status-' + str(index),
                        'assistant', 'status', 'Retained status ' + str(index), fixture.time))
                fixture.store.control(job['id'], 'local:single-user', 'cancel', 0)
                fixture.store.tx(lambda db: db.executemany('DELETE FROM gateway_conversation_messages WHERE conversation_id=? AND sequence=?',
                                                          [(cid, sequence) for sequence in missing]))
                route = '/v1/gateway/conversations/' + cid
                response = client.get(route + '/messages')
                if response.status_code != 200:
                    raise AssertionError(response.text)
                page = response.json()
                expected = [{'first_sequence': value, 'last_sequence': value} for value in missing]
                if page['gap_details'] != {'missing_ranges': expected} or page['history_complete'] or page['has_more']:
                    raise AssertionError('The actual HTTP gap page did not preserve the declared retention boundary')
                snapshot = client.get(route)
                tail = client.get(route + '/messages', params={'cursor': page['next_cursor']})
                if snapshot.status_code != 200 or tail.status_code != 200 or tail.json()['items'] or tail.json()['gap']:
                    raise AssertionError('The actual HTTP gap cursor did not advance through its declared watermark')
                for suffix, value in [('gap', response), ('gap-snapshot', snapshot), ('gap-tail', tail)]:
                    # Save the wire encoder's text, not a hand-authored approximation.
                    (destination / ('replay-' + name + '-' + suffix + '.json')).write_text(value.text + '\n', encoding='utf-8')
        if fixture.backend.calls:
            raise AssertionError('Read-only fixture capture dispatched model or repository work')
    finally:
        fixture.tearDown()


def export_custom_base(destination):
    """Capture original source, target and base separately through HTTP."""
    destination.mkdir(parents=True, exist_ok=True)
    fixture = DurableEngineeringTests()
    fixture.setUp()
    try:
        fixture.time = 1794052800.0
        fixture.admit('local:single-user')
        request = dict(fixture.request, request_id='custom-base-command', scope='implement',
                       branch='work/custom', base='release/base', source_branch='feature/source', source_sha='a' * 40)
        job = fixture.store.create('local:single-user', request)
        fixture.store.control(job['id'], 'local:single-user', 'cancel', 0)
        config = Config(mode='loopback')
        app = FastAPI()
        app.add_middleware(Admission, config=config, store=DeviceStore(fixture.temp.name + '/auth.db'))
        install_continuity_routes(app, fixture.service, ServiceState(), config)
        routes = {'custom-base-catalog': '/v1/gateway/job-catalog',
                  'custom-base-snapshot': '/v1/gateway/conversations/' + job['conversation_id'],
                  'custom-base-command': '/v1/gateway/commands/custom-base-command'}
        with TestClient(app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 1234)) as client:
            for name, route in routes.items():
                response = client.get(route)
                if response.status_code != 200:
                    raise AssertionError(response.text)
                (destination / (name + '.json')).write_text(response.text + '\n', encoding='utf-8')
        if fixture.backend.calls:
            raise AssertionError('Read-only fixture capture dispatched model or repository work')
    finally:
        fixture.tearDown()


if __name__ == '__main__':
    destination = Path(__file__).resolve().parents[1] / 'contracts' / 'continuity-v1-fixtures'
    if '--gaps-only' not in sys.argv:
        export(destination)
    export_gaps(destination)
    export_custom_base(destination)
