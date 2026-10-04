using System.Security.Cryptography;
using System.Text;
using DayCue.Companion.Net;
using DayCue.Companion.Platform;
using DayCue.Companion.Security;
using Microsoft.Win32;
using Xunit;

namespace DayCue.Companion.Tests;

public class SecurityAndPlatformTests
{
    [Fact]
    public void Cng_key_is_non_exportable_signs_p1363_and_verifies_with_the_public_key()
    {
        var name = KeyVault.NewKeyName();
        try
        {
            var spki = KeyVault.CreateKey(name);
            using var key = KeyVault.Open(name);
            Assert.Throws<CryptographicException>(() => key.ExportParameters(includePrivateParameters: true));

            var signer = new EcdsaSigner("co_x", key);
            var msg = Wire.SignalMessage("co_x", "active", 1791114000000, 180);
            Assert.Equal("daycue.signal.v1\nco_x\nactive\n1791114000000\n180", msg);
            var sigB64Url = signer.Sign(msg);
            Assert.DoesNotContain('=', sigB64Url);
            Assert.DoesNotContain('+', sigB64Url);
            var raw = Convert.FromBase64String(sigB64Url.Replace('-', '+').Replace('_', '/') + "==".Substring(0, (4 - sigB64Url.Length % 4) % 4));
            Assert.Equal(64, raw.Length);

            using var pub = ECDsa.Create();
            pub.ImportSubjectPublicKeyInfo(Convert.FromBase64String(spki), out _);
            Assert.True(pub.VerifyData(Encoding.UTF8.GetBytes(msg), raw, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
            Assert.False(pub.VerifyData(Encoding.UTF8.GetBytes(msg.Replace("active", "idle")), raw, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));

            // survives reopening (persisted in the per-user key store)
            Assert.True(KeyVault.Exists(name));
        }
        finally { KeyVault.Delete(name); }
        Assert.False(KeyVault.Exists(name));
    }

    [Fact]
    public void Pairing_record_stores_only_a_dpapi_protected_token_and_unpair_deletes_everything()
    {
        var dir = Path.Combine(Path.GetTempPath(), "daycue-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var data = new AppData(dir);
            var keyName = KeyVault.NewKeyName();
            KeyVault.CreateKey(keyName);
            const string token = "dcd_synthetic_token_for_test";
            data.SavePairing(new PairingRecord { RelayUrl = "https://relay.example/", DeviceId = "co_1", KeyName = keyName, TokenProtected = AppData.Protect(token), PairedAt = DateTimeOffset.UtcNow });

            var onDisk = File.ReadAllText(data.PairingPath);
            Assert.DoesNotContain(token, onDisk);
            Assert.Equal(token, AppData.Unprotect(data.LoadPairing()!.TokenProtected));
            Assert.DoesNotContain("PRIVATE", onDisk, StringComparison.OrdinalIgnoreCase);

            data.DeletePairing();
            Assert.Null(data.LoadPairing());
            Assert.False(KeyVault.Exists(keyName));
            Assert.False(File.Exists(data.PairingPath));
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    [Fact]
    public void Pause_state_round_trips()
    {
        var dir = Path.Combine(Path.GetTempPath(), "daycue-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var data = new AppData(dir);
            Assert.False(data.LoadState().PausedIndefinitely);
            var until = DateTimeOffset.UtcNow.AddHours(1);
            data.SaveState(new LocalState { PausedUntil = until });
            Assert.Equal(until, data.LoadState().PausedUntil);
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    [Fact]
    public void Startup_registration_toggles_a_value_in_hkcu_only()
    {
        var sub = @"Software\DayCueCompanionTest-" + Guid.NewGuid().ToString("N");
        try
        {
            var reg = new StartupRegistration("DayCueCompanionTest", sub);
            Assert.False(reg.IsEnabled);
            reg.Enable(@"C:\Apps\DayCueCompanion.exe");
            Assert.True(reg.IsEnabled);
            using (var k = Registry.CurrentUser.OpenSubKey(sub))
                Assert.Equal("\"C:\\Apps\\DayCueCompanion.exe\"", k!.GetValue("DayCueCompanionTest"));
            reg.Disable();
            Assert.False(reg.IsEnabled);
            reg.Disable(); // idempotent
        }
        finally { Registry.CurrentUser.DeleteSubKeyTree(sub, throwOnMissingSubKey: false); }
    }

    [Fact]
    public void Win32_idle_source_returns_a_plausible_duration()
    {
        var idle = new Win32IdleSource().GetIdleTime();
        Assert.True(idle >= TimeSpan.Zero && idle < TimeSpan.FromDays(60));
    }
}
