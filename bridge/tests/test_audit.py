import json
import sqlite3
from datetime import UTC, datetime

import httpx
import pytest
from sqlalchemy import inspect, select

from hermes_mobile.auth import secret_digest
from hermes_mobile.db import Database, DeviceCredential
from hermes_mobile.hermes_rest import HermesApiError
from test_catalog import AUTH, client
from test_cron import CASES, CREATE, JOB


def events(api, **params):
    response = api.get('/v1/audit', headers=AUTH, params=params)
    assert response.status_code == 200
    return response.json()


@pytest.mark.parametrize('method,path,body,payload', CASES)
def test_cron_audit_fields_and_redaction(tmp_path, method, path, body, payload):
    api, rest, _ = client(tmp_path, payload)
    original = rest.request

    async def forward(*args, **kwargs):
        with api.app.state.database.engine.connect() as connection:
            assert connection.exec_driver_sql('SELECT COUNT(*) FROM audit_events').scalar_one() == 0
        return await original(*args, **kwargs)

    rest.request = forward
    with api:
        assert events(api)['items'] == []
        result = api.request(method, '/v1/cron' + path, json=body, headers=AUTH)
        assert result.status_code == 200
        page = events(api)
        if method == 'GET':
            assert page == {'items': [], 'next_before_id': None}
            return
        row, = page['items']
        action = {'PUT': 'update', 'DELETE': 'delete'}.get(method)
        action = action or ('create' if path == '/jobs' else path.rsplit('/', 1)[-1])
        assert row == {
            'id': 1, 'timestamp': row['timestamp'], 'device_id': 'phone-1',
            'action': 'cron.' + action, 'target': JOB['id'],
            'outcome': 'success', 'detail': None,
        }
        assert datetime.fromisoformat(row['timestamp']).utcoffset().total_seconds() == 0
        from hermes_mobile.db import AuditEvent
        with api.app.state.database.session() as session:
            stored, = session.scalars(select(AuditEvent)).all()
            raw = json.dumps({c.name: str(getattr(stored, c.name)) for c in AuditEvent.__table__.columns})
        for private in ['private prompt', 'new prompt', 'phone-secret', 'Authorization']:
            assert private not in raw + json.dumps(page)


@pytest.mark.parametrize('error,code', [
    (HermesApiError(404, 'upstream-secret'), 'cron_job_not_found'),
    (HermesApiError(400, 'upstream-secret'), 'hermes_rejected'),
    (HermesApiError(500, 'upstream-secret'), 'hermes_unavailable'),
    (httpx.ReadTimeout('upstream-secret'), 'hermes_unavailable'),
    (ValueError('upstream-secret'), 'hermes_unavailable'),
])
@pytest.mark.parametrize('method,path,body', [
    ('POST', '/jobs', CREATE), ('PUT', '/jobs/abc123def456', {'updates': {'prompt': 'private'}}),
    ('DELETE', '/jobs/abc123def456', None), ('POST', '/jobs/abc123def456/pause', None),
    ('POST', '/jobs/abc123def456/resume', None), ('POST', '/jobs/abc123def456/trigger', None),
])
def test_audit_failure_codes(tmp_path, error, code, method, path, body):
    api, rest, _ = client(tmp_path, {})

    async def fail(*args, **kwargs):
        raise error

    rest.request = fail
    with api:
        response = api.request(method, '/v1/cron' + path, json=body, headers=AUTH)
        assert response.json()['detail']['code'] == code
        row, = events(api)['items']
        assert row['outcome'] == 'failure'
        assert row['detail'] == code
        assert row['target'] == (None if path == '/jobs' else JOB['id'])
        assert 'upstream-secret' not in json.dumps(row)


def test_audit_never_copies_request_or_response_metadata(tmp_path):
    payload = {**JOB, 'name': 'response private name', 'token': 'response-secret',
               'nested': {'Authorization': 'Bearer response-secret'}}
    api, _, _ = client(tmp_path, payload)
    with api:
        for method, path, body in [
            ('POST', '/jobs', {**CREATE, 'name': 'request private name', 'prompt': 'api_key=hidden-secret'}),
            ('PUT', '/jobs/' + JOB['id'], {'updates': {'prompt': 'password=hidden-secret'}}),
            ('DELETE', '/jobs/' + JOB['id'], None),
        ]:
            assert api.request(method, '/v1/cron' + path, json=body, headers=AUTH).status_code == 200
        serialized = json.dumps(events(api))
        with api.app.state.database.engine.connect() as connection:
            raw = str(connection.exec_driver_sql('SELECT * FROM audit_events').all())
        for secret in ['hidden-secret', 'response-secret', 'private name', 'private prompt', 'phone-secret']:
            assert secret not in serialized + raw


