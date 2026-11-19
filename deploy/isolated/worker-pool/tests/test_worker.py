import pathlib
import sys
import unittest
from unittest.mock import Mock
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

class WorkerTests(unittest.TestCase):
    def test_health_does_not_spawn_closed_rpc_and_disconnect_spent(self):
        self.assertTrue((pathlib.Path(__file__).resolve().parents[1] / 'worker.py').exists(), 'worker entry absent')
        from worker import Session, Denied
        from gate import EXACT_TOOLS
        tools = [{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]
        agent = Mock(tools=tools, valid_tool_names=EXACT_TOOLS)
        factory = Mock(return_value=agent)
        session = Session(factory, 'abc123')
        self.assertFalse(session.handle('health', {})['ready'])
        factory.assert_not_called()
        for method, params in [('configure',{}), ('reload.mcp',{}), ('session.create',{'cwd':'/host'}),
                               ('session.create',{'model':'other'}), ('session.resume',{'session_id':'old'})]:
            with self.subTest(method=method), self.assertRaises(Denied): session.handle(method,params)
        created = session.handle('session.create', {})
        factory.assert_called_once_with()
        self.assertTrue(session.handle('health', {})['ready'])
        sid = created['session_id']
        with self.assertRaises(Denied): session.handle('prompt.submit', {'session_id':sid,'text':'x','tools':['terminal']})
        with self.assertRaises(Denied): session.handle('prompt.submit', {'session_id':'stale','text':'x'})
        self.assertEqual(session.handle('session.interrupt', {'session_id':sid}), {'status':'interrupted'})
        session.disconnect()
        with self.assertRaises(Denied): session.handle('session.create', {})
        self.assertFalse(session.handle('health', {})['ready'])
        factory.assert_called_once_with()

if __name__ == '__main__': unittest.main()
