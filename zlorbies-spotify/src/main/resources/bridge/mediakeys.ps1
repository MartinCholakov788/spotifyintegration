# Spotify Integration Mod - media key helper (optional, only while your own music plays).
#
# Lets the keyboard's play/pause, next and previous keys control the songs of your music folder even when Minecraft is in
# the background. It does nothing else: no keystrokes are read, only these three keys are listened for.
#
#   stdout : "H ok"           once, when at least one key could be taken
#            "K play|next|prev" when that key is pressed
#            "E <message>"     when no key could be taken (another program uses them)
#   stdin  : "quit" (or closing it) stops the helper and gives the keys back
#
# Keep this file plain ASCII (Windows PowerShell 5.1 reads BOM-less files as ANSI).

$ErrorActionPreference = 'Stop'

try {
    Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Threading;

public static class ZKeys
{
    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int x; public int y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public POINT pt;
    }

    [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr h, int id, uint mod, uint vk);
    [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr h, int id);
    [DllImport("user32.dll")] static extern int GetMessage(out MSG m, IntPtr h, uint a, uint b);
    [DllImport("user32.dll")] static extern bool PostThreadMessage(uint tid, uint msg, IntPtr w, IntPtr l);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    static uint tid;
    static Thread loop;

    public static void Start()
    {
        loop = new Thread(Run);
        loop.IsBackground = true;
        loop.Start();
    }

    public static void Stop()
    {
        if (tid != 0) PostThreadMessage(tid, 0x12, IntPtr.Zero, IntPtr.Zero);
        if (loop != null) loop.Join(1000);
    }

    static void Say(string s)
    {
        Console.Out.WriteLine(s);
        Console.Out.Flush();
    }

    static void Run()
    {
        tid = GetCurrentThreadId();
        // 0xB3 play/pause, 0xB0 next track, 0xB1 previous track
        uint[] vk = new uint[] { 0xB3, 0xB0, 0xB1 };
        string[] name = new string[] { "play", "next", "prev" };
        int got = 0;
        for (int i = 0; i < vk.Length; i++)
        {
            if (RegisterHotKey(IntPtr.Zero, i + 1, 0x4000, vk[i])) got++; // 0x4000 = MOD_NOREPEAT
        }
        if (got == 0) { Say("E another program already uses the media keys"); return; }
        Say("H ok");
        MSG m;
        while (GetMessage(out m, IntPtr.Zero, 0, 0) > 0)
        {
            if (m.message == 0x0312)
            {
                int id = m.wParam.ToInt32() - 1;
                if (id >= 0 && id < name.Length) Say("K " + name[id]);
            }
        }
        for (int i = 0; i < vk.Length; i++) UnregisterHotKey(IntPtr.Zero, i + 1);
    }
}
'@
    [ZKeys]::Start()
    while ($true) {
        $line = [Console]::In.ReadLine()
        if ($null -eq $line -or $line.Trim() -eq 'quit') { break }
    }
    [ZKeys]::Stop()
}
catch {
    [Console]::Out.WriteLine('E ' + $_.Exception.Message)
    exit 1
}
