using DayCue.Companion.Core;
using Xunit;

namespace DayCue.Companion.Tests;

public sealed class FakeClock : IClock
{
    public DateTimeOffset UtcNow { get; set; } = new(2026, 10, 4, 12, 0, 0, TimeSpan.Zero);
    public void Advance(double seconds) => UtcNow = UtcNow.AddSeconds(seconds);
}

public sealed class FakeIdle : IIdleSource
{
    public TimeSpan Idle { get; set; } = TimeSpan.Zero;
    public TimeSpan GetIdleTime() => Idle;
}

public class ActivityTrackerTests
{
    private readonly FakeClock _clock = new();
    private readonly FakeIdle _idle = new();
    private readonly ActivityTracker _t;

    public ActivityTrackerTests()
    {
        _t = new ActivityTracker(new CompanionSettings { IdleThresholdSeconds = 120, HeartbeatSeconds = 60, TtlSeconds = 180, DebounceSeconds = 3 }, _clock, _idle);
    }

    /// <summary>Advances time in 1 s steps (the tick is coarser in production) and collects every emitted signal.</summary>
    private List<Signal> Run(int seconds)
    {
        var list = new List<Signal>();
        for (var i = 0; i < seconds; i++)
        {
            _clock.Advance(1);
            _idle.Idle += TimeSpan.FromSeconds(1); // no input: idle grows
            if (_t.Tick() is { } s) list.Add(s);
        }
        return list;
    }

    [Fact]
    public void First_tick_emits_active_with_ttl()
    {
        var s = _t.Tick();
        Assert.NotNull(s);
        Assert.Equal(ActivityState.Active, s!.Value.State);
        Assert.Equal(180, s.Value.TtlSeconds);
        Assert.Equal(_clock.UtcNow, s.Value.ObservedAt);
    }

    [Fact]
    public void Becomes_idle_at_threshold_and_only_then()
    {
        _t.Tick();
        var before = Run(118); // idle = 118 s, below threshold; heartbeats only
        Assert.All(before, s => Assert.Equal(ActivityState.Active, s.State));
        var after = Run(10);
        Assert.Contains(after, s => s.State == ActivityState.Idle);
        Assert.Equal(ActivityState.Idle, _t.Current);
    }

    [Fact]
    public void Input_returns_to_active_after_debounce()
    {
        _t.Tick();
        _idle.Idle = TimeSpan.FromSeconds(500);
        Run(5); // idle committed
        Assert.Equal(ActivityState.Idle, _t.Current);

        _idle.Idle = TimeSpan.Zero; // user touches the mouse
        _clock.Advance(1);
        Assert.Null(_t.Tick()); // candidate active, not yet held for 3 s
        _clock.Advance(2);
        Assert.Null(_t.Tick());
        _clock.Advance(1);
        var s = _t.Tick();
        Assert.Equal(ActivityState.Active, s!.Value.State);
    }

    [Fact]
    public void Flapping_between_active_and_idle_is_not_reported()
    {
        _t.Tick();
        _idle.Idle = TimeSpan.FromSeconds(121);
        var emitted = new List<Signal>();
        for (var i = 0; i < 10; i++)
        {
            // idle crosses the threshold for one second, then input resets it
            _idle.Idle = TimeSpan.FromSeconds(i % 2 == 0 ? 121 : 0);
            _clock.Advance(1);
            if (_t.Tick() is { } s && s.State != ActivityState.Active) emitted.Add(s);
        }
        Assert.Empty(emitted);
        Assert.Equal(ActivityState.Active, _t.Current);
    }

    [Fact]
    public void Lock_is_reported_immediately_even_inside_debounce()
    {
        _t.Tick();
        _clock.Advance(1);
        var s = _t.OnSessionLock();
        Assert.Equal(ActivityState.Locked, s!.Value.State);
        Assert.Equal(_clock.UtcNow, s.Value.ObservedAt);
    }

    [Fact]
    public void Unlock_returns_to_active_after_debounce_not_immediately()
    {
        _t.Tick();
        _t.OnSessionLock();
        _clock.Advance(30);
        Assert.Null(_t.OnSessionUnlock());
        Assert.Null(Tickafter(2));
        var s = Tickafter(1);
        Assert.Equal(ActivityState.Active, s!.Value.State);
    }

    private Signal? Tickafter(double sec) { _clock.Advance(sec); return _t.Tick(); }

    [Fact]
    public void Lock_unlock_lock_quickly_reports_locked_twice_never_a_phantom_active()
    {
        _t.Tick();
        var all = new List<Signal?> { _t.OnSessionLock() };
        _clock.Advance(0.5);
        all.Add(_t.OnSessionUnlock());
        _clock.Advance(0.5);
        all.Add(_t.OnSessionLock());
        Assert.Equal([ActivityState.Locked], all.Where(x => x is not null).Select(x => x!.Value.State).Distinct());
    }

    [Fact]
    public void Suspend_sends_asleep_with_max_ttl_and_resume_returns_after_debounce()
    {
        _t.Tick();
        var asleep = _t.OnSuspend();
        Assert.Equal(ActivityState.Asleep, asleep!.Value.State);
        Assert.Equal(600, asleep.Value.TtlSeconds);

        // no heartbeat while asleep
        Assert.Empty(Run(300));

        Assert.Null(_t.OnResume());
        _idle.Idle = TimeSpan.Zero;
        Assert.Null(Tickafter(1));
        var s = Tickafter(3);
        Assert.Equal(ActivityState.Active, s!.Value.State);
    }

