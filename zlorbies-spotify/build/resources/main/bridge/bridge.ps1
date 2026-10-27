# Spotify Integration Mod - Windows media bridge.
#
# Started by the Minecraft mod (hidden). It talks to the Windows "System Media Transport Controls" - the same
# thing that powers the media overlay when you press a keyboard media key - so Spotify (or any other player)
# can be read and controlled without logging in to anything.
#
#   stdout : one JSON object per line  ({"t":"hello"|"state"|"error", ...})
#   stdin  : one command per line      (toggle, next, prev, seek <ms>, volume <0-100>, ...)
#
# Keep this file plain ASCII (Windows PowerShell 5.1 reads BOM-less files as ANSI).

param(
    [string]$OutDir = $env:TEMP,
    [string]$Source = 'spotify',
    [int]$PollMs = 500
)

$ErrorActionPreference = 'Stop'
$script:source = $Source
$script:pollMs = [Math]::Max(150, [Math]::Min(5000, $PollMs))
$script:outDir = $OutDir

$utf8 = New-Object System.Text.UTF8Encoding($false)
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), $utf8)
$stdout = New-Object System.IO.StreamWriter([Console]::OpenStandardOutput(), $utf8)
$stdout.AutoFlush = $true
$stdout.NewLine = "`n"

function Send($obj) {
    $stdout.WriteLine(($obj | ConvertTo-Json -Compress -Depth 4))
}

$script:lastErr = ''
$script:lastErrAt = [DateTime]::MinValue
function Send-Error([string]$where, $err) {
    $msg = $where + ': ' + $err.Exception.Message
    $now = [DateTime]::UtcNow
    if ($msg -ne $script:lastErr -or ($now - $script:lastErrAt).TotalSeconds -gt 15) {
        $script:lastErr = $msg
        $script:lastErrAt = $now
        Send ([ordered]@{ t = 'error'; msg = $msg })
    }
}

# ---------------------------------------------------------------- Windows media session (WinRT)
$script:smtcOk = $false
$script:mgr = $null
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $script:tManager = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
    $script:tProps = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
    $script:tStream = [Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
    $script:tRepeat = [Windows.Media.MediaPlaybackAutoRepeatMode,Windows.Media,ContentType=WindowsRuntime]

    $script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1

    function Await($op, $resultType) {
        $task = $script:asTask.MakeGenericMethod($resultType).Invoke($null, @($op))
        if (-not $task.Wait(6000)) { throw 'the Windows media call timed out' }
        return $task.Result
    }

    $managerType = $script:tManager
    $request = $managerType::RequestAsync()
    $script:mgr = Await $request $managerType
    $script:smtcOk = $true
} catch {
    Send-Error 'media session' $_
}

