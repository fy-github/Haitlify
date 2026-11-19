"""Real pinned MCP SDK; stdin-only fixture grants, exact-loopback network guard.
No Hermes configuration, model, tool mocks, or manually serialized MCP requests.
"""
import asyncio
import importlib.metadata
import json
import logging
import socket
import sys

fixture = json.loads(sys.stdin.readline())
port = fixture['port']
assert type(port) is int and 0 < port < 65536
assert sys.version_info[:2] == (3, 14)
assert importlib.metadata.version('mcp') == '2.0.0'
assert importlib.metadata.version('httpx2') == '2.12.0'
allowed_connections = 0
blocked_connections = 0
original_connect = socket.socket.connect
original_connect_ex = socket.socket.connect_ex
original_resolve = socket.getaddrinfo

def check(address):
    global allowed_connections, blocked_connections
    if not isinstance(address, tuple) or address[:2] != ('127.0.0.1', port):
        blocked_connections += 1
        raise PermissionError('probe network denied')
    allowed_connections += 1

def connect(self, address):
    check(address)
    return original_connect(self, address)

def connect_ex(self, address):
    check(address)
    return original_connect_ex(self, address)

def resolve(host, service, *args, **kwargs):
    if host not in ('127.0.0.1', b'127.0.0.1') or int(service) != port:
        raise PermissionError('probe DNS denied')
    return original_resolve(host, service, *args, **kwargs)

socket.socket.connect = connect
socket.socket.connect_ex = connect_ex
socket.getaddrinfo = resolve
logging.disable(logging.CRITICAL)

import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

base = f'http://127.0.0.1:{port}'
SPEC = {'id': 'sdk-case', 'name': 'Visible content', 'startUrl': 'https://example.com/app',
        'steps': [{'id': 'check', 'action': 'assertVisible', 'target': '#content', 'value': None}]}


def emit(value):
    print(json.dumps(value, sort_keys=True), flush=True)


def value(result):
    assert result.is_error is False
    assert len(result.content) == 1 and result.content[0].type == 'text'
    assert json.loads(result.content[0].text) == result.structured_content
    return result.structured_content


