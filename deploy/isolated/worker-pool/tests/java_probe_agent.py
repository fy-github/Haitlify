import os,json,pathlib,signal,subprocess,sys,time
from gate import EXACT_TOOLS
D=pathlib.Path(os.environ['PROBE_DIR'])
def group():
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    p=subprocess.Popen([sys.executable,'-c','import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(120)'])
    (D/'pids').write_text(json.dumps([os.getpid(),p.pid]))
class Agent:
    tools=[{'type':'function','function':{'name':x}} for x in EXACT_TOOLS]
    valid_tool_names=EXACT_TOOLS
    def run_conversation(self,text):
        with (D/'calls').open('a') as f: f.write(json.dumps(text)+'\n')
        if text=='block':
            group()
            while True: time.sleep(.05)
        if text=='huge': return {'completed':True,'final_response':'界'*65536}
        if text=='exception': raise RuntimeError('SECRET sentinel')
        if text=='failed': return {'completed':True,'failed':True,'final_response':'SECRET false success'}
        if text=='error': return {'completed':True,'error':'SECRET provider','final_response':'SECRET false success'}
        if text=='partial': return {'completed':True,'partial':True,'final_response':'SECRET false success'}
        return {'completed':True,'failed':False,'partial':False,'interrupted':False,'final_response':'verified reply'}
    def interrupt(self): pass
def factory():
    (D/'leader').write_text(str(os.getpid()))
    if os.environ.get('PROBE_MODE')=='initblock':
        group()
        while True: time.sleep(.05)
    return Agent()