def test_audit_pagination_all_devices(tmp_path):
    from hermes_mobile.services.audit import append_event
    api, _, _ = client(tmp_path, JOB)
    with api:
        database = api.app.state.database
        for i in range(103):
            append_event(database, device_id='old-phone', action='cron.delete', target=str(i), outcome='success')
        with database.session() as session:
            session.add(DeviceCredential(id='phone-2', secret_digest=secret_digest('second-secret'),
                device_name='second', created_at=datetime.now(UTC).replace(tzinfo=None)))
            session.commit()
        first = events(api)
        assert len(first['items']) == 50
        assert first['next_before_id'] == 54
        assert {item['device_id'] for item in first['items']} == {'old-phone'}
        other = api.get('/v1/audit', headers={'Authorization': 'Bearer second-secret'}).json()
        assert first == other
        # New writes cannot shift a before_id page.
        append_event(database, device_id='phone-1', action='cron.delete', target='new', outcome='success')
        second = events(api, before_id=54, limit=100)
        assert [row['id'] for row in second['items']] == list(range(53, 0, -1))
        assert second['next_before_id'] is None
        assert events(api, before_id=1)['items'] == []
        assert len(events(api, limit=1)['items']) == 1
        assert len(events(api, limit=100)['items']) == 100


@pytest.mark.parametrize('params', [{'limit': 0}, {'limit': 101}, {'limit': 'bad'}, {'before_id': 0}, {'before_id': 'bad'}])
def test_audit_invalid_pagination(tmp_path, params):
    api, _, _ = client(tmp_path, {})
    with api:
        response = api.get('/v1/audit', params=params, headers=AUTH)
        assert response.status_code == 400
        assert response.json()['detail']['code'] == 'invalid_audit_request'


def test_audit_requires_auth_and_has_no_write_routes(tmp_path):
    api, _, _ = client(tmp_path, {})
    with api:
        assert api.get('/v1/audit').status_code == 401
        assert api.get('/v1/audit', headers={'Authorization': 'Bearer wrong'}).status_code == 401
        for method in ['POST', 'PUT', 'PATCH', 'DELETE']:
            for path in ['/v1/audit', '/v1/audit/1']:
                assert api.request(method, path, headers=AUTH).status_code in {404, 405}
        paths = api.app.openapi()['paths']
        assert {path: set(methods) for path, methods in paths.items() if path.startswith('/v1/audit')} == {'/v1/audit': {'get'}}


@pytest.mark.parametrize('upstream_fails', [False, True])
def test_audit_storage_failure_preserves_action(tmp_path, monkeypatch, caplog, upstream_fails):
    from hermes_mobile.db import AuditEvent
    from sqlalchemy.orm import Session
    api, rest, _ = client(tmp_path, JOB)
    original = Session.add

    def fail_audit(self, instance, *args, **kwargs):
        if isinstance(instance, AuditEvent):
            raise RuntimeError('private database parameters')
        return original(self, instance, *args, **kwargs)

    monkeypatch.setattr(Session, 'add', fail_audit)
    if upstream_fails:
        async def fail(*args, **kwargs):
            raise HermesApiError(404, 'private upstream')
        rest.request = fail
    with api:
        result = api.delete('/v1/cron/jobs/' + JOB['id'], headers=AUTH)
        assert result.status_code == (404 if upstream_fails else 200)
        assert events(api)['items'] == []
    assert 'audit_write_failed' in caplog.text
    assert 'private' not in caplog.text


def test_existing_database_adds_audit_table_without_losing_rows(tmp_path):
    path = tmp_path / 'bridge.db'
    with sqlite3.connect(path) as connection:
        connection.execute('CREATE TABLE device_event_cursors (device_id VARCHAR(32) PRIMARY KEY, event_id INTEGER NOT NULL)')
        connection.execute("INSERT INTO device_event_cursors VALUES ('existing', 123)")
    database = Database(f'sqlite:///{path}')
    try:
        database.create_tables()
        database.create_tables()
        assert 'audit_events' in inspect(database.engine).get_table_names()
        with database.engine.connect() as connection:
            assert connection.exec_driver_sql('SELECT * FROM device_event_cursors').all() == [('existing', 123)]
    finally:
        database.close()


