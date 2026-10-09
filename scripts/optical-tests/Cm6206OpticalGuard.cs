// Scoped CM6206 PCM optical-output configuration, with durable original-state journal.
// Only registers 0, 1 and 5 may be modified; there is no INIT or audio-stream code.
// HID register protocol and bit fields: C-Media CM6206 datasheet sections 6.1,
// https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace Sistema51.Hardware
{
    public sealed class Cm6206OpticalGuard : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)] private struct InterfaceData { public int size; public Guid guid; public int flags; public IntPtr reserved; }
        [StructLayout(LayoutKind.Sequential)] private struct Caps {
            public ushort Usage, UsagePage, InputReportByteLength, OutputReportByteLength, FeatureReportByteLength;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 17)] public ushort[] Reserved;
            public ushort Links, InputButtons, InputValues, InputData, OutputButtons, OutputValues, OutputData, FeatureButtons, FeatureValues, FeatureData;
        }
        [DllImport("hid.dll")] private static extern void HidD_GetHidGuid(out Guid guid);
        [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern IntPtr SetupDiGetClassDevs(ref Guid guid, string enumerator, IntPtr parent, uint flags);
        [DllImport("setupapi.dll", SetLastError = true)] private static extern bool SetupDiEnumDeviceInterfaces(IntPtr info, IntPtr device, ref Guid guid, uint index, ref InterfaceData data);
        [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr info, ref InterfaceData data, IntPtr detail, uint size, out uint required, IntPtr devinfo);
        [DllImport("setupapi.dll")] private static extern bool SetupDiDestroyDeviceInfoList(IntPtr info);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
        [DllImport("hid.dll")] private static extern bool HidD_GetPreparsedData(SafeFileHandle handle, out IntPtr data);
        [DllImport("hid.dll")] private static extern bool HidD_FreePreparsedData(IntPtr data);
        [DllImport("hid.dll")] private static extern int HidP_GetCaps(IntPtr data, out Caps caps);
        [DllImport("hid.dll")] private static extern bool HidD_FlushQueue(SafeFileHandle handle);
        [DllImport("kernel32.dll")] private static extern bool CancelIoEx(SafeFileHandle handle, IntPtr overlapped);

        private SafeFileHandle handle;
        private FileStream stream, journal;
        private int[] original;
        private readonly int[] touchedMask = new int[6];
        private bool disposed;
        private bool configurationAttempted, recoveryAdopted;
        private string path;
        public string DevicePath { get { return path; } }
        public int[] Original { get { return original == null ? null : (int[])original.Clone(); } }
        public bool RestorationVerified { get; private set; }
        public List<string> RestorationErrors { get; private set; }
        public List<string> Operations { get; private set; }
        public bool WritesPerformed { get; private set; }

        private static List<string> Paths()
        {
            Guid guid; HidD_GetHidGuid(out guid);
            IntPtr info = SetupDiGetClassDevs(ref guid, null, IntPtr.Zero, 18);
            if (info == new IntPtr(-1)) throw new IOException("SetupDiGetClassDevs: " + Marshal.GetLastWin32Error());
            List<string> paths = new List<string>();
            try {
                for (uint index = 0; ; index++) {
                    InterfaceData data = new InterfaceData(); data.size = Marshal.SizeOf(typeof(InterfaceData));
                    if (!SetupDiEnumDeviceInterfaces(info, IntPtr.Zero, ref guid, index, ref data)) {
                        int error = Marshal.GetLastWin32Error();
                        if (error != 259) throw new IOException("SetupDiEnumDeviceInterfaces: " + error);
                        break;
                    }
                    uint required; SetupDiGetDeviceInterfaceDetail(info, ref data, IntPtr.Zero, 0, out required, IntPtr.Zero);
                    if (required < 8 || required > 65536) throw new IOException("Invalid HID interface detail size.");
                    IntPtr detail = Marshal.AllocHGlobal((int)required);
                    try {
                        Marshal.WriteInt32(detail, IntPtr.Size == 8 ? 8 : 6);
                        if (!SetupDiGetDeviceInterfaceDetail(info, ref data, detail, required, out required, IntPtr.Zero))
                            throw new IOException("SetupDiGetDeviceInterfaceDetail: " + Marshal.GetLastWin32Error());
                        string candidate = Marshal.PtrToStringUni(IntPtr.Add(detail, 4));
                        if (candidate != null) {
                            string lower = candidate.ToLowerInvariant();
                            if (lower.Contains("vid_0d8c&pid_0102") && lower.Contains("&mi_03")) paths.Add(candidate);
                        }
                    } finally { Marshal.FreeHGlobal(detail); }
                }
            } finally { SetupDiDestroyDeviceInfoList(info); }
            return paths;
        }

        public Cm6206OpticalGuard(string journalPath)
        {
            Operations = new List<string>(); RestorationErrors = new List<string>();
            string fullJournal = Path.GetFullPath(journalPath);
            if (fullJournal.IndexOf(Path.DirectorySeparatorChar + "android-a34" + Path.DirectorySeparatorChar + "artifacts" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) < 0)
                throw new ArgumentException("Journal must remain inside android-a34/artifacts.");
            if (File.Exists(fullJournal)) throw new IOException("Refusing to overwrite an existing original-state journal.");
            try {
                List<string> paths = Paths();
                if (paths.Count != 1) throw new IOException("Exactly one VID_0D8C PID_0102 MI_03 HID interface is required; found " + paths.Count + ".");
                path = paths[0];
                handle = CreateFile(path, 0xC0000000, 3, IntPtr.Zero, 3, 0x40000000, IntPtr.Zero);
                if (handle.IsInvalid) throw new IOException("CreateFile HID: " + Marshal.GetLastWin32Error());
                IntPtr data;
                if (!HidD_GetPreparsedData(handle, out data)) throw new IOException("HidD_GetPreparsedData failed.");
                Caps caps;
                try { if (HidP_GetCaps(data, out caps) != 0x00110000) throw new IOException("HidP_GetCaps failed."); }
                finally { HidD_FreePreparsedData(data); }
                if (caps.InputReportByteLength != 4 || caps.OutputReportByteLength != 5)
                    throw new IOException("Expected exact HID input/output report lengths 4/5.");
                // HID messages must remain separate reports. Buffering can concatenate
                // two five-byte commands and block while preparing the next read.
                stream = new FileStream(handle, FileAccess.ReadWrite, 1, true);
                original = ReadAll();
                Directory.CreateDirectory(Path.GetDirectoryName(fullJournal));
                journal = new FileStream(fullJournal, FileMode.CreateNew, FileAccess.Write, FileShare.Read);
                Operations.Add("Read original registers; no register writes performed.");
                SaveJournal(); // Must durably succeed before EnsureExternalPcm48 can write.
            } catch { Close(); throw; }
        }

        private void CheckOpen() { if (disposed || stream == null) throw new ObjectDisposedException("Cm6206OpticalGuard"); }
        private void Wait(Task task, string operation)
        {
            if (!task.Wait(1000)) {
                CancelIoEx(handle, IntPtr.Zero);
                try { task.Wait(250); } catch { }
                throw new IOException(operation + " timed out after 1000 ms; pending IO cancelled.");
            }
        }
        private int Read(int register)
        {
            CheckOpen();
            if (!HidD_FlushQueue(handle)) throw new IOException("HidD_FlushQueue failed.");
            byte[] command = { 0, 0x30, 0, 0, (byte)register };
            Wait(stream.WriteAsync(command, 0, 5), "Read-register request");
            byte[] reply = new byte[4];
            Task<int> read = stream.ReadAsync(reply, 0, 4); Wait(read, "Read-register response");
            int count = read.Result;
            int offset = count >= 4 && reply[0] == 0 ? 1 : 0;
            if (count < offset + 3 || (reply[offset] & 0xE0) != 0x20) throw new IOException("Unexpected HID register response.");
            return reply[offset + 1] | (reply[offset + 2] << 8);
        }
        public int[] ReadAll()
        {
            CheckOpen(); int[] values = new int[6];
            for (int register = 0; register < 6; register++) values[register] = Read(register);
            return values;
        }
        private void Write(int register, int value)
        {
            if (register != 0 && register != 1 && register != 5) throw new InvalidOperationException("Only registers 0, 1 and 5 may be written.");
            WritesPerformed = true;
            Operations.Add("Submit HID write REG" + register + " value 0x" + value.ToString("X4"));
            SaveJournal();
            byte[] command = { 0, 0x20, (byte)value, (byte)(value >> 8), (byte)register };
            Wait(stream.WriteAsync(command, 0, 5), "Write-register request");
        }
        private static void Validate(int[] values)
        {
            if ((values[0] & 0x7000) != 0x2000) throw new InvalidOperationException("Register 0 must already specify 48 kHz; frequency bits will not be changed.");
            if ((values[1] & 1) != 0) throw new InvalidOperationException("SPDIF input mixing must already be disabled.");
            if ((values[5] & 0x3000) != 0x3000) throw new InvalidOperationException("ADC/DAC reset-release bits must already be set; reset bits will not be changed.");
        }
        private void Apply(int register, int mask, int desired)
        {
            int before = Read(register), target = (before & ~mask) | (desired & mask);
            if (target != before) {
                // Mark a potentially completed write before submission; failures still need restoration.
                touchedMask[register] |= ((before ^ target) | (original[register] ^ target)) & mask;
                WritesPerformed = true;
                Operations.Add("Apply REG" + register + " 0x" + before.ToString("X4") + " -> 0x" + target.ToString("X4") + " mask 0x" + mask.ToString("X4"));
                SaveJournal();
                Write(register, target);
            }
            int after = Read(register);
            if ((after & mask) != (desired & mask)) throw new IOException("Controlled-bit readback mismatch in REG" + register + ".");
        }
        public void EnsureExternalPcm48()
        {
            EnsureExternalPcm48(true);
        }
        public void EnsureExternalPcm48(bool copyrightNotAsserted)
        {
            CheckOpen(); if (journal == null) throw new IOException("Original-state journal is unavailable.");
            configurationAttempted = true;
            Validate(ReadAll());
            int statusBits = copyrightNotAsserted ? 4 : 0;
            Apply(0, 0x8007, statusBits); // Own synthetic signal: compare copyright-status bit, preserving other status.
            Apply(1, 0x000E, 0); // Valid PCM output enabled, internal SPDIF loopback disabled.
            Apply(5, 0x0F00, 0); // USB front pair, ADC-to-SPDIF off; preserve reset bits 13/12.
            int[] after = ReadAll(); Validate(after);
            if ((after[0] & 0x8007) != statusBits || (after[1] & 0xE) != 0 || (after[5] & 0xF00) != 0)
                throw new IOException("PCM configuration changed before final verification.");
            Operations.Add("External PCM48 configuration verified."); SaveJournal();
        }
        public void AdoptRecovery(int[] previous, int[] masks, string previousDevicePath)
        {
            CheckOpen();
            if (configurationAttempted || WritesPerformed || recoveryAdopted)
                throw new InvalidOperationException("Recovery may be adopted only once, before configuration or writes.");
            if (previous == null || masks == null || previous.Length != 6 || masks.Length != 6)
                throw new ArgumentException("Recovery requires six previous registers and six masks.");
            if (!string.Equals(path, previousDevicePath, StringComparison.Ordinal))
                throw new ArgumentException("Recovery device path must exactly match the currently opened HID interface.");
            int[] allowed = { 0x8007, 0x000E, 0, 0, 0, 0x0F00 };
            for (int register = 0; register < 6; register++) {
                if (previous[register] < 0 || previous[register] > 0xFFFF || masks[register] < 0 || masks[register] > 0xFFFF ||
                    (masks[register] & ~allowed[register]) != 0)
                    throw new ArgumentException("Recovery values or masks are invalid for REG" + register + ".");
            }
            for (int register = 0; register < 6; register++) {
                original[register] = (original[register] & ~masks[register]) | (previous[register] & masks[register]);
                touchedMask[register] |= masks[register];
            }
            recoveryAdopted = true;
            Operations.Add("Imported prior restoration baseline and masks for the exact same HID device.");
            SaveJournal(); // Persist inherited obligations before Dispose restores them.
        }
        private static string Quoted(string value) { return "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n") + "\""; }
        private void SaveJournal()
        {
            if (journal == null) throw new IOException("Original-state journal unavailable.");
            StringBuilder json = new StringBuilder("{\"kind\":\"cm6206_scoped_pcm_guard\",\"path\":");
            json.Append(Quoted(path)).Append(",\"original\":[").Append(string.Join(",", original)).Append("],\"touchedMasks\":[").Append(string.Join(",", touchedMask));
            json.Append("],\"writesPerformed\":").Append(WritesPerformed ? "true" : "false").Append(",\"restorationVerified\":").Append(RestorationVerified ? "true" : "false");
            json.Append(",\"operations\":[");
            for (int index = 0; index < Operations.Count; index++) { if (index > 0) json.Append(','); json.Append(Quoted(Operations[index])); }
            json.Append("],\"restorationErrors\":[");
            for (int index = 0; index < RestorationErrors.Count; index++) { if (index > 0) json.Append(','); json.Append(Quoted(RestorationErrors[index])); }
            json.Append("]}"); byte[] bytes = Encoding.UTF8.GetBytes(json.ToString());
            journal.Position = 0; journal.Write(bytes, 0, bytes.Length); journal.SetLength(bytes.Length); journal.Flush(true);
        }
        public void Dispose()
        {
            if (disposed) return;
            for (int register = 0; register < 6; register++) {
                if (touchedMask[register] == 0) continue;
                try {
                    int current = Read(register), mask = touchedMask[register];
                    int target = (current & ~mask) | (original[register] & mask);
                    if (target != current) Write(register, target);
                    if ((Read(register) & mask) != (original[register] & mask)) throw new IOException("Restore readback mismatch.");
                    Operations.Add("Restored controlled bits REG" + register + " mask 0x" + mask.ToString("X4"));
                } catch (Exception error) { RestorationErrors.Add("REG" + register + ": " + error.GetBaseException().Message); }
            }
            RestorationVerified = RestorationErrors.Count == 0 && original != null;
            try { if (journal != null) SaveJournal(); } catch (Exception error) { RestorationErrors.Add("Journal: " + error.GetBaseException().Message); RestorationVerified = false; }
            disposed = true; Close();
        }
        private void Close()
        {
            try { if (stream != null) stream.Dispose(); else if (handle != null) handle.Dispose(); }
            catch (Exception error) { if (RestorationErrors != null) RestorationErrors.Add("Close HID: " + error.GetBaseException().Message); RestorationVerified = false; }
            finally { stream = null; handle = null; }
            try { if (journal != null) journal.Dispose(); }
            catch (Exception error) { if (RestorationErrors != null) RestorationErrors.Add("Close journal: " + error.GetBaseException().Message); RestorationVerified = false; }
            finally { journal = null; }
        }
    }
}
