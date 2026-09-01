package com.anthropic.claudecode.eclipse.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

/**
 * Per-tab status strip for the Claude Terminal view: a one-line, at-a-glance indicator of the
 * current model, effort level, thinking indicator, context-window usage, session cost, and the
 * 5-hour/weekly subscription usage limits (with reset countdowns). It is fed by {@link #setStatus}
 * from  {@code StatusBridge}.
 *
 * <p>The bar is a single custom-painted {@link Canvas} (rather than a row of {@code Label}s)
 * for two reasons: it draws real, theme-coloured mini progress bars that match the design
 * mockups and look native, and it lays itself out responsively by <em>measuring</em> the
 * actual text — choosing the widest form that fits the current width instead of a hard-coded
 * pixel breakpoint:
 * <ol>
 *   <li><b>long</b> — {@code Opus 4.8 · high │ Context ▮ 24% │ Session ▮ 41% resets in 2h 23m …}</li>
 *   <li><b>short</b> — single-letter labels, narrow bars, no {@code %} sign / {@code resets in}
 *       word: {@code Opus 4.8 · high │ C ▮ 24 │ S ▮ 41 2h 23m │ W ▮ 75 6d 17h}</li>
 *   <li>if even the short form overflows, trailing meter groups are dropped from the right
 *       (model is always kept).</li>
 * </ol>
 *
 * <p><b>The long↔short breakpoint is shared by both views.</b> It is measured with reset
 * text always reserved, whether or not the current status carries any, so the labels
 * expand and contract at the same window width in the Claude Terminal (whose statusLine
 * supplies reset epochs) and in Claude Code (whose {@code /usage} probe supplies only
 * percentages). Measuring just the visible text would let the same window show
 * {@code Context/Session/Weekly} in one view and {@code C/S/W} in the other, flipping on
 * every switch. The reserved space is simply left unused when there is no reset time to
 * draw — that missing countdown is the only intended difference between the two.
 * It also paints a 1px delimiter line along its top edge, separating it from the terminal
 * area above.
 *
 * <p>One instance per terminal tab; it shows only that tab's data and is disposed with the
 * tab. Every method below runs on the SWT UI thread.
 */
public final class ClaudeStatusBar extends Canvas {

    // Geometry (device-independent px; SWT scales with the display DPI).
    private static final int MARGIN_X = 8;   // left/right inset
    private static final int GAP = 5;        // spacing between pieces within a meter
    private static final int SEP_PAD = 8;    // padding either side of a group separator
    private static final int SEP_W = SEP_PAD * 2 + 1;
    private static final int BAR_W_LONG = 56;
    private static final int BAR_W_SHORT = 26;
    private static final int BAR_H = 6;

    // Reset moment shown in limit tooltips, formatted in the user's locale (e.g. "Jun 27, 2026, 2:30 PM").
    private static final DateTimeFormatter RESET_TIME_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    // Per-segment identity colours. Created here, disposed on widget dispose.
    private final Color ctxColor;     // context below 70% — green
    private final Color limitColor;   // limits below 80% — blue (session + weekly)
    private final Color yellowColor;  // warning threshold
    private final Color redColor;     // critical threshold
    private final Color trackColor;   // empty bar track

    // Theme colours (system, not disposed).
    private final Color fgColor;      // labels / model name
    private final Color mutedColor;   // effort, "resets in …", placeholder
    private final Color sepColor;     // group separators
    private final Color borderColor;  // top delimiter line

    private ClaudeStatus lastStatus;

    // Hover regions for per-segment tooltips, rebuilt on every paint (x-range → tooltip text).
    private final List<HoverRegion> hoverRegions = new ArrayList<>();

    public ClaudeStatusBar(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);

        var d = getDisplay();
        ctxColor   = new Color(d,  90, 168,  92);   // green
        limitColor = new Color(d,  62, 120, 214);   // blue
        yellowColor = new Color(d, 200, 145,  30);  // amber
        redColor   = new Color(d, 196,  58,  58);   // red
        trackColor = new Color(d, 208, 208, 208);

