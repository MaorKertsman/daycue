using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using DayCue.Companion.Core;
using DayCue.Companion.Net;
using DayCue.Companion.Security;
using Xunit;

namespace DayCue.Companion.Tests;

/// <summary>Skipped (not failed) when node or the relay's node_modules are unavailable.</summary>
public sealed class RelayFactAttribute : FactAttribute
{
    public RelayFactAttribute()
    {
        if (RelayFixture.FindMcpDir() is null) Skip = "mcp/ with node_modules not found";
        else if (!RelayFixture.NodeAvailable()) Skip = "node is not on PATH";
    }
}

/// <summary>Starts the real relay from mcp/ (tsx, in-memory store) on a free loopback port.</summary>
public sealed class RelayFixture : IAsyncLifetime
{
    public const string OwnerSecret = "integration-owner-secret-0123456789abcdef";
    private Process? _proc;
    public Uri Base { get; private set; } = null!;
    public HttpClient Http { get; } = new();

    public static string? FindMcpDir()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
        {
            var mcp = Path.Combine(d.FullName, "mcp");
            if (File.Exists(Path.Combine(mcp, "package.json")) && Directory.Exists(Path.Combine(mcp, "node_modules", "tsx"))) return mcp;
        }
        return null;
    }

    public static bool NodeAvailable()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("node", "--version") { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true });
            return p is not null && p.WaitForExit(10_000) && p.ExitCode == 0;
        }
        catch { return false; }
    }

    public async Task InitializeAsync()
    {
        var mcp = FindMcpDir();
        if (mcp is null || !NodeAvailable()) return;
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        Base = new Uri($"http://127.0.0.1:{port}/");
        var psi = new ProcessStartInfo("node", "node_modules/tsx/dist/cli.mjs src/node/server.ts")
        {
            WorkingDirectory = mcp,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.Environment["PORT"] = port.ToString();
        psi.Environment["HOST"] = "127.0.0.1";
        psi.Environment["DAYCUE_BASE_URL"] = $"http://127.0.0.1:{port}";
        psi.Environment["DAYCUE_OWNER_SECRET"] = OwnerSecret;
        psi.Environment["DAYCUE_ALLOW_MEMORY_STORE"] = "true";
        _proc = Process.Start(psi)!;
        _proc.OutputDataReceived += (_, _) => { };
        _proc.ErrorDataReceived += (_, _) => { };
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();
        var deadline = DateTime.UtcNow.AddSeconds(60);
        while (DateTime.UtcNow < deadline)
        {
            try { if ((await Http.GetAsync(new Uri(Base, "healthz"))).IsSuccessStatusCode) return; } catch { /* not up yet */ }
            await Task.Delay(300);
        }
        throw new InvalidOperationException("relay did not start");
    }

    public Task DisposeAsync()
    {
        try { _proc?.Kill(entireProcessTree: true); } catch { /* already gone */ }
        _proc?.Dispose();
        Http.Dispose();
        return Task.CompletedTask;
    }

    public async Task<JsonElement> PostJson(string path, object body, string? bearer = null)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, new Uri(Base, path)) { Content = JsonContent.Create(body) };
        if (bearer is not null) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        using var resp = await Http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        Assert.True(resp.IsSuccessStatusCode, $"{path}: {(int)resp.StatusCode} {text}");
        return JsonDocument.Parse(text).RootElement.Clone();
    }

    public async Task<JsonElement> GetJson(string path, string bearer)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, new Uri(Base, path));
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        using var resp = await Http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        Assert.True(resp.IsSuccessStatusCode, $"{path}: {(int)resp.StatusCode} {text}");
        return JsonDocument.Parse(text).RootElement.Clone();
    }
}

/// <summary>
/// End to end against the real relay code: a fake phone pairs with an owner code and mints a companion code, the real companion code pairs with a
/// non-exportable CNG key and posts signed signals, and the phone reads the raw signal back and verifies it with the companion's public key.
/// </summary>
public class RelayIntegrationTests(RelayFixture relay) : IClassFixture<RelayFixture>
{
    private sealed record Paired(PairResult Result, ECDsa Key, string KeyName, string SpkiB64);

