using System.Text.Json;

namespace DayCue.Companion.Core;

/// <summary>User-editable tuning (optional file settings.json in the DayCue config folder). All values are clamped to safe ranges.</summary>
public sealed class CompanionSettings
{
    /// <summary>No input for this long means idle.</summary>
    public int IdleThresholdSeconds { get; set; } = 120;
    /// <summary>Resend the current state this often while the state holds (active, idle, locked).</summary>
    public int HeartbeatSeconds { get; set; } = 60;
    /// <summary>How long the phone may trust a signal. The relay accepts 10..600.</summary>
    public int TtlSeconds { get; set; } = 180;
    /// <summary>A new active/idle state must hold this long before it is sent (lock and sleep are sent immediately).</summary>
    public int DebounceSeconds { get; set; } = 3;
    /// <summary>How often idle time is polled.</summary>
    public int PollSeconds { get; set; } = 5;
    /// <summary>Per-request HTTP timeout. A free relay host can take about 90 s to wake.</summary>
    public int HttpTimeoutSeconds { get; set; } = 100;
    /// <summary>Attempts per signal on network errors or 5xx before giving up until the next heartbeat.</summary>
    public int MaxAttempts { get; set; } = 4;

    public const int AsleepTtlSeconds = 600;
    public const int ShrinkTtlSeconds = 10;

    public CompanionSettings Normalized()
    {
        var s = (CompanionSettings)MemberwiseClone();
        s.TtlSeconds = Math.Clamp(s.TtlSeconds, 30, 600);
        s.HeartbeatSeconds = Math.Clamp(s.HeartbeatSeconds, 10, s.TtlSeconds / 2);
        s.IdleThresholdSeconds = Math.Clamp(s.IdleThresholdSeconds, 15, 3600);
        s.DebounceSeconds = Math.Clamp(s.DebounceSeconds, 0, 30);
        s.PollSeconds = Math.Clamp(s.PollSeconds, 2, 30);
        s.HttpTimeoutSeconds = Math.Clamp(s.HttpTimeoutSeconds, 10, 180);
        s.MaxAttempts = Math.Clamp(s.MaxAttempts, 1, 6);
        return s;
    }

    public static CompanionSettings Load(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var opts = new JsonSerializerOptions { PropertyNameCaseInsensitive = true, ReadCommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true };
                return (JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(path), opts) ?? new CompanionSettings()).Normalized();
            }
        }
        catch { /* fall back to defaults on a malformed file */ }
        return new CompanionSettings().Normalized();
    }
}
