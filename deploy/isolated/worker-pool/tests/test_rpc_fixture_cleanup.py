"""Regression tests for RPC fixture publication cleanup."""
import importlib.util
import os
import pathlib
import tempfile
from worker_test_paths import scratch_root
import unittest
from unittest.mock import patch


POOL = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = POOL / 'tests' / 'rpc_agent_fixture.py'


def load_fixture():
    spec = importlib.util.spec_from_file_location('rpc_agent_fixture_cleanup_target', FIXTURE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RpcFixtureCleanupTests(unittest.TestCase):
    def test_fdopen_failure_closes_raw_fd_and_removes_private_temp(self):
        fixture = load_fixture()
        with tempfile.TemporaryDirectory(dir=scratch_root()) as directory:
            target = pathlib.Path(directory) / 'pids'
            real_mkstemp = fixture.tempfile.mkstemp
            opened_fds = []

            def mkstemp(*args, **kwargs):
                fd, name = real_mkstemp(*args, **kwargs)
                opened_fds.append((fd, pathlib.Path(name)))
                return fd, name

            with patch.object(fixture.tempfile, 'mkstemp', side_effect=mkstemp), \
                    patch.object(fixture.os, 'fdopen', side_effect=OSError('fdopen failed')):
                with self.assertRaisesRegex(OSError, 'fdopen failed'):
                    fixture.publish_pid_file(target, '[333,444]')

            self.assertEqual(list(pathlib.Path(directory).iterdir()), [])
            self.assertEqual(len(opened_fds), 1)
            with self.assertRaises(OSError):
                os.fstat(opened_fds[0][0])

    def test_fdopen_failure_preserves_existing_target(self):
        fixture = load_fixture()
        with tempfile.TemporaryDirectory(dir=scratch_root()) as directory:
            target = pathlib.Path(directory) / 'pids'
            target.write_text('[111,222]')
            with patch.object(fixture.os, 'fdopen', side_effect=OSError('fdopen failed')):
                with self.assertRaisesRegex(OSError, 'fdopen failed'):
                    fixture.publish_pid_file(target, '[333,444]')
            self.assertEqual(target.read_text(), '[111,222]')
            self.assertEqual(list(pathlib.Path(directory).iterdir()), [target])

    def test_cleanup_failure_is_chained_without_masking_publish_failure(self):
        fixture = load_fixture()
        with tempfile.TemporaryDirectory(dir=scratch_root()) as directory:
            target = pathlib.Path(directory) / 'pids'
            real_unlink = pathlib.Path.unlink
            temporary = []

            def unlink(path, *args, **kwargs):
                if path != target:
                    temporary.append(path)
                    real_unlink(path, *args, **kwargs)
                    raise RuntimeError('cleanup failed')
                return real_unlink(path, *args, **kwargs)

            with patch.object(fixture.os, 'fsync', side_effect=OSError('publish failed')), \
                    patch.object(fixture.pathlib.Path, 'unlink', unlink):
                with self.assertRaisesRegex(OSError, 'publish failed') as raised:
                    fixture.publish_pid_file(target, '[333,444]')

            self.assertIsInstance(raised.exception.__cause__, RuntimeError)
            self.assertEqual(str(raised.exception.__cause__), 'cleanup failed')
            self.assertFalse(target.exists())
            self.assertEqual(len(temporary), 1)
            self.assertFalse(temporary[0].exists())


if __name__ == '__main__':
    unittest.main()
