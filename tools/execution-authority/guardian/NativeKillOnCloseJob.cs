using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace ItemGuard.Execution {
    // Host-only job capability for an owned, disposable child; not a guardian entrypoint.
    public sealed class NativeKillOnCloseJob : IDisposable {
        const uint JobObjectExtendedLimitInformationClass = 9;
        const uint JobObjectLimitActiveProcess = 0x00000008;
        const uint JobObjectLimitKillOnJobClose = 0x00002000;
        IntPtr handle;
        bool disposed;
        public uint ActiveProcessLimit { get; private set; }
        internal IntPtr Handle {
            get {
                if (disposed || handle == IntPtr.Zero) throw new ObjectDisposedException("NativeKillOnCloseJob");
                return handle;
            }
        }

        NativeKillOnCloseJob(IntPtr handle, uint activeProcessLimit) {
            this.handle = handle;
            ActiveProcessLimit = activeProcessLimit;
        }

        public static NativeKillOnCloseJob Create() {
            IntPtr handle = CreateJobObject(IntPtr.Zero, null);
            if (handle == IntPtr.Zero) throw Failure("CreateJobObject");
            try {
                var limits = new JobObjectExtendedLimitInformation();
                limits.BasicLimitInformation.LimitFlags = JobObjectLimitActiveProcess | JobObjectLimitKillOnJobClose;
                limits.BasicLimitInformation.ActiveProcessLimit = 1;
                int size = Marshal.SizeOf(typeof(JobObjectExtendedLimitInformation));
                IntPtr buffer = Marshal.AllocHGlobal(size);
                try {
                    Marshal.StructureToPtr(limits, buffer, false);
                    if (!SetInformationJobObject(handle, JobObjectExtendedLimitInformationClass, buffer, (uint)size))
                        throw Failure("SetInformationJobObject");
                } finally { Marshal.FreeHGlobal(buffer); }
                return new NativeKillOnCloseJob(handle, 1);
            } catch { CloseHandle(handle); throw; }
        }

        public void Assign(Process process) {
            if (process == null) throw new ArgumentNullException("process");
            if (disposed) throw new ObjectDisposedException("NativeKillOnCloseJob");
            if (!AssignProcessToJobObject(handle, process.Handle)) throw Failure("AssignProcessToJobObject");
        }
        internal void AssignProcessHandle(IntPtr processHandle) {
            if (disposed) throw new ObjectDisposedException("NativeKillOnCloseJob");
            if (!AssignProcessToJobObject(handle, processHandle)) throw Failure("AssignProcessToJobObject");
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            IntPtr closing = handle; handle = IntPtr.Zero;
            if (closing != IntPtr.Zero) CloseHandle(closing);
        }

        static Exception Failure(string operation) {
            return new InvalidOperationException(operation + " failed", new Win32Exception(Marshal.GetLastWin32Error()));
        }

        [StructLayout(LayoutKind.Sequential)] struct JobObjectBasicLimitInformation {
            public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)] struct IoCounters {
            public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
        }
        [StructLayout(LayoutKind.Sequential)] struct JobObjectExtendedLimitInformation {
            public JobObjectBasicLimitInformation BasicLimitInformation;
            public IoCounters IoInfo;
            public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, uint informationClass, IntPtr information, uint length);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr handle);
    }
}
