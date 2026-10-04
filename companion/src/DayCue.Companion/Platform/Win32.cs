using System.Runtime.InteropServices;
using DayCue.Companion.Core;
using Microsoft.Win32;

namespace DayCue.Companion.Platform;

/// <summary>Idle time via GetLastInputInfo: a single tick count, no input content, no hooks.</summary>
public sealed class Win32IdleSource : IIdleSource
{
    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    public TimeSpan GetIdleTime()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        if (!GetLastInputInfo(ref info)) return TimeSpan.Zero;
        var now = unchecked((uint)Environment.TickCount); // 32-bit tick count, wraps like dwTime; unsigned subtraction handles the wrap
        return TimeSpan.FromMilliseconds(unchecked(now - info.dwTime));
    }
}

/// <summary>Run at startup via the per-user Run key. Off by default; never touches HKLM.</summary>
public sealed class StartupRegistration(string valueName = "DayCueCompanion", string subKey = @"Software\Microsoft\Windows\CurrentVersion\Run")
{
    public bool IsEnabled
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(subKey);
            return k?.GetValue(valueName) is string;
        }
    }

    public void Enable(string exePath)
    {
        using var k = Registry.CurrentUser.CreateSubKey(subKey);
        k.SetValue(valueName, $"\"{exePath}\"", RegistryValueKind.String);
    }

    public void Disable()
    {
        using var k = Registry.CurrentUser.OpenSubKey(subKey, writable: true);
        k?.DeleteValue(valueName, throwOnMissingValue: false);
    }
}
