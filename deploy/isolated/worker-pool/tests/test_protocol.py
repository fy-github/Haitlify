import copy
import pathlib
import sys
import time
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


def grant():
    return dict(protocol='haitlify.worker.configure.v1', binding='binding-a', generation='generation-a',
                lease=1, incarnation='abc123', policy='mcp-three-v1', expiry=int(time.time()) + 60,
                capability='TEST-CAPABILITY-NOT-A-REAL-TOKEN')


class ProtocolTests(unittest.TestCase):
    def test_closed_config_and_fencing(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        self.assertTrue((root / 'protocol.py').exists(), 'configure protocol missing')
        from protocol import Lease, Rejected
        lease = Lease()
        with self.assertRaises(Rejected): lease.active()
        original = grant()
        public = lease.configure(original)
        self.assertNotIn('capability', public)
        self.assertEqual(public['binding'], original['binding'])
        for key, value in [('lease', True), ('expiry', 0), ('policy', 'typo'), ('extra', 'x')]:
            bad = grant(); bad[key] = value
            with self.subTest(key=key), self.assertRaises(Rejected): Lease().configure(bad)
        with self.assertRaises(Rejected): lease.configure(grant())
        with self.assertRaises(Rejected): lease.revoke(dict(public, incarnation='stale'))
        lease.revoke(public)
        with self.assertRaises(Rejected): lease.active()
        with self.assertRaises(Rejected): lease.configure(grant())

if __name__ == '__main__': unittest.main()
