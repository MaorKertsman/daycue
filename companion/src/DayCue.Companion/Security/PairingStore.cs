using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace DayCue.Companion.Security;

/// <summary>What is stored after pairing: where the relay is, this device's id, the DPAPI-protected credential and the name of the CNG key. No private key bytes.</summary>
public sealed class PairingRecord
{
    public string RelayUrl { get; set; } = "";
    public string DeviceId { get; set; } = "";
    public string Label { get; set; } = "";
    public string KeyName { get; set; } = "";
    /// <summary>The dcd_ device credential, protected with DPAPI for the current Windows user.</summary>
    public string TokenProtected { get; set; } = "";
    public long ClockSkewMs { get; set; }
    public DateTimeOffset PairedAt { get; set; }
}

/// <summary>Pause state survives restarts so an owner who paused is not silently resumed.</summary>
public sealed class LocalState
{
    public bool PausedIndefinitely { get; set; }
    public DateTimeOffset? PausedUntil { get; set; }
}

/// <summary>Config lives in %APPDATA%\DayCue (or DAYCUE_COMPANION_DIR). Never in the repository.</summary>
public sealed class AppData
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("daycue.companion.credential.v1");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public string Dir { get; }
    public string PairingPath => Path.Combine(Dir, "pairing.json");
    public string StatePath => Path.Combine(Dir, "state.json");
    public string SettingsPath => Path.Combine(Dir, "settings.json");

    public AppData(string? dir = null)
    {
        Dir = dir
            ?? Environment.GetEnvironmentVariable("DAYCUE_COMPANION_DIR")
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DayCue");
        Directory.CreateDirectory(Dir);
    }

    public static string Protect(string secret)
        => Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(secret), Entropy, DataProtectionScope.CurrentUser));

    public static string Unprotect(string protectedB64)
        => Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(protectedB64), Entropy, DataProtectionScope.CurrentUser));

    private void WriteAtomic(string path, object value)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(value, Json));
        File.Move(tmp, path, overwrite: true);
    }

    public PairingRecord? LoadPairing()
    {
        try { return File.Exists(PairingPath) ? JsonSerializer.Deserialize<PairingRecord>(File.ReadAllText(PairingPath)) : null; }
        catch (JsonException) { return null; }
    }

    public void SavePairing(PairingRecord r) => WriteAtomic(PairingPath, r);

    /// <summary>Unpair: removes the credential file and deletes the CNG private key.</summary>
    public void DeletePairing()
    {
        var r = LoadPairing();
        if (r is not null && !string.IsNullOrEmpty(r.KeyName)) KeyVault.Delete(r.KeyName);
        if (File.Exists(PairingPath)) File.Delete(PairingPath);
    }

    public LocalState LoadState()
    {
        try { return File.Exists(StatePath) ? JsonSerializer.Deserialize<LocalState>(File.ReadAllText(StatePath)) ?? new() : new(); }
        catch (JsonException) { return new(); }
    }

    public void SaveState(LocalState s) => WriteAtomic(StatePath, s);
}
