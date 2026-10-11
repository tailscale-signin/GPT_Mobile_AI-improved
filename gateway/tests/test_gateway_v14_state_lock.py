import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from v14.state_lock import StateDirectoryLock


class StateDirectoryLockTests(unittest.TestCase):
    def test_lock_is_held_for_lifetime_and_released_after_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "gateway.sqlite3")
            lock = StateDirectoryLock(database)
            lock.acquire()
            try:
                result = self._child_try_lock(database)
                self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
            finally:
                lock.release()
            result = self._child_try_lock(database)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @staticmethod
    def _child_try_lock(database):
        script = """import sys
from v14.state_lock import StateDirectoryAlreadyOwned, StateDirectoryLock
lock = StateDirectoryLock(sys.argv[1])
try:
    lock.acquire()
except StateDirectoryAlreadyOwned:
    raise SystemExit(23)
else:
    lock.release()
"""
        return subprocess.run(
            [sys.executable, "-c", script, database],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True,
            text=True,
            timeout=10,
        )


if __name__ == "__main__":
    unittest.main()
