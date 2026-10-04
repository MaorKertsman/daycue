using System.Security.Cryptography;
using System.Text;
using DayCue.Companion.Security;

namespace DayCue.Companion.App;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        var data = new AppData();
        // One instance per config folder.
        var id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(data.Dir.ToLowerInvariant())))[..16];
        using var mutex = new Mutex(true, @"Local\DayCue.Companion." + id, out var first);
        if (!first) return 0;

        // Diagnostic: "--exit-after <seconds>" quits cleanly (used to smoke-test startup).
        int? exitAfter = null;
        var i = Array.IndexOf(args, "--exit-after");
        if (i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out var secs)) exitAfter = secs;

        ApplicationConfiguration.Initialize();
        Application.ThreadException += (_, e) => TrayApp.LogError(data, e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => TrayApp.LogError(data, e.ExceptionObject as Exception);
        using var app = new TrayApp(data, exitAfter);
        Application.Run(app);
        return 0;
    }
}
