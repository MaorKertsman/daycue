using DayCue.Companion.Core;

namespace DayCue.Companion.Net;

public enum ConnectionState { NotSent, Online, Retrying, Offline, CredentialRejected }

public sealed record SenderStatus(ConnectionState Connection, DateTimeOffset? LastSentAt, ActivityState? LastSentState, string? Detail);

/// <summary>
/// Delivers signals one at a time, newest wins. There is no backlog: while a send is in flight or backing off, a newer signal replaces any older pending
/// one, and a signal that outlived its TTL is dropped. Signals are ephemeral; after an outage only the current state is sent.
/// </summary>
public sealed class SignalSender : IDisposable
{
    private readonly ISignalTransport _transport;
    private readonly ISignalSigner _signer;
    private readonly IClock _clock;
    private readonly int _maxAttempts;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _gate = new();

    private Signal? _pending;
    private Task _worker = Task.CompletedTask;
    private bool _running;
    private bool _disabled;
    private long _lastObservedMs;
    private long _skewMs;
    private SenderStatus _status = new(ConnectionState.NotSent, null, null, null);

    public event Action<SenderStatus>? StatusChanged;

    public SignalSender(ISignalTransport transport, ISignalSigner signer, IClock clock, int maxAttempts, long initialSkewMs = 0, Func<TimeSpan, CancellationToken, Task>? delay = null)
    {
        _transport = transport;
        _signer = signer;
        _clock = clock;
        _maxAttempts = Math.Max(1, maxAttempts);
        _skewMs = initialSkewMs;
        _delay = delay ?? Task.Delay;
    }

    public SenderStatus Status { get { lock (_gate) return _status; } }
    public bool CredentialRejected { get { lock (_gate) return _disabled; } }

    public void Submit(Signal signal)
    {
        lock (_gate)
        {
            if (_disabled) return;
            _pending = signal;
            if (_running) return;
            _running = true;
            _worker = Task.Run(Loop);
        }
    }

    /// <summary>Waits until nothing is in flight or pending, or the timeout passes. Used for the best-effort "asleep"/"exit" signal.</summary>
    public async Task WaitIdleAsync(TimeSpan timeout)
    {
        Task w;
        lock (_gate) w = _worker;
        try { await w.WaitAsync(timeout).ConfigureAwait(false); } catch (TimeoutException) { }
    }

    private void SetStatus(Func<SenderStatus, SenderStatus> f)
    {
        SenderStatus s;
        lock (_gate) { _status = f(_status); s = _status; }
        StatusChanged?.Invoke(s);
    }

    private Signal? TakePending()
    {
        lock (_gate)
        {
            var p = _pending;
            _pending = null;
            if (p is null) _running = false;
            return p;
        }
    }

    private bool HasNewer() { lock (_gate) return _pending is not null; }

    private async Task Loop()
    {
        while (!_cts.IsCancellationRequested && TakePending() is { } sig)
        {
            try { await SendWithRetries(sig).ConfigureAwait(false); }
            catch (OperationCanceledException) { }
            catch (Exception e) { SetStatus(s => s with { Connection = ConnectionState.Offline, Detail = e.GetType().Name }); }
        }
        lock (_gate) _running = false;
    }

    private async Task SendWithRetries(Signal sig)
    {
        var ct = _cts.Token;
        long observedMs;
        lock (_gate)
        {
            observedMs = Math.Max(sig.ObservedAt.ToUnixTimeMilliseconds() + _skewMs, _lastObservedMs + 1);
            _lastObservedMs = observedMs;
        }
        var message = Wire.SignalMessage(_signer.DeviceId, sig.State.ToWire(), observedMs, sig.TtlSeconds);
        var signature = _signer.Sign(message);

        for (var attempt = 1; attempt <= _maxAttempts; attempt++)
        {
            if (_clock.UtcNow >= sig.ExpiresAt) return; // stale before it could be delivered; the next heartbeat carries the current state
            var r = await _transport.PostSignalAsync(sig.State.ToWire(), observedMs, sig.TtlSeconds, signature, ct).ConfigureAwait(false);
            switch (r.Outcome)
            {
                case SendOutcome.Ok:
                    AdoptServerTime(r.ServerTimeMs);
                    SetStatus(_ => new SenderStatus(ConnectionState.Online, _clock.UtcNow, sig.State, null));
                    return;
                case SendOutcome.AlreadyAccepted:
                case SendOutcome.Expired:
                    SetStatus(s => s with { Connection = ConnectionState.Online, Detail = r.Detail });
                    return;
                case SendOutcome.Unauthorized:
                    lock (_gate) { _disabled = true; _pending = null; }
                    SetStatus(s => s with { Connection = ConnectionState.CredentialRejected, Detail = "The relay rejected this device credential (revoked?). Unpair and pair again." });
                    return;
                case SendOutcome.Rejected:
                    SetStatus(s => s with { Connection = ConnectionState.Offline, Detail = "Relay refused the signal: " + r.Detail });
                    return;
                default:
                    if (HasNewer() || attempt == _maxAttempts)
                    {
                        SetStatus(s => s with { Connection = ConnectionState.Offline, Detail = r.Detail });
                        return;
                    }
                    SetStatus(s => s with { Connection = ConnectionState.Retrying, Detail = r.Detail });
                    var backoff = r.RetryAfter ?? TimeSpan.FromSeconds(Math.Min(30, 2 << (attempt - 1))); // 2, 4, 8, 16, 30 s
                    if (backoff > TimeSpan.FromSeconds(60)) backoff = TimeSpan.FromSeconds(60);
                    await _delay(backoff, ct).ConfigureAwait(false);
                    break;
            }
        }
    }

    /// <summary>If the PC clock is off by more than a few seconds, shift observedAt so the relay's future/expiry checks still pass.</summary>
    private void AdoptServerTime(long? serverMs)
    {
        if (serverMs is not { } s) return;
        var diff = s - _clock.UtcNow.ToUnixTimeMilliseconds();
        lock (_gate) _skewMs = Math.Abs(diff) > 5000 ? diff : 0;
    }

    public void Dispose() => _cts.Cancel();
}