# ---------------------------------------------------------------- per-app volume + media keys (optional)
$script:audioOk = $false
try {
    Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

public static class ZAppAudio
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

    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionManager2
    {
        int GetAudioSessionControl(IntPtr a, int b, out IntPtr c);
        int GetSimpleAudioVolume(IntPtr a, int b, out IntPtr c);
        int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionEnumerator
    {
        int GetCount(out int count);
        int GetSession(int index, out IAudioSessionControl2 session);
    }

    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl2
    {
        int GetState(out int state);
        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, ref Guid context);
        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid context);
        int GetGroupingParam(out Guid grouping);
        int SetGroupingParam(ref Guid grouping, ref Guid context);
        int RegisterAudioSessionNotification(IntPtr notify);
        int UnregisterAudioSessionNotification(IntPtr notify);
        int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetProcessId(out uint pid);
        int IsSystemSoundsSession();
        int SetDuckingPreference([MarshalAs(UnmanagedType.Bool)] bool optOut);
    }

    [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISimpleAudioVolume
    {
        int SetMasterVolume(float level, ref Guid context);
        int GetMasterVolume(out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioMeterInformation
    {
        int GetPeakValue(out float peak);
        int GetMeteringChannelCount(out uint count);
        int GetChannelsPeakValues(uint count, [MarshalAs(UnmanagedType.LPArray)] float[] peaks);
        int QueryHardwareSupport(out uint mask);
    }

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

    private static List<ISimpleAudioVolume> Find(string processName)
    {
        List<ISimpleAudioVolume> found = new List<ISimpleAudioVolume>();
        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
        IMMDevice device;
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
        Guid iid = typeof(IAudioSessionManager2).GUID;
        object activated;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out activated));
        IAudioSessionManager2 manager = (IAudioSessionManager2)activated;
        IAudioSessionEnumerator sessions;
        Marshal.ThrowExceptionForHR(manager.GetSessionEnumerator(out sessions));
        int count;
        sessions.GetCount(out count);
        for (int i = 0; i < count; i++)
        {
            IAudioSessionControl2 control;
            sessions.GetSession(i, out control);
            uint pid;
            control.GetProcessId(out pid);
            if (pid == 0) continue;
            string name = "";
            try { name = Process.GetProcessById((int)pid).ProcessName; } catch (Exception) { }
            if (name.IndexOf(processName, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                ISimpleAudioVolume volume = control as ISimpleAudioVolume;
                if (volume != null) found.Add(volume);
            }
        }
        return found;
    }

    /// <summary>0..100, or -1 when the app has no audio session right now.</summary>
    public static int GetVolume(string processName)
    {
        List<ISimpleAudioVolume> list = Find(processName);
        if (list.Count == 0) return -1;
        float level;
        list[0].GetMasterVolume(out level);
        return (int)Math.Round(level * 100.0);
    }

    public static bool GetMute(string processName)
    {
        List<ISimpleAudioVolume> list = Find(processName);
        if (list.Count == 0) return false;
        bool muted;
        list[0].GetMute(out muted);
        return muted;
    }

    public static bool SetVolume(string processName, int percent)
    {
        List<ISimpleAudioVolume> list = Find(processName);
        Guid none = Guid.Empty;
        float level = Math.Max(0, Math.Min(100, percent)) / 100f;
        foreach (ISimpleAudioVolume v in list) v.SetMasterVolume(level, ref none);
        return list.Count > 0;
    }

    public static bool SetMute(string processName, bool mute)
    {
        List<ISimpleAudioVolume> list = Find(processName);
        Guid none = Guid.Empty;
        foreach (ISimpleAudioVolume v in list) v.SetMute(mute, ref none);
        return list.Count > 0;
    }

    // ---- live loudness of the app (the same meter the Windows volume mixer draws)
    private static readonly Stopwatch clock = Stopwatch.StartNew();
    private static List<IAudioMeterInformation> meters = new List<IAudioMeterInformation>();
    private static string meterApp = "";
    private static long meterAt = -100000;

    private static List<IAudioMeterInformation> FindMeters(string processName)
    {
        List<IAudioMeterInformation> found = new List<IAudioMeterInformation>();
        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
        IMMDevice device;
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
        Guid iid = typeof(IAudioSessionManager2).GUID;
        object activated;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out activated));
        IAudioSessionManager2 manager = (IAudioSessionManager2)activated;
        IAudioSessionEnumerator sessions;
        Marshal.ThrowExceptionForHR(manager.GetSessionEnumerator(out sessions));
        int count;
        sessions.GetCount(out count);
        for (int i = 0; i < count; i++)
        {
            IAudioSessionControl2 control;
            sessions.GetSession(i, out control);
            uint pid;
            control.GetProcessId(out pid);
            if (pid == 0) continue;
            string name = "";
            try { name = Process.GetProcessById((int)pid).ProcessName; } catch (Exception) { }
            if (name.IndexOf(processName, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                IAudioMeterInformation meter = control as IAudioMeterInformation;
                if (meter != null) found.Add(meter);
            }
        }
        return found;
    }

    /// <summary>0..1 loudest peak among the app's audio sessions right now, or -1 when it has none.</summary>
    public static float Peak(string processName)
    {
        long now = clock.ElapsedMilliseconds;
        bool same = string.Equals(meterApp, processName, StringComparison.OrdinalIgnoreCase);
        long age = now - meterAt;
        if (!same || age > (meters.Count == 0 ? 1000 : 2500))
        {
            try { meters = FindMeters(processName); } catch (Exception) { meters = new List<IAudioMeterInformation>(); }
            meterApp = processName;
            meterAt = now;
        }
        float best = -1f;
        bool stale = false;
        foreach (IAudioMeterInformation meter in meters)
        {
            try
            {
                float p;
                int hr = meter.GetPeakValue(out p);
                if (hr == 0) { if (p > best) best = p; } else { stale = true; }
            }
            catch (Exception) { stale = true; }
        }
        if (stale) meterAt = -100000;
        return best;
    }

    /// <summary>Presses a keyboard media key (0xB3 play/pause, 0xB0 next, 0xB1 previous).</summary>
    public static void MediaKey(int vk)
    {
        keybd_event((byte)vk, 0, 0x1, UIntPtr.Zero);
        keybd_event((byte)vk, 0, 0x3, UIntPtr.Zero);
    }
}
"@
    $script:audioOk = $true
} catch {
    Send-Error 'volume support' $_
}

