"""No model/network fixture. Output explicitly synthetic, only lifecycle evidence."""
import os
import time
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gate import EXACT_TOOLS

class Agent:
    tools = [{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]
    valid_tool_names = EXACT_TOOLS
    def run_conversation(self, text):
        if text == 'block':
            while True: time.sleep(.05)
        if text == 'exception': raise RuntimeError('must-not-leak')
        if text in ('astral','cjk','surrogate'):
            return {'completed':True,'final_response':{'astral':'\U0001f642','cjk':'中','surrogate':'\ud800'}[text]*65536}
        return {'completed':True,'final_response':'fixture-only'}

def factory(): return Agent()

def home_config_factory():
    """Real-entry preflight seam: the child may load only a fresh generated home."""
    import json
    home = Path(os.environ['HERMES_HOME'])
    config = json.loads((home / 'config.yaml').read_text())
    if config != {'tools': {'tool_search': {'enabled': 'off'}},
                 'platform_toolsets': {'cli': ['mcp-haitlify']},
                 'compression': {'enabled': False},
                 'agent': {'environment_probe': False}}:
        raise RuntimeError('invalid generated home')
    return Agent()

def missing_holding():
    # Private child seam: successful candidate, then actual EOF before holding.
    import __main__
    original=__main__.write
    def write(fd,data,deadline):
        if b'"kind":"holding"' in data: os._exit(23)
        return original(fd,data,deadline)
    __main__.write=write
    return Agent()

def publication_server(directory, mode):
    """Real HELD barrier; private instrumentation only, never a timing guess."""
    import json
    import worker_lifecycle as lc
    import worker_bootstrap as wb
    d = Path(directory)
    def barrier(name):
        (d/(name+'.ready')).touch()
        end = time.monotonic()+5
        while not (d/(name+'.go')).exists():
            if time.monotonic() >= end: raise RuntimeError('fixture barrier timeout')
            time.sleep(.001)
    receive = lc.Owner.receive
    def held(self):
        result = receive(self)
        if self.phase == 'held':
            (d/'owned.json').write_text(json.dumps({'pid':self.pid,'candidate':self.candidate}))
            barrier('held')
        return result
    lc.Owner.receive = held
    cleanup = lc.Owner.cleanup
    def cleaned(self):
        result = cleanup(self)
        (d/'cleanup.json').write_text(json.dumps({'state':result,'fds':self.ledger.fds,
            'record':self.exit_record,'pid':self.pid,'events':self.events}))
        if mode == 'kernel':
            original_read = lc.os.read
            limit = int((d/'readlimit').read_text())
            def read(fd, size):
                return original_read(fd, min(size,limit) if fd==0 else size)
            lc.os.read = read
        if mode == 'backpressure':
            # Fill the actual nonblocking stdout pipe, not a mocked write.
            count = 0
            while True:
                try: count += os.write(1,b' '*4096)
                except BlockingIOError: break
            (d/'filler.json').write_text(json.dumps(count))
        return result
    lc.Owner.cleanup = cleaned
    original_write = wb.write
    def write(fd, data, deadline):
        if b'"id":1000,' in data:
            now = time.monotonic_ns()
            (d/'write.json').write_text(json.dumps({'now':now,'deadline':deadline}))
            if mode in ('time','slowdrip'):
                result = original_write(fd,data,deadline)
                barrier('write')
                # Expire the real loop deadline after a complete response write.
                target = now+30_000_000
                while time.monotonic_ns() < target: time.sleep(.001)
                return result
        if mode == 'published' and b'"method":"event"' in data: barrier('published')
        return original_write(fd,data,deadline)
    wb.write = write
    return lc.serve('worker_lifecycle_fixture:factory',
                    [str(Path(__file__).resolve().parents[1]),str(Path(__file__).resolve().parent)])


if __name__ == '__main__':
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from worker_lifecycle import serve
    raise SystemExit(serve('worker_lifecycle_fixture:factory', [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parent)]))
