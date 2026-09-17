using System;
using System.Runtime.InteropServices;

namespace ItemGuard.Execution {
    // Disposable low-integrity primary token. It does not start a process.
    public sealed class NativeRestrictedToken : IDisposable {
        const uint TokenDuplicate = 0x0002;
        const uint TokenQuery = 0x0008;
        const uint TokenAdjustDefault = 0x0080;
        const uint DisableMaxPrivilege = 0x00000001;
        const int TokenIntegrityLevel = 25;
        IntPtr handle;
        bool disposed;
        public bool IsPrimary { get; private set; }
        internal IntPtr Handle { get { if (disposed) throw new ObjectDisposedException("NativeRestrictedToken"); return handle; } }

        NativeRestrictedToken(IntPtr handle) { this.handle = handle; IsPrimary = true; }

        public static NativeRestrictedToken CreateLowIntegrityFromCurrentProcess() {
            IntPtr source;
            if (!NativeTokenShape.OpenProcessToken(NativeTokenShape.GetCurrentProcess(), TokenDuplicate | TokenQuery | TokenAdjustDefault, out source))
                throw NativeTokenShape.Failure("OpenProcessToken");
            try {
                IntPtr restricted;
                if (!CreateRestrictedToken(source, DisableMaxPrivilege, 0, IntPtr.Zero, 0, IntPtr.Zero, 0, IntPtr.Zero, out restricted))
                    throw NativeTokenShape.Failure("CreateRestrictedToken");
                try {
                    SetLowIntegrity(restricted);
                    return new NativeRestrictedToken(restricted);
                } catch { NativeTokenShape.CloseHandle(restricted); throw; }
            } finally { NativeTokenShape.CloseHandle(source); }
        }

        public NativeTokenShape ReadShape() {
            if (disposed) throw new ObjectDisposedException("NativeRestrictedToken");
            return NativeTokenShape.ReadToken(handle);
        }

        static void SetLowIntegrity(IntPtr token) {
            IntPtr sid;
            if (!ConvertStringSidToSid("S-1-16-4096", out sid)) throw NativeTokenShape.Failure("ConvertStringSidToSid");
            try {
                var label = new TokenMandatoryLabel();
                label.Label.Sid = sid;
                label.Label.Attributes = 0x00000020; // SE_GROUP_INTEGRITY
                int size = Marshal.SizeOf(typeof(TokenMandatoryLabel));
                IntPtr buffer = Marshal.AllocHGlobal(size);
                try {
                    Marshal.StructureToPtr(label, buffer, false);
                    if (!SetTokenInformation(token, TokenIntegrityLevel, buffer, (uint)size))
                        throw NativeTokenShape.Failure("SetTokenInformation(TokenIntegrityLevel)");
                } finally { Marshal.FreeHGlobal(buffer); }
            } finally { LocalFree(sid); }
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            IntPtr closing = handle; handle = IntPtr.Zero;
            if (closing != IntPtr.Zero) NativeTokenShape.CloseHandle(closing);
        }

        [StructLayout(LayoutKind.Sequential)] struct SidAndAttributes { public IntPtr Sid; public uint Attributes; }
        [StructLayout(LayoutKind.Sequential)] struct TokenMandatoryLabel { public SidAndAttributes Label; }
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool CreateRestrictedToken(IntPtr existingToken, uint flags, uint disableSidCount, IntPtr sidsToDisable, uint deletePrivilegeCount, IntPtr privilegesToDelete, uint restrictedSidCount, IntPtr sidsToRestrict, out IntPtr newToken);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool SetTokenInformation(IntPtr token, int informationClass, IntPtr information, uint informationLength);
        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool ConvertStringSidToSid(string stringSid, out IntPtr sid);
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr LocalFree(IntPtr memory);
    }
}
