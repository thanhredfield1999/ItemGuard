using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Principal;

namespace ItemGuard.Execution {
    // Read-only token-shape probe; it does not create or launch a process.
    public sealed class NativeTokenShape {
        const int TokenIntegrityLevel = 25;
        const int TokenIsRestricted = 40;
        public const uint LowIntegrityRid = 0x1000;
        public const uint MediumIntegrityRid = 0x2000;
        public bool IsRestricted { get; private set; }
        public uint IntegrityRid { get; private set; }
        public string UserSid { get; private set; }

        NativeTokenShape(bool restricted, uint integrityRid, string userSid) {
            IsRestricted = restricted; IntegrityRid = integrityRid; UserSid = userSid;
        }

        public static NativeTokenShape ReadCurrentProcess() {
            IntPtr token;
            if (!OpenProcessToken(GetCurrentProcess(), 0x0008, out token)) throw Failure("OpenProcessToken");
            try { return ReadToken(token); }
            finally { CloseHandle(token); }
        }

        internal static NativeTokenShape ReadToken(IntPtr token) {
            uint restricted = ReadDword(token, TokenIsRestricted);
            IntPtr buffer = ReadBuffer(token, TokenIntegrityLevel);
            try {
                IntPtr sid = Marshal.ReadIntPtr(buffer);
                IntPtr countPtr = GetSidSubAuthorityCount(sid);
                if (countPtr == IntPtr.Zero) throw new InvalidOperationException("integrity SID has no subauthorities");
                byte count = Marshal.ReadByte(countPtr);
                if (count == 0) throw new InvalidOperationException("integrity SID is empty");
                IntPtr ridPtr = GetSidSubAuthority(sid, (uint)(count - 1));
                if (ridPtr == IntPtr.Zero) throw new InvalidOperationException("integrity SID has no RID");
                return new NativeTokenShape(restricted != 0, unchecked((uint)Marshal.ReadInt32(ridPtr)), WindowsIdentity.GetCurrent().User.Value);
            } finally { Marshal.FreeHGlobal(buffer); }
        }

        static uint ReadDword(IntPtr token, int informationClass) {
            IntPtr buffer = ReadBuffer(token, informationClass);
            try { return unchecked((uint)Marshal.ReadInt32(buffer)); }
            finally { Marshal.FreeHGlobal(buffer); }
        }
        internal static IntPtr ReadBuffer(IntPtr token, int informationClass) {
            uint length;
            GetTokenInformation(token, informationClass, IntPtr.Zero, 0, out length);
            if (length == 0) throw Failure("GetTokenInformation length");
            IntPtr buffer = Marshal.AllocHGlobal((int)length);
            if (!GetTokenInformation(token, informationClass, buffer, length, out length)) {
                Marshal.FreeHGlobal(buffer); throw Failure("GetTokenInformation");
            }
            return buffer;
        }
        internal static Exception Failure(string op) { return new InvalidOperationException(op + " failed", new Win32Exception(Marshal.GetLastWin32Error())); }
        [DllImport("kernel32.dll")] internal static extern IntPtr GetCurrentProcess();
        [DllImport("advapi32.dll", SetLastError = true)] internal static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
        [DllImport("advapi32.dll", SetLastError = true)] internal static extern bool GetTokenInformation(IntPtr token, int infoClass, IntPtr buffer, uint length, out uint returnLength);
        [DllImport("advapi32.dll")] internal static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
        [DllImport("advapi32.dll")] internal static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);
        [DllImport("kernel32.dll", SetLastError = true)] internal static extern bool CloseHandle(IntPtr handle);
    }
}
