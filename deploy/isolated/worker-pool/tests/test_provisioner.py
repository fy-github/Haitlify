import json
import pathlib
import sys
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

class ProvisionerTests(unittest.TestCase):
    def test_plan_scoped_isolation_and_fenced_cleanup(self):
        self.assertTrue((pathlib.Path(__file__).resolve().parents[1] / 'provisioner.py').exists(), 'provisioner missing')
        from provisioner import plan, cleanup_commands, Rejected
        config = dict(slot='slot01', incarnation='abc123', subnet='10.221.1.0/24',
                      worker_image='local/hermes@sha256:' + 'a'*64,
                      edge_image='local/edge@sha256:' + 'b'*64, model='fixture')
        result = plan(config)
        worker = result['compose']['services']['worker']
        self.assertEqual(worker['networks'], ['slot'])
        self.assertTrue(result['compose']['networks']['slot']['internal'])
        self.assertTrue(worker['read_only'])
        self.assertEqual(worker['cap_drop'], ['ALL'])
        self.assertEqual(worker['restart'], 'no')
        self.assertEqual(worker['volumes'], [])
        self.assertNotIn('docker.sock', json.dumps(result['compose']))
        self.assertNotIn('capability', json.dumps(worker))
        self.assertEqual(len(result['compose']['services']), 3)
        # Dry-run creates stopped resources only; NEVER a start/apply escape hatch.
        self.assertTrue(all('start' not in c and 'run' not in c and 'prune' not in c for c in result['prepare_commands']))
        exact = {'kind':'container','id':'a'*64,'labels':result['labels']}
        cmds = cleanup_commands(config, [exact])
        self.assertEqual(cmds, [['docker','container','rm','--force','a'*64]])
        for labels in ({}, dict(result['labels'], incarnation='old'), dict(result['labels'], pool='legacy')):
            with self.assertRaises(Rejected): cleanup_commands(config, [dict(exact, labels=labels)])
        for key, value in [('slot','../../legacy'),('incarnation','a;rm'),('worker_image','hermes:latest'),('subnet','127.0.0.0/24')]:
            with self.subTest(key=key), self.assertRaises(Rejected): plan(dict(config, **{key:value}))

    def test_cli_plan_cleanup_dry_run_and_apply_blocked(self):
        import subprocess
        import tempfile
        from provisioner import plan
        root = pathlib.Path(__file__).resolve().parents[1]
        config = json.loads((root/'pool.example.json').read_text())
        labels = plan(config)['labels']
        with tempfile.TemporaryDirectory(dir='/Users/lify/.hermes/cache/scratch') as tmp:
            inv = pathlib.Path(tmp)/'inventory.json'
            inv.write_text(json.dumps([{'kind':'container','id':'a'*64,'labels':labels}]))
            args = [sys.executable,str(root/'provisioner.py')]
            completed = subprocess.run(args+['cleanup','--config',str(root/'pool.example.json'),
                '--inventory',str(inv),'--dry-run'],capture_output=True,text=True,timeout=5)
            self.assertEqual(completed.returncode,0,completed.stderr)
            receipt = json.loads(completed.stdout)
            self.assertTrue(receipt['dry_run'])
            self.assertEqual(receipt['commands'][0][-1], 'a'*64)
            blocked = subprocess.run(args+['apply'],capture_output=True,text=True,timeout=5)
            self.assertEqual(blocked.returncode,2)
            self.assertEqual(json.loads(blocked.stdout)['verdict'],'BLOCKED')

if __name__ == '__main__': unittest.main()