        fgColor = d.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
        mutedColor = d.getSystemColor(SWT.COLOR_DARK_GRAY);
        sepColor = d.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);
        borderColor = d.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);

        setBackground(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        setFont(JFaceResources.getDialogFont());

        addPaintListener(this::onPaint);
        addMouseMoveListener(e -> updateTooltip(e.x));
        addListener(SWT.Resize, e -> redraw());
        addDisposeListener(e -> {
            ctxColor.dispose();
            limitColor.dispose();
            yellowColor.dispose();
            redColor.dispose();
            trackColor.dispose();
        });
    }

    /**
     * Pushes a fresh status snapshot and repaints. Per-element visibility preferences are
     * re-read on every paint, so preference toggles apply live. {@code null} clears the bar
     * back to its placeholder.
     */
    public void setStatus(ClaudeStatus status) {
        if (isDisposed()) return;
        this.lastStatus = status;
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        int fontH;
        GC gc = new GC(this);
        try {
            gc.setFont(getFont());
            fontH = gc.getFontMetrics().getHeight();
        } finally {
            gc.dispose();
        }
        int height = (hHint == SWT.DEFAULT) ? fontH + 8 : hHint;
        int width = (wHint == SWT.DEFAULT) ? 500 : wHint;
        return new Point(width, height);
    }

    // ── Painting ────────────────────────────────────────────────────────────

    private void onPaint(PaintEvent ev) {
        GC gc = ev.gc;
        gc.setFont(getFont());
        gc.setAntialias(SWT.ON);
        Rectangle area = getClientArea();

        gc.setBackground(getBackground());
        gc.fillRectangle(area);

        // Top delimiter line, separating the bar from the terminal area above.
        gc.setForeground(borderColor);
        gc.drawLine(0, 0, area.width, 0);

        int midY = area.height / 2 + 1;

        hoverRegions.clear();

        if (lastStatus == null) {
            drawText(gc, "Claude Code — waiting for status...", MARGIN_X, midY, mutedColor);
            return;
        }

        // Pick the widest form that fits: long → short → short with trailing groups dropped.
        //
        // The long↔short decision is measured with reset text ALWAYS reserved
        // (RESERVE_RESET_WIDTH), even when this particular status carries none, so the
        // labels flip at the SAME window width in both views. Without that reservation
        // the Claude Code view — whose /usage probe supplies percentages but no reset
        // epochs — measures narrower and keeps "Context/Session/Weekly" at widths where
        // the Terminal, carrying "(resets in 2h 23m)", has already dropped to "C/S/W";
        // switching views then flips the labels back and forth for no visible reason.
        // Rendering still uses the real data, so the reserved space simply goes unused
        // when there is no reset time to draw.
        boolean compact =
                totalWidth(buildSegments(gc, false, true, RESERVE_RESET_WIDTH)) > area.width;
        SegLayout layout = buildSegments(gc, compact, true, MEASURE_ACTUAL);
        if (totalWidth(layout) > area.width) {
            layout = buildSegments(gc, compact, false, MEASURE_ACTUAL);
            dropToFit(layout, area.width);
        }
        draw(gc, layout, area.width, midY);
    }

    private void draw(GC gc, SegLayout layout, int areaWidth, int midY) {
        drawGroup(gc, layout.left, MARGIN_X, midY);
        int rightStart = areaWidth - MARGIN_X - groupWidth(layout.right);
        drawGroup(gc, layout.right, rightStart, midY);
    }

    private void drawGroup(GC gc, List<Seg> segs, int startX, int midY) {
        int x = startX;
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) {
                int sx = x + SEP_PAD;
                gc.setForeground(sepColor);
                gc.drawLine(sx, midY - 6, sx, midY + 6);
                x += SEP_W;
            }
            Seg seg = segs.get(i);
            seg.painter.paint(gc, x, midY);
            if (seg.tooltip != null) {
                hoverRegions.add(new HoverRegion(x, x + seg.width, seg.tooltip));
            }
            x += seg.width;
        }
    }

    /** Updates the canvas tooltip to match the segment under {@code mouseX} (or clears it). */
    private void updateTooltip(int mouseX) {
        String tip = null;
        for (HoverRegion r : hoverRegions) {
            if (mouseX >= r.x0() && mouseX < r.x1()) {
                tip = r.tooltip();
                break;
            }
        }
        if (!Objects.equals(tip, getToolTipText())) {
            setToolTipText(tip);
        }
    }

    private int totalWidth(SegLayout layout) {
        return MARGIN_X * 2 + groupWidth(layout.left) + groupWidth(layout.right);
    }

    private int groupWidth(List<Seg> segs) {
        if (segs.isEmpty()) return 0;
        int w = (segs.size() - 1) * SEP_W;
        for (Seg s : segs) w += s.width;
        return w;
    }

    /** Drops trailing segments until the row fits; right group is trimmed first, model is always kept. */
    private void dropToFit(SegLayout layout, int avail) {
        while (!layout.right.isEmpty() && totalWidth(layout) > avail) {
            layout.right.remove(layout.right.size() - 1);
        }
        while (layout.left.size() > 1 && totalWidth(layout) > avail) {
            layout.left.remove(layout.left.size() - 1);
        }
    }

    // ── Segment construction ────────────────────────────────────────────────

    /** {@code reserveResets} value that measures a rate-limit meter as if it carried
     *  reset text — used only to fix the long↔short breakpoint (see {@link #onPaint}). */
    private static final boolean RESERVE_RESET_WIDTH = true;
    /** {@code reserveResets} value that measures exactly what will be drawn. */
    private static final boolean MEASURE_ACTUAL = false;

    /** Reset strings used purely for width reservation — never drawn. These are the
     *  <em>widest</em> forms {@link #formatRemaining} can produce ({@code "%dh %dm"} with
     *  two-digit hours; the {@code "%dd %dh"} branch is never wider, as the 7-day window
     *  caps days at 6). Reserving the widest case means the text actually drawn can never
     *  exceed what the breakpoint measured. */
    private static final String RESET_SAMPLE_LONG = "(resets in 23h 59m)";
    private static final String RESET_SAMPLE_SHORT = "(23h 59m)";

    private SegLayout buildSegments(GC gc, boolean compact, boolean withResets,
                                    boolean reserveResets) {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        ClaudeStatus s = lastStatus;
        List<Seg> left = new ArrayList<>();
        List<Seg> right = new ArrayList<>();

        Seg model = buildModelSeg(gc, prefs, s);
        if (model != null) left.add(model);

        if (prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_CONTEXT)) {
            // Context never carries a reset time, so it never reserves width for one.
            left.add(buildMeter(gc, compact ? "C" : "Context",
                    s.contextUsedPercentage().orElse(0.0), true,
                    OptionalLong.empty(), compact, withResets, false,
                    buildContextTooltip(s)));
        }

        if (prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_COST) && s.totalCostUsd().isPresent()) {
            left.add(buildCostSeg(gc, s.totalCostUsd().getAsDouble(), compact));
        }

        var five = s.fiveHour();
        if (prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_SESSION_5H)
                && five.isPresent() && five.get().usedPercentage().isPresent()) {
            right.add(buildMeter(gc, compact ? "S" : "Session",
                    five.get().usedPercentage().getAsDouble(), false,
                    five.get().resetsAt(), compact, withResets, reserveResets,
                    buildLimitTooltip("5-hour session usage limit",
                            five.get().usedPercentage().getAsDouble(), five.get().resetsAt())));
        }

        var seven = s.sevenDay();
        if (prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_WEEKLY)
                && seven.isPresent() && seven.get().usedPercentage().isPresent()) {
            right.add(buildMeter(gc, compact ? "W" : "Weekly",
                    seven.get().usedPercentage().getAsDouble(), false,
                    seven.get().resetsAt(), compact, withResets, reserveResets,
                    buildLimitTooltip("Weekly usage limit",
                            seven.get().usedPercentage().getAsDouble(), seven.get().resetsAt())));
        }

        return new SegLayout(left, right);
    }

    /**
     * Context meter tooltip: usage percentage, window size, and the raw token breakdown
     * ({@code current_usage}) when the first API call has populated it.
     */
    private static String buildContextTooltip(ClaudeStatus s) {
        StringBuilder sb = new StringBuilder("Context window usage: ");
        sb.append(String.format(Locale.ROOT, "%.1f%% used", s.contextUsedPercentage().orElse(0.0)));
        if (s.contextWindowSize().isPresent()) {
            sb.append('\n').append(String.format(Locale.ROOT, "Window size: %,d tokens",
                    s.contextWindowSize().getAsLong()));
        }
        if (s.inputTokens().isPresent() || s.outputTokens().isPresent()
                || s.cacheCreationTokens().isPresent() || s.cacheReadTokens().isPresent()) {
            sb.append(String.format(Locale.ROOT, "\n%,d in / %,d out / %,d cache created / %,d cache read tokens",
            		s.inputTokens().orElse(0), s.outputTokens().orElse(0),
            		s.cacheCreationTokens().orElse(0), s.cacheReadTokens().orElse(0)));
        }
        return sb.toString();
    }

    /** Session/weekly limit tooltip: percentage consumed and the reset date/time. */
    private static String buildLimitTooltip(String title, double pct, OptionalLong resetsAt) {
        StringBuilder sb = new StringBuilder(title);
        sb.append(String.format(Locale.ROOT, ": %.1f%% used", pct));
        if (resetsAt.isPresent()) {
            sb.append('\n').append("Resets at ").append(formatResetTime(resetsAt.getAsLong()));
        }
        return sb.toString();
    }

    /** Reset moment in the user's locale/zone, e.g. {@code "Jun 27, 2026, 2:30 PM"}. */
    private static String formatResetTime(long resetsAtEpochSec) {
        return Instant.ofEpochSecond(resetsAtEpochSec)
                .atZone(ZoneId.systemDefault())
                .format(RESET_TIME_FORMAT);
    }

    /**
     * {@code Opus 4.8 · high · thinking} — model name in foreground; effort and the thinking
     * indicator muted, each joined by {@code · } after the preceding piece. Any piece may be
     * hidden (by preference or absent data).
     */
    private Seg buildModelSeg(GC gc, IPreferenceStore prefs, ClaudeStatus s) {
        String model = prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_MODEL)
                && s.modelDisplayName().isPresent() ? s.modelDisplayName().get() : null;
        String effort = prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_EFFORT)
                && s.effortLevel().isPresent() ? s.effortLevel().get() : null;
        boolean thinking = prefs.getBoolean(Constants.PREF_STATUSLINE_SHOW_THINKING)
                && s.thinkingEnabled().orElse(false);

        // Muted suffixes after the model name, in display order.
        List<String> muted = new ArrayList<>();
        if (effort != null) muted.add(effort);
        if (thinking) muted.add("thinking");
        if (model == null && muted.isEmpty()) return null;

        final String dot = " · ";
        int dw = gc.textExtent(dot).x;
        int mw = model != null ? gc.textExtent(model).x : 0;
        int[] partW = new int[muted.size()];
        int width = mw;
        boolean anyBefore = model != null;
        for (int i = 0; i < muted.size(); i++) {
            partW[i] = gc.textExtent(muted.get(i)).x;
            if (anyBefore) width += dw;
            width += partW[i];
            anyBefore = true;
        }

        Seg seg = new Seg(width);
        seg.painter = (g, x, midY) -> {
            int cx = x;
            boolean drawn = false;
            if (model != null) {
                drawText(g, model, cx, midY, fgColor);
                cx += mw;
                drawn = true;
            }
            for (int i = 0; i < muted.size(); i++) {
                if (drawn) {
                    drawText(g, dot, cx, midY, mutedColor);
                    cx += dw;
                }
                drawText(g, muted.get(i), cx, midY, mutedColor);
                cx += partW[i];
                drawn = true;
            }
        };
        seg.tooltip = buildModelTooltip(model, s);
        return seg;
    }

    /**
     * Model tooltip. Effort and thinking are always shown — independent of their bar-visibility
     * preferences — with thinking rendered as {@code enabled}/{@code disabled} and effort falling
     * back to {@code default} when the status doesn't report a level.
     */
    private static String buildModelTooltip(String model, ClaudeStatus s) {
        StringBuilder sb = new StringBuilder();
        if (model != null) sb.append("Model: ").append(model).append('\n');
        sb.append("Effort level: ").append(s.effortLevel().orElse("default"));
        sb.append('\n').append("Thinking: ")
                .append(s.thinkingEnabled().orElse(false) ? "enabled" : "disabled");
        return sb.toString();
    }

    /** Accumulated session cost: {@code Cost $1.23} (long) / {@code $1.23} (short). */
    private Seg buildCostSeg(GC gc, double cost, boolean compact) {
        String value = String.format(Locale.ROOT, "$%.2f", cost);
        String label = compact ? null : "Cost";
        int lw = label != null ? gc.textExtent(label).x + GAP : 0;
        int vw = gc.textExtent(value).x;

        Seg seg = new Seg(lw + vw);
        seg.painter = (g, x, midY) -> {
            int cx = x;
            if (label != null) {
                drawText(g, label, cx, midY, fgColor);
                cx += lw;
            }
            drawText(g, value, cx, midY, fgColor);
        };
        seg.tooltip = String.format(Locale.ROOT, "Session cost: $%.4f", cost);
        return seg;
    }

    /** A usage meter: {@code Context ▮▮░ 24% resets in 2h 23m} (long) / {@code C ▮ 24 2h 23m} (short). */
    private Seg buildMeter(GC gc, String label, double pct, boolean isContext,
                           OptionalLong resetsAt, boolean compact, boolean withResets,
                           boolean reserveResets, String tooltip) {
        int barW = compact ? BAR_W_SHORT : BAR_W_LONG;
        int rounded = (int) Math.floor(Math.max(0.0, pct)); // round down for display
        String pctText = compact ? Integer.toString(rounded) : rounded + "%";

        String resetText = null;
        if (withResets && resetsAt.isPresent()) {
            String rem = formatRemaining(resetsAt.getAsLong());
            if (rem != null) resetText = compact ? "(" + rem + ")" : "(resets in " + rem + ")";
        }

        Color color = meterColor(pct, isContext);
        int lw = gc.textExtent(label).x;
        int pw = gc.textExtent(pctText).x;
        // Width reservation for the shared breakpoint: when this meter carries no reset
        // text but the caller is measuring the common case, charge it for one anyway so
        // both views break at the same width. Never drawn — only `resetText` is painted.
        String measured = resetText;
        if (measured == null && withResets && reserveResets) {
            measured = compact ? RESET_SAMPLE_SHORT : RESET_SAMPLE_LONG;
        }
        int rw = measured != null ? gc.textExtent(measured).x : 0;
        int width = lw + GAP + barW + GAP + pw + (measured != null ? GAP + rw : 0);

        final String fReset = resetText;
        Seg seg = new Seg(width);
        seg.painter = (g, x, midY) -> {
            int cx = x;
            drawText(g, label, cx, midY, fgColor);
            cx += lw + GAP;
            drawBar(g, cx, midY, barW, pct, color);
            cx += barW + GAP;
            drawText(g, pctText, cx, midY, color);
            cx += pw + GAP;
            if (fReset != null) drawText(g, fReset, cx, midY, mutedColor);
        };
        seg.tooltip = tooltip;
        return seg;
    }

    /**
     * Context: green &lt; 70%, amber 70–85%, red ≥ 85%.
     * Limits (session/weekly): blue &lt; 80%, amber 80–90%, red ≥ 90%.
     */
    private Color meterColor(double pct, boolean isContext) {
        if (isContext) {
            if (pct >= 85.0) return redColor;
            if (pct >= 70.0) return yellowColor;
            return ctxColor;
        } else {
            if (pct >= 90.0) return redColor;
            if (pct >= 80.0) return yellowColor;
            return limitColor;
        }
    }

    // ── Primitives ──────────────────────────────────────────────────────────

    private static void drawText(GC gc, String text, int x, int midY, Color color) {
        Point e = gc.textExtent(text);
        gc.setForeground(color);
        gc.drawText(text, x, midY - e.y / 2, true);
    }

    private void drawBar(GC gc, int x, int midY, int w, double pct, Color color) {
        // drawText box-centres the text on midY, but the glyph ink sits below that
        // geometric centre (drawText leaves more empty leading above the cap line than
        // below the baseline), so a bar centred on midY reads as too high. Nudge it
        // down by half the descent to line up with the digits/labels beside it.
        int barCenterY = midY + gc.getFontMetrics().getDescent() / 2;
        int y = barCenterY - BAR_H / 2;
        gc.setBackground(trackColor);
        gc.fillRoundRectangle(x, y, w, BAR_H, BAR_H, BAR_H);
        int fw = (int) Math.round(w * Math.max(0.0, Math.min(100.0, pct)) / 100.0);
        if (fw > 0) {
            if (fw < BAR_H) fw = BAR_H; // keep the rounded cap legible at tiny percentages
            gc.setBackground(color);
            gc.fillRoundRectangle(x, y, fw, BAR_H, BAR_H, BAR_H);
        }
    }

    /** Time until {@code resetsAtEpochSec} as {@code "Xd Yh"} / {@code "Xh Ym"}; {@code "now"} once elapsed. */
    private static String formatRemaining(long resetsAtEpochSec) {
        long diff = resetsAtEpochSec - (System.currentTimeMillis() / 1000L);
        if (diff <= 0) return "now";
        long days = diff / 86400;
        long hours = (diff % 86400) / 3600;
        long minutes = (diff % 3600) / 60;
        if (days > 0) return String.format(Locale.ROOT, "%dd %dh", days, hours);
        return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
    }

    private static final class SegLayout {
        final List<Seg> left;
        final List<Seg> right;
        SegLayout(List<Seg> left, List<Seg> right) { this.left = left; this.right = right; }
    }

    /** One rendered group: its measured width, a closure that paints it, and its hover tooltip. */
    private static final class Seg {
        final int width;
        Painter painter;
        String tooltip;

        Seg(int width) { this.width = width; }
    }

    private interface Painter {
        void paint(GC gc, int x, int midY);
    }

    /** A horizontal hit region [{@code x0}, {@code x1}) carrying the tooltip for its segment. */
    private record HoverRegion(int x0, int x1, String tooltip) {}
}
