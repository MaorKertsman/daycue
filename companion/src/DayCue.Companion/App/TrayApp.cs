using System.Net.NetworkInformation;
using System.Security.Cryptography;
using DayCue.Companion.Core;
using DayCue.Companion.Net;
using DayCue.Companion.Platform;
using DayCue.Companion.Security;
using Microsoft.Win32;
using Timer = System.Windows.Forms.Timer;

namespace DayCue.Companion.App;

public sealed class TrayApp : ApplicationContext
{
    public const string PrivacyText =
        "DayCue Companion tells your phone one thing: whether this computer is in active use.\r\n\r\n" +
        "It sends only:\r\n" +
        "  - one of four states: active, idle, locked, asleep\r\n" +
        "  - the time of that observation and how long it stays valid (a few minutes)\r\n" +
        "  - a cryptographic signature proving it came from this paired app\r\n\r\n" +
        "It NEVER collects or transmits keystrokes, mouse movements, screenshots, window titles, process or application names, " +
        "browser history, files, or document contents. \"Idle\" is measured with one Windows number (time since the last input of any kind); " +
        "what the input was is never seen.\r\n\r\n" +
        "Signals go over HTTPS only to the relay address you entered, and expire on their own. Use Pause reporting to send nothing, " +
        "or Unpair to delete this app's key and credentials from this PC.";

    private readonly AppData _data;
    private readonly CompanionSettings _settings;
    private readonly IClock _clock = new SystemClock();
    private readonly ActivityTracker _tracker;
    private readonly StartupRegistration _startup = new();
    private readonly SynchronizationContext _ui;
    private readonly NotifyIcon _tray;
    private readonly Timer _poll = new();
    private readonly Timer _followUp = new();
    private readonly Timer? _exitTimer;
    private readonly ContextMenuStrip _menu = new();
    private HttpClient? _http;
    private SignalSender? _sender;
    private PairingRecord? _pairing;
    private bool _exiting;

    public TrayApp(AppData data, int? exitAfterSeconds)
    {
        _data = data;
        _settings = CompanionSettings.Load(data.SettingsPath);
        _ui = SynchronizationContext.Current ?? new SynchronizationContext();
        _tracker = new ActivityTracker(_settings, _clock, new Win32IdleSource());

        var st = data.LoadState();
        _tracker.RestorePause(st.PausedUntil, st.PausedIndefinitely);

        _pairing = data.LoadPairing();
        if (_pairing is not null) StartSender();

        _tray = new NotifyIcon { ContextMenuStrip = _menu, Visible = true, Icon = IconFactory.For(null, false, false), Text = "DayCue Companion" };
        _menu.Opening += (_, _) => RebuildMenu();
        _tray.DoubleClick += (_, _) => ShowStatusBalloon();

        SystemEvents.SessionSwitch += OnSessionSwitch;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.SessionEnding += OnSessionEnding;
        NetworkChange.NetworkAvailabilityChanged += OnNetworkAvailability;

        _poll.Interval = _settings.PollSeconds * 1000;
        _poll.Tick += (_, _) => Dispatch(_tracker.Tick());
        _poll.Start();
        _followUp.Tick += (_, _) => { _followUp.Stop(); Dispatch(_tracker.Tick()); };

        Dispatch(_tracker.Tick());

        if (exitAfterSeconds is { } s)
        {
            _exitTimer = new Timer { Interval = Math.Max(1, s) * 1000 };
            _exitTimer.Tick += (_, _) => { _exitTimer.Stop(); ExitApp(); };
            _exitTimer.Start();
        }
    }

    // ------------------------------------------------------------------ signal flow

