using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class RegistroAudioPrivileges {
    [StructLayout(LayoutKind.Sequential)] struct Luid { public uint Low; public int High; }
    [StructLayout(LayoutKind.Sequential)] struct TokenPrivilege { public uint Count; public Luid Id; public uint Attributes; }
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool LookupPrivilegeValue(string system, string name, out Luid id);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivilege privilege, uint size, IntPtr previous, IntPtr returned);
    [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode)] static extern int RegOpenKeyEx(IntPtr root, string subkey, uint options, uint access, out IntPtr key);
    [DllImport("advapi32.dll")] static extern int RegCloseKey(IntPtr key);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string sddl, uint revision, out IntPtr descriptor, out uint bytes);
    [DllImport("advapi32.dll")] static extern int RegSetKeySecurity(IntPtr key, uint information, IntPtr descriptor);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr data);
    public static void SetAudioKeySecurity(string subkey, string sddl, uint information, uint access) {
        string first = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render\{1480f3d6-872e-45ff-a839-c8b330d0127e}\FxProperties";
        string second = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render\{e89cb1c3-e885-4df2-800f-ac950f115f89}\FxProperties";
        if (subkey != first && subkey != second) throw new ArgumentException("Unexpected audio registry key");
        IntPtr key;
        int error = RegOpenKeyEx(new IntPtr(unchecked((int)0x80000002)), subkey, 0, access | 0x100, out key);
        if (error != 0) throw new Win32Exception(error);
        IntPtr descriptor = IntPtr.Zero;
        try {
            uint bytes;
            if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, out descriptor, out bytes)) throw new Win32Exception();
            error = RegSetKeySecurity(key, information, descriptor);
            if (error != 0) throw new Win32Exception(error);
        } finally { if (descriptor != IntPtr.Zero) LocalFree(descriptor); RegCloseKey(key); }
    }
    public static void Enable(string name) {
        IntPtr token;
        if (!OpenProcessToken(GetCurrentProcess(), 0x28, out token)) throw new Win32Exception();
        try {
            Luid id;
            if (!LookupPrivilegeValue(null, name, out id)) throw new Win32Exception();
            TokenPrivilege privilege = new TokenPrivilege { Count=1, Id=id, Attributes=2 };
            if (!AdjustTokenPrivileges(token, false, ref privilege, 0, IntPtr.Zero, IntPtr.Zero)) throw new Win32Exception();
            int error = Marshal.GetLastWin32Error();
            if (error != 0) throw new Win32Exception(error);
        } finally { CloseHandle(token); }
    }
}
