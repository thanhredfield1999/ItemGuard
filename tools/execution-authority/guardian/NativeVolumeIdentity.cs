using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace ItemGuard.Execution {
    public sealed class NativeVolumeIdentity {
        const uint FilePersistentAcls = 0x00000008;

        public uint VolumeInformationSerial { get; private set; }
        public uint FileSystemFlags { get; private set; }
        public string FileSystemName { get; private set; }
        public bool HasPersistentAcls { get { return (FileSystemFlags & FilePersistentAcls) != 0; } }

        NativeVolumeIdentity(uint serial, uint flags, string fileSystemName) {
            VolumeInformationSerial = serial;
            FileSystemFlags = flags;
            FileSystemName = fileSystemName;
        }

        public static NativeVolumeIdentity Read(string exactPath) {
            if (String.IsNullOrEmpty(exactPath)) throw new ArgumentException("exactPath");
            string full = Path.GetFullPath(exactPath);
            string root = Path.GetPathRoot(full);
            if (String.IsNullOrEmpty(root)) throw new IOException("path has no volume root");
            var volumeName = new StringBuilder(261);
            var fileSystemName = new StringBuilder(261);
            uint serial;
            uint maximumComponentLength;
            uint flags;
            if (!GetVolumeInformation(root, volumeName, (uint)volumeName.Capacity, out serial,
                out maximumComponentLength, out flags, fileSystemName, (uint)fileSystemName.Capacity))
                throw new IOException("GetVolumeInformation failed", new Win32Exception(Marshal.GetLastWin32Error()));
            return new NativeVolumeIdentity(serial, flags, fileSystemName.ToString());
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool GetVolumeInformation(string rootPathName, StringBuilder volumeNameBuffer,
            uint volumeNameSize, out uint volumeSerialNumber, out uint maximumComponentLength,
            out uint fileSystemFlags, StringBuilder fileSystemNameBuffer, uint fileSystemNameSize);
    }
}
