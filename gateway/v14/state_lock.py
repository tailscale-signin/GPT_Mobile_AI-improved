"""Cross-platform exclusive ownership of one Gateway state directory."""
from __future__ import annotations

import os
from pathlib import Path


class StateDirectoryAlreadyOwned(RuntimeError):
    """Another Gateway process currently owns this state directory."""


class StateDirectoryLock:
    def __init__(self, database_path: str | os.PathLike[str]):
        path = Path(database_path).expanduser().resolve()
        self.lock_path = path.parent / ".gateway-v14.lock"
        self._stream = None

    def acquire(self) -> None:
        if self._stream is not None:
            return
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        stream = self.lock_path.open("a+b")
        try:
            stream.seek(0, os.SEEK_END)
            if stream.tell() == 0:
                stream.write(b"\0")
                stream.flush()
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                try:
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                except OSError as exc:
                    raise StateDirectoryAlreadyOwned("Another Gateway process owns this state directory") from exc
            else:
                import fcntl
                try:
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError as exc:
                    raise StateDirectoryAlreadyOwned("Another Gateway process owns this state directory") from exc
            self._stream = stream
            stream.seek(1)
            stream.truncate()
            stream.write(f"pid={os.getpid()}\n".encode("ascii"))
            stream.flush()
        except BaseException:
            stream.close()
            raise

    def release(self) -> None:
        stream, self._stream = self._stream, None
        if stream is None:
            return
        try:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        finally:
            stream.close()
