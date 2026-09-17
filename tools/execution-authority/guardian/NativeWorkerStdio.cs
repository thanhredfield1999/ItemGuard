using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace ItemGuard.Execution {
    // Owns six anonymous-pipe endpoints for one future worker. It creates no process:
    // only the selected child endpoints remain inheritable for CreateProcessAsUserW.
    public sealed class NativeWorkerStdio : IDisposable {
        const uint HandleFlagInherit = 0x00000001;
        [StructLayout(LayoutKind.Sequential)] struct SecurityAttributes {
            public int Length;
            public IntPtr SecurityDescriptor;
            [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
        }

        IntPtr guardianStdin, childStdin, guardianStdout, childStdout, guardianStderr, childStderr;
        public IntPtr GuardianStdin { get { ThrowIfDisposed(); return guardianStdin; } }
        public IntPtr ChildStdin { get { ThrowIfDisposed(); return childStdin; } }
        public IntPtr GuardianStdout { get { ThrowIfDisposed(); return guardianStdout; } }
        public IntPtr ChildStdout { get { ThrowIfDisposed(); return childStdout; } }
        public IntPtr GuardianStderr { get { ThrowIfDisposed(); return guardianStderr; } }
        public IntPtr ChildStderr { get { ThrowIfDisposed(); return childStderr; } }
        bool disposed;

        NativeWorkerStdio() { }

        public static NativeWorkerStdio Create() {
            var result = new NativeWorkerStdio();
            var attributes = new SecurityAttributes {
                Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                SecurityDescriptor = IntPtr.Zero,
                InheritHandle = true
            };
            IntPtr memory = Marshal.AllocHGlobal(attributes.Length);
            try {
                Marshal.StructureToPtr(attributes, memory, false);
                // guardian writes stdin; child reads it
                CreateOrThrow(out result.childStdin, out result.guardianStdin, memory);
                // child writes stdout/stderr; guardian reads them
                CreateOrThrow(out result.guardianStdout, out result.childStdout, memory);
                CreateOrThrow(out result.guardianStderr, out result.childStderr, memory);
                ClearInheritanceOrThrow(result.guardianStdin);
                ClearInheritanceOrThrow(result.guardianStdout);
                ClearInheritanceOrThrow(result.guardianStderr);
                return result;
            } catch {
                result.Dispose();
                throw;
            } finally {
                Marshal.FreeHGlobal(memory);
            }
        }

        public bool ChildEndpointsInheritable() {
            ThrowIfDisposed();
            return IsInheritable(childStdin) && IsInheritable(childStdout) && IsInheritable(childStderr);
        }

        public bool GuardianEndpointsNonInheritable() {
            ThrowIfDisposed();
            return !IsInheritable(guardianStdin) && !IsInheritable(guardianStdout) && !IsInheritable(guardianStderr);
        }

        public bool HasDistinctEndpoints() {
            ThrowIfDisposed();
            return guardianStdin != childStdin && guardianStdout != childStdout && guardianStderr != childStderr
                && guardianStdin != guardianStdout && guardianStdin != guardianStderr
                && childStdin != childStdout && childStdin != childStderr
                && guardianStdout != guardianStderr && childStdout != childStderr;
        }

        // Call only after CreateProcessAsUserW has duplicated the three inheritable
        // child endpoints. Keeping them open in the guardian would hide EOF.
        public void SealAfterCreate() {
            ThrowIfDisposed();
            Close(ref childStdin); Close(ref childStdout); Close(ref childStderr);
        }

        static void CreateOrThrow(out IntPtr read, out IntPtr write, IntPtr attributes) {
            if (!CreatePipe(out read, out write, attributes, 0))
                throw Failure("CreatePipe");
        }

        static void ClearInheritanceOrThrow(IntPtr handle) {
            if (!SetHandleInformation(handle, HandleFlagInherit, 0))
                throw Failure("SetHandleInformation");
        }

        static bool IsInheritable(IntPtr handle) {
            uint flags;
            if (!GetHandleInformation(handle, out flags)) throw Failure("GetHandleInformation");
            return (flags & HandleFlagInherit) != 0;
        }

        void ThrowIfDisposed() {
            if (disposed) throw new ObjectDisposedException("NativeWorkerStdio");
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            Close(ref guardianStdin); Close(ref childStdin);
            Close(ref guardianStdout); Close(ref childStdout);
            Close(ref guardianStderr); Close(ref childStderr);
        }

        static void Close(ref IntPtr handle) {
            if (handle == IntPtr.Zero) return;
            IntPtr closing = handle; handle = IntPtr.Zero;
            CloseHandle(closing);
        }

        static Exception Failure(string operation) {
            return new InvalidOperationException(operation + " failed", new Win32Exception(Marshal.GetLastWin32Error()));
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CreatePipe(out IntPtr readPipe, out IntPtr writePipe, IntPtr attributes, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetHandleInformation(IntPtr handle, out uint flags);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr handle);
    }
}