# ---------------------------------------------------------------- helpers
function Get-Session {
    if (-not $script:smtcOk) { return $null }
    $sessions = $script:mgr.GetSessions()
    if ($script:source -eq 'spotify') {
        foreach ($s in $sessions) {
            if ($s.SourceAppUserModelId -match 'spotify') { return $s }
        }
        return $null
    }
    $cur = $script:mgr.GetCurrentSession()
    if ($null -ne $cur) { return $cur }
    foreach ($s in $sessions) { return $s }
    return $null
}

# Name of the process whose audio session carries the volume (Spotify.exe -> "Spotify").
function Get-AudioProcess($session) {
    if ($script:source -eq 'spotify') { return 'Spotify' }
    if ($null -eq $session) { return '' }
    $id = [string]$session.SourceAppUserModelId
    if ($id -match '^([^!\\/]+)\.exe$') { return $Matches[1] }
    if ($id -match 'spotify') { return 'Spotify' }
    return ''
}

function Spotify-Running {
    return ($null -ne (Get-Process -Name 'Spotify' -ErrorAction SilentlyContinue | Select-Object -First 1))
}

# Last-resort control: press the keyboard media key (only when Spotify is the target and is running).
function Press-MediaKey([int]$vk) {
    if (-not $script:audioOk) { return }
    if ($script:source -eq 'spotify' -and -not (Spotify-Running)) { return }
    [ZAppAudio]::MediaKey($vk)
}

# ---------------------------------------------------------------- cover art
$script:coverKey = ''
$script:coverName = ''
$script:coverHash = ''
$script:coverRev = 0
$script:coverTries = 0
$script:coverNextAt = [DateTime]::MinValue
$script:coverStatus = 'waiting for the first song'
$script:coverType = ''

# Three ways to get the bytes out of the Windows picture stream; the first one that works wins.
# The stream often reports Size 0 (it is fetched on demand), so every method reads until the stream ends
# instead of trusting the size.
$script:asStreamForRead = $null
try {
    $script:asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IInputStream'
    } | Select-Object -First 1
} catch { }

function Get-BytesAdapter($ras) {
    $ms = New-Object System.IO.MemoryStream
    # Called through reflection: PowerShell cannot pick the right overload for a Windows stream by itself.
    $stream = $script:asStreamForRead.Invoke($null, @($ras))
    $stream.CopyTo($ms)
    return ,$ms.ToArray()
}

