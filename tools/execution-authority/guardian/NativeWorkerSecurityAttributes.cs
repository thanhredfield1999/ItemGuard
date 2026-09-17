using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace ItemGuard.Execution {
    // Owns descriptors and SECURITY_ATTRIBUTES for the exact duration of a native
    // CreateProcessAsUserW call. Construction alone creates no OS process or handle.
    public sealed class NativeWorkerSecurityAttributes : IDisposable {
        [StructLayout(LayoutKind.Sequential)]
        struct SecurityAttributes {
            public int Length;
            public IntPtr SecurityDescriptor;
            [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
        }

        IntPtr processAttributes;
        IntPtr threadAttributes;
        IntPtr processDescriptor;
        IntPtr threadDescriptor;
        bool disposed;

        public IntPtr ProcessAttributes { get { ThrowIfDisposed(); return processAttributes; } }
        public IntPtr ThreadAttributes { get { ThrowIfDisposed(); return threadAttributes; } }
        public IntPtr ProcessDescriptor { get { ThrowIfDisposed(); return processDescriptor; } }
        public IntPtr ThreadDescriptor { get { ThrowIfDisposed(); return threadDescriptor; } }
        public bool ProcessInheritHandle { get { ThrowIfDisposed(); return false; } }
        public bool ThreadInheritHandle { get { ThrowIfDisposed(); return false; } }
        public string ReadProcessDescriptorSddl() { ThrowIfDisposed(); return ConvertToSddl(processDescriptor); }
        public string ReadThreadDescriptorSddl() { ThrowIfDisposed(); return ConvertToSddl(threadDescriptor); }

        NativeWorkerSecurityAttributes(IntPtr processAttributes, IntPtr threadAttributes,
            IntPtr processDescriptor, IntPtr threadDescriptor) {
            this.processAttributes = processAttributes;
            this.threadAttributes = threadAttributes;
            this.processDescriptor = processDescriptor;
            this.threadDescriptor = threadDescriptor;
        }

        public static NativeWorkerSecurityAttributes Create(string workerSid) {
            IntPtr processDescriptor = IntPtr.Zero;
            IntPtr threadDescriptor = IntPtr.Zero;
            IntPtr processAttributes = IntPtr.Zero;
            IntPtr threadAttributes = IntPtr.Zero;
            try {
                processDescriptor = ConvertOrThrow(NativeWorkerSecurityPolicy.BuildProcessSddl(workerSid));
                threadDescriptor = ConvertOrThrow(NativeWorkerSecurityPolicy.BuildThreadSddl(workerSid));
                processAttributes = AllocateAttributes(processDescriptor);
                threadAttributes = AllocateAttributes(threadDescriptor);
                return new NativeWorkerSecurityAttributes(processAttributes, threadAttributes,
                    processDescriptor, threadDescriptor);
            } catch {
                if (threadAttributes != IntPtr.Zero) Marshal.FreeHGlobal(threadAttributes);
                if (processAttributes != IntPtr.Zero) Marshal.FreeHGlobal(processAttributes);
                if (threadDescriptor != IntPtr.Zero) LocalFree(threadDescriptor);
                if (processDescriptor != IntPtr.Zero) LocalFree(processDescriptor);
                throw;
            }
        }

        static IntPtr ConvertOrThrow(string sddl) {
            IntPtr descriptor;
            uint length;
            if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, out descriptor, out length)
                || descriptor == IntPtr.Zero)
                throw new InvalidOperationException("ConvertStringSecurityDescriptorToSecurityDescriptor failed",
                    new Win32Exception(Marshal.GetLastWin32Error()));
            return descriptor;
        }

        static string ConvertToSddl(IntPtr descriptor) {
            IntPtr text = IntPtr.Zero;
            uint length;
            try {
                if (!ConvertSecurityDescriptorToStringSecurityDescriptor(descriptor, 1, 0x00000004,
                    out text, out length) || text == IntPtr.Zero)
                    throw new InvalidOperationException("ConvertSecurityDescriptorToStringSecurityDescriptor failed",
                        new Win32Exception(Marshal.GetLastWin32Error()));
                return Marshal.PtrToStringUni(text);
            } finally {
                if (text != IntPtr.Zero) LocalFree(text);
            }
        }

        static IntPtr AllocateAttributes(IntPtr descriptor) {
            var attributes = new SecurityAttributes {
                Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                SecurityDescriptor = descriptor,
                InheritHandle = false
            };
            IntPtr memory = Marshal.AllocHGlobal(attributes.Length);
            Marshal.StructureToPtr(attributes, memory, false);
            return memory;
        }

        void ThrowIfDisposed() {
            if (disposed) throw new ObjectDisposedException("NativeWorkerSecurityAttributes");
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            if (threadAttributes != IntPtr.Zero) Marshal.FreeHGlobal(threadAttributes);
            if (processAttributes != IntPtr.Zero) Marshal.FreeHGlobal(processAttributes);
            if (threadDescriptor != IntPtr.Zero) LocalFree(threadDescriptor);
            if (processDescriptor != IntPtr.Zero) LocalFree(processDescriptor);
            threadAttributes = processAttributes = threadDescriptor = processDescriptor = IntPtr.Zero;
        }

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string stringSecurityDescriptor,
            uint stringSdRevision, out IntPtr securityDescriptor, out uint securityDescriptorSize);
        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool ConvertSecurityDescriptorToStringSecurityDescriptor(IntPtr securityDescriptor,
            uint requestedStringSdRevision, uint securityInformation, out IntPtr stringSecurityDescriptor,
            out uint stringSecurityDescriptorLen);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr LocalFree(IntPtr memory);
    }
}
