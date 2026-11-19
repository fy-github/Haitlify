#!/usr/bin/env python3
"""Private stdlib-only -I -S -B bootstrap. No application import before permit."""
import json
import os
import re
import select
import sys
import time

MAX_FRAME = 1048576
MAX_TEXT = 65536

class ProtocolError(ValueError):
    pass

def strict(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: raise ProtocolError('duplicate')
            result[key] = value
        return result
    def reject(value): raise ProtocolError('nonfinite')
    try:
        return json.loads(data.decode('utf-8'), object_pairs_hook=pairs, parse_constant=reject)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise ProtocolError('json') from exc

def encode(value, limit=MAX_FRAME):
    data = (json.dumps(value, ensure_ascii=True, separators=(',', ':'), allow_nan=False)+'\n').encode('utf-8')
    if len(data) > limit: raise ProtocolError('size')
    return data

def write(fd, data, deadline):
    offset = 0
    while offset < len(data):
        remaining = (deadline-time.monotonic_ns()) / 1e9
        if remaining <= 0: raise ProtocolError('deadline')
        try:
            if not select.select([], [fd], [], remaining)[1]: continue
            count = os.write(fd, data[offset:])
            if count == 0: raise ProtocolError('zero-write')
            offset += count
        except (BlockingIOError, InterruptedError): continue

def read_one(fd, deadline, limit):
    data = b''
    while True:
        remaining = (deadline-time.monotonic_ns())/1e9
        if remaining <= 0: raise ProtocolError('deadline')
        try:
            if not select.select([fd], [], [], remaining)[0]: continue
            part = os.read(fd, min(65536, limit+1-len(data)))
        except (BlockingIOError, InterruptedError): continue
        if not part: raise ProtocolError('eof')
        data += part
        if len(data) > limit: raise ProtocolError('size')
        if b'\n' in data:
            line, tail = data.split(b'\n', 1)
            if tail: raise ProtocolError('trailing')
            return strict(line)

def control(value, kind, generation):
    return (type(value) is dict and set(value) == {'kind','generation'}
            and value['kind'] == kind and value['generation'] == generation)

def payload_valid(value):
    if type(value) is not dict or set(value) != {'status','text'}: return False
    status, text = value['status'], value['text']
    if type(text) is not str: return False
    return ((status == 'complete' and bool(text.strip()) and len(text) <= MAX_TEXT)
            or (status in ('error','interrupted') and text == ''))

def child(argv):
    ref, generation, ds, de, roots_json = argv
    ds, de = int(ds), int(de)
    if not (sys.flags.isolated and sys.flags.no_site and sys.flags.dont_write_bytecode):
        raise ProtocolError('flags')
    if not re.fullmatch('[a-f0-9]{32}', generation) or not time.monotonic_ns() < ds <= de:
        raise ProtocolError('metadata')
    # Keep roots and factory opaque until permission is completely consumed.
    os.set_blocking(0, False)
    os.set_blocking(1, False)
    write(1, encode({'kind':'spawn_waiting','generation':generation},4095), ds)
    permit = read_one(0, ds, 4095)
    if not control(permit, 'start_factory', generation): raise ProtocolError('permit')
    if time.monotonic_ns() >= ds: raise ProtocolError('deadline')
    roots = strict(roots_json.encode())
    if type(roots) is not list or not roots or any(type(p) is not str or not os.path.isabs(p) for p in roots):
        raise ProtocolError('roots')
    output = os.dup(1)
    os.set_inheritable(output, False)
    sink = os.open(os.devnull, os.O_WRONLY)
    os.dup2(sink, 1); os.dup2(sink, 2); os.close(sink)
    sys.path[:0] = roots
    import importlib
    from worker import terminal_payload
    from gate import EXACT_TOOLS, require_tools
    module, name = ref.split(':')
    agent = getattr(importlib.import_module(module), name)()
    require_tools(agent.tools)
    if agent.valid_tool_names != EXACT_TOOLS: raise ProtocolError('tools')
    write(output, encode({'kind':'ready','generation':generation},4095), ds)
    run = read_one(0, de, MAX_FRAME)
    if (type(run) is not dict or set(run) != {'kind','generation','turn','text'}
            or run['kind'] != 'run' or run['generation'] != generation
            or type(run['turn']) is not int or run['turn'] != 1
            or type(run['text']) is not str or not run['text'].strip() or len(run['text']) > MAX_TEXT):
        raise ProtocolError('run')
    try:
        require_tools(agent.tools)
        if agent.valid_tool_names != EXACT_TOOLS: raise ProtocolError('tools')
        payload = terminal_payload(agent.run_conversation(run['text']))
    except BaseException:
        payload = {'status':'error','text':''}
    write(output, encode({'kind':'terminal_candidate','generation':generation,'turn':1,'payload':payload}), de)
    write(output, encode({'kind':'holding','generation':generation,'turn':1},4095), de)
    # A held leader never naturally returns on the successful path.
    while time.monotonic_ns() < de:
        if select.select([0], [], [], min(.05, max(0,(de-time.monotonic_ns())/1e9)))[0]:
            raise ProtocolError('hold-input-or-eof')
    raise ProtocolError('hold-deadline')

if __name__ == '__main__':
    try:
        child(sys.argv[1:])
    except BaseException:
        os._exit(23)