function Get-BytesReader($ras) {
    $tReader = [Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime]
    $reader = $tReader::new($ras)
    $ms = New-Object System.IO.MemoryStream
    while ($ms.Length -lt 8388608) {
        $n = [uint32](Await ($reader.LoadAsync([uint32]65536)) ([uint32]))
        if ($n -eq 0) { break }
        $chunk = New-Object byte[] ([int]$n)
        $reader.ReadBytes($chunk)
        $ms.Write($chunk, 0, $chunk.Length)
    }
    try { [void]$reader.DetachStream() } catch { }
    return ,$ms.ToArray()
}

function Get-BytesBuffer($ras) {
    $tBuf = [Windows.Storage.Streams.Buffer,Windows.Storage.Streams,ContentType=WindowsRuntime]
    $tIBuf = [Windows.Storage.Streams.IBuffer,Windows.Storage.Streams,ContentType=WindowsRuntime]
    $asTaskP = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperationWithProgress`2'
    } | Select-Object -First 1
    $method = $asTaskP.MakeGenericMethod($tIBuf, [uint32])
    $ms = New-Object System.IO.MemoryStream
    while ($ms.Length -lt 8388608) {
        $buf = $tBuf::new([uint32]65536)
        $op = $ras.ReadAsync($buf, [uint32]65536, 0)
        $task = $method.Invoke($null, @($op))
        if (-not $task.Wait(6000)) { throw 'the picture read timed out' }
        $result = $task.Result
        if ($result.Length -eq 0) { break }
        $chunk = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::ToArray($result)
        $ms.Write($chunk, 0, $chunk.Length)
    }
    return ,$ms.ToArray()
}

# The game reads JPEG, PNG, GIF and BMP. Anything else is converted to PNG here.
function Convert-Picture([byte[]]$b) {
    if ($b.Length -ge 4) {
        if ($b[0] -eq 0xFF -and $b[1] -eq 0xD8) { return ,$b }
        if ($b[0] -eq 0x89 -and $b[1] -eq 0x50) { return ,$b }
        if ($b[0] -eq 0x47 -and $b[1] -eq 0x49) { return ,$b }
        if ($b[0] -eq 0x42 -and $b[1] -eq 0x4D) { return ,$b }
    }
    try {
        Add-Type -AssemblyName System.Drawing
        $in = New-Object System.IO.MemoryStream (,$b)
        $img = [System.Drawing.Image]::FromStream($in)
        $out = New-Object System.IO.MemoryStream
        $img.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
        $img.Dispose()
        return ,$out.ToArray()
    } catch {
        $head = ($b[0..([Math]::Min(7, $b.Length - 1))] | ForEach-Object { $_.ToString('X2') }) -join ' '
        $script:coverStatus = 'unknown picture format (starts with ' + $head + ')'
        return ,$b
    }
}

function Read-Cover($props) {
    if ($null -eq $props) { $script:coverStatus = 'no media information from the player'; return $null }
    $thumb = $props.Thumbnail
    if ($null -eq $thumb) { $script:coverStatus = 'the player has not given a picture (yet)'; return $null }
    $ras = Await ($thumb.OpenReadAsync()) $script:tStream
    $problems = @()
    $bytes = $null
    $sizeNote = 'unknown'
    try {
        try { $script:coverType = [string]$ras.ContentType } catch { }
        try { $sizeNote = [string]$ras.Size } catch { }
        foreach ($method in @('adapter', 'reader', 'buffer')) {
            try { $ras.Seek(0) } catch { }
            try {
                $b = $null
                if ($method -eq 'adapter') { $b = Get-BytesAdapter $ras }
                elseif ($method -eq 'reader') { $b = Get-BytesReader $ras }
                else { $b = Get-BytesBuffer $ras }
                if ($null -ne $b -and $b.Length -gt 64) { $bytes = $b; break }
                $got = 0
                if ($null -ne $b) { $got = $b.Length }
                $problems += ($method + ': ' + $got + ' bytes')
            } catch {
                $problems += ($method + ': ' + $_.Exception.Message)
            }
        }
    } finally {
        try { $ras.Dispose() } catch { }
    }
    if ($null -eq $bytes) {
        $script:coverStatus = 'could not read the picture - ' + ($problems -join ' | ') + ' [stream size ' + $sizeNote + ', type ' + $script:coverType + ']'
        return $null
    }
    return ,(Convert-Picture $bytes)
}

function Update-Cover($props, [string]$key) {
    $now = [DateTime]::UtcNow
    if ($key -ne $script:coverKey) {
        # The picture is kept until a different one arrives: songs of one album share their cover.
        $script:coverKey = $key
        $script:coverTries = 14
        $script:coverNextAt = $now
    }
    # While no picture has been found at all, keep looking now and then (the player may be slow to load it).
    $keepLooking = ($script:coverName -eq '')
    if ($now -lt $script:coverNextAt) { return }
    if ($script:coverTries -le 0 -and -not $keepLooking) { return }
    if ($script:coverTries -gt 0) { $script:coverTries-- }
    $wait = 1000
    if ($script:coverTries -lt 8) { $wait = 2500 }
    if ($script:coverTries -le 0) { $wait = 5000 }
    $script:coverNextAt = $now.AddMilliseconds($wait)
    try {
        [byte[]]$bytes = Read-Cover $props
        if ($null -eq $bytes -or $bytes.Length -lt 64) { return }
        $hash = [System.BitConverter]::ToString((New-Object System.Security.Cryptography.SHA1Managed).ComputeHash($bytes))
        if ($hash -eq $script:coverHash) { $script:coverStatus = 'ok'; return }
        $script:coverHash = $hash
        $script:coverRev++
        $name = 'cover_' + $script:coverRev + '.bin'
        [System.IO.File]::WriteAllBytes((Join-Path $script:outDir $name), $bytes)
        $script:coverName = $name
        $script:coverStatus = 'ok'
        # a few more looks, in case the player is still swapping in the final picture
        if ($script:coverTries -gt 3) { $script:coverTries = 3 }
        # keep the folder tidy: only the three newest covers stay
        $old = $script:coverRev - 3
        if ($old -gt 0) {
            $oldPath = Join-Path $script:outDir ('cover_' + $old + '.bin')
            if (Test-Path $oldPath) { Remove-Item $oldPath -Force -ErrorAction SilentlyContinue }
        }
    } catch {
        $script:coverStatus = 'error: ' + $_.Exception.Message
        Send-Error 'cover art' $_
    }
}

# ---------------------------------------------------------------- state
$script:vol = -1
$script:muted = $false
$script:volTick = 0

function Refresh-Volume($session) {
    if (-not $script:audioOk) { $script:vol = -1; return }
    $name = Get-AudioProcess $session
    if ($name -eq '') { $script:vol = -1; return }
    try {
        $script:vol = [ZAppAudio]::GetVolume($name)
        $script:muted = [ZAppAudio]::GetMute($name)
    } catch {
        $script:vol = -1
    }
}

function Emit-Fallback {
    if ($script:source -eq 'spotify') { $script:audioName = 'Spotify' }
    # No media session available: read the Spotify window title ("Artist - Song" while playing).
    $p = Get-Process -Name 'Spotify' -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | Select-Object -First 1
    $title = ''
    $artist = ''
    $status = 'none'
    if ($null -ne $p) {
        $status = 'paused'
        if ($p.MainWindowTitle -match '^(.+?) - (.+)$') {
            $artist = $Matches[1]
            $title = $Matches[2]
            $status = 'playing'
        }
    }
    Send ([ordered]@{
        t = 'state'; status = $status; app = 'Spotify'; title = $title; artist = $artist; album = ''
        pos = 0; dur = 0; shuffle = $false; repeat = 0
        canSeek = $false; canNext = $true; canPrev = $true; canToggle = $true; canShuffle = $false; canRepeat = $false
        vol = -1; mute = $false; cover = ''; basic = $true
        cs = 'no media session'; ct = ''
    })
}

function Emit-State {
    if (-not $script:smtcOk) { Emit-Fallback; return }
    $s = Get-Session
    if ($null -eq $s) {
        Send ([ordered]@{ t = 'state'; status = 'none'; app = ''; src = $script:source })
        return
    }
    $info = $s.GetPlaybackInfo()
    $tl = $s.GetTimelineProperties()
    $props = Await ($s.TryGetMediaPropertiesAsync()) $script:tProps

    $status = 'stopped'
    switch ([int]$info.PlaybackStatus) {
        4 { $status = 'playing' }
        5 { $status = 'paused' }
        default { $status = 'stopped' }
    }

    $script:audioName = Get-AudioProcess $s
    $title = [string]$props.Title
    $artist = [string]$props.Artist
    $album = [string]$props.AlbumTitle
    $key = ('{0:x}' -f ($title + '|' + $artist + '|' + $album).GetHashCode())
    Update-Cover $props $key

    $start = [double]$tl.StartTime.TotalMilliseconds
    $dur = [double]$tl.EndTime.TotalMilliseconds - $start
    $pos = [double]$tl.Position.TotalMilliseconds - $start
    if ($status -eq 'playing' -and $tl.LastUpdatedTime.Year -gt 2000) {
        $age = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
        if ($age -gt 0) { $pos += $age }
    }
    if ($dur -lt 0) { $dur = 0 }
    if ($pos -lt 0) { $pos = 0 }
    if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }

    $repeat = 0
    if ($null -ne $info.AutoRepeatMode) { $repeat = [int]$info.AutoRepeatMode }
    $shuffle = $false
    if ($null -ne $info.IsShuffleActive) { $shuffle = [bool]$info.IsShuffleActive }

    $script:volTick++
    if ($script:volTick -ge 4 -or $script:vol -lt 0) {
        $script:volTick = 0
        Refresh-Volume $s
    }

    $ctl = $info.Controls
    Send ([ordered]@{
        t = 'state'; status = $status; app = [string]$s.SourceAppUserModelId
        title = $title; artist = $artist; album = $album; albumArtist = [string]$props.AlbumArtist
        pos = [int64][Math]::Round($pos); dur = [int64][Math]::Round($dur)
        shuffle = $shuffle; repeat = $repeat
        canSeek = [bool]$ctl.IsPlaybackPositionEnabled
        canNext = [bool]$ctl.IsNextEnabled; canPrev = [bool]$ctl.IsPreviousEnabled
        canToggle = [bool]$ctl.IsPlayPauseToggleEnabled
        canShuffle = [bool]$ctl.IsShuffleEnabled; canRepeat = [bool]$ctl.IsRepeatEnabled
        vol = $script:vol; mute = $script:muted
        cover = $script:coverName; key = $key
        cs = $script:coverStatus; ct = $script:coverType
    })
}

# ---------------------------------------------------------------- commands
function Run-Control([string]$what, $arg) {
    $s = Get-Session
    $ok = $false
    if ($null -ne $s) {
        switch ($what) {
            'toggle'  { $ok = [bool](Await ($s.TryTogglePlayPauseAsync()) ([bool])) }
            'play'    { $ok = [bool](Await ($s.TryPlayAsync()) ([bool])) }
            'pause'   { $ok = [bool](Await ($s.TryPauseAsync()) ([bool])) }
            'next'    { $ok = [bool](Await ($s.TrySkipNextAsync()) ([bool])) }
            'prev'    { $ok = [bool](Await ($s.TrySkipPreviousAsync()) ([bool])) }
            'seek'    { $ok = [bool](Await ($s.TryChangePlaybackPositionAsync([int64]([double]$arg * 10000))) ([bool])) }
            'shuffle' { $ok = [bool](Await ($s.TryChangeShuffleActiveAsync([bool]([int]$arg -ne 0))) ([bool])) }
            'repeat'  {
                $mode = [Enum]::ToObject($script:tRepeat, [int]$arg)
                $ok = [bool](Await ($s.TryChangeAutoRepeatModeAsync($mode)) ([bool]))
            }
        }
    }
    if (-not $ok) {
        switch ($what) {
            'toggle' { Press-MediaKey 0xB3 }
            'next'   { Press-MediaKey 0xB0 }
            'prev'   { Press-MediaKey 0xB1 }
        }
    }
}

function Run-Command([string]$line) {
    $line = $line.Trim()
    if ($line -eq '') { return }
    $parts = $line -split ' ', 2
    $cmd = $parts[0].ToLowerInvariant()
    $arg = ''
    if ($parts.Count -gt 1) { $arg = $parts[1].Trim() }

    switch ($cmd) {
        'toggle'  { Run-Control 'toggle' $null }
        'play'    { Run-Control 'play' $null }
        'pause'   { Run-Control 'pause' $null }
        'next'    { Run-Control 'next' $null }
        'prev'    { Run-Control 'prev' $null }
        'seek'    { Run-Control 'seek' $arg }
        'shuffle' { Run-Control 'shuffle' $arg }
        'repeat'  { Run-Control 'repeat' $arg }
        'volume' {
            if ($script:audioOk) {
                $name = Get-AudioProcess (Get-Session)
                if ($name -ne '') {
                    [void][ZAppAudio]::SetVolume($name, [int]$arg)
                    $script:vol = [Math]::Max(0, [Math]::Min(100, [int]$arg))
                }
            }
        }
        'mute' {
            if ($script:audioOk) {
                $name = Get-AudioProcess (Get-Session)
                if ($name -ne '') {
                    [void][ZAppAudio]::SetMute($name, ([int]$arg -ne 0))
                    $script:muted = ([int]$arg -ne 0)
                }
            }
        }
        'source' {
            if ($arg -eq 'any') { $script:source = 'any' } else { $script:source = 'spotify' }
            $script:coverKey = ''
            $script:vol = -1
        }
        'poll'    { $script:pollMs = [Math]::Max(150, [Math]::Min(5000, [int]$arg)) }
        'refresh' { $script:coverKey = ''; $script:vol = -1 }
        'quit'    { exit 0 }
    }
}

# ---------------------------------------------------------------- main loop
Send ([ordered]@{
    t = 'hello'; ps = $PSVersionTable.PSVersion.ToString(); smtc = $script:smtcOk; audio = $script:audioOk
    source = $script:source
})

$script:audioName = ''
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$readTask = $stdin.ReadLineAsync()
$nextEmit = [DateTime]::UtcNow
while ($true) {
    try {
        while ($readTask.IsCompleted) {
            if ($readTask.IsFaulted -or $readTask.IsCanceled) { exit 0 }
            $line = $readTask.Result
            if ($null -eq $line) { exit 0 }          # Minecraft closed the pipe: we are done
            try { Run-Command $line } catch { Send-Error 'command' $_ }
            $readTask = $stdin.ReadLineAsync()
            $nextEmit = [DateTime]::UtcNow.AddMilliseconds(120)
        }
        if ([DateTime]::UtcNow -ge $nextEmit) {
            Emit-State
            $nextEmit = [DateTime]::UtcNow.AddMilliseconds($script:pollMs)
        }
    } catch {
        Send-Error 'state' $_
        $nextEmit = [DateTime]::UtcNow.AddMilliseconds($script:pollMs)
    }
    # Live loudness of the player, about 40 times a second: this is what makes the bars follow the beat.
    if ($script:audioOk -and $script:audioName -ne '') {
        try {
            $pk = [ZAppAudio]::Peak($script:audioName)
            $stdout.WriteLine('{"t":"lv","v":' + $pk.ToString('0.0000', $inv) + '}')
        } catch { }
    }
    Start-Sleep -Milliseconds 20
}
