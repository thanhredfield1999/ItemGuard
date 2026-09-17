using System;
using System.Text;

namespace ItemGuard.Execution {
    public sealed class NativeFileIdentity {
        public ulong VolumeSerial { get; private set; }
        public byte[] FileId { get; private set; }
        public string FileIdHex { get; private set; }

        internal NativeFileIdentity(ulong volumeSerial, byte[] fileId) {
            if (fileId == null || fileId.Length != 16) throw new ArgumentException("fileId");
            VolumeSerial = volumeSerial;
            FileId = (byte[])fileId.Clone();
            var text = new StringBuilder(32);
            foreach (byte b in FileId) text.Append(b.ToString("x2"));
            FileIdHex = text.ToString();
        }
    }
}
