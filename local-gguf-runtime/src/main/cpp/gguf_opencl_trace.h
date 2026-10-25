#pragma once
#include <CL/cl.h>

// The optional hook is implemented by the owning JNI library. This header is
// injected only into the pinned OpenCL backend, after its OpenCL declarations.
extern "C" void gptmobile_opencl_trace(const char *, bool, int) __attribute__((weak));
inline void gguf_cl_trace(const char * api, bool before, int code = 0) {
    if (gptmobile_opencl_trace) gptmobile_opencl_trace(api, before, code);
}
inline cl_int gguf_clGetPlatformIDs(cl_uint n, cl_platform_id * ids, cl_uint * count) {
    gguf_cl_trace("clGetPlatformIDs", true); auto r = clGetPlatformIDs(n, ids, count); gguf_cl_trace("clGetPlatformIDs", false, r); return r;
}
inline cl_int gguf_clGetDeviceIDs(cl_platform_id p, cl_device_type t, cl_uint n, cl_device_id * ids, cl_uint * count) {
    gguf_cl_trace("clGetDeviceIDs", true); auto r = clGetDeviceIDs(p, t, n, ids, count); gguf_cl_trace("clGetDeviceIDs", false, r); return r;
}
inline cl_int gguf_clGetDeviceInfo(cl_device_id d, cl_device_info p, size_t n, void * v, size_t * size) {
    gguf_cl_trace("clGetDeviceInfo", true); auto r = clGetDeviceInfo(d, p, n, v, size); gguf_cl_trace("clGetDeviceInfo", false, r); return r;
}
inline cl_context gguf_clCreateContext(const cl_context_properties * p, cl_uint n, const cl_device_id * d, void (CL_CALLBACK * notify)(const char *, const void *, size_t, void *), void * user, cl_int * error) {
    cl_int status = CL_SUCCESS; gguf_cl_trace("clCreateContext", true); auto r = clCreateContext(p, n, d, notify, user, &status); if (error) *error = status; gguf_cl_trace("clCreateContext", false, status); return r;
}
inline cl_command_queue gguf_clCreateCommandQueue(cl_context c, cl_device_id d, cl_command_queue_properties p, cl_int * error) {
    cl_int status = CL_SUCCESS; gguf_cl_trace("clCreateCommandQueue", true); auto r = clCreateCommandQueue(c, d, p, &status); if (error) *error = status; gguf_cl_trace("clCreateCommandQueue", false, status); return r;
}
inline cl_program gguf_clCreateProgramWithSource(cl_context c, cl_uint n, const char ** text, const size_t * lengths, cl_int * error) {
    cl_int status = CL_SUCCESS; gguf_cl_trace("clCreateProgramWithSource", true); auto r = clCreateProgramWithSource(c, n, text, lengths, &status); if (error) *error = status; gguf_cl_trace("clCreateProgramWithSource", false, status); return r;
}
inline cl_program gguf_clCreateProgramWithBinary(cl_context c, cl_uint n, const cl_device_id * d, const size_t * lengths, const unsigned char ** bytes, cl_int * binaryStatus, cl_int * error) {
    cl_int status = CL_SUCCESS; gguf_cl_trace("clCreateProgramWithBinary", true); auto r = clCreateProgramWithBinary(c, n, d, lengths, bytes, binaryStatus, &status); if (error) *error = status; gguf_cl_trace("clCreateProgramWithBinary", false, status); return r;
}
inline cl_int gguf_clBuildProgram(cl_program p, cl_uint n, const cl_device_id * d, const char * options, void (CL_CALLBACK * notify)(cl_program, void *), void * user) {
    gguf_cl_trace("clBuildProgram", true); auto r = clBuildProgram(p, n, d, options, notify, user); gguf_cl_trace("clBuildProgram", false, r); return r;
}
#define clGetPlatformIDs gguf_clGetPlatformIDs
#define clGetDeviceIDs gguf_clGetDeviceIDs
#define clGetDeviceInfo gguf_clGetDeviceInfo
#define clCreateContext gguf_clCreateContext
#define clCreateCommandQueue gguf_clCreateCommandQueue
#define clCreateProgramWithSource gguf_clCreateProgramWithSource
#define clCreateProgramWithBinary gguf_clCreateProgramWithBinary
#define clBuildProgram gguf_clBuildProgram
