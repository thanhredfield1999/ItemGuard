using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;

namespace ItemGuard.Execution {
    // Host-only primitive for the first dual-journal slice. It is not runtime authority.
    public sealed class NativeJournalLeaf : IDisposable {
        const uint FileAppendData = 0x0004;
        const uint FileReadData = 0x0001;
        const uint FileReadAttributes = 0x0080;
        const uint ReadControl = 0x00020000;
        const uint Synchronize = 0x00100000;
        const uint FileShareRead = 0x00000001;
        const uint FileOpen = 0x00000001;
        const uint FileCreate = 0x00000002;
        const uint FileDirectoryFile = 0x00000001;
        const uint FileWriteThrough = 0x00000002;
        const uint FileSynchronousIoNonAlert = 0x00000020;
        const uint FileNonDirectoryFile = 0x00000040;
        const uint ObjCaseInsensitive = 0x00000040;
        const uint ObjDontReparse = 0x00001000;
        const int FileCreated = 2;
        const int FileIdInfo = 18;
        static readonly IntPtr InvalidHandleValue = new IntPtr(-1);

        IntPtr parentHandle;
        IntPtr leafHandle;
        readonly bool appendAllowed;
        bool disposed;

        NativeJournalLeaf(IntPtr parentHandle, IntPtr leafHandle, bool appendAllowed) {
            this.parentHandle = parentHandle;
            this.leafHandle = leafHandle;
            this.appendAllowed = appendAllowed;
        }

        public NativeFileIdentity Identity { get { ThrowIfDisposed(); return ReadIdentity(leafHandle); } }

        public static NativeJournalLeaf Create(string parentDirectory, string leafName) {
            if (String.IsNullOrEmpty(parentDirectory)) throw new ArgumentException("parentDirectory");
            if (String.IsNullOrEmpty(leafName) || leafName == "." || leafName == ".."
                || leafName.IndexOf(Path.DirectorySeparatorChar) >= 0
                || leafName.IndexOf(Path.AltDirectorySeparatorChar) >= 0
                || !String.Equals(Path.GetFileName(leafName), leafName, StringComparison.Ordinal))
                throw new ArgumentException("leafName");
            string fullParent = Path.GetFullPath(parentDirectory);
            if (!Directory.Exists(fullParent)) throw new DirectoryNotFoundException(fullParent);

            IntPtr parent = IntPtr.Zero;
            IntPtr leaf = IntPtr.Zero;
            try {
                parent = OpenParent(fullParent);
                leaf = CreateLeaf(parent, leafName);
                FlushOrThrow(parent, "parent directory");
                return new NativeJournalLeaf(parent, leaf, true);
            } catch {
                if (leaf != IntPtr.Zero && leaf != InvalidHandleValue) CloseHandle(leaf);
                if (parent != IntPtr.Zero && parent != InvalidHandleValue) CloseHandle(parent);
                throw;
            }
        }

        public static NativeJournalLeaf OpenExistingReadOnly(string parentDirectory, string leafName) {
            if (String.IsNullOrEmpty(parentDirectory)) throw new ArgumentException("parentDirectory");
            if (String.IsNullOrEmpty(leafName) || leafName == "." || leafName == ".."
                || leafName.IndexOf(Path.DirectorySeparatorChar) >= 0
                || leafName.IndexOf(Path.AltDirectorySeparatorChar) >= 0
                || !String.Equals(Path.GetFileName(leafName), leafName, StringComparison.Ordinal))
                throw new ArgumentException("leafName");
            string fullParent = Path.GetFullPath(parentDirectory);
            if (!Directory.Exists(fullParent)) throw new DirectoryNotFoundException(fullParent);
            IntPtr parent = IntPtr.Zero;
            IntPtr leaf = IntPtr.Zero;
            try {
                parent = OpenParent(fullParent);
                leaf = OpenExistingLeaf(parent, leafName);
                return new NativeJournalLeaf(parent, leaf, false);
            } catch {
                if (leaf != IntPtr.Zero && leaf != InvalidHandleValue) CloseHandle(leaf);
                if (parent != IntPtr.Zero && parent != InvalidHandleValue) CloseHandle(parent);
                throw;
            }
        }

