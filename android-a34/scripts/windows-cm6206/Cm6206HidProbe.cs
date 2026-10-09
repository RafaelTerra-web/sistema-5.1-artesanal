using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Sistema51.Hardware {
    public sealed class HidRegister { public int register; public bool ok; public string valueHex; public string replyHex; public string error; }
    public sealed class HidDeviceReport { public string path; public int inputReportBytes; public int outputReportBytes; public List<HidRegister> registers=new List<HidRegister>(); public string error; }
    public static class Cm6206HidProbe {
        [StructLayout(LayoutKind.Sequential)] private struct InterfaceData { public int size; public Guid guid; public int flags; public IntPtr reserved; }
        [StructLayout(LayoutKind.Sequential)] private struct Caps {
            public ushort Usage,UsagePage,InputReportByteLength,OutputReportByteLength,FeatureReportByteLength;
            [MarshalAs(UnmanagedType.ByValArray,SizeConst=17)] public ushort[] Reserved;
            public ushort Links,InputButtons,InputValues,InputData,OutputButtons,OutputValues,OutputData,FeatureButtons,FeatureValues,FeatureData;
        }
        [DllImport("hid.dll")] private static extern void HidD_GetHidGuid(out Guid guid);
        [DllImport("setupapi.dll",SetLastError=true,CharSet=CharSet.Unicode)] private static extern IntPtr SetupDiGetClassDevs(ref Guid guid,string enumerator,IntPtr parent,uint flags);
        [DllImport("setupapi.dll",SetLastError=true)] private static extern bool SetupDiEnumDeviceInterfaces(IntPtr info,IntPtr device,ref Guid guid,uint index,ref InterfaceData data);
        [DllImport("setupapi.dll",SetLastError=true,CharSet=CharSet.Unicode)] private static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr info,ref InterfaceData data,IntPtr detail,uint size,out uint required,IntPtr devinfo);
        [DllImport("setupapi.dll")] private static extern bool SetupDiDestroyDeviceInfoList(IntPtr info);
        [DllImport("kernel32.dll",SetLastError=true,CharSet=CharSet.Unicode)] private static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint disposition,uint flags,IntPtr template);
        [DllImport("hid.dll")] private static extern bool HidD_GetPreparsedData(SafeFileHandle handle,out IntPtr data);
        [DllImport("hid.dll")] private static extern bool HidD_FreePreparsedData(IntPtr data);
        [DllImport("hid.dll")] private static extern int HidP_GetCaps(IntPtr data,out Caps caps);
        [DllImport("hid.dll")] private static extern bool HidD_FlushQueue(SafeFileHandle handle);
        [DllImport("kernel32.dll")] private static extern bool CancelIoEx(SafeFileHandle handle,IntPtr overlapped);
        private static List<string> Paths() {
            Guid guid;HidD_GetHidGuid(out guid);IntPtr info=SetupDiGetClassDevs(ref guid,null,IntPtr.Zero,18);var paths=new List<string>();
            if(info==new IntPtr(-1))throw new IOException("SetupDiGetClassDevs: "+Marshal.GetLastWin32Error());
            try { for(uint index=0;;index++) {
                InterfaceData data=new InterfaceData();data.size=Marshal.SizeOf(typeof(InterfaceData));
                if(!SetupDiEnumDeviceInterfaces(info,IntPtr.Zero,ref guid,index,ref data))break;
                uint required;SetupDiGetDeviceInterfaceDetail(info,ref data,IntPtr.Zero,0,out required,IntPtr.Zero);
                IntPtr detail=Marshal.AllocHGlobal((int)required);
                try { Marshal.WriteInt32(detail,IntPtr.Size==8?8:6);if(!SetupDiGetDeviceInterfaceDetail(info,ref data,detail,required,out required,IntPtr.Zero))continue;
                    string path=Marshal.PtrToStringUni(IntPtr.Add(detail,4));if(path!=null&&path.ToLowerInvariant().Contains("vid_0d8c&pid_0102"))paths.Add(path);
                } finally {Marshal.FreeHGlobal(detail);}
            }} finally {SetupDiDestroyDeviceInfoList(info);}return paths;
        }
        public static List<HidDeviceReport> ReadOnlyRegisters() {
            var results=new List<HidDeviceReport>();
            foreach(string path in Paths()) {
                var item=new HidDeviceReport();item.path=path;results.Add(item);
                try { using(SafeFileHandle handle=CreateFile(path,0xC0000000,3,IntPtr.Zero,3,0x40000000,IntPtr.Zero)) {
                    if(handle.IsInvalid)throw new IOException("CreateFile: "+Marshal.GetLastWin32Error());
                    IntPtr data;if(!HidD_GetPreparsedData(handle,out data))throw new IOException("HidD_GetPreparsedData failed");Caps caps;
                    try {int status=HidP_GetCaps(data,out caps);if(status!=0x00110000)throw new IOException("HidP_GetCaps: "+status.ToString("X8"));}
                    finally {HidD_FreePreparsedData(data);}
                    item.inputReportBytes=caps.InputReportByteLength;item.outputReportBytes=caps.OutputReportByteLength;
                    if(caps.OutputReportByteLength<5||caps.InputReportByteLength<3)throw new IOException("Report length incompatible with known CM6206 protocol");
                    using(FileStream stream=new FileStream(handle,FileAccess.ReadWrite,512,true)) {
                        for(int register=0;register<6;register++) {
                            var result=new HidRegister();result.register=register;item.registers.Add(result);
                            try {HidD_FlushQueue(handle);byte[] command=new byte[caps.OutputReportByteLength];command[1]=0x30;command[4]=(byte)register;
                                var write=stream.WriteAsync(command,0,command.Length);if(!write.Wait(1000)){CancelIoEx(handle,IntPtr.Zero);throw new IOException("Read-register request timeout");}
                                byte[] reply=new byte[caps.InputReportByteLength];var read=stream.ReadAsync(reply,0,reply.Length);if(!read.Wait(1000)){CancelIoEx(handle,IntPtr.Zero);throw new IOException("Register response timeout");}
                                int count=read.Result;result.replyHex=BitConverter.ToString(reply,0,count);
                                int offset=count>=4&&reply[0]==0?1:0;
                                if(count<offset+3||(reply[offset]&0xE0)!=0x20)throw new IOException("Unexpected register response");
                                int value=reply[offset+1]|(reply[offset+2]<<8);result.valueHex="0x"+value.ToString("X4");result.ok=true;
                            }catch(Exception error){result.error=error.GetBaseException().Message;break;}
                        }
                    }
                }}catch(Exception error){item.error=error.GetBaseException().Message;}
            }return results;
        }
    }
}
