using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using DayCue.Companion.Core;

namespace DayCue.Companion.App;

/// <summary>Draws the tray icons in code: a dark rounded tile with a ring and a state-coloured dot. No third-party assets.</summary>
public static class IconFactory
{
    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);

    private static readonly Dictionary<string, Icon> Cache = new();

    public static Icon For(ActivityState? state, bool paused, bool paired)
    {
        var key = !paired ? "unpaired" : paused ? "paused" : state?.ToString() ?? "unknown";
        if (Cache.TryGetValue(key, out var cached)) return cached;
        Color? dot = key switch
        {
            "Active" => Color.FromArgb(46, 184, 114),
            "Idle" => Color.FromArgb(242, 169, 0),
            "Locked" => Color.FromArgb(90, 141, 238),
            "Asleep" => Color.FromArgb(150, 120, 210),
            _ => null, // unpaired, paused, unknown: hollow
        };
        return Cache[key] = Draw(dot, hollowStyle: key == "paused" ? 1 : 0);
    }

    private static Icon Draw(Color? dot, int hollowStyle)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var tile = RoundedRect(new RectangleF(1, 1, 30, 30), 7);
            using (var bg = new SolidBrush(Color.FromArgb(32, 40, 58))) g.FillPath(bg, tile);
            using (var ring = new Pen(Color.FromArgb(225, 230, 240), 3f)) g.DrawEllipse(ring, 6.5f, 6.5f, 19f, 19f);
            if (dot is { } c)
            {
                using var b = new SolidBrush(c);
                g.FillEllipse(b, 11, 11, 10, 10);
            }
            else if (hollowStyle == 1)
            {
                using var b = new SolidBrush(Color.FromArgb(225, 230, 240)); // pause bars
                g.FillRectangle(b, 11.5f, 11, 3.2f, 10);
                g.FillRectangle(b, 17.5f, 11, 3.2f, 10);
            }
        }
        var h = bmp.GetHicon();
        try { return (Icon)Icon.FromHandle(h).Clone(); }
        finally { DestroyIcon(h); }
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        var d = radius * 2;
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}
