#!/usr/bin/env python3
"""Trusted-host provisioner: render stopped pool resources, control edges, fenced cleanup.
No daemon/listener exposed to workers. No Docker invocation in this reviewed slice.
A production apply/start is deliberately BLOCKED pending firewall/runtime review.
"""
import argparse
import ipaddress
import json
import pathlib
import re
import socket
import os
import stat
import select
import sys
import time
from protocol import FIELDS, Rejected, validate, validate_public
from edge import strict_json

POOL = 'haitlify-independent-v1'
MAX = 1048576
CLI_SECONDS = 10.0


def remaining(deadline):
    seconds = deadline - time.monotonic()
    if seconds <= 0: raise Rejected('control deadline exceeded')
    return seconds


def read_input(deadline):
    deadline = min(deadline, time.monotonic() + 1.0)
    fd = sys.stdin.fileno()
    blocking = os.get_blocking(fd)
    raw = bytearray()
    try:
        os.set_blocking(fd, False)
        while True:
            if not select.select([fd], [], [], remaining(deadline))[0]:
                raise Rejected('control input deadline exceeded')
            remaining(deadline)
            try: chunk = os.read(fd, MAX + 1 - len(raw))
            except BlockingIOError: continue
            if not chunk: break
            raw.extend(chunk)
            if len(raw) > MAX: raise Rejected('control input too large')
        result = strict_json(raw)
        remaining(deadline)
        return result
    finally:
        os.set_blocking(fd, blocking)

def write_output(result, deadline):
    # No buffered stdout data: interpreter shutdown must not retry a partial frame.
    deadline = min(deadline, time.monotonic() + .25)
    fd = sys.stdout.fileno()
    blocking = os.get_blocking(fd)
    try:
        raw = json.dumps(result).encode() + b'\n'
        os.set_blocking(fd, False)
        offset = 0
        while offset < len(raw):
            if not select.select([], [fd], [], remaining(deadline))[1]: return False
            remaining(deadline)
            try: sent = os.write(fd, raw[offset:])
            except BlockingIOError: continue
            if sent <= 0: return False
            offset += sent
        remaining(deadline)
        return True
    except (OSError, ValueError):
        return False
    finally:
        os.set_blocking(fd, blocking)


def plan(config):
    fields = {'slot','incarnation','subnet','worker_image','edge_image','model'}
    if not isinstance(config, dict) or set(config) != fields: raise Rejected('exact pool fields required')
    for key in ('slot','incarnation'):
        if not isinstance(config[key], str) or not re.fullmatch('[a-z0-9]{1,16}', config[key]):
            raise Rejected('invalid resource identity')
    for key in ('worker_image','edge_image'):
        if not isinstance(config[key], str) or not re.fullmatch('[a-z0-9./_-]+@sha256:[a-f0-9]{64}', config[key]):
            raise Rejected('digest-pinned image required')
    if not isinstance(config['model'], str) or not re.fullmatch('[A-Za-z0-9._/-]{1,100}', config['model']):
        raise Rejected('invalid model')
    subnet = ipaddress.ip_network(config['subnet'], strict=True)
    if subnet.version != 4 or subnet.prefixlen != 24 or not subnet.subnet_of(ipaddress.ip_network('10.221.0.0/16')):
        raise Rejected('dedicated pool /24 required')
    name = 'hlwp-' + config['slot'] + '-' + config['incarnation']
    labels = dict(pool=POOL, slot=config['slot'], incarnation=config['incarnation'])
    common = dict(read_only=True, user='10001:10001', cap_drop=['ALL'],
        security_opt=['no-new-privileges:true'], restart='no', pids_limit=64,
        mem_limit='512m', cpus=1, labels=labels, volumes=[], networks=['slot'],
        init=True, logging={'driver':'local','options':{'max-size':'5m','max-file':'2'}})
    worker = dict(common, image=config['worker_image'], stdin_open=True, tty=False,
        entrypoint=['python3','/opt/pool/worker.py'], working_dir='/workspace',
        environment={'HOME':'/home/worker','HERMES_HOME':'/home/worker/.hermes',
                     'POOL_INCARNATION':config['incarnation'], 'POOL_MODEL':config['model'],
                     'PYTHONDONTWRITEBYTECODE':'1','PYTHONUNBUFFERED':'1'},
        tmpfs=['/home/worker:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=128m',
               '/workspace:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=128m',
               '/tmp:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=32m'])
    services = {'worker':worker}
    for role in ('mcp','provider'):
        services[role+'-edge'] = dict(common, image=config['edge_image'],
            entrypoint=['python3','/opt/pool/edge.py'],
            command=['--role',role,'--listen','0.0.0.0','--port','8080',
                     '--control','/control/manage.sock','--target', '${'+role.upper()+'_FIXED_TARGET:?fixed TLS destination required}',
                     '--model',config['model']] + (['--key-file','/secret/provider-key'] if role=='provider' else []),
            environment={'PYTHONDONTWRITEBYTECODE':'1','HOME':'/tmp'},
            tmpfs=['/tmp:rw,noexec,nosuid,nodev,uid=10001,gid=10001,size=32m'],
            # Trusted host provisioning directory only. Worker never receives these mounts.
            volumes=[{'type':'bind','source':'/run/'+name+'/'+role,'target':'/control'}] +
                    ([{'type':'bind','source':'/run/'+name+'/provider-key','target':'/secret/provider-key','read_only':True}]
                     if role=='provider' else []))
    compose = {'name':name, 'services':services, 'networks':{'slot':{'name':name,
        'internal':True,'labels':labels,'driver':'bridge','enable_ipv6':False,
        'ipam':{'config':[{'subnet':str(subnet)}]}}}}
    # These are proposals, not executable admission receipts. Create stays stopped.
    commands = [['docker','compose','--project-name',name,'--file',name+'.json','create','--no-build']]
    return {'verdict':'BLOCKED', 'reason':'production start disabled until host firewall and real isolation drill',
            'labels':labels,'compose':compose,'prepare_commands':commands,
            'required_before_start':['inspect image source pin and empty image HOME/no credentials',
                'verify subnet unused against host routes and ALL existing networks (read-only)',
                'apply per-slot bridge + host INPUT/FORWARD rules before any process starts',
                'connect each trusted edge to a separate fixed-destination egress namespace',
                'read back kernel firewall and container isolation; no worker control mount',
                'configure both edges with matching grant, then admit exactly one stdio owner']}