    private void StartSender()
    {
        _sender?.Dispose();
        _http?.Dispose();
        var p = _pairing!;
        if (!Wire.TryNormalizeRelayUrl(p.RelayUrl, out var url)) return;
        ECDsa key;
        string token;
        try
        {
            key = KeyVault.Open(p.KeyName);
            token = AppData.Unprotect(p.TokenProtected);
        }
        catch (Exception e) when (e is CryptographicException or FormatException)
        {
            LogError(_data, e);
            _pairing = null; // key or credential unusable (e.g. profile moved): treat as unpaired
            return;
        }
        _http = RelayClient.CreateHttpClient(TimeSpan.FromSeconds(_settings.HttpTimeoutSeconds));
        var client = new RelayClient(_http, url, token);
        _sender = new SignalSender(client, new EcdsaSigner(p.DeviceId, key), _clock, _settings.MaxAttempts, p.ClockSkewMs);
        _sender.StatusChanged += _ => _ui.Post(_ => RefreshUi(), null);
    }

    private void Dispatch(Signal? signal)
    {
        PersistPauseIfChanged();
        if (signal is { } s) _sender?.Submit(s);
        RefreshUi();
    }

    /// <summary>After an event, check again once the debounce window has passed so a quick unlock/resume is reported promptly.</summary>
    private void ScheduleFollowUp()
    {
        _ui.Post(_ =>
        {
            _followUp.Interval = (_settings.DebounceSeconds * 1000) + 500;
            _followUp.Stop();
            _followUp.Start();
        }, null);
    }

    private (bool, DateTimeOffset?) _pauseSaved;
    private void PersistPauseIfChanged()
    {
        var now = (_tracker.IsPaused, _tracker.PausedUntil);
        if (now == _pauseSaved) return;
        _data.SaveState(new LocalState { PausedIndefinitely = now.Item1 && now.Item2 is null, PausedUntil = now.Item2 });
        _pauseSaved = now;
    }

