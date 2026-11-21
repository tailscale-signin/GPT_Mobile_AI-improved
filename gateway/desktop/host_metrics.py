"""Measurements collected by the desktop observer. Missing collectors yield absent values."""
from collections import deque
import ctypes
import os
import shutil
import subprocess
import time


class HostMetrics:
    def __init__(self):
        self.history = deque(maxlen=240)
        self.previous = None
        self.last_sample = None
        self.gpu = shutil.which('nvidia-smi') if os.name == 'nt' else None

    def observe(self, snapshot):
        now = time.time()
        if self.last_sample is None or now - self.last_sample >= 15:
            self.last_sample = now
            point = {'at': now, 'workers': snapshot.get('service', {}).get('activeWorkers'),
                     'queued': snapshot.get('service', {}).get('queuedWorkers'), 'requests': snapshot.get('requests'),
                     'openJobs': snapshot.get('openJobs'), 'errors': snapshot.get('errors'),
                     'tokensPerSecond': snapshot.get('usage', {}).get('tokensPerSecond')}
            if os.name == 'nt':
                self.windows(point)
            self.history.append(point)
        return list(self.history)

    def windows(self, point):
        from ctypes import wintypes
        stamps = [wintypes.FILETIME() for _ in range(3)]
        if ctypes.windll.kernel32.GetSystemTimes(*(ctypes.byref(t) for t in stamps)):
            idle, kernel, user = [(t.dwHighDateTime << 32) | t.dwLowDateTime for t in stamps]
            if self.previous:
                total = kernel + user - self.previous[1]
                if total > 0:
                    point['cpuPercent'] = round(100 * (1 - (idle - self.previous[0]) / total), 1)
            self.previous = idle, kernel + user
        class Memory(ctypes.Structure):
            _fields_ = [('length', wintypes.DWORD), ('load', wintypes.DWORD)] + [
                (name, ctypes.c_ulonglong) for name in ('total', 'available', 'page', 'freePage', 'virtual', 'freeVirtual', 'extended')]
        memory = Memory()
        memory.length = ctypes.sizeof(memory)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
            point['memoryGiB'] = round((memory.total - memory.available) / 2**30, 2)
        if self.gpu:
            try:
                output = subprocess.run([self.gpu, '--query-gpu=name,utilization.gpu,memory.used', '--format=csv,noheader,nounits'],
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=2, check=True,
                    creationflags=subprocess.CREATE_NO_WINDOW, text=True)
                point['adapters'] = []
                for line in output.stdout.splitlines()[:16]:
                    name, percent, mib = line.rsplit(',', 2)
                    point['adapters'].append({'name': name.strip(), 'gpuPercent': float(percent), 'vramMiB': float(mib)})
            except (OSError, ValueError, subprocess.SubprocessError):
                pass
