using DayCue.Companion.Core;

namespace DayCue.Companion.Net;

/// <summary>Wire format helpers. The signed message is defined by docs/architecture/RELAY.md 4.5 and mcp/src/relay.ts signalMessage().</summary>
public static class Wire
{
    /// <summary>"daycue.signal.v1\n{deviceId}\n{state}\n{observedAt}\n{ttlSeconds}" with no trailing newline.</summary>
    public static string SignalMessage(string companionDeviceId, string state, long observedAtMs, int ttlSeconds)
        => $"daycue.signal.v1\n{companionDeviceId}\n{state}\n{observedAtMs}\n{ttlSeconds}";

    public static string ToBase64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>Relay URLs must be https. Plain http is allowed only for loopback (local development).</summary>
    public static bool TryNormalizeRelayUrl(string? input, out Uri url)
    {
        url = null!;
        if (string.IsNullOrWhiteSpace(input)) return false;
        if (!Uri.TryCreate(input.Trim(), UriKind.Absolute, out var u)) return false;
        var loopback = u.IsLoopback;
        if (!(u.Scheme == Uri.UriSchemeHttps || (u.Scheme == Uri.UriSchemeHttp && loopback))) return false;
        if (!string.IsNullOrEmpty(u.UserInfo)) return false;
        url = new Uri(u.GetLeftPart(UriPartial.Authority) + "/");
        return true;
    }
}

public enum SendOutcome
{
    /// <summary>Accepted.</summary>
    Ok,
    /// <summary>409 stale_signal: the relay already holds this or a newer signal. Nothing to do.</summary>
    AlreadyAccepted,
    /// <summary>422 already_expired: the signal outlived its TTL in transit. Drop it; the next heartbeat carries the current state.</summary>
    Expired,
    /// <summary>401: the credential was revoked or is wrong. Stop sending until re-paired.</summary>
    Unauthorized,
    /// <summary>Other 4xx (400, 403 bad_signature): retrying cannot help.</summary>
    Rejected,
    /// <summary>Network error, timeout, 5xx or 429: retry with backoff.</summary>
    Retry,
}

public readonly record struct SendResult(SendOutcome Outcome, long? ServerTimeMs = null, TimeSpan? RetryAfter = null, string? Detail = null);

public interface ISignalTransport
{
    Task<SendResult> PostSignalAsync(string state, long observedAtMs, int ttlSeconds, string signature, CancellationToken ct);
}

public interface ISignalSigner
{
    string DeviceId { get; }
    /// <summary>Returns the base64url ECDSA P-256/SHA-256 signature (raw r||s) over the UTF-8 message.</summary>
    string Sign(string message);
}
