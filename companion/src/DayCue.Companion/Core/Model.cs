namespace DayCue.Companion.Core;

/// <summary>The only four things the companion ever reports. Wire names are lower case (see docs/architecture/RELAY.md 4.5).</summary>
public enum ActivityState { Active, Idle, Locked, Asleep }

public static class ActivityStateExtensions
{
    public static string ToWire(this ActivityState s) => s switch
    {
        ActivityState.Active => "active",
        ActivityState.Idle => "idle",
        ActivityState.Locked => "locked",
        ActivityState.Asleep => "asleep",
        _ => throw new ArgumentOutOfRangeException(nameof(s)),
    };
}

/// <summary>An unsigned activity signal: state, when it was observed, and how long the phone may trust it.</summary>
public readonly record struct Signal(ActivityState State, DateTimeOffset ObservedAt, int TtlSeconds)
{
    public DateTimeOffset ExpiresAt => ObservedAt.AddSeconds(TtlSeconds);
}

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}

public sealed class SystemClock : IClock
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}

/// <summary>Time since the last keyboard or mouse input. Only a duration; never what the input was.</summary>
public interface IIdleSource
{
    TimeSpan GetIdleTime();
}
