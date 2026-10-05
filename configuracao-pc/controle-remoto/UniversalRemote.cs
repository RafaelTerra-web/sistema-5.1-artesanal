using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class UniversalRemote
{
    public sealed class WindowInfo { public string id, title, app; public bool current; }
    private delegate bool EnumProc(IntPtr hwnd, IntPtr parameter);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc callback, IntPtr parameter);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int count);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool ShowWindowAsync(IntPtr hwnd, int command);
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool PeekMessage(out Message message, IntPtr hwnd, uint min, uint max, uint remove);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attribute, out int value, int size);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(Point point);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll", SetLastError=true)] private static extern uint SendInput(uint count, Input[] inputs, int size);
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X,Y; }
    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left,Top,Right,Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct Message { public IntPtr hwnd; public uint message; public UIntPtr wParam; public IntPtr lParam; public uint time; public Point point; public uint privateData; }
    [StructLayout(LayoutKind.Sequential)] private struct MouseInput { public int dx,dy; public uint data,flags,time; public UIntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] private struct KeyInput { public ushort key,scan; public uint flags,time; public UIntPtr extra; }
    [StructLayout(LayoutKind.Explicit)] private struct Union { [FieldOffset(0)] public MouseInput mouse; [FieldOffset(0)] public KeyInput key; }
    [StructLayout(LayoutKind.Sequential)] private struct Input { public uint type; public Union data; }

    private static WindowInfo Describe(IntPtr hwnd)
    {
        if (!IsWindowVisible(hwnd)) return null;
        int cloaked; if(DwmGetWindowAttribute(hwnd,14,out cloaked,4)==0 && cloaked!=0)return null;
        StringBuilder title=new StringBuilder(512); GetWindowText(hwnd,title,title.Capacity);
        if(title.Length==0 || title.ToString()=="Program Manager")return null;
        uint pid; GetWindowThreadProcessId(hwnd,out pid);
        try { using(Process process=Process.GetProcessById((int)pid)) {
            if(process.ProcessName=="powershell" || process.ProcessName=="node")return null;
            return new WindowInfo { id="window:"+hwnd.ToInt64().ToString("x")+":"+pid+":"+process.StartTime.ToUniversalTime().Ticks,
                title=title.ToString(),app=process.ProcessName,current=hwnd==GetForegroundWindow() };
        }} catch {return null;}
    }
    public static WindowInfo[] Windows()
    {
        List<WindowInfo> windows=new List<WindowInfo>();
        EnumWindows((hwnd,p)=>{WindowInfo info=Describe(hwnd); if(info!=null)windows.Add(info);return true;},IntPtr.Zero);
        return windows.ToArray();
    }
    private static IntPtr Resolve(string target)
    {
        if(target=="foreground") {
            IntPtr hwnd=GetForegroundWindow(); if(Describe(hwnd)!=null)return hwnd;
        } else {
            IntPtr found=IntPtr.Zero;
            EnumWindows((hwnd,p)=>{WindowInfo info=Describe(hwnd);if(info!=null && info.id==target){found=hwnd;return false;}return true;},IntPtr.Zero);
            if(found!=IntPtr.Zero)return found;
        }
        throw new InvalidOperationException("Esse aplicativo nao esta disponivel. Atualize e escolha outra janela.");
    }
    private static void Focus(IntPtr hwnd)
    {
        if(IsIconic(hwnd))ShowWindowAsync(hwnd,9);
        if(GetForegroundWindow()!=hwnd) {
            // A console worker has no message queue until it calls a message API.
            // AttachThreadInput cannot attach a thread without that queue.
            Message message;PeekMessage(out message,IntPtr.Zero,0,0,0);
            uint ignored;uint current=GetCurrentThreadId(),foreground=GetWindowThreadProcessId(GetForegroundWindow(),out ignored),target=GetWindowThreadProcessId(hwnd,out ignored);
            bool attachedForeground=false,attachedTarget=false;
            try {
                if(current!=foreground && foreground!=0)attachedForeground=AttachThreadInput(current,foreground,true);
                if(current!=target && foreground!=target)attachedTarget=AttachThreadInput(current,target,true);
                BringWindowToTop(hwnd);SetForegroundWindow(hwnd);
            }
            finally {
                if(attachedTarget)AttachThreadInput(current,target,false);
                if(attachedForeground)AttachThreadInput(current,foreground,false);
            }
            for(int n=0;n<12 && GetForegroundWindow()!=hwnd;n++)Thread.Sleep(20);
        }
        if(!IsWindow(hwnd) || GetForegroundWindow()!=hwnd)throw new InvalidOperationException("O Windows nao ativou essa janela. Abra o aplicativo no PC e tente novamente.");
    }
    private static Input Key(ushort key,bool up,bool unicode)
    { return new Input {type=1,data=new Union {key=new KeyInput {key=unicode?(ushort)0:key,scan=unicode?key:(ushort)0,flags=(up?2u:0u)|(unicode?4u:0u)}}}; }
    private static void Send(IntPtr hwnd,List<Input> inputs)
    {
        if(GetForegroundWindow()!=hwnd)throw new InvalidOperationException("O aplicativo mudou antes do comando. Tente novamente.");
        if(SendInput((uint)inputs.Count,inputs.ToArray(),Marshal.SizeOf(typeof(Input)))!=(uint)inputs.Count)
            throw new InvalidOperationException("O Windows recusou o comando. Aplicativos executados como administrador podem nao aceitar o controle.");
    }
    private static void Press(IntPtr hwnd,ushort key,ushort modifier)
    {
        List<Input> inputs=new List<Input>();if(modifier!=0)inputs.Add(Key(modifier,false,false));
        inputs.Add(Key(key,false,false));inputs.Add(Key(key,true,false));if(modifier!=0)inputs.Add(Key(modifier,true,false));Send(hwnd,inputs);
    }
    private static Point CursorInside(IntPtr hwnd)
    {
        Rect r;if(!GetWindowRect(hwnd,out r))throw new InvalidOperationException("Janela indisponivel.");
        Point p;GetCursorPos(out p);
        if(p.X<r.Left || p.X>=r.Right || p.Y<r.Top || p.Y>=r.Bottom) {p.X=(r.Left+r.Right)/2;p.Y=(r.Top+r.Bottom)/2;SetCursorPos(p.X,p.Y);}
        return p;
    }
    public static void Command(string target,string action,string mode,string text,int dx,int dy)
    {
        // Validate completely before focusing or injecting input.
        string allowed="|up|down|left|right|select|back|home|search|fullscreen|tab|shifttab|browserback|space|rewind|forward|skipintro|next|previous|scrollup|scrolldown|move|text|";
        if(action==null || action.IndexOf('|')>=0 || !allowed.Contains("|"+action+"|") || (mode!="keyboard" && mode!="mouse"))throw new ArgumentException("Comando invalido.");
        if(Math.Abs((long)dx)>300 || Math.Abs((long)dy)>300)throw new ArgumentException("Movimento invalido.");
        if(action=="text" && (String.IsNullOrWhiteSpace(text) || text.Length>500))throw new ArgumentException("Use ate 500 caracteres.");
        if(action=="text")foreach(char c in text)if(Char.IsControl(c))throw new ArgumentException("Texto invalido.");
        IntPtr hwnd=Resolve(target);
        if(action=="skipintro" && Describe(hwnd).title.IndexOf("Netflix",StringComparison.OrdinalIgnoreCase)<0)throw new ArgumentException("Pular introducao e um atalho da Netflix. Selecione a janela Netflix.");
        Focus(hwnd);
        if(action=="move" || (mode=="mouse" && (action=="up" || action=="down" || action=="left" || action=="right"))) {
            if(action!="move") {dx=action=="left"?-64:action=="right"?64:0;dy=action=="up"?-64:action=="down"?64:0;}
            Point p=CursorInside(hwnd);Rect r;GetWindowRect(hwnd,out r);
            SetCursorPos(Math.Max(r.Left+3,Math.Min(r.Right-4,p.X+dx)),Math.Max(r.Top+3,Math.Min(r.Bottom-4,p.Y+dy)));return;
        }
        if((mode=="mouse" && action=="select") || action=="scrollup" || action=="scrolldown") {
            Point point=CursorInside(hwnd);
            if(GetAncestor(WindowFromPoint(point),2)!=hwnd)throw new InvalidOperationException("O ponteiro esta sobre outra janela. Mova o mouse e tente novamente.");
            List<Input> inputs=new List<Input>();
            if(action=="select") {inputs.Add(new Input {type=0,data=new Union {mouse=new MouseInput {flags=2}}});inputs.Add(new Input {type=0,data=new Union {mouse=new MouseInput {flags=4}}});}
            else inputs.Add(new Input {type=0,data=new Union {mouse=new MouseInput {flags=0x800,data=unchecked((uint)(action=="scrollup"?120:-120))}}});
            Send(hwnd,inputs);return;
        }
        if(action=="text") {List<Input> inputs=new List<Input>();foreach(char c in text){inputs.Add(Key(c,false,true));inputs.Add(Key(c,true,true));}Send(hwnd,inputs);return;}
        ushort key=0,modifier=0;
        switch(action) {
            case "up":key=0x26;break;case "down":key=0x28;break;case "left":case "rewind":key=0x25;break;case "right":case "forward":key=0x27;break;
            case "select":key=0x0d;break;case "back":key=0x1b;break;case "home":key=0x24;modifier=0x11;break;
            case "search":key=0x46;modifier=0x11;break;case "fullscreen":key=Describe(hwnd).title.IndexOf("Netflix",StringComparison.OrdinalIgnoreCase)>=0?(ushort)0x46:(ushort)0x7a;break;
            case "tab":key=9;break;case "shifttab":key=9;modifier=0x10;break;case "browserback":key=0x25;modifier=0x12;break;
            case "space":key=0x20;break;case "skipintro":key=0x53;break;case "next":key=0xb0;break;case "previous":key=0xb1;break;
        }
        Press(hwnd,key,modifier);
    }
}
