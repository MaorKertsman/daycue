using DayCue.Companion.Net;

namespace DayCue.Companion.App;

/// <summary>Asks for the relay URL and the pairing code shown by the phone. Closes only when pairing succeeded.</summary>
public sealed class PairForm : Form
{
    private readonly TextBox _url = new() { Width = 340 };
    private readonly TextBox _code = new() { Width = 340, CharacterCasing = CharacterCasing.Upper };
    private readonly TextBox _label = new() { Width = 340, Text = "Windows PC", MaxLength = 60 };
    private readonly Label _status = new() { AutoSize = false, Width = 340, Height = 44, ForeColor = Color.DimGray };
    private readonly Button _ok = new() { Text = "Pair", Width = 90 };
    private readonly Button _cancel = new() { Text = "Cancel", Width = 90, DialogResult = DialogResult.Cancel };
    private readonly Func<Uri, string, string, Task<string?>> _pair;

    /// <param name="pair">Performs pairing; returns null on success or a user-readable error.</param>
    public PairForm(string? lastUrl, Func<Uri, string, string, Task<string?>> pair)
    {
        _pair = pair;
        Text = "Pair DayCue Companion";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ShowInTaskbar = true;
        ClientSize = new Size(370, 290);
        Icon = IconFactory.For(null, false, true);
        _url.Text = lastUrl ?? "";

        var y = 12;
        void Add(string caption, Control c)
        {
            Controls.Add(new Label { Text = caption, Left = 14, Top = y, AutoSize = true });
            c.Left = 14; c.Top = y + 20;
            Controls.Add(c);
            y += 54;
        }
        Add("Relay URL (https://...)", _url);
        Add("Pairing code shown in the DayCue phone app", _code);
        Add("Name shown on the phone", _label);
        _status.Left = 14; _status.Top = y - 4;
        Controls.Add(_status);
        _ok.Left = 14 + 340 - 190; _ok.Top = 250;
        _cancel.Left = 14 + 340 - 90; _cancel.Top = 250;
        Controls.AddRange([_ok, _cancel]);
        AcceptButton = _ok;
        CancelButton = _cancel;
        _ok.Click += async (_, _) => await OnPair();
    }

    private async Task OnPair()
    {
        if (!Wire.TryNormalizeRelayUrl(_url.Text, out var url))
        {
            Fail("Enter the relay address as https://... (http is allowed only for localhost).");
            return;
        }
        if (string.IsNullOrWhiteSpace(_code.Text)) { Fail("Enter the pairing code from the phone."); return; }
        SetBusy(true, "Pairing... (a sleeping relay can take up to a minute and a half)");
        string? error;
        try { error = await _pair(url, _code.Text.Trim(), string.IsNullOrWhiteSpace(_label.Text) ? "Windows PC" : _label.Text.Trim()); }
        catch (Exception e) { error = e.Message; }
        if (error is null) { DialogResult = DialogResult.OK; Close(); return; }
        SetBusy(false, null);
        Fail(error);
    }

    private void Fail(string msg) { _status.ForeColor = Color.Firebrick; _status.Text = msg; }

    private void SetBusy(bool busy, string? msg)
    {
        _ok.Enabled = _url.Enabled = _code.Enabled = _label.Enabled = !busy;
        if (msg is not null) { _status.ForeColor = Color.DimGray; _status.Text = msg; }
    }
}
