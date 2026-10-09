// Read-only CoreAudio format discovery. This file never initializes or starts streams.
// COM vtable order follows the definitions already used in configuracao-pc/RelayLoopback.cs.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Sistema51.Cm6206
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class DeviceEnumeratorClass { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr callback);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr callback);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IDeviceCollection
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int Item(uint index, out IDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsctx, IntPtr activation, out IntPtr value);
        [PreserveSig] int OpenPropertyStore(int access, out IProperties properties);
        [PreserveSig] int GetId(out IntPtr id);
        [PreserveSig] int GetState(out int state);
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct PropertyKey
    {
        public Guid FormatId;
        public uint Id;
        public PropertyKey(string formatId, uint id) { FormatId = new Guid(formatId); Id = id; }
        public override string ToString() { return FormatId.ToString("B") + "," + Id; }
    }

    // PROPVARIANT is 24 bytes on x64; strings, scalars and GUIDs suffice for this discovery.
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    internal struct PropertyValue
    {
        [FieldOffset(0)] public ushort Type;
        [FieldOffset(8)] public IntPtr Pointer;
        [FieldOffset(8)] public uint Unsigned;
        [FieldOffset(8)] public int Signed;
        [FieldOffset(8)] public short Boolean;
        public string Describe()
        {
            if (Type == 31) return Marshal.PtrToStringUni(Pointer);
            if (Type == 30) return Marshal.PtrToStringAnsi(Pointer);
            if (Type == 19) return Unsigned.ToString();
            if (Type == 3) return Signed.ToString();
            if (Type == 11) return (Boolean != 0).ToString();
            if (Type == 72 && Pointer != IntPtr.Zero) return ((Guid)Marshal.PtrToStructure(Pointer, typeof(Guid))).ToString("B");
            return "[PROPVARIANT type " + Type + "]";
        }
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IProperties
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropertyValue value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropertyValue value);
        [PreserveSig] int Commit();
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int flags, long duration, long periodicity, IntPtr format, IntPtr session);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint frames);
        // For exclusive-mode queries the closest-format pointer must be NULL.
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, IntPtr closestFormatPointer);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long normal, out long minimum);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, out IntPtr service);
    }

    public sealed class WaveFormat
    {
        public int Tag;
        public int Channels;
        public int SampleRate;
        public int AverageBytesPerSecond;
        public int BlockAlign;
        public int BitsPerSample;
        public int ExtraBytes;
        public int ValidBitsPerSample;
        public string ChannelMask;
        public string Subformat;
        public string Encoding;
    }

    public sealed class FormatQuery
    {
        public string ShareMode;
        public WaveFormat Requested;
        public int HResult;
        public string HResultHex;
        public string Result;
        public WaveFormat ClosestFormat;
    }

    public sealed class Endpoint
    {
        public string Id;
        public string Flow;
        public string FriendlyName;
        public int State;
        public bool IsCm6206Candidate;
        public string MatchReason;
        public Dictionary<string, string> Properties = new Dictionary<string, string>();
        public WaveFormat MixFormat;
        public long DefaultPeriod100ns;
        public long MinimumPeriod100ns;
        public List<FormatQuery> Queries = new List<FormatQuery>();
        public List<string> Errors = new List<string>();
    }

    public static class FormatProbe
    {
        private static readonly Guid AudioClientId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
        private static readonly Guid Pcm = new Guid("00000001-0000-0010-8000-00AA00389B71");
        private static readonly Guid Float = new Guid("00000003-0000-0010-8000-00AA00389B71");
        private static readonly PropertyKey FriendlyName = new PropertyKey("A45C254E-DF1C-4EFD-8020-67D146A850E0", 14);

        [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PropertyValue value);

        private static void Require(int hr, string operation)
        {
            if (hr < 0) throw new COMException(operation + " returned " + Hex(hr), hr);
        }

        private static string Hex(int hr) { return "0x" + unchecked((uint)hr).ToString("X8"); }

        private static void Release(object instance)
        {
            if (instance != null && Marshal.IsComObject(instance)) Marshal.ReleaseComObject(instance);
        }

        private static WaveFormat ReadFormat(IntPtr value)
        {
            if (value == IntPtr.Zero) return null;
            WaveFormat result = new WaveFormat();
            result.Tag = (ushort)Marshal.ReadInt16(value, 0);
            result.Channels = (ushort)Marshal.ReadInt16(value, 2);
            result.SampleRate = Marshal.ReadInt32(value, 4);
            result.AverageBytesPerSecond = Marshal.ReadInt32(value, 8);
            result.BlockAlign = (ushort)Marshal.ReadInt16(value, 12);
            result.BitsPerSample = (ushort)Marshal.ReadInt16(value, 14);
            result.ExtraBytes = (ushort)Marshal.ReadInt16(value, 16);
            result.ValidBitsPerSample = result.BitsPerSample;
            result.Encoding = result.Tag == 1 ? "PCM" : (result.Tag == 3 ? "IEEE_FLOAT" : "tag " + result.Tag);
            if (result.Tag == 0xFFFE && result.ExtraBytes >= 22)
            {
                result.ValidBitsPerSample = (ushort)Marshal.ReadInt16(value, 18);
                result.ChannelMask = "0x" + unchecked((uint)Marshal.ReadInt32(value, 20)).ToString("X8");
                byte[] guidBytes = new byte[16];
                Marshal.Copy(IntPtr.Add(value, 24), guidBytes, 0, 16);
                Guid subformat = new Guid(guidBytes);
                result.Subformat = subformat.ToString();
                result.Encoding = subformat == Pcm ? "PCM" : (subformat == Float ? "IEEE_FLOAT" : "other");
            }
            return result;
        }

        private static IntPtr MakeFormat(int channels, uint channelMask, bool extensible)
        {
            byte[] bytes = new byte[extensible ? 40 : 18];
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)(extensible ? 0xFFFE : 1)), 0, bytes, 0, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)channels), 0, bytes, 2, 2);
            Buffer.BlockCopy(BitConverter.GetBytes(48000), 0, bytes, 4, 4);
            Buffer.BlockCopy(BitConverter.GetBytes(48000 * channels * 2), 0, bytes, 8, 4);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)(channels * 2)), 0, bytes, 12, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 14, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)(extensible ? 22 : 0)), 0, bytes, 16, 2);
            if (extensible)
            {
                Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 18, 2);
                Buffer.BlockCopy(BitConverter.GetBytes(channelMask), 0, bytes, 20, 4);
                Buffer.BlockCopy(Pcm.ToByteArray(), 0, bytes, 24, 16);
            }
            IntPtr result = Marshal.AllocHGlobal(bytes.Length);
            Marshal.Copy(bytes, 0, result, bytes.Length);
            return result;
        }

        private static FormatQuery Query(IAudioClient client, int mode, int channels, uint mask, bool extensible)
        {
            IntPtr requested = MakeFormat(channels, mask, extensible);
            IntPtr closestPointer = IntPtr.Zero;
            IntPtr closest = IntPtr.Zero;
            try
            {
                if (mode == 0)
                {
                    closestPointer = Marshal.AllocHGlobal(IntPtr.Size);
                    Marshal.WriteIntPtr(closestPointer, IntPtr.Zero);
                }
                int hr = client.IsFormatSupported(mode, requested, closestPointer);
                if (closestPointer != IntPtr.Zero) closest = Marshal.ReadIntPtr(closestPointer);
                FormatQuery result = new FormatQuery();
                result.ShareMode = mode == 0 ? "shared" : "exclusive";
                result.Requested = ReadFormat(requested);
                result.HResult = hr;
                result.HResultHex = Hex(hr);
                result.Result = hr == 0 ? "supported_exactly" : (hr == 1 ? "closest_format_only" : (unchecked((uint)hr) == 0x88890008 ? "unsupported_format" : "error"));
                result.ClosestFormat = ReadFormat(closest);
                return result;
            }
            finally
            {
                if (closest != IntPtr.Zero) Marshal.FreeCoTaskMem(closest);
                if (closestPointer != IntPtr.Zero) Marshal.FreeHGlobal(closestPointer);
                Marshal.FreeHGlobal(requested);
            }
        }

        private static void ReadProperties(IDevice device, Endpoint endpoint)
        {
            IProperties properties = null;
            try
            {
                Require(device.OpenPropertyStore(0, out properties), "OpenPropertyStore read-only");
                uint count;
                Require(properties.GetCount(out count), "Property GetCount");
                for (uint index = 0; index < count; index++)
                {
                    PropertyKey key;
                    Require(properties.GetAt(index, out key), "Property GetAt");
                    PropertyValue value;
                    int hr = properties.GetValue(ref key, out value);
                    try
                    {
                        if (hr >= 0)
                        {
                            string text = value.Describe();
                            endpoint.Properties[key.ToString()] = text;
                            if (key.FormatId == FriendlyName.FormatId && key.Id == FriendlyName.Id) endpoint.FriendlyName = text;
                        }
                    }
                    finally { PropVariantClear(ref value); }
                }
            }
            finally { Release(properties); }
        }

        private static void ProbeEndpoint(IDevice device, Endpoint endpoint)
        {
            IntPtr clientPointer = IntPtr.Zero;
            IAudioClient client = null;
            IntPtr mix = IntPtr.Zero;
            try
            {
                Guid iid = AudioClientId;
                Require(device.Activate(ref iid, 23, IntPtr.Zero, out clientPointer), "Activate IAudioClient");
                client = (IAudioClient)Marshal.GetTypedObjectForIUnknown(clientPointer, typeof(IAudioClient));
                Require(client.GetMixFormat(out mix), "GetMixFormat");
                endpoint.MixFormat = ReadFormat(mix);
                long normal, minimum;
                Require(client.GetDevicePeriod(out normal, out minimum), "GetDevicePeriod");
                endpoint.DefaultPeriod100ns = normal;
                endpoint.MinimumPeriod100ns = minimum;
                int[] channels = { 2, 6, 6, 8, 2, 6, 8 };
                uint[] masks = { 3, 0x3F, 0x60F, 0x63F, 0, 0, 0 };
                for (int mode = 0; mode <= 1; mode++)
                    for (int index = 0; index < channels.Length; index++)
                        endpoint.Queries.Add(Query(client, mode, channels[index], masks[index], index < 4));
            }
            finally
            {
                if (mix != IntPtr.Zero) Marshal.FreeCoTaskMem(mix);
                Release(client);
                if (clientPointer != IntPtr.Zero) Marshal.Release(clientPointer);
            }
        }

        public static Endpoint[] Run()
        {
            List<Endpoint> result = new List<Endpoint>();
            IDeviceEnumerator enumerator = null;
            IDeviceCollection collection = null;
            try
            {
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.EnumAudioEndpoints(2, 1, out collection), "EnumAudioEndpoints active render/capture");
                uint count;
                Require(collection.GetCount(out count), "Endpoint GetCount");
                for (uint index = 0; index < count; index++)
                {
                    IDevice device = null;
                    IntPtr id = IntPtr.Zero;
                    Endpoint endpoint = new Endpoint();
                    try
                    {
                        Require(collection.Item(index, out device), "Endpoint Item");
                        Require(device.GetId(out id), "GetId");
                        endpoint.Id = Marshal.PtrToStringUni(id);
                        endpoint.Flow = endpoint.Id.StartsWith("{0.0.0.") ? "render" : "capture";
                        Require(device.GetState(out endpoint.State), "GetState");
                        ReadProperties(device, endpoint);
                        if (endpoint.FriendlyName != null && endpoint.FriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) >= 0)
                        {
                            endpoint.IsCm6206Candidate = true;
                            endpoint.MatchReason = "FriendlyName contains USB Sound Device; corroborate with PnP VID/PID and endpoint parent in the PowerShell report.";
                        }
                        foreach (string property in endpoint.Properties.Values)
                        {
                            if (property != null && property.IndexOf("VID_0D8C&PID_0102", StringComparison.OrdinalIgnoreCase) >= 0)
                            {
                                endpoint.IsCm6206Candidate = true;
                                endpoint.MatchReason = "Endpoint property directly references VID_0D8C&PID_0102.";
                            }
                        }
                        if (endpoint.IsCm6206Candidate) ProbeEndpoint(device, endpoint);
                    }
                    catch (Exception error) { endpoint.Errors.Add(error.Message); }
                    finally
                    {
                        if (id != IntPtr.Zero) Marshal.FreeCoTaskMem(id);
                        Release(device);
                    }
                    result.Add(endpoint);
                }
            }
            finally { Release(collection); Release(enumerator); }
            return result.ToArray();
        }
    }
}