        public int AppendAndFlush(byte[] bytes) {
            if (bytes == null) throw new ArgumentNullException("bytes");
            ThrowIfDisposed();
            if (!appendAllowed) throw new InvalidOperationException("read-only journal leaf");
            uint written;
            if (!WriteFile(leafHandle, bytes, (uint)bytes.Length, out written, IntPtr.Zero))
                throw NativeFailure("WriteFile");
            if (written != bytes.Length) throw new IOException("WriteFile short write");
            FlushOrThrow(leafHandle, "journal leaf");
            return (int)written;
        }

        // Read through the already-opened native handle. Callers bind identity and
        // content to the same non-reparse object rather than reopening by path.
        public byte[] ReadAllExact(int maximumBytes) {
            if (maximumBytes < 0) throw new ArgumentOutOfRangeException("maximumBytes");
            ThrowIfDisposed();
            long length;
            if (!GetFileSizeEx(leafHandle, out length)) throw NativeFailure("GetFileSizeEx");
            if (length < 0 || length > maximumBytes || length > Int32.MaxValue)
                throw new IOException("journal leaf exceeds read bound");
            var bytes = new byte[(int)length];
            if (bytes.Length == 0) return bytes;
            uint read;
            if (!ReadFile(leafHandle, bytes, (uint)bytes.Length, out read, IntPtr.Zero))
                throw NativeFailure("ReadFile");
            if (read != bytes.Length) throw new IOException("ReadFile short read");
            return bytes;
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            IntPtr leaf = leafHandle; leafHandle = IntPtr.Zero;
            IntPtr parent = parentHandle; parentHandle = IntPtr.Zero;
            if (leaf != IntPtr.Zero && leaf != InvalidHandleValue) CloseHandle(leaf);
            if (parent != IntPtr.Zero && parent != InvalidHandleValue) CloseHandle(parent);
        }

        static IntPtr OpenParent(string path) {
            IntPtr handle;
            IoStatusBlock io;
            using (var name = new OwnedUnicodeString(ToNtPath(path))) {
                var attrs = new ObjectAttributes(IntPtr.Zero, name.StructurePointer, IntPtr.Zero);
                int status = NtCreateFile(out handle,
                    0x0001 | 0x0020 | 0x0002 | 0x0080 | FileReadAttributes | 0x00000100 | ReadControl | Synchronize,
                    ref attrs, out io, ref name.Value, 0, FileShareRead, FileOpen,
                    FileDirectoryFile | FileWriteThrough | FileSynchronousIoNonAlert, IntPtr.Zero, 0);
                if (status != 0) throw NtFailure("NtCreateFile parent", status);
            }
            return handle;
        }

        static IntPtr OpenExistingLeaf(IntPtr parent, string leafName) {
            IntPtr handle;
            IoStatusBlock io;
            using (var name = new OwnedUnicodeString(leafName)) {
                var attrs = new ObjectAttributes(parent, name.StructurePointer, IntPtr.Zero);
                int status = NtCreateFile(out handle,
                    FileReadData | FileReadAttributes | ReadControl | Synchronize,
                    ref attrs, out io, ref name.Value, 0, FileShareRead, FileOpen,
                    FileNonDirectoryFile | FileSynchronousIoNonAlert, IntPtr.Zero, 0);
                if (status != 0) throw NtFailure("NtCreateFile existing leaf", status);
            }
            return handle;
        }

