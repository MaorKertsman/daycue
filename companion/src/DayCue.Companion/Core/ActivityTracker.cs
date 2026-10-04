namespace DayCue.Companion.Core;

/// <summary>
/// Pure, thread-safe state machine. It never touches Windows or the network: an injected clock and idle source go in, signals to send come out
/// (null means nothing to send). Callers feed it events and a coarse tick.
/// </summary>
public sealed class ActivityTracker
{
    private readonly object _gate = new();
    private readonly CompanionSettings _s;
    private readonly IClock _clock;
    private readonly IIdleSource _idle;

    private bool _locked, _suspended;
    private ActivityState? _committed;
    private ActivityState? _candidate;
    private DateTimeOffset _candidateSince;
    private DateTimeOffset _lastSent = DateTimeOffset.MinValue;
    private bool _paused;
    private DateTimeOffset? _pausedUntil; // null while paused means until resumed
    private bool _forceNext;

    public ActivityTracker(CompanionSettings settings, IClock clock, IIdleSource idle)
    {
        _s = settings.Normalized();
        _clock = clock;
        _idle = idle;
    }

    public ActivityState? Current { get { lock (_gate) return _committed; } }
    public bool IsPaused { get { lock (_gate) return _paused; } }
    public DateTimeOffset? PausedUntil { get { lock (_gate) return _paused ? _pausedUntil : null; } }

    /// <summary>The state implied by current inputs, ignoring debounce.</summary>
    private ActivityState Target()
    {
        if (_suspended) return ActivityState.Asleep;
        if (_locked) return ActivityState.Locked;
        return _idle.GetIdleTime() >= TimeSpan.FromSeconds(_s.IdleThresholdSeconds) ? ActivityState.Idle : ActivityState.Active;
    }

    private Signal Emit(ActivityState state, DateTimeOffset now)
    {
        _lastSent = now;
        _forceNext = false;
        return new Signal(state, now, state == ActivityState.Asleep ? CompanionSettings.AsleepTtlSeconds : _s.TtlSeconds);
    }

    private Signal? Evaluate()
    {
        var now = _clock.UtcNow;
        if (_paused)
        {
            if (_pausedUntil is { } until && now >= until)
            {
                _paused = false;
                _pausedUntil = null;
                _committed = null; // re-derive from live inputs and send immediately
            }
            else return null;
        }

        var target = Target();
        if (_committed is null)
        {
            _committed = target;
            _candidate = null;
            return Emit(target, now);
        }

        if (target == _committed)
        {
            _candidate = null;
            // Cannot heartbeat while asleep; the asleep signal carries the longest TTL instead.
            if (target != ActivityState.Asleep && (_forceNext || now - _lastSent >= TimeSpan.FromSeconds(_s.HeartbeatSeconds)))
                return Emit(target, now);
            return null;
        }

        // Lock and sleep are reported immediately. Active/idle changes must hold for the debounce period so flapping is not reported.
        if (target is ActivityState.Locked or ActivityState.Asleep)
        {
            _committed = target;
            _candidate = null;
            return Emit(target, now);
        }

        if (_candidate != target) { _candidate = target; _candidateSince = now; }
        if (now - _candidateSince >= TimeSpan.FromSeconds(_s.DebounceSeconds))
        {
            _committed = target;
            _candidate = null;
            return Emit(target, now);
        }
        return null;
    }

    /// <summary>Coarse poll: state change, heartbeat, or pause expiry.</summary>
    public Signal? Tick() { lock (_gate) return Evaluate(); }

    public Signal? OnSessionLock() { lock (_gate) { _locked = true; return Evaluate(); } }
    public Signal? OnSessionUnlock() { lock (_gate) { _locked = false; return Evaluate(); } }
    public Signal? OnSuspend() { lock (_gate) { _suspended = true; return Evaluate(); } }
    public Signal? OnResume() { lock (_gate) { _suspended = false; return Evaluate(); } }

    /// <summary>Connectivity came back: resend the current state once (only the current state is ever sent, never a backlog).</summary>
    public Signal? OnConnectivityRestored() { lock (_gate) { _forceNext = true; return Evaluate(); } }

    /// <summary>
    /// Pause reporting. RELAY.md has no paused state, so nothing is reported while paused. One final signal repeats the current state with the minimum
    /// TTL so the phone sees "unknown" within about 10 s instead of after the full TTL. Returns null if nothing was ever sent.
    /// </summary>
    public Signal? Pause(TimeSpan? duration)
    {
        lock (_gate)
        {
            var now = _clock.UtcNow;
            var wasPaused = _paused;
            _paused = true;
            _pausedUntil = duration is null ? null : now + duration;
            _candidate = null;
            return !wasPaused && _committed is { } c ? new Signal(c, now, CompanionSettings.ShrinkTtlSeconds) : null;
        }
    }

    /// <summary>Same shrink signal for a user-initiated exit (sleep already carries its own TTL).</summary>
    public Signal? FinalSignal()
    {
        lock (_gate)
            return !_paused && _committed is { } c && c != ActivityState.Asleep ? new Signal(c, _clock.UtcNow, CompanionSettings.ShrinkTtlSeconds) : null;
    }

    public Signal? ResumeReporting()
    {
        lock (_gate)
        {
            if (!_paused) return null;
            _paused = false;
            _pausedUntil = null;
            _committed = null;
            return Evaluate();
        }
    }

    /// <summary>Restores a persisted pause after a restart.</summary>
    public void RestorePause(DateTimeOffset? until, bool indefinite)
    {
        lock (_gate)
        {
            if (indefinite) { _paused = true; _pausedUntil = null; }
            else if (until is { } u && u > _clock.UtcNow) { _paused = true; _pausedUntil = u; }
        }
    }
}