async def main():
    statuses = []
    negatives = []
    wire = []
    mode = None
    session_id = None
    async def before(request):
        nonlocal mode
        if request.method == 'POST':
            body = json.loads(request.content)
            wire.append({'method': body['method'], 'version': request.headers.get('MCP-Protocol-Version'),
                         'offered': body.get('params', {}).get('protocolVersion')})
        if mode == 'wrong_session':
            request.headers['Mcp-Session-Id'] = 'unknown-fixture-session'
        elif mode == 'cross_token':
            request.headers['Authorization'] = 'Bearer ' + fixture['otherToken']
        elif mode == 'wrong_token':
            request.headers['Authorization'] = 'Bearer unissued-fixture-token'
    async def after(response):
        nonlocal session_id
        statuses.append((response.request.method, response.status_code))
        if response.headers.get('Mcp-Session-Id'):
            session_id = response.headers['Mcp-Session-Id']

    async def rejected(operation, expected):
        start = len(statuses)
        failed = False
        try:
            await operation()
        except Exception:
            failed = True
        assert failed, 'SDK accepted rejected request'
        assert ('POST', expected) in statuses[start:], 'wrong rejection status'

    async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + fixture['token']},
                                  trust_env=False, timeout=8,
                                  event_hooks={'request': [before], 'response': [after]}) as http:
        async with streamable_http_client(base + '/mcp', http_client=http) as streams:
            async with ClientSession(*streams, read_timeout_seconds=8) as sdk:
                init = await sdk.initialize()
                assert init.protocol_version == '2025-11-25'
                assert init.server_info.name == 'haitlify-private-drafts'
                assert session_id
                listed = await sdk.list_tools()
                assert [t.name for t in listed.tools] == ['generation_context', 'draft_create', 'draft_get']
                context = value(await sdk.call_tool('generation_context'))
                assert context == {'requirementRef': 'requirement:immutable', 'requirementText': 'Immutable requirement',
                                   'targetUrl': 'https://example.com/app', 'targetRevision': 'v1'}
                created = value(await sdk.call_tool('draft_create', {'spec': SPEC}))
                got = value(await sdk.call_tool('draft_get'))
                replay = value(await sdk.call_tool('draft_create', {'spec': SPEC}))
                assert created == got == replay
                assert created['state'] == 'DRAFT' and created.get('confirmedBy') is None
                assert created['spec'] == SPEC
                for name, code in [('wrong_session', 404), ('cross_token', 404), ('wrong_token', 401)]:
                    mode = name
                    await rejected(sdk.list_tools, code)
                    negatives.append({'case': name, 'status': code})
                    mode = None
                # Obtain an actual ordinary API login cookie from the same H2 fixture.
                async with httpx2.AsyncClient(trust_env=False, timeout=8) as browser:
                    await browser.get(base + '/api/auth/me')
                    csrf = browser.cookies.get('XSRF-TOKEN')
                    assert csrf
                    login = await browser.post(base + '/api/auth/login',
                        headers={'X-XSRF-TOKEN': csrf},
                        json={'username': fixture['username'], 'password': 'SDK-fixture-password-1!'})
                    assert login.status_code == 200 and browser.cookies.get('JSESSIONID')
                    assert (await browser.get(base + '/api/auth/me')).status_code == 200
                    # Still the real SDK emits initialize, now carrying only browser cookies.
                    async with streamable_http_client(base + '/mcp', http_client=browser) as cookie_streams:
                        async with ClientSession(*cookie_streams, read_timeout_seconds=8) as cookie_sdk:
                            cookie_status = []
                            async def cookie_after(response):
                                cookie_status.append(response.status_code)
                            browser.event_hooks['response'].append(cookie_after)
                            try:
                                await cookie_sdk.initialize()
                            except Exception:
                                assert 401 in cookie_status
                                negatives.append({'case': 'authenticated_api_cookie', 'status': 401})
                            else:
                                raise AssertionError('ordinary cookie replaced grant')
                emit({'stage': 'created', 'draft': created, 'context': context,
                      'protocol': init.protocol_version, 'offered': wire[0]['offered']})
                assert await asyncio.to_thread(sys.stdin.readline) == 'revoked\n'
                await rejected(lambda: sdk.call_tool('draft_get'), 401)
                await rejected(lambda: sdk.call_tool('draft_create', {'spec': SPEC}), 401)
                await rejected(sdk.list_tools, 401)
                negatives.extend({'case': 'revoked_' + name, 'status': 401}
                                 for name in ('draft_get', 'draft_create', 'tools_list'))
                assert all(x['version'] == '2025-11-25' for x in wire if x['method'] != 'initialize')
    assert blocked_connections == 0
    emit({'stage': 'complete', 'mcp': importlib.metadata.version('mcp'), 'httpx2': importlib.metadata.version('httpx2'),
          'python': sys.version.split()[0], 'protocol': init.protocol_version, 'offered': wire[0]['offered'],
          'tools': [t.name for t in listed.tools], 'negative_checks': len(negatives), 'negatives': negatives,
          'network_allowed': allowed_connections, 'network_blocked': blocked_connections,
          'get_statuses': [code for method, code in statuses if method == 'GET'],
          'delete_statuses': [code for method, code in statuses if method == 'DELETE']})

try:
    asyncio.run(main())
except BaseException as error:
    # Never print exception text: SDK exceptions may include request headers.
    def failure_types(exc):
        nested = getattr(exc, 'exceptions', ())
        return [type(exc).__name__] + [name for child in nested for name in failure_types(child)]
    emit({'stage': 'failed', 'error_types': failure_types(error)})
    sys.exit(1)