        static IntPtr CreateLeaf(IntPtr parent, string leafName) {
            IntPtr descriptor = IntPtr.Zero;
            try {
                string sid = WindowsIdentity.GetCurrent().User.Value;
                string sddl = "O:" + sid + "G:" + sid + "D:(D;;WDWO;;;OW)(A;;GR;;;" + sid + ")S:(ML;;NW;;;ME)";
                uint descriptorLength;
                if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, out descriptor, out descriptorLength))
                    throw NativeFailure("ConvertStringSecurityDescriptorToSecurityDescriptor");
                IntPtr handle;
                IoStatusBlock io;
                using (var name = new OwnedUnicodeString(leafName)) {
                    var attrs = new ObjectAttributes(parent, name.StructurePointer, descriptor);
                    int status = NtCreateFile(out handle,
                        FileAppendData | FileReadAttributes | ReadControl | Synchronize,
                        ref attrs, out io, ref name.Value, 0, FileShareRead, FileCreate,
                        FileNonDirectoryFile | FileWriteThrough | FileSynchronousIoNonAlert, IntPtr.Zero, 0);
                    if (status != 0) throw NtFailure("NtCreateFile leaf", status);
                    if (io.Information.ToInt64() != FileCreated) {
                        CloseHandle(handle);
                        throw new IOException("NtCreateFile leaf did not report FILE_CREATED");
                    }
                }
                return handle;
            } finally {
                if (descriptor != IntPtr.Zero) LocalFree(descriptor);
            }
        }

        static string ToNtPath(string path) {
            return "\\??\\" + path.Replace('/', '\\');
        }

        static NativeFileIdentity ReadIdentity(IntPtr handle) {
            FileIdInfoData info;
            if (!GetFileInformationByHandleEx(handle, FileIdInfo, out info, (uint)Marshal.SizeOf(typeof(FileIdInfoData))))
                throw NativeFailure("GetFileInformationByHandleEx(FileIdInfo)");
            return new NativeFileIdentity(info.VolumeSerialNumber, info.FileId);
        }

        static void FlushOrThrow(IntPtr handle, string target) {
            if (!FlushFileBuffers(handle)) throw NativeFailure("FlushFileBuffers " + target);
        }

        static Exception NtFailure(string operation, int status) {
            return new IOException(operation + " failed", new Win32Exception((int)RtlNtStatusToDosError(status)));
        }

        static Exception NativeFailure(string operation) {
            return new IOException(operation + " failed", new Win32Exception(Marshal.GetLastWin32Error()));
        }

        void ThrowIfDisposed() {
            if (disposed || leafHandle == IntPtr.Zero) throw new ObjectDisposedException("NativeJournalLeaf");
        }

        [StructLayout(LayoutKind.Sequential)]
        struct IoStatusBlock {
            public IntPtr Status;
            public IntPtr Information;
        }

        [StructLayout(LayoutKind.Sequential)]
        sealed class OwnedUnicodeString : IDisposable {
            public NativeUnicode Value;
            public readonly IntPtr StructurePointer;
            public OwnedUnicodeString(string text) {
                Value = new NativeUnicode(text);
                StructurePointer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NativeUnicode)));
                Marshal.StructureToPtr(Value, StructurePointer, false);
            }
            public void Dispose() {
                Marshal.FreeHGlobal(StructurePointer);
                Marshal.FreeHGlobal(Value.Buffer);
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct NativeUnicode {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
            public NativeUnicode(string text) {
                Length = checked((ushort)(text.Length * 2));
                MaximumLength = checked((ushort)(Length + 2));
                Buffer = Marshal.StringToHGlobalUni(text);
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct ObjectAttributes {
            public int Length;
            public IntPtr RootDirectory;
            public IntPtr ObjectName;
            public uint Attributes;
            public IntPtr SecurityDescriptor;
            public IntPtr SecurityQualityOfService;
            public ObjectAttributes(IntPtr rootDirectory, IntPtr objectName, IntPtr securityDescriptor) {
                Length = Marshal.SizeOf(typeof(ObjectAttributes));
                RootDirectory = rootDirectory;
                ObjectName = objectName;
                Attributes = ObjCaseInsensitive | ObjDontReparse;
                SecurityDescriptor = securityDescriptor;
                SecurityQualityOfService = IntPtr.Zero;
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct FileIdInfoData {
            public ulong VolumeSerialNumber;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)]
            public byte[] FileId;
        }

        [DllImport("ntdll.dll")]
        static extern int NtCreateFile(out IntPtr fileHandle, uint desiredAccess, ref ObjectAttributes objectAttributes,
            out IoStatusBlock ioStatusBlock, ref NativeUnicode fileName, uint fileAttributes, uint shareAccess,
            uint createDisposition, uint createOptions, IntPtr eaBuffer, uint eaLength);
        [DllImport("ntdll.dll")]
        static extern uint RtlNtStatusToDosError(int status);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool WriteFile(IntPtr handle, byte[] buffer, uint numberOfBytesToWrite, out uint numberOfBytesWritten, IntPtr overlapped);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool ReadFile(IntPtr handle, byte[] buffer, uint numberOfBytesToRead, out uint numberOfBytesRead, IntPtr overlapped);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetFileSizeEx(IntPtr handle, out long fileSize);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool FlushFileBuffers(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetFileInformationByHandleEx(IntPtr handle, int fileInformationClass,
            out FileIdInfoData fileInformation, uint bufferSize);
        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string stringSecurityDescriptor, uint stringSdRevision,
            out IntPtr securityDescriptor, out uint securityDescriptorSize);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr LocalFree(IntPtr memory);
    }
}
