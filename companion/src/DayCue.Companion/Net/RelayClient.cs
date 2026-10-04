using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace DayCue.Companion.Net;

public sealed class PairingException(string message) : Exception(message);

public sealed record PairResult(string DeviceId, string Token, long? ServerTimeMs);

/// <summary>HTTPS client for the two companion endpoints: <c>POST /v1/pair/companion</c> and <c>POST /v1/companion/signal</c>.</summary>
public sealed class RelayClient : ISignalTransport
{
    private readonly HttpClient _http;
    private readonly Uri _base;
    private readonly string _token;

    public RelayClient(HttpClient http, Uri baseUrl, string deviceToken)
    {
        _http = http;
        _base = baseUrl;
        _token = deviceToken;
    }

    public static HttpClient CreateHttpClient(TimeSpan timeout)
    {
        var http = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(20) }) { Timeout = timeout };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("DayCueCompanion/0.1");
        return http;
    }

    public static async Task<PairResult> PairAsync(HttpClient http, Uri baseUrl, string code, string publicKeySpkiBase64, string label, CancellationToken ct)
    {
        HttpResponseMessage resp;
        try
        {
            resp = await http.PostAsJsonAsync(new Uri(baseUrl, "v1/pair/companion"), new { code = code.Trim(), publicKey = publicKeySpkiBase64, label }, ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            throw new PairingException("Could not reach the relay (it may be waking up; try again in a minute): " + e.Message);
        }
        using (resp)
        {
            var text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (resp.StatusCode != HttpStatusCode.Created && resp.StatusCode != HttpStatusCode.OK)
                throw new PairingException(DescribeError(resp.StatusCode, text));
            try
            {
                using var doc = JsonDocument.Parse(text);
                var root = doc.RootElement;
                var id = root.GetProperty("deviceId").GetString();
                var token = root.GetProperty("token").GetString();
                long? st = root.TryGetProperty("serverTime", out var s) && s.TryGetInt64(out var v) ? v : null;
                if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(token)) throw new PairingException("Relay response is missing deviceId or token.");
                return new PairResult(id, token, st);
            }
            catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
            {
                throw new PairingException("Unexpected relay response.");
            }
        }
    }

    private static string DescribeError(HttpStatusCode status, string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            var r = doc.RootElement;
            var msg = r.TryGetProperty("message", out var m) ? m.GetString() : r.TryGetProperty("error_description", out var d) ? d.GetString() : null;
            if (!string.IsNullOrWhiteSpace(msg)) return $"{(int)status}: {msg}";
        }
        catch (JsonException) { /* not json */ }
        return $"Relay answered HTTP {(int)status}.";
    }

    public async Task<SendResult> PostSignalAsync(string state, long observedAtMs, int ttlSeconds, string signature, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, new Uri(_base, "v1/companion/signal"))
        {
            Content = JsonContent.Create(new { state, observedAt = observedAtMs, ttlSeconds, signature }),
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);
        try
        {
            using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
            var text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            long? serverTime = null;
            string? err = null;
            try
            {
                using var doc = JsonDocument.Parse(text);
                if (doc.RootElement.TryGetProperty("serverTime", out var s) && s.TryGetInt64(out var v)) serverTime = v;
                if (doc.RootElement.TryGetProperty("error", out var e)) err = e.GetString();
            }
            catch (JsonException) { /* non-json body (proxy error page) */ }

            if (resp.IsSuccessStatusCode) return new SendResult(SendOutcome.Ok, serverTime);
            var code = (int)resp.StatusCode;
            return code switch
            {
                401 => new SendResult(SendOutcome.Unauthorized, null, null, err),
                409 when err == "stale_signal" => new SendResult(SendOutcome.AlreadyAccepted, null, null, err),
                422 when err == "already_expired" => new SendResult(SendOutcome.Expired, null, null, err),
                429 => new SendResult(SendOutcome.Retry, null, resp.Headers.RetryAfter?.Delta, "rate limited"),
                408 => new SendResult(SendOutcome.Retry, null, null, "timeout"),
                >= 500 => new SendResult(SendOutcome.Retry, null, null, $"HTTP {code}"),
                _ => new SendResult(SendOutcome.Rejected, null, null, err ?? $"HTTP {code}"),
            };
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            return new SendResult(SendOutcome.Retry, null, null, e is HttpRequestException ? "network error" : "timeout");
        }
    }
}
