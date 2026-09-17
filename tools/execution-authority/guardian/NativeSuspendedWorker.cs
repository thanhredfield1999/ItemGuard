using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace ItemGuard.Execution {
    // Disposable suspended worker primitive; no runtime namespace or arbitrary argv.
    public sealed class NativeSuspendedWorker : IDisposable {

        IntPtr process, thread;
        NativeWorkerStdio stdio;
        bool disposed;
        bool releaseAttempted;
        public bool IsInAssignedJob { get; private set; }
        public bool Released { get; private set; }
        // Handle inheritance is enabled only because the STARTUPINFOEX handle list
        // permits exactly the three selected stdio endpoints.
        public static bool RequiresExplicitStdioHandleList { get { return WorkerStartupPolicy.InheritsOnlyExplicitStdioHandles; } }
        public bool HasExited { get { uint code; return GetExitCodeProcess(process, out code) && code != 259; } }
        NativeSuspendedWorker(IntPtr process, IntPtr thread, bool inJob, NativeWorkerStdio stdio) {
            this.process = process; this.thread = thread; this.stdio = stdio; IsInAssignedJob = inJob;
        }

        public static NativeSuspendedWorker Create(NativeRestrictedToken token, NativeKillOnCloseJob job) {
            if (token == null || job == null) throw new ArgumentNullException();
            EnableCallerPrivilege("SeIncreaseQuotaPrivilege");
            ProcessInformation info;
            var command = new StringBuilder(WorkerLaunchPolicy.BuildExitCommandLine());
            IntPtr environment = Marshal.AllocHGlobal(4);
            NativeWorkerStdio stdio = null;
            Marshal.WriteInt16(environment, 0, 0); Marshal.WriteInt16(environment, 2, 0);
            try {
                stdio = NativeWorkerStdio.Create();
                using (var startup = WorkerStartupInfo.Create(stdio))
                using (var security = NativeWorkerSecurityAttributes.Create(token.ReadShape().UserSid)) {
                    // Descriptor memory and the explicit inheritable stdio endpoints are
                    // held through the exact native call. Attribute inheritance excludes
                    // every handle outside the three child pipe endpoints.
                    if (!CreateProcessAsUser(token.Handle, WorkerLaunchPolicy.SystemCommandProcessor, command,
                        security.ProcessAttributes, security.ThreadAttributes, true,
                        WorkerStartupPolicy.CreateFlags, environment,
                        WorkerLaunchPolicy.SystemWorkingDirectory, ref startup.Value, out info))
                        throw Failure("CreateProcessAsUserW");
                    stdio.SealAfterCreate();
                }
            } catch {
                if (stdio != null) stdio.Dispose();
                throw;
            } finally {
                Marshal.FreeHGlobal(environment);
            }
            try {
                job.AssignProcessHandle(info.hProcess);
                bool inJob;
                if (!IsProcessInJob(info.hProcess, job.Handle, out inJob) || !inJob) throw Failure("IsProcessInJob(owned job)");
                return new NativeSuspendedWorker(info.hProcess, info.hThread, true, stdio);
            } catch {
                TerminateAndWait(info.hProcess);
                stdio.Dispose();
                CloseHandle(info.hThread); CloseHandle(info.hProcess); throw;
            }
        }
        public void Release() {
            if (disposed) throw new ObjectDisposedException("NativeSuspendedWorker");
            if (Released || releaseAttempted) throw new InvalidOperationException("worker release was already attempted");
            releaseAttempted = true;
            uint previous = ResumeThread(thread);
            if (MustTerminateAfterResumeResult(previous)) {
                TerminateAndWaitBeforeFailure();
                throw Failure("ResumeThread");
            }
            Released = true;
        }
        // ResumeThread returns the prior suspend count. A guardian release is valid
        // only from the single CREATE_SUSPENDED count; zero means it was already
        // runnable and greater values leave the worker suspended.
        public static bool IsExactFirstResumeResult(uint previousSuspendCount) {
            return previousSuspendCount == 1;
        }
        // Once ResumeThread returns anything other than the sole CREATE_SUSPENDED
        // count, the worker's execution state is uncertain. Do not leave it owned
        // but alive while reporting a failed release.
        public static bool MustTerminateAfterResumeResult(uint previousSuspendCount) {
            return !IsExactFirstResumeResult(previousSuspendCount);
        }
        void TerminateAndWaitBeforeFailure() {
            TerminateAndWait(process);
        }
        static void TerminateAndWait(IntPtr targetProcess) {
            if (!TerminateProcess(targetProcess, 1)) throw Failure("TerminateProcess");
            if (WaitForSingleObject(targetProcess, 5000) != 0) throw Failure("WaitForSingleObject");
        }
        public int WaitForExit(int milliseconds) {
            if (WaitForSingleObject(process, (uint)milliseconds) != 0) throw new TimeoutException("worker did not exit");
            uint code; if (!GetExitCodeProcess(process, out code)) throw Failure("GetExitCodeProcess");
            return unchecked((int)code);
        }
        public void Dispose() {
            if (disposed) return;
            disposed = true;
            if (stdio != null) { stdio.Dispose(); stdio = null; }
            if (thread != IntPtr.Zero) CloseHandle(thread);
            if (process != IntPtr.Zero) CloseHandle(process);
            thread = process = IntPtr.Zero;
        }
        static Exception Failure(string op) { return new InvalidOperationException(op + " failed", new Win32Exception(Marshal.GetLastWin32Error())); }
        static void EnableCallerPrivilege(string name) {
            IntPtr token;
            if (!NativeTokenShape.OpenProcessToken(NativeTokenShape.GetCurrentProcess(), 0x0008 | 0x0020, out token)) throw Failure("OpenProcessToken(adjust)");
            try {
                Luid luid; if (!LookupPrivilegeValue(null, name, out luid)) throw Failure("LookupPrivilegeValue");
                var privileges = new TokenPrivileges { Count = 1, Luid = luid, Attributes = 2 };
                if (!AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero) || Marshal.GetLastWin32Error() != 0) throw Failure("AdjustTokenPrivileges");
            } finally { NativeTokenShape.CloseHandle(token); }
        }
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct StartupInfo {
            public int cb; public string r1, desktop, title;
            public uint x,y,xs,ys,xc,yc,fill,flags;
            public ushort show, cbReserved2;
            public IntPtr reserved2, stdin, stdout, stderr;
        }
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct StartupInfoEx {
            public StartupInfo StartupInfo;
            public IntPtr AttributeList;
        }
        sealed class WorkerStartupInfo : IDisposable {
            public StartupInfoEx Value;
            IntPtr handles;
            bool disposed;
            WorkerStartupInfo() { }
            public static WorkerStartupInfo Create(NativeWorkerStdio stdio) {
                var result = new WorkerStartupInfo();
                result.Value.StartupInfo.cb = Marshal.SizeOf(typeof(StartupInfoEx));
                result.Value.StartupInfo.flags = WorkerStartupPolicy.StartupFlags;
                result.Value.StartupInfo.stdin = stdio.ChildStdin;
                result.Value.StartupInfo.stdout = stdio.ChildStdout;
                result.Value.StartupInfo.stderr = stdio.ChildStderr;
                IntPtr bytes = IntPtr.Zero;
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref bytes);
                if (bytes == IntPtr.Zero) throw Failure("InitializeProcThreadAttributeList(size)");
                try {
                    result.Value.AttributeList = Marshal.AllocHGlobal(bytes);
                    if (!InitializeProcThreadAttributeList(result.Value.AttributeList, 1, 0, ref bytes))
                        throw Failure("InitializeProcThreadAttributeList");
                    result.handles = Marshal.AllocHGlobal(IntPtr.Size * WorkerStartupPolicy.InheritedHandleCount);
                    Marshal.WriteIntPtr(result.handles, 0, stdio.ChildStdin);
                    Marshal.WriteIntPtr(result.handles, IntPtr.Size, stdio.ChildStdout);
                    Marshal.WriteIntPtr(result.handles, IntPtr.Size * 2, stdio.ChildStderr);
                    if (!UpdateProcThreadAttribute(result.Value.AttributeList, 0,
                        new IntPtr(unchecked((int)WorkerStartupPolicy.HandleListAttribute)), result.handles,
                        (IntPtr)(IntPtr.Size * WorkerStartupPolicy.InheritedHandleCount), IntPtr.Zero, IntPtr.Zero))
                        throw Failure("UpdateProcThreadAttribute(handle list)");
                    return result;
                } catch { result.Dispose(); throw; }
            }
            public void Dispose() {
                if (disposed) return;
                disposed = true;
                if (Value.AttributeList != IntPtr.Zero) {
                    DeleteProcThreadAttributeList(Value.AttributeList);
                    Marshal.FreeHGlobal(Value.AttributeList); Value.AttributeList = IntPtr.Zero;
                }
                if (handles != IntPtr.Zero) { Marshal.FreeHGlobal(handles); handles = IntPtr.Zero; }
            }
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInformation { public IntPtr hProcess,hThread; public uint dwProcessId,dwThreadId; }
        [StructLayout(LayoutKind.Sequential)] struct Luid { public uint LowPart; public int HighPart; }
        [StructLayout(LayoutKind.Sequential)] struct TokenPrivileges { public uint Count; public Luid Luid; public uint Attributes; }
        [DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool CreateProcessAsUser(IntPtr token, string app, StringBuilder command, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr environment, string cwd, ref StartupInfoEx startup, out ProcessInformation info);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute, IntPtr value, IntPtr size, IntPtr previous, IntPtr returned);
        [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process, uint exitCode);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr handle);
        [DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool LookupPrivilegeValue(string system, string name, out Luid luid);
        [DllImport("advapi32.dll", SetLastError=true)] static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivileges state, uint length, IntPtr previous, IntPtr returned);
    }
}