    private async Task<(string phoneToken, Paired co)> PairEverything()
    {
        // fake phone
        var phoneKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var ownerCode = (await relay.PostJson("v1/owner/pair-codes", new { }, RelayFixture.OwnerSecret)).GetProperty("code").GetString()!;
        var phone = await relay.PostJson("v1/pair/phone", new { code = ownerCode, publicKey = Convert.ToBase64String(phoneKey.ExportSubjectPublicKeyInfo()), label = "test phone" });
        var phoneToken = phone.GetProperty("token").GetString()!;
        var coCode = (await relay.PostJson("v1/phone/companion-codes", new { }, phoneToken)).GetProperty("code").GetString()!;

        // real companion code path
        var keyName = KeyVault.NewKeyName();
        var spki = KeyVault.CreateKey(keyName);
        var result = await RelayClient.PairAsync(relay.Http, relay.Base, coCode, spki, "test pc", CancellationToken.None);
        return (phoneToken, new Paired(result, KeyVault.Open(keyName), keyName, spki));
    }

    private static bool PhoneVerifies(JsonElement signal, string companionId, string spkiB64)
    {
        // The phone's check: rebuild the message from the raw fields and verify with the companion public key.
        var msg = $"daycue.signal.v1\n{companionId}\n{signal.GetProperty("state").GetString()}\n{signal.GetProperty("observedAt").GetInt64()}\n{signal.GetProperty("ttlSeconds").GetInt32()}";
        var sig = signal.GetProperty("sig").GetString()!.Replace('-', '+').Replace('_', '/');
        sig = sig.PadRight(sig.Length + (4 - sig.Length % 4) % 4, '=');
        using var pub = ECDsa.Create();
        pub.ImportSubjectPublicKeyInfo(Convert.FromBase64String(spkiB64), out _);
        return pub.VerifyData(Encoding.UTF8.GetBytes(msg), Convert.FromBase64String(sig), HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
    }

    [RelayFact]
    public async Task Pair_sign_post_and_phone_reads_back_a_verifiable_signal()
    {
        var (phoneToken, co) = await PairEverything();
        try
        {
            Assert.StartsWith("co_", co.Result.DeviceId);
            var client = new RelayClient(relay.Http, relay.Base, co.Result.Token);
            var signer = new EcdsaSigner(co.Result.DeviceId, co.Key);
            var clock = new SystemClock();
            using var sender = new SignalSender(client, signer, clock, 2);

            foreach (var state in new[] { ActivityState.Active, ActivityState.Locked, ActivityState.Idle, ActivityState.Asleep })
            {
                var ttl = state == ActivityState.Asleep ? 600 : 180;
                sender.Submit(new Signal(state, clock.UtcNow, ttl));
                await sender.WaitIdleAsync(TimeSpan.FromSeconds(20));
                Assert.Equal(ConnectionState.Online, sender.Status.Connection);

                var act = await relay.GetJson("v1/phone/activity", phoneToken);
                var sig = act.GetProperty("signal");
                Assert.Equal(state.ToWire(), sig.GetProperty("state").GetString());
                Assert.Equal(ttl, sig.GetProperty("ttlSeconds").GetInt32());
                Assert.True(PhoneVerifies(sig, co.Result.DeviceId, co.SpkiB64), $"phone could not verify {state} signature");
                // freshness as the phone must compute it
                var serverNow = act.GetProperty("serverTime").GetInt64();
                Assert.True(sig.GetProperty("observedAt").GetInt64() + ttl * 1000L > serverNow);
                Assert.Contains(act.GetProperty("companions").EnumerateArray(), c => c.GetProperty("id").GetString() == co.Result.DeviceId);
                await Task.Delay(5); // distinct observedAt values
            }
        }
        finally { co.Key.Dispose(); KeyVault.Delete(co.KeyName); }
    }

    [RelayFact]
    public async Task Unpair_revokes_the_credential_on_the_relay_best_effort()
    {
        var (_, co) = await PairEverything();
        try
        {
            var client = new RelayClient(relay.Http, relay.Base, co.Result.Token);
            var signer = new EcdsaSigner(co.Result.DeviceId, co.Key);
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var ok = await client.PostSignalAsync("active", now, 180, signer.Sign(Wire.SignalMessage(co.Result.DeviceId, "active", now, 180)), CancellationToken.None);
            Assert.Equal(SendOutcome.Ok, ok.Outcome);

            Assert.True(await RelayClient.RevokeSelfAsync(relay.Http, relay.Base, co.Result.Token, CancellationToken.None));
            var after = await client.PostSignalAsync("idle", now + 10, 180, signer.Sign(Wire.SignalMessage(co.Result.DeviceId, "idle", now + 10, 180)), CancellationToken.None);
            Assert.Equal(SendOutcome.Unauthorized, after.Outcome); // the credential is dead on the relay
            Assert.True(await RelayClient.RevokeSelfAsync(relay.Http, relay.Base, co.Result.Token, CancellationToken.None)); // already revoked is fine
        }
        finally { co.Key.Dispose(); KeyVault.Delete(co.KeyName); }
    }

    [Fact]
    public async Task Revoke_on_an_unreachable_relay_returns_false_instead_of_throwing()
    {
        using var http = RelayClient.CreateHttpClient(TimeSpan.FromSeconds(2));
        Assert.False(await RelayClient.RevokeSelfAsync(http, new Uri("http://127.0.0.1:1/"), "dcd_token", CancellationToken.None));
    }

    [RelayFact]
    public async Task Relay_enforces_replay_expiry_and_signature_and_client_maps_them()
    {
        var (_, co) = await PairEverything();
        try
        {
            var client = new RelayClient(relay.Http, relay.Base, co.Result.Token);
            var signer = new EcdsaSigner(co.Result.DeviceId, co.Key);
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            async Task<SendResult> Post(string state, long observedAt, int ttl, string? sigOverride = null)
                => await client.PostSignalAsync(state, observedAt, ttl, sigOverride ?? signer.Sign(Wire.SignalMessage(co.Result.DeviceId, state, observedAt, ttl)), CancellationToken.None);

            Assert.Equal(SendOutcome.Ok, (await Post("active", now, 180)).Outcome);

            // replay: same observedAt again, and an older one
            Assert.Equal(SendOutcome.AlreadyAccepted, (await Post("active", now, 180)).Outcome);
            Assert.Equal(SendOutcome.AlreadyAccepted, (await Post("idle", now - 1000, 180)).Outcome);

            // expiry: valid signature but observedAt + ttl already in the past
            Assert.Equal(SendOutcome.Expired, (await Post("idle", now - 3_600_000, 60)).Outcome);

            // forged: signature for a different state
            var good = signer.Sign(Wire.SignalMessage(co.Result.DeviceId, "active", now + 10, 180));
            Assert.Equal(SendOutcome.Rejected, (await Post("locked", now + 10, 180, good)).Outcome);

            // too far in the future (clock skew > 2 min)
            Assert.Equal(SendOutcome.Rejected, (await Post("active", now + 600_000, 180)).Outcome);

            // a wrong credential is Unauthorized
            var bad = new RelayClient(relay.Http, relay.Base, "dcd_not_a_real_token");
            Assert.Equal(SendOutcome.Unauthorized, (await bad.PostSignalAsync("active", now + 20, 180, good, CancellationToken.None)).Outcome);
        }
        finally { co.Key.Dispose(); KeyVault.Delete(co.KeyName); }
    }

    [RelayFact]
    public async Task Pairing_code_is_single_use_and_wrong_code_is_a_clear_error()
    {
        var phoneKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var ownerCode = (await relay.PostJson("v1/owner/pair-codes", new { }, RelayFixture.OwnerSecret)).GetProperty("code").GetString()!;
        var phone = await relay.PostJson("v1/pair/phone", new { code = ownerCode, publicKey = Convert.ToBase64String(phoneKey.ExportSubjectPublicKeyInfo()) });
        var coCode = (await relay.PostJson("v1/phone/companion-codes", new { }, phone.GetProperty("token").GetString()!)).GetProperty("code").GetString()!;
        var name = KeyVault.NewKeyName();
        try
        {
            var spki = KeyVault.CreateKey(name);
            await RelayClient.PairAsync(relay.Http, relay.Base, coCode.ToLowerInvariant(), spki, "pc", CancellationToken.None); // case-insensitive
            var ex = await Assert.ThrowsAsync<PairingException>(() => RelayClient.PairAsync(relay.Http, relay.Base, coCode, spki, "pc", CancellationToken.None));
            Assert.Contains("403", ex.Message);
        }
        finally { KeyVault.Delete(name); }
    }
}
