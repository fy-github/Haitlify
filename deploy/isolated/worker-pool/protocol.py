"""New application protocol, NOT a Hermes RPC. Control socket is never worker-visible."""
import re
import threading
import time

class Rejected(ValueError):
    pass

FIELDS = {'protocol', 'binding', 'generation', 'lease', 'incarnation', 'policy', 'expiry', 'capability'}

def validate_public(config, *, active=True):
    if not isinstance(config, dict) or set(config) != FIELDS - {'capability'}:
        raise Rejected('exact public identity fields required')
    if config['protocol'] != 'haitlify.worker.configure.v1' or config['policy'] != 'mcp-three-v1':
        raise Rejected('unsupported protocol or policy')
    for key in ('binding', 'generation', 'incarnation'):
        if not isinstance(config[key], str) or not re.fullmatch('[a-zA-Z0-9-]{1,64}', config[key]):
            raise Rejected('invalid identity')
    if type(config['lease']) is not int or config['lease'] < 1:
        raise Rejected('invalid lease')
    if type(config['expiry']) is not int or config['expiry'] < 1:
        raise Rejected('invalid expiry')
    if active:
        now = time.time()
        if not now < config['expiry'] <= now + 3600:
            raise Rejected('invalid expiry')
    return dict(config)


def validate(config):
    if not isinstance(config, dict) or set(config) != FIELDS:
        raise Rejected('exact configure fields required')
    validate_public({k: v for k, v in config.items() if k != 'capability'})
    if not isinstance(config['capability'], str) or not re.fullmatch('[A-Za-z0-9._~-]{24,4096}', config['capability']):
        raise Rejected('invalid capability')
    return dict(config)

class Lease:
    def __init__(self):
        self.lock = threading.RLock()
        self.config = None
        self.spent = False

    def configure(self, config):
        with self.lock:
            if self.config is not None or self.spent:
                raise Rejected('single-use incarnation; recreate after cleanup')
            self.config = validate(config)
            return {k: v for k, v in self.config.items() if k != 'capability'}

    def status(self):
        with self.lock:
            public = {k: self.config[k] if self.config else None
                      for k in FIELDS - {'capability'}}
            return dict(public, ready=bool(self.config and not self.spent and
                                          self.config['expiry'] > time.time()),
                        revoked=self.spent, spent=self.spent)

    def active(self):
        with self.lock:
            if self.config is None or self.spent or self.config['expiry'] <= time.time():
                raise Rejected('lease inactive')
            return dict(self.config)

    def revoke(self, expected):
        with self.lock:
            expected = validate_public(expected, active=False)
            if self.config is None or any(
                    type(expected[k]) is not type(self.config[k]) or expected[k] != self.config[k]
                    for k in FIELDS - {'capability'}):
                raise Rejected('stale lease identity')
            self.spent = True
            self.config['capability'] = ''