    [Fact]
    public void Asleep_beats_locked_and_resume_while_locked_is_locked()
    {
        _t.Tick();
        _t.OnSessionLock();
        var asleep = _t.OnSuspend();
        Assert.Equal(ActivityState.Asleep, asleep!.Value.State);
        var s = _t.OnResume();
        Assert.Equal(ActivityState.Locked, s!.Value.State); // still locked after waking: reported immediately
    }

    [Fact]
    public void Heartbeat_repeats_current_state_each_interval_with_fresh_observedAt()
    {
        _t.Tick();
        _idle.Idle = TimeSpan.Zero;
        var beats = new List<Signal>();
        for (var i = 0; i < 180; i++)
        {
            _clock.Advance(1);
            _idle.Idle = TimeSpan.Zero; // keep typing
            if (_t.Tick() is { } s) beats.Add(s);
        }
        Assert.Equal(3, beats.Count);
        Assert.All(beats, b => Assert.Equal(ActivityState.Active, b.State));
        Assert.True(beats[1].ObservedAt > beats[0].ObservedAt);
        Assert.Equal(60, (beats[1].ObservedAt - beats[0].ObservedAt).TotalSeconds);
    }

    [Fact]
    public void Heartbeat_also_runs_while_locked()
    {
        _t.Tick();
        _t.OnSessionLock();
        var beats = Run(125);
        Assert.Equal(2, beats.Count);
        Assert.All(beats, b => Assert.Equal(ActivityState.Locked, b.State));
    }

    [Fact]
    public void Heartbeat_is_not_sent_when_nothing_is_due()
    {
        _t.Tick();
        _idle.Idle = TimeSpan.Zero;
        _clock.Advance(30);
        Assert.Null(_t.Tick());
    }

    [Fact]
    public void Pause_sends_one_minimum_ttl_signal_then_nothing()
    {
        _t.Tick();
        var final = _t.Pause(null);
        Assert.Equal(ActivityState.Active, final!.Value.State);
        Assert.Equal(10, final.Value.TtlSeconds);

        _t.OnSessionLock(); // events while paused are not reported either
        Assert.Empty(Run(600));
        Assert.True(_t.IsPaused);
    }

    [Fact]
    public void Pause_for_an_hour_resumes_by_itself_and_reports_current_state()
    {
        _t.Tick();
        _t.Pause(TimeSpan.FromHours(1));
        Assert.Empty(Run(3599));
        _idle.Idle = TimeSpan.Zero;
        _clock.Advance(2);
        var s = _t.Tick();
        Assert.False(_t.IsPaused);
        Assert.Equal(ActivityState.Active, s!.Value.State);
    }

    [Fact]
    public void Resume_reporting_sends_immediately_without_debounce()
    {
        _t.Tick();
        _t.Pause(null);
        _clock.Advance(100);
        _idle.Idle = TimeSpan.Zero;
        var s = _t.ResumeReporting();
        Assert.Equal(ActivityState.Active, s!.Value.State);
        Assert.False(_t.IsPaused);
    }

    [Fact]
    public void Pausing_twice_does_not_send_a_second_final_signal()
    {
        _t.Tick();
        Assert.NotNull(_t.Pause(null));
        Assert.Null(_t.Pause(TimeSpan.FromHours(1)));
    }

    [Fact]
    public void Persisted_pause_is_restored()
    {
        _t.RestorePause(null, indefinite: true);
        Assert.True(_t.IsPaused);
        Assert.Null(_t.Tick());
        var t2 = new ActivityTracker(new CompanionSettings(), _clock, _idle);
        t2.RestorePause(_clock.UtcNow.AddMinutes(-1), indefinite: false); // already expired: not paused
        Assert.False(t2.IsPaused);
    }

    [Fact]
    public void Connectivity_restored_resends_only_the_current_state()
    {
        _t.Tick();
        _clock.Advance(5);
        _idle.Idle = TimeSpan.Zero;
        var s = _t.OnConnectivityRestored();
        Assert.Equal(ActivityState.Active, s!.Value.State);
        Assert.Null(_t.Tick()); // not repeated
    }

    [Fact]
    public void Final_signal_on_exit_shrinks_ttl_but_not_for_asleep_or_paused()
    {
        _t.Tick();
        Assert.Equal(10, _t.FinalSignal()!.Value.TtlSeconds);
        _t.OnSuspend();
        Assert.Null(_t.FinalSignal());
    }

    [Fact]
    public void Settings_are_clamped_to_relay_limits()
    {
        var s = new CompanionSettings { TtlSeconds = 5000, HeartbeatSeconds = 1000, IdleThresholdSeconds = 1 }.Normalized();
        Assert.Equal(600, s.TtlSeconds);
        Assert.Equal(300, s.HeartbeatSeconds);
        Assert.Equal(15, s.IdleThresholdSeconds);
        var tiny = new CompanionSettings { TtlSeconds = 1 }.Normalized();
        Assert.InRange(tiny.TtlSeconds, 30, 600);
    }
}