    // SystemEvents callbacks arrive on a dedicated thread; the tracker and sender are thread-safe.
    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        Signal? s;
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock: s = _tracker.OnSessionLock(); break;
            case SessionSwitchReason.SessionUnlock: s = _tracker.OnSessionUnlock(); ScheduleFollowUp(); break;
            default: return;
        }
        // Lock goes out immediately from this thread, not via the UI thread.
        if (s is { } sig) _sender?.Submit(sig);
        _ui.Post(_ => RefreshUi(), null);
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        switch (e.Mode)
        {
            case PowerModes.Suspend:
                // Best effort: the system gives very little time before sleeping.
                if (_tracker.OnSuspend() is { } asleep && _sender is { } snd)
                {
                    snd.Submit(asleep);
                    snd.WaitIdleAsync(TimeSpan.FromSeconds(3)).GetAwaiter().GetResult();
                }
                break;
            case PowerModes.Resume:
                var s = _tracker.OnResume();
                _ui.Post(_ => Dispatch(s), null);
                ScheduleFollowUp();
                break;
        }
    }

    private void OnSessionEnding(object sender, SessionEndingEventArgs e)
    {
        // Logoff or shutdown: report asleep (longest TTL) best-effort.
        if (_tracker.OnSuspend() is { } s && _sender is { } snd)
        {
            snd.Submit(s);
            snd.WaitIdleAsync(TimeSpan.FromSeconds(2)).GetAwaiter().GetResult();
        }
    }

    private void OnNetworkAvailability(object? sender, NetworkAvailabilityEventArgs e)
    {
        if (!e.IsAvailable) return;
        var s = _tracker.OnConnectivityRestored();
        _ui.Post(_ => Dispatch(s), null);
    }

    // ------------------------------------------------------------------ UI

    private string ConnectionText()
    {
        if (_pairing is null || _sender is null) return "not paired";
        var st = _sender.Status;
        return st.Connection switch
        {
            ConnectionState.NotSent => "waiting for first send",
            ConnectionState.Online => "online",
            ConnectionState.Retrying => "retrying" + (st.Detail is null ? "" : $" ({st.Detail})"),
            ConnectionState.Offline => "offline, will retry" + (st.Detail is null ? "" : $" ({st.Detail})"),
            ConnectionState.CredentialRejected => "credential rejected, pair again",
            _ => "unknown",
        };
    }

    private string StateText()
    {
        if (_tracker.IsPaused)
            return _tracker.PausedUntil is { } u ? $"paused until {u.ToLocalTime():HH:mm}" : "paused until resumed";
        return (_tracker.Current?.ToWire() ?? "unknown");
    }

    private void RefreshUi()
    {
        if (_exiting) return;
        var paired = _pairing is not null && _sender is not null;
        _tray.Icon = IconFactory.For(_tracker.Current, _tracker.IsPaused, paired);
        var text = $"DayCue: {StateText()} | {ConnectionText()}";
        _tray.Text = text.Length > 120 ? text[..120] : text;
    }

    private void ShowStatusBalloon() => _tray.ShowBalloonTip(3000, "DayCue Companion", $"{StateText()} | {ConnectionText()}", ToolTipIcon.None);

    private void RebuildMenu()
    {
        _menu.Items.Clear();
        var paired = _pairing is not null && _sender is not null;
        var st = _sender?.Status;
        void Info(string t) => _menu.Items.Add(new ToolStripMenuItem(t) { Enabled = false });

        Info(paired ? $"Paired: yes ({new Uri(_pairing!.RelayUrl).Host})" : "Paired: no");
        Info("This PC: " + StateText());
        Info(st?.LastSentAt is { } at ? $"Last sent: {at.ToLocalTime():HH:mm:ss} ({st.LastSentState?.ToWire()})" : "Last sent: nothing yet");
        Info("Connection: " + ConnectionText());
        _menu.Items.Add(new ToolStripSeparator());

        if (_tracker.IsPaused)
            _menu.Items.Add("Resume reporting", null, (_, _) => Dispatch(_tracker.ResumeReporting()));
        else
        {
            var pause = new ToolStripMenuItem("Pause reporting") { Enabled = paired };
            pause.DropDownItems.Add("For 1 hour", null, (_, _) => PauseFor(TimeSpan.FromHours(1)));
            pause.DropDownItems.Add("Until I resume", null, (_, _) => PauseFor(null));
            _menu.Items.Add(pause);
        }

        _menu.Items.Add(paired ? new ToolStripMenuItem("Unpair...", null, (_, _) => Unpair()) : new ToolStripMenuItem("Pair...", null, (_, _) => Pair()));
        _menu.Items.Add(new ToolStripSeparator());

        var startup = new ToolStripMenuItem("Run at startup") { Checked = SafeStartupEnabled(), CheckOnClick = false };
        startup.Click += (_, _) => ToggleStartup();
        _menu.Items.Add(startup);
        _menu.Items.Add("About / Privacy...", null, (_, _) => MessageBox.Show(PrivacyText, "DayCue Companion: privacy", MessageBoxButtons.OK, MessageBoxIcon.Information));
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add("Exit", null, (_, _) => ExitApp());
    }

    private bool SafeStartupEnabled() { try { return _startup.IsEnabled; } catch { return false; } }

    private void ToggleStartup()
    {
        try
        {
            if (_startup.IsEnabled) _startup.Disable();
            else _startup.Enable(Environment.ProcessPath ?? Application.ExecutablePath);
        }
        catch (Exception e) { MessageBox.Show("Could not change the startup setting: " + e.Message, "DayCue Companion"); }
    }

    private void PauseFor(TimeSpan? d)
    {
        var final = _tracker.Pause(d);
        if (final is { } f) _sender?.Submit(f); // one last signal with the minimum TTL so the phone stops trusting the state within ~10 s
        PersistPauseIfChanged();
        RefreshUi();
    }

    // ------------------------------------------------------------------ pairing

    private void Pair()
    {
        using var form = new PairForm(_pairing?.RelayUrl, DoPair);
        form.ShowDialog();
        RefreshUi();
    }

    private async Task<string?> DoPair(Uri url, string code, string label)
    {
        var keyName = KeyVault.NewKeyName();
        try
        {
            var spki = KeyVault.CreateKey(keyName);
            using var http = RelayClient.CreateHttpClient(TimeSpan.FromSeconds(_settings.HttpTimeoutSeconds));
            var result = await RelayClient.PairAsync(http, url, code, spki, label, CancellationToken.None);
            var skew = result.ServerTimeMs is { } st ? st - _clock.UtcNow.ToUnixTimeMilliseconds() : 0;
            var record = new PairingRecord
            {
                RelayUrl = url.ToString(),
                DeviceId = result.DeviceId,
                Label = label,
                KeyName = keyName,
                TokenProtected = AppData.Protect(result.Token),
                ClockSkewMs = Math.Abs(skew) > 5000 ? skew : 0,
                PairedAt = _clock.UtcNow,
            };
            _data.SavePairing(record);
            _pairing = record;
            StartSender();
            if (_pairing is null) throw new PairingException("Could not load the new key.");
            Dispatch(_tracker.OnConnectivityRestored()); // send the current state right away
            return null;
        }
        catch (PairingException e)
        {
            KeyVault.Delete(keyName);
            return e.Message;
        }
        catch (Exception e)
        {
            KeyVault.Delete(keyName);
            LogError(_data, e);
            return "Pairing failed: " + e.Message;
        }
    }

    private void Unpair()
    {
        var r = MessageBox.Show(
            "Delete this PC's DayCue key and credentials and stop reporting?\r\n\r\n" +
            "The app also asks the relay to revoke this device's credential (best effort; if the relay is unreachable the credential stays valid " +
            "until you remove the device there: relay owner API DELETE /v1/owner/devices/<id>).",
            "Unpair DayCue Companion", MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
        if (r != DialogResult.OK) return;
        var revoked = TryRevokeOnRelay();
        _sender?.Dispose();
        _sender = null;
        _http?.Dispose();
        _http = null;
        _data.DeletePairing();
        _pairing = null;
        RefreshUi();
        if (!revoked)
            MessageBox.Show("Unpaired on this PC. The relay could not be reached, so it may still list this device as active. Remove it with the relay owner API (DELETE /v1/owner/devices/<id>) when convenient.",
                "Unpair DayCue Companion", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    /// <summary>Asks the relay to revoke this device's credential before the local secrets are deleted. Bounded to a few seconds; never throws.</summary>
    private bool TryRevokeOnRelay()
    {
        try
        {
            var p = _pairing;
            if (p is null || !Wire.TryNormalizeRelayUrl(p.RelayUrl, out var url)) return true;
            var token = AppData.Unprotect(p.TokenProtected);
            using var http = RelayClient.CreateHttpClient(TimeSpan.FromSeconds(5));
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(6));
            return Task.Run(() => RelayClient.RevokeSelfAsync(http, url, token, cts.Token)).GetAwaiter().GetResult();
        }
        catch (Exception e) when (e is CryptographicException or FormatException or InvalidOperationException) { return false; }
    }

    // ------------------------------------------------------------------ lifecycle

    private void ExitApp()
    {
        if (_exiting) return;
        _exiting = true;
        _poll.Stop();
        _followUp.Stop();
        SystemEvents.SessionSwitch -= OnSessionSwitch;
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.SessionEnding -= OnSessionEnding;
        NetworkChange.NetworkAvailabilityChanged -= OnNetworkAvailability;
        try
        {
            if (_sender is { } snd && _tracker.FinalSignal() is { } f)
            {
                snd.Submit(f); // minimum-TTL final signal: the phone sees "unknown" within about 10 s
                snd.WaitIdleAsync(TimeSpan.FromSeconds(3)).GetAwaiter().GetResult();
            }
        }
        catch { /* best effort */ }
        _tray.Visible = false;
        _tray.Dispose();
        _sender?.Dispose();
        _http?.Dispose();
        ExitThread();
    }

    public static void LogError(AppData data, Exception? e)
    {
        try { File.AppendAllText(Path.Combine(data.Dir, "error.log"), $"{DateTime.Now:s} {e?.GetType().Name}: {e?.Message}\r\n"); }
        catch { /* nothing else to do */ }
    }
}