def cleanup_commands(config, inventory):
    expected = plan(config)['labels']
    if not isinstance(inventory, list): raise Rejected('inventory required')
    commands = []
    for item in inventory:
        if set(item) != {'kind','id','labels'} or item['labels'] != expected:
            raise Rejected('foreign resource or stale incarnation')
        if item['kind'] not in {'container','network'} or not re.fullmatch('[a-f0-9]{64}', item['id']):
            raise Rejected('exact full resource ID required')
        commands.append(['docker',item['kind'],'rm'] + (['--force'] if item['kind']=='container' else []) + [item['id']])
    return sorted(commands, key=lambda c: c[1] != 'container')


def control(path, message, attempt=None, deadline=None):
    # Host-only UDS. Each exchange consumes the same CLI deadline.
    deadline = min(deadline if deadline is not None else time.monotonic()+CLI_SECONDS,
                   time.monotonic() + 4.0)
    remaining(deadline)
    info = os.lstat(path)
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) not in {0o600, 0o700}:
        raise Rejected('control socket ownership or mode unsafe')
    frame = json.dumps(message).encode() + b'\n'
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(min(.5, remaining(deadline))); sock.connect(path)
        send_deadline = min(deadline, time.monotonic() + .25)
        offset = 0
        while offset < len(frame):
            sock.settimeout(remaining(send_deadline))
            # Even the first failing send cannot prove that nothing was sent.
            if attempt is not None: attempt['control_state'] = 'UNKNOWN'
            sent = sock.send(frame[offset:])
            if sent <= 0: raise Rejected('control send failed')
            offset += sent
        remaining(send_deadline)
        raw = bytearray()
        while not raw.endswith(b'\n'):
            sock.settimeout(remaining(deadline))
            chunk = sock.recv(MAX + 1 - len(raw))
            if not chunk: raise Rejected('invalid receipt')
            raw.extend(chunk)
            if len(raw) > MAX: raise Rejected('invalid receipt')
        result = strict_json(raw)
        remaining(deadline)
        # Closed schema/type/state checks are performed on both observations.
        return result


PUBLIC_FIELDS = FIELDS - {'capability'}
STATE_FIELDS = {'ok', 'role', 'ready', 'revoked', 'spent'}


def verify_receipt(result, expected, role, revoked):
    if not isinstance(result, dict) or set(result) != PUBLIC_FIELDS | STATE_FIELDS:
        raise Rejected('control receipt fields mismatch')
    if any(type(result[k]) is not bool for k in ('ok', 'ready', 'revoked', 'spent')):
        raise Rejected('control receipt boolean type mismatch')
    if result['ok'] is not True:
        raise Rejected('control operation rejected')
    if result['role'] != role:
        raise Rejected('control edge role mismatch')
    if any(type(result[k]) is not type(expected[k]) or result[k] != expected[k]
           for k in PUBLIC_FIELDS):
        raise Rejected('control binding identity mismatch')
    if result['ready'] is not (not revoked) or result['revoked'] is not revoked or result['spent'] is not revoked:
        raise Rejected('control readback state mismatch')
    # Active receipts recheck time; exact revocation evidence survives expiry.
    validate_public({k: result[k] for k in PUBLIC_FIELDS}, active=not revoked)


