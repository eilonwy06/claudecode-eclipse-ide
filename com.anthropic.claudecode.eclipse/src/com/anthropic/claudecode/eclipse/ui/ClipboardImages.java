package com.anthropic.claudecode.eclipse.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the images out of rich content on the clipboard.
 *
 * <p>When you copy a chunk of a web page or a document, the images usually aren't on the
 * clipboard as bitmaps at all — only the HTML fragment is, with {@code <img src="…">} tags
 * pointing at wherever the images actually live. This turns that fragment into a list of
 * data URLs the composer can attach, downloading the remote ones.
 *
 * <p>Everything here is deliberately conservative, because a page fragment can reference
 * dozens of spacers, icons and tracking pixels that nobody wants as attachments: only
 * {@code http(s)} and {@code data:} sources, only formats the API accepts, nothing smaller
 * than {@link #MIN_DIMENSION} px, and never more than {@link #MAX_IMAGES} per paste.
 */
final class ClipboardImages {

    /** Most images one paste will ever attach — a page fragment can reference dozens. */
    static final int MAX_IMAGES = 8;
    /** Below this on either axis it's a spacer, an icon or a tracking pixel, not content. */
    static final int MIN_DIMENSION = 32;
    static final int FETCH_TIMEOUT_MS = 5000;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile(
            "(?is)\\b(src|width|height|style)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\">]+))");
    private static final Pattern STYLE_SIZE = Pattern.compile(
            "(?is)\\b(width|height)\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*px");
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    private ClipboardImages() {}

    /**
     * The fragment's visible text, tags removed. Only ever asked whether it's blank: a
     * fragment with no text is an image the user copied on its own, which the platform also
     * puts on the clipboard as a bitmap — that's the better source, so we leave it alone.
     */
    static String visibleText(String html) {
        if (html == null) return "";
        String s = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&#160;", " ");
        return s.replace('\u00A0', ' ').trim();
    }

    /**
     * Whether the clipboard's HTML is <em>content</em> — images together with words — as
     * opposed to a single image the user copied on its own.
     *
     * <p>This is the whole basis for deciding what a paste does, so it is deliberately
     * narrow. Rich content pastes its text and its images. Everything else is an image
     * copied alone, which the platform also puts on the clipboard as a bitmap: that bitmap
     * is the better source (it's already local, no download), and its text alternative is
     * a URL or a file name that must not end up in the composer.
     */
    static boolean isRichContent(String html) {
        return !imageSources(html).isEmpty() && !visibleText(html).isEmpty();
    }

    /**
     * Usable image sources from an HTML fragment, in document order, deduplicated and
     * capped. Skips anything that can't or shouldn't be attached:
     * <ul>
     * <li>relative URLs — SWT's HTMLTransfer hands back only the fragment, dropping the
     *     CF_HTML header's SourceURL, so there's nothing to resolve them against. Browsers
     *     write absolute URLs into clipboard HTML, so this costs almost nothing in practice.
     * <li>{@code file:} — deliberately excluded, not overlooked. Clipboard HTML can come
     *     from anywhere, and following a local path in it would read a file off this machine
     *     into the conversation. Copying image files in the file manager still works; that
     *     goes through FileTransfer, where the user picked the files themselves.
     * <li>SVG, which the API doesn't accept, and images the markup itself says are tiny.
     * </ul>
     */
    static List<String> imageSources(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher tags = IMG_TAG.matcher(html);
        while (tags.find() && out.size() < MAX_IMAGES) {
            String tag = tags.group();
            String src = null, style = null;
            boolean tiny = false;
            Matcher a = ATTR.matcher(tag);
            while (a.find()) {
                String name = a.group(1).toLowerCase(Locale.ROOT);
                String value = a.group(2) != null ? a.group(2)
                             : a.group(3) != null ? a.group(3)
                             : a.group(4) != null ? a.group(4) : "";
                switch (name) {
                    case "src"   -> src = value.trim();
                    case "style" -> style = value;
                    case "width", "height" -> tiny |= isTiny(value);
                }
            }
            if (style != null) {
                Matcher sm = STYLE_SIZE.matcher(style);
                while (sm.find()) tiny |= isTiny(sm.group(2));
            }
            if (tiny) continue;
            String url = normalizeSource(src);
            if (!url.isEmpty() && seen.add(url)) out.add(url);
        }
        return out;
    }

    /** A dimension the markup declares, small enough to mean "not content". */
    private static boolean isTiny(String value) {
        try {
            return Double.parseDouble(value.trim().replace("px", "")) < MIN_DIMENSION;
        } catch (RuntimeException e) {
            return false;   // percentages, "auto", junk — no opinion
        }
    }

    /** "" for anything we won't follow. */
    private static String normalizeSource(String src) {
        if (src == null || src.isEmpty()) return "";
        String s = src.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:image/")) {
            return lower.startsWith("data:image/svg") ? "" : s;
        }
        if (s.startsWith("//")) { s = "https:" + s; lower = s.toLowerCase(Locale.ROOT); }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        String path = lower.split("[?#]", 2)[0];
        return path.endsWith(".svg") ? "" : s;
    }

    /** Whether a source can be turned into an attachment without going to the network. */
    static boolean isLocal(String src) {
        return src != null && src.toLowerCase(Locale.ROOT).startsWith("data:");
    }

    /**
     * A source as a data URL the composer can attach, or "" if it can't be used.
     * {@code data:} sources are validated and returned as-is; {@code http(s)} ones are
     * downloaded — so only call this off the UI thread for those.
     */
    static String toDataUrl(String src) {
        if (src == null || src.isEmpty()) return "";
        if (isLocal(src)) return validDataUrl(src) ? src : "";
        return download(src);
    }

    private static boolean validDataUrl(String src) {
        int comma = src.indexOf(',');
        if (comma < 0) return false;
        String meta = src.substring(0, comma).toLowerCase(Locale.ROOT);
        return meta.contains(";base64") && !mediaTypeOf(meta.substring("data:".length())).isEmpty();
    }

    private static String download(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(FETCH_TIMEOUT_MS);
            conn.setReadTimeout(FETCH_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "image/*");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return "";
            String mime = mediaTypeOf(conn.getContentType());
            if (mime.isEmpty()) mime = mediaTypeOf(url.split("[?#]", 2)[0]);
            if (mime.isEmpty()) return "";
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = readCapped(in);
            }
            if (bytes.length == 0 || !isContentSized(bytes)) return "";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readCapped(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_IMAGE_BYTES) return new byte[0];
        }
        return out.toByteArray();
    }

    /**
     * Whether the bytes are a real image rather than a spacer or tracking pixel. Decoding is
     * the reliable test, but SWT can't read every format the API accepts (WebP), so a format
     * it declines falls back to a size floor — a tracking pixel is a hundred-odd bytes and
     * anything genuinely {@value #MIN_DIMENSION}px square is far larger.
     */
    private static boolean isContentSized(byte[] bytes) {
        try {
            org.eclipse.swt.graphics.ImageData[] data =
                    new org.eclipse.swt.graphics.ImageLoader().load(new ByteArrayInputStream(bytes));
            if (data.length == 0) return false;
            return data[0].width >= MIN_DIMENSION && data[0].height >= MIN_DIMENSION;
        } catch (Throwable undecodable) {
            return bytes.length >= 1024;
        }
    }

    /** The API-accepted media type named by a content-type header or a file name, else "". */
    static String mediaTypeOf(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("image/png")  || t.endsWith(".png"))  return "image/png";
        if (t.contains("image/jpeg") || t.contains("image/jpg")
                || t.endsWith(".jpg") || t.endsWith(".jpeg")) return "image/jpeg";
        if (t.contains("image/gif")  || t.endsWith(".gif"))  return "image/gif";
        if (t.contains("image/webp") || t.endsWith(".webp")) return "image/webp";
        return "";
    }
}
