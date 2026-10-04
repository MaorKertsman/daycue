using DayCue.Companion.Core;
using DayCue.Companion.Net;
using Xunit;

namespace DayCue.Companion.Tests;

public sealed class FakeSigner : ISignalSigner
{
    public string DeviceId => "co_test";
    public string Sign(string message) => "sig:" + message.Replace('\n', '|');
}

public sealed class FakeTransport : ISignalTransport
{
    public readonly List<(string State, long ObservedAt, int Ttl, string Sig)> Posts = new();
    public readonly Queue<SendResult> Script = new();
    public SendResult Default { get; set; } = new(SendOutcome.Ok);
    public Func<Task>? Gate { get; set; }

    public async Task<SendResult> PostSignalAsync(string state, long observedAtMs, int ttlSeconds, string signature, CancellationToken ct)
    {
        Posts.Add((state, observedAtMs, ttlSeconds, signature));
        if (Gate is not null) await Gate();
        return Script.Count > 0 ? Script.Dequeue() : Default;
    }
}

public class SenderTests
{
    private readonly FakeClock _clock = new();
    private readonly FakeTransport _tr = new();
    private readonly List<TimeSpan> _delays = new();

    private SignalSender Make(int attempts = 4, long skew = 0) =>
        new(_tr, new FakeSigner(), _clock, attempts, skew, (d, _) => { _delays.Add(d); return Task.CompletedTask; });

    private Signal Sig(ActivityState s = ActivityState.Active, int ttl = 180) => new(s, _clock.UtcNow, ttl);

    [Fact]
    public async Task Signs_the_documented_message_and_reports_online()
    {
        using var s = Make();
        var sig = Sig();
        s.Submit(sig);
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        var p = Assert.Single(_tr.Posts);
        Assert.Equal("active", p.State);
        Assert.Equal($"sig:daycue.signal.v1|co_test|active|{sig.ObservedAt.ToUnixTimeMilliseconds()}|180", p.Sig);
        Assert.Equal(ConnectionState.Online, s.Status.Connection);
    }

    [Fact]
    public async Task Retries_with_bounded_exponential_backoff_then_goes_offline()
    {
        _tr.Default = new SendResult(SendOutcome.Retry, null, null, "network error");
        using var s = Make(attempts: 4);
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(4, _tr.Posts.Count);
        Assert.Equal([TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(4), TimeSpan.FromSeconds(8)], _delays);
        Assert.Equal(ConnectionState.Offline, s.Status.Connection);
        // identical observedAt on every retry (idempotent for the relay)
        Assert.Single(_tr.Posts.Select(p => p.ObservedAt).Distinct());
    }

    [Fact]
    public async Task Recovers_when_a_retry_succeeds()
    {
        _tr.Script.Enqueue(new SendResult(SendOutcome.Retry));
        _tr.Script.Enqueue(new SendResult(SendOutcome.Ok));
        using var s = Make();
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(2, _tr.Posts.Count);
        Assert.Equal(ConnectionState.Online, s.Status.Connection);
    }

    [Fact]
    public async Task Signal_that_expired_while_offline_is_dropped_not_sent()
    {
        _tr.Script.Enqueue(new SendResult(SendOutcome.Retry));
        var delayRan = false;
        using var s = new SignalSender(_tr, new FakeSigner(), _clock, 4, 0, (d, _) => { _clock.Advance(400); delayRan = true; return Task.CompletedTask; });
        s.Submit(Sig(ttl: 180));
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.True(delayRan);
        Assert.Single(_tr.Posts); // the second attempt was suppressed because the TTL had passed
    }

    [Fact]
    public async Task No_backlog_newest_signal_replaces_pending_ones()
    {
        var release = new TaskCompletionSource();
        _tr.Gate = () => release.Task;
        using var s = Make();
        s.Submit(Sig(ActivityState.Active));           // in flight, blocked
        await Task.Delay(100);
        _clock.Advance(1); s.Submit(Sig(ActivityState.Idle));
        _clock.Advance(1); s.Submit(Sig(ActivityState.Locked));
        _clock.Advance(1); s.Submit(Sig(ActivityState.Asleep));
        release.SetResult();
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(["active", "asleep"], _tr.Posts.Select(p => p.State).ToArray());
    }

    [Fact]
    public async Task Observed_at_is_strictly_increasing_even_for_same_millisecond()
    {
        using var s = Make();
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        s.Submit(Sig()); // same clock reading
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.True(_tr.Posts[1].ObservedAt > _tr.Posts[0].ObservedAt);
    }

    [Fact]
    public async Task Unauthorized_stops_sending_until_recreated()
    {
        _tr.Default = new SendResult(SendOutcome.Unauthorized);
        using var s = Make();
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(ConnectionState.CredentialRejected, s.Status.Connection);
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Single(_tr.Posts);
    }

    [Theory]
    [InlineData(SendOutcome.AlreadyAccepted)]
    [InlineData(SendOutcome.Expired)]
    [InlineData(SendOutcome.Rejected)]
    public async Task Terminal_outcomes_are_not_retried(SendOutcome outcome)
    {
        _tr.Default = new SendResult(outcome, null, null, "x");
        using var s = Make();
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        Assert.Single(_tr.Posts);
        Assert.Empty(_delays);
    }

    [Fact]
    public async Task Clock_skew_is_applied_to_observed_at_and_learned_from_server_time()
    {
        // PC clock is 10 minutes slow: the first ok response teaches the skew, the next signal is shifted.
        var serverNow = _clock.UtcNow.AddMinutes(10).ToUnixTimeMilliseconds();
        _tr.Script.Enqueue(new SendResult(SendOutcome.Ok, serverNow));
        using var s = Make();
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        _clock.Advance(60);
        s.Submit(Sig());
        await s.WaitIdleAsync(TimeSpan.FromSeconds(5));
        var shift = _tr.Posts[1].ObservedAt - _clock.UtcNow.ToUnixTimeMilliseconds();
        Assert.InRange(shift, 599_000, 601_000);
    }

    [Fact]
    public void Relay_url_must_be_https_except_loopback()
    {
        Assert.True(Wire.TryNormalizeRelayUrl("https://daycue-relay.example.com/", out var u));
        Assert.Equal("https://daycue-relay.example.com/", u.ToString());
        Assert.True(Wire.TryNormalizeRelayUrl("http://localhost:8787", out _));
        Assert.True(Wire.TryNormalizeRelayUrl("http://127.0.0.1:8787/x", out var l));
        Assert.Equal("http://127.0.0.1:8787/", l.ToString());
        Assert.False(Wire.TryNormalizeRelayUrl("http://relay.example.com", out _));
        Assert.False(Wire.TryNormalizeRelayUrl("https://user:pw@relay.example.com", out _));
        Assert.False(Wire.TryNormalizeRelayUrl("ftp://x", out _));
        Assert.False(Wire.TryNormalizeRelayUrl("", out _));
    }
}