def test_redactor_nested_metadata_and_long_text():
    from hermes_mobile.services.redaction import redact
    source = {'safe': [{'API_KEY': 'hidden1', 'nested': {'refreshToken': 'hidden2', 'ok': True}},
                       {'client_secret': 'hidden3', 'Password': 'hidden4', 'Authorization': 'hidden5',
                        'system_prompt': 'hidden6'}],
              'detail': 'sensitive prose ' * 50, 'code': 'hermes_rejected', 'count': 2,
              'free': 'Authorization: Bearer hidden7'}
    output = redact(source)
    serialized = json.dumps(output)
    assert 'hidden' not in serialized
    assert 'sensitive prose' not in serialized
    assert output['code'] == 'hermes_rejected'
    assert output['safe'][0]['nested']['ok'] is True
    assert output['count'] == 2
    assert source['safe'][0]['API_KEY'] == 'hidden1'


def test_audit_insert_redacts_metadata_before_storage(tmp_path):
    from hermes_mobile.services.audit import append_event
    api, _, _ = client(tmp_path, {})
    with api:
        append_event(api.app.state.database, device_id='phone-1', action='mcp.secret_update',
            target='server-1', outcome='success', detail={'nested': [{'api_key': 'hidden-secret'}], 'text': 'private ' * 100})
        text = json.dumps(events(api))
        assert 'hidden-secret' not in text
        assert 'private ' not in text


def test_audit_read_storage_error_is_stable(tmp_path, monkeypatch):
    from sqlalchemy.exc import OperationalError
    from hermes_mobile.routes import audit
    api, _, _ = client(tmp_path, {})

    def fail(*args, **kwargs):
        raise OperationalError('private sql', {'token': 'hidden-secret'}, Exception('private'))

    monkeypatch.setattr(audit, 'read_events', fail)
    with api:
        result = api.get('/v1/audit', headers=AUTH)
        assert result.status_code == 503
        assert result.json()['detail']['code'] == 'audit_storage_unavailable'
        assert 'private' not in result.text
        assert 'hidden-secret' not in result.text


def test_audit_commit_failure_rolls_back(tmp_path, monkeypatch, caplog):
    from sqlalchemy.orm import Session
    api, _, _ = client(tmp_path, JOB)

    def fail(*args, **kwargs):
        raise RuntimeError('private commit parameters')

    with api:
        monkeypatch.setattr(Session, 'commit', fail)
        assert api.post('/v1/cron/jobs', json=CREATE, headers=AUTH).status_code == 200
        assert events(api)['items'] == []
    assert 'audit_write_failed' in caplog.text
    assert 'private' not in caplog.text


def test_rejected_request_and_reads_do_not_audit(tmp_path):
    api, rest, _ = client(tmp_path, JOB)
    with api:
        assert api.post('/v1/cron/jobs', json=CREATE).status_code == 401
        assert api.post('/v1/cron/jobs', json={}, headers=AUTH).status_code == 400
        assert events(api)['items'] == []
        assert rest.calls == []
        api.app.state.thread_service = None
        assert api.get('/v1/cron/jobs', headers=AUTH).status_code == 503
        assert events(api)['items'] == []
        assert api.delete('/v1/cron/jobs/' + JOB['id'], headers=AUTH).status_code == 503
        row, = events(api)['items']
        assert (row['outcome'], row['detail']) == ('failure', 'hermes_unavailable')


@pytest.mark.parametrize('value', ['x' * 201, 'password=short', 'api_key=short', 'Bearer short', 'line\nbreak'])
def test_redactor_removes_whole_unsafe_text(value):
    from hermes_mobile.services.redaction import REDACTED, redact
    assert redact({'detail': value}) == {'detail': REDACTED}


def test_detail_is_bounded_after_serialization(tmp_path):
    from hermes_mobile.services.audit import append_event
    from hermes_mobile.services.redaction import REDACTED
    api, _, _ = client(tmp_path, {})
    with api:
        append_event(api.app.state.database, device_id='phone-1', action='cron.create',
            target=None, outcome='success', detail={'items': ['x' * 100] * 10})
        row, = events(api)['items']
        assert row['detail'] == REDACTED
