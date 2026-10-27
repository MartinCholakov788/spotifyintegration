# Spotify Integration Mod - audio capture helper (optional, "Real audio analysis").
#
# Listens to the sound a Windows app is playing (Windows 10 build 19041 or newer: only that app and its helper
# processes; older Windows: everything you hear) and writes it, as small mono 16-bit pieces, to stdout so the mod can
# draw the real spectrum and beat of the song. Nothing is recorded to disk and nothing leaves the PC.
#
#   stdout : "H <mode> <rate>"            once, when listening has started (mode = app | system)
#            "P <base64 pcm16 mono>"      about 50 times a second
#            "E <message>"                 when something failed
#   stdin  : "quit" (or closing it) stops the helper
#
# Keep this file plain ASCII (Windows PowerShell 5.1 reads BOM-less files as ANSI).

param(
    [string]$Name = 'spotify',
    [int]$SystemOnly = 0
)

$ErrorActionPreference = 'Stop'

try {
    Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class ZCapture
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorCom { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAgileObject { }

    [ClassInterface(ClassInterfaceType.None)]
    private class Completion : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public ManualResetEvent Done = new ManualResetEvent(false);
        public int Result = -1;
        public object Interface;

        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            try
            {
                int hr;
                object iface;
                operation.GetActivateResult(out hr, out iface);
                Result = hr;
                Interface = iface;
            }
            catch (Exception)
            {
                Result = -2;
            }
            Done.Set();
            return 0;
        }
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true)]
    private static extern int ActivateAudioInterfaceAsync(
        [MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        ref Guid riid,
        IntPtr activationParams,
        IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation operation);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateEvent(IntPtr attributes, bool manualReset, bool initialState, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    private static Stream output;
    private static volatile bool quit;

    private static void Write(string text)
    {
        byte[] bytes = Encoding.ASCII.GetBytes(text + "\n");
        lock (typeof(ZCapture))
        {
            output.Write(bytes, 0, bytes.Length);
            output.Flush();
        }
    }

    // ---- what we are reading: format of the captured audio
    private static int channels;
    private static int rate;
    private static int bits;
    private static bool isFloat;

    private static IAudioClient clientObj;
    private static IAudioCaptureClient capture;
    private static IntPtr eventHandle = IntPtr.Zero;
    private static string mode = "";

    private static int FindProcess(string name)
    {
        Process best = null;
        foreach (Process p in Process.GetProcessesByName(name))
        {
            try
            {
                if (best == null) best = p;
                if (p.MainWindowHandle != IntPtr.Zero) { best = p; break; }
            }
            catch (Exception) { }
        }
        return best == null ? 0 : best.Id;
    }

    // ---- Windows 10 2004+: only the chosen program (and the processes it started)
    private static bool StartApp(int pid)
    {
        IntPtr blob = Marshal.AllocHGlobal(12);
        IntPtr variant = Marshal.AllocHGlobal(24);
        try
        {
            Marshal.WriteInt32(blob, 0, 1);      // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
            Marshal.WriteInt32(blob, 4, pid);    // TargetProcessId
            Marshal.WriteInt32(blob, 8, 0);      // PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
            for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(variant, i, 0);
            Marshal.WriteInt16(variant, 0, 65);  // VT_BLOB
            Marshal.WriteInt32(variant, 8, 12);  // cbSize
            Marshal.WriteIntPtr(variant, 8 + IntPtr.Size, blob);

            Completion handler = new Completion();
            IActivateAudioInterfaceAsyncOperation op;
            Guid iid = typeof(IAudioClient).GUID;
            int hr = ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid, variant, handler, out op);
            if (hr != 0) return false;
            if (!handler.Done.WaitOne(5000)) return false;
            if (handler.Result != 0 || handler.Interface == null) return false;
            clientObj = (IAudioClient)handler.Interface;

            // this kind of stream has no mix format: ask for plain 16-bit stereo
            rate = 48000;
            channels = 2;
            bits = 16;
            isFloat = false;
            IntPtr fmt = Marshal.AllocHGlobal(18);
            try
            {
                Marshal.WriteInt16(fmt, 0, 1);                      // WAVE_FORMAT_PCM
                Marshal.WriteInt16(fmt, 2, (short)channels);
                Marshal.WriteInt32(fmt, 4, rate);
                Marshal.WriteInt32(fmt, 8, rate * channels * 2);
                Marshal.WriteInt16(fmt, 12, (short)(channels * 2));
                Marshal.WriteInt16(fmt, 14, (short)bits);
                Marshal.WriteInt16(fmt, 16, 0);
                // loopback (0x20000) + event driven (0x40000) + let Windows convert if needed (0x80000000 | 0x08000000)
                int flags = 0x00020000 | 0x00040000 | unchecked((int)0x80000000) | 0x08000000;
                hr = clientObj.Initialize(0, flags, 2000000L, 0L, fmt, IntPtr.Zero);
                if (hr != 0) return false;
            }
            finally
            {
                Marshal.FreeHGlobal(fmt);
            }
            eventHandle = CreateEvent(IntPtr.Zero, false, false, null);
            if (clientObj.SetEventHandle(eventHandle) != 0) return false;
            Guid capIid = typeof(IAudioCaptureClient).GUID;
            object service;
            if (clientObj.GetService(ref capIid, out service) != 0) return false;
            capture = (IAudioCaptureClient)service;
            mode = "app";
            return true;
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            Marshal.FreeHGlobal(blob);
            Marshal.FreeHGlobal(variant);
        }
    }

    // ---- every Windows version: everything that goes to the speakers
    private static bool StartSystem()
    {
        try
        {
            IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDevice device;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
            Guid iid = typeof(IAudioClient).GUID;
            object activated;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out activated));
            clientObj = (IAudioClient)activated;
            IntPtr fmt;
            Marshal.ThrowExceptionForHR(clientObj.GetMixFormat(out fmt));
            int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
            channels = Marshal.ReadInt16(fmt, 2);
            rate = Marshal.ReadInt32(fmt, 4);
            bits = Marshal.ReadInt16(fmt, 14);
            isFloat = tag == 3;
            if (tag == 0xFFFE)
            {
                // WAVE_FORMAT_EXTENSIBLE: the first part of the sub-format GUID says float (3) or integer (1)
                isFloat = Marshal.ReadInt32(fmt, 24) == 3;
            }
            Marshal.ThrowExceptionForHR(clientObj.Initialize(0, 0x00020000, 2000000L, 0L, fmt, IntPtr.Zero));
            Guid capIid = typeof(IAudioCaptureClient).GUID;
            object service;
            Marshal.ThrowExceptionForHR(clientObj.GetService(ref capIid, out service));
            capture = (IAudioCaptureClient)service;
            eventHandle = IntPtr.Zero;
            mode = "system";
            return true;
        }
        catch (Exception e)
        {
            Write("E system capture: " + e.Message.Replace('\n', ' ').Replace('\r', ' '));
            return false;
        }
    }

    private static void Loop()
    {
        int chunkSamples = rate / 50;
        short[] chunk = new short[chunkSamples];
        byte[] bytes = new byte[chunkSamples * 2];
        int filled = 0;
        float[] mono = new float[2048];
        float[] fbuf = new float[4096];
        short[] sbuf = new short[4096];
        Marshal.ThrowExceptionForHR(clientObj.Start());
        while (!quit)
        {
            if (eventHandle != IntPtr.Zero) WaitForSingleObject(eventHandle, 100);
            else Thread.Sleep(8);
            uint packet;
            while (!quit && capture.GetNextPacketSize(out packet) == 0 && packet > 0)
            {
                IntPtr data;
                uint frames;
                uint flags;
                ulong devPos;
                ulong qpcPos;
                if (capture.GetBuffer(out data, out frames, out flags, out devPos, out qpcPos) != 0) break;
                bool silent = (flags & 2) != 0;
                int count = (int)frames;
                int total = count * channels;
                if (mono.Length < count) mono = new float[count];
                if (silent || total <= 0)
                {
                    for (int f = 0; f < count; f++) mono[f] = 0f;
                }
                else if (isFloat && bits == 32)
                {
                    if (fbuf.Length < total) fbuf = new float[total];
                    Marshal.Copy(data, fbuf, 0, total);
                    for (int f = 0; f < count; f++)
                    {
                        float sum = 0f;
                        for (int c = 0; c < channels; c++) sum += fbuf[f * channels + c];
                        mono[f] = sum / channels;
                    }
                }
                else if (bits == 16)
                {
                    if (sbuf.Length < total) sbuf = new short[total];
                    Marshal.Copy(data, sbuf, 0, total);
                    for (int f = 0; f < count; f++)
                    {
                        float sum = 0f;
                        for (int c = 0; c < channels; c++) sum += sbuf[f * channels + c];
                        mono[f] = sum / channels / 32768f;
                    }
                }
                else
                {
                    int step = bits / 8;
                    for (int f = 0; f < count; f++)
                    {
                        float sum = 0f;
                        for (int c = 0; c < channels; c++)
                        {
                            int off = (f * channels + c) * step;
                            if (bits == 32) sum += Marshal.ReadInt32(data, off) / 2147483648f;
                            else if (bits == 24) sum += (((Marshal.ReadByte(data, off + 2) << 24) | (Marshal.ReadByte(data, off + 1) << 16) | (Marshal.ReadByte(data, off) << 8)) >> 8) / 8388608f;
                        }
                        mono[f] = sum / channels;
                    }
                }
                for (int f = 0; f < count; f++)
                {
                    float v = mono[f];
                    if (v > 1f) v = 1f;
                    if (v < -1f) v = -1f;
                    chunk[filled++] = (short)(v * 32767f);
                    if (filled == chunkSamples)
                    {
                        Buffer.BlockCopy(chunk, 0, bytes, 0, bytes.Length);
                        Write("P " + Convert.ToBase64String(bytes));
                        filled = 0;
                    }
                }
                capture.ReleaseBuffer(frames);
            }
        }
        try { clientObj.Stop(); } catch (Exception) { }
    }

    public static void Run(string name, bool systemOnly)
    {
        output = Console.OpenStandardOutput();
        Thread watcher = new Thread(delegate ()
        {
            try
            {
                string line;
                while ((line = Console.In.ReadLine()) != null)
                {
                    if (line.Trim() == "quit") break;
                }
            }
            catch (Exception) { }
            quit = true;
        });
        watcher.IsBackground = true;
        watcher.Start();

        Thread worker = new Thread(delegate ()
        {
            try
            {
                bool ok = false;
                if (!systemOnly)
                {
                    int pid = FindProcess(name);
                    if (pid != 0) ok = StartApp(pid);
                    else Write("E " + name + " is not running");
                }
                if (!ok && !quit) ok = StartSystem();
                if (!ok) { Write("E no way to capture audio on this PC"); return; }
                Write("H " + mode + " " + rate);
                Loop();
            }
            catch (Exception e)
            {
                Write("E " + e.GetType().Name + ": " + e.Message.Replace('\n', ' ').Replace('\r', ' '));
            }
        });
        worker.SetApartmentState(ApartmentState.MTA);
        worker.Start();
        worker.Join();
    }
}
"@
    [ZCapture]::Run($Name, ($SystemOnly -ne 0))
} catch {
    [Console]::Out.WriteLine('E ' + ($_.Exception.Message -replace "[\r\n]+", ' '))
    exit 1
}
