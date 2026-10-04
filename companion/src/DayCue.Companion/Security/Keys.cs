using System.Security.Cryptography;
using System.Text;
using DayCue.Companion.Net;

namespace DayCue.Companion.Security;

/// <summary>Signs with an ECDSA P-256 key. The signature is raw r||s (IEEE P1363, 64 bytes), base64url, which the relay verifier accepts.</summary>
public sealed class EcdsaSigner(string deviceId, ECDsa key) : ISignalSigner
{
    public string DeviceId { get; } = deviceId;
    public string Sign(string message) => Wire.ToBase64Url(key.SignData(Encoding.UTF8.GetBytes(message), HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
}

/// <summary>
/// Per-user, non-exportable ECDSA P-256 key in the Windows CNG software key store (Microsoft Software Key Storage Provider, current-user scope).
/// The private key never exists in this process as bytes and cannot be exported; only the public SPKI is read.
/// </summary>
public static class KeyVault
{
    public static string NewKeyName() => "DayCue.Companion." + Guid.NewGuid().ToString("N");

    /// <summary>Creates the key and returns its X.509 SubjectPublicKeyInfo as standard base64.</summary>
    public static string CreateKey(string name)
    {
        var p = new CngKeyCreationParameters
        {
            ExportPolicy = CngExportPolicies.None,
            KeyUsage = CngKeyUsages.Signing,
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
        };
        using var key = CngKey.Create(CngAlgorithm.ECDsaP256, name, p);
        using var ec = new ECDsaCng(key);
        return Convert.ToBase64String(ec.ExportSubjectPublicKeyInfo());
    }

    public static ECDsa Open(string name) => new ECDsaCng(CngKey.Open(name, CngProvider.MicrosoftSoftwareKeyStorageProvider));

    public static bool Exists(string name) => CngKey.Exists(name, CngProvider.MicrosoftSoftwareKeyStorageProvider);

    public static void Delete(string name)
    {
        try
        {
            if (!Exists(name)) return;
            using var k = CngKey.Open(name, CngProvider.MicrosoftSoftwareKeyStorageProvider);
            k.Delete();
        }
        catch (CryptographicException) { /* already gone */ }
    }
}