def control_intent(payload):
    if not isinstance(payload, dict):
        raise Rejected('control intent object required')
    op = payload.get('op')
    if op == 'configure' and set(payload) == {'op', 'config'}:
        config = validate(payload['config'])
        return payload, {k: config[k] for k in PUBLIC_FIELDS}, False
    if op in ('status', 'revoke'):
        fields = {'op', 'expected'} | ({'incarnation'} if op == 'revoke' else set())
        if set(payload) == fields:
            expected = validate_public(payload['expected'], active=op != 'revoke')
            message = {'op':op}
            if op == 'revoke':
                if type(payload['incarnation']) is not str or payload['incarnation'] != expected['incarnation']:
                    raise Rejected('control revoke incarnation mismatch')
                message['incarnation'] = payload['incarnation']
                message['expected'] = expected
            return message, expected, op == 'revoke'
    raise Rejected('control intent fields mismatch')


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    render = commands.add_parser('plan'); render.add_argument('--config', required=True)
    clean = commands.add_parser('cleanup'); clean.add_argument('--config', required=True)
    clean.add_argument('--inventory', required=True); clean.add_argument('--dry-run', action='store_true', required=True)
    ctl = commands.add_parser('control'); ctl.add_argument('--socket', required=True)
    ctl.add_argument('--role', choices=['mcp','provider'], required=True)
    rec = commands.add_parser('reconcile'); rec.add_argument('--socket', required=True)
    rec.add_argument('--role', choices=['mcp','provider'], required=True)
    commands.add_parser('apply')
    args = parser.parse_args()
    attempt = {'control_state':'NOT_SENT', 'quarantine_required':True} if args.command in ('control', 'reconcile') else {}
    deadline = time.monotonic() + CLI_SECONDS
    try:
        if args.command == 'apply':
            print(json.dumps({'verdict':'BLOCKED','reason':'real Docker operations not implemented/authorized in this slice'})); return 2
        if args.command == 'reconcile':
            intent = read_input(deadline)
            if not isinstance(intent, dict) or set(intent) != {'operation', 'expected'} or intent['operation'] not in ('configure', 'revoke'):
                raise Rejected('reconcile intent fields mismatch')
            expected = validate_public(intent['expected'], active=False)
            revoked = intent['operation'] == 'revoke'
            first = control(args.socket, {'op':'status'}, attempt, deadline)
            second = control(args.socket, {'op':'status'}, attempt, deadline)
            verify_receipt(first, expected, args.role, revoked)
            verify_receipt(second, expected, args.role, revoked)
            result = dict(control_state='VERIFIED', verified_state='REVOKED' if revoked else 'ACTIVE',
                          admission_allowed=False, reuse_allowed=False, readback=second)
            return 0 if write_output(result, deadline) else 1
        if args.command == 'control':
            payload, expected, revoked = control_intent(read_input(deadline))
            if payload['op'] in ('configure', 'revoke'):
                payload = dict(payload, role=args.role)
            receipt = control(args.socket, payload, attempt, deadline)
            # Read back exact target after mutation; no retries on uncertainty.
            status = control(args.socket, {'op':'status'}, attempt, deadline)
            verify_receipt(receipt, expected, args.role, revoked)
            verify_receipt(status, expected, args.role, revoked)
            receipt['readback'] = status
            return 0 if write_output(receipt, deadline) else 1
        config = strict_json(pathlib.Path(args.config).read_bytes())
        if args.command == 'plan': result = plan(config)
        else: result = {'dry_run':True,'commands':cleanup_commands(config, strict_json(pathlib.Path(args.inventory).read_bytes()))}
        print(json.dumps(result, indent=2)); return 0
    except Rejected as error:
        # Rejected messages are fixed local diagnostics, never peer/input text.
        result = dict(verdict='FAIL', reason=str(error), **attempt)
    except (ValueError, OSError, TypeError, KeyError, RecursionError):
        result = dict(verdict='FAIL', reason='invalid configuration, receipt, or ownership', **attempt)
    if attempt:
        write_output(result, deadline)
    else:
        print(json.dumps(result))
    return 1

if __name__ == '__main__': raise SystemExit(main())
