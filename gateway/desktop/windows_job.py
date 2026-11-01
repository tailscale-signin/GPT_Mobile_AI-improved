"""Bind child processes to the supervisor lifetime on Windows (including MCP children)."""
import os


def create():
    if os.name != 'nt':
        return None
    import ctypes as c
    from ctypes import wintypes as w
    class Basic(c.Structure):
        _fields_ = [('PerProcessUserTimeLimit', c.c_int64), ('PerJobUserTimeLimit', c.c_int64),
                    ('LimitFlags', w.DWORD), ('MinimumWorkingSetSize', c.c_size_t),
                    ('MaximumWorkingSetSize', c.c_size_t), ('ActiveProcessLimit', w.DWORD),
                    ('Affinity', c.c_size_t), ('PriorityClass', w.DWORD), ('SchedulingClass', w.DWORD)]
    class IO(c.Structure):
        _fields_ = [(name, c.c_uint64) for name in ('ReadOperationCount','WriteOperationCount','OtherOperationCount','ReadTransferCount','WriteTransferCount','OtherTransferCount')]
    class Limits(c.Structure):
        _fields_ = [('BasicLimitInformation', Basic), ('IoInfo', IO), ('ProcessMemoryLimit', c.c_size_t),
                    ('JobMemoryLimit', c.c_size_t), ('PeakProcessMemoryUsed', c.c_size_t), ('PeakJobMemoryUsed', c.c_size_t)]
    kernel = c.WinDLL('kernel32', use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [c.c_void_p, w.LPCWSTR]
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.SetInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD]
    kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.CreateJobObjectW(None, None)
    if not handle:
        raise c.WinError(c.get_last_error())
    limits = Limits()
    limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not kernel.SetInformationJobObject(handle, 9, c.byref(limits), c.sizeof(limits)):
        kernel.CloseHandle(handle)
        raise c.WinError(c.get_last_error())
    return kernel, handle


def attach(job, child):
    if job:
        import ctypes
        kernel, handle = job
        if not kernel.AssignProcessToJobObject(handle, int(child._handle)):
            child.terminate()
            child.wait(timeout=15)
            raise ctypes.WinError(ctypes.get_last_error())


def close(job):
    if job:
        job[0].CloseHandle(job[1])
