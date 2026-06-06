package com.anthropic.claudecode.eclipse.resolvers;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWTError;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.statushandlers.StatusManager;

import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

/**
 * Recognizes a URL inside a chunk of text taken from a Claude Code answer.
 *
 * <p>Only the {@code http://} and {@code https://} schemes are supported ({@code file://} links are
 * the concern of {@link FileEntityResolver}). The URL itself never contains whitespace, so it is
 * bounded by the next whitespace character; any surrounding prose is ignored.
 * 
 * <p>Intentionally don't support "www."-like links, as they conflict with other resolvers (e.g. {@code www.x.com}
 * can be a file or a java identifier or a link). Moreover, Claude Code won't probably emit them without 
 * {@code http://}.
 *
 * <p>Behavior depends on {@code allowStripEdges}:
 * <ul>
 *   <li><b>{@code true}</b> — the text may carry junk around the URL (brackets, quotes, punctuation,
 *       leading/trailing prose). The scheme is located at its earliest (case-insensitive)
 *       occurrence, everything before it is dropped, the URL is cut at the next whitespace, and
 *       trailing punctuation and unbalanced closing brackets are trimmed.</li>
 *   <li><b>{@code false}</b> — nothing is stripped. A match is returned only if the text is
 *       <em>exactly</em> a URL: it begins with the scheme, has content after it, and contains no
 *       whitespace; otherwise {@code null}.</li>
 * </ul>
 * The refined URL always preserves its original case.
 *
 * <pre>
 *   (https://x.com).                            -&gt; https://x.com                            (strip)
 *   [Anthropic](https://anthropic.com)          -&gt; https://anthropic.com                    (strip)
 *   see this https://x.com here                 -&gt; https://x.com                            (strip)
 *   https://en.wikipedia.org/wiki/Foo_(bar)     -&gt; https://en.wikipedia.org/wiki/Foo_(bar)  (unchanged)
 *   ftp://x.com / notaurl / https://            -&gt; no match
 *   (https://x.com)  with allowStripEdges=false -&gt; no match
 * </pre>
 */
public class WebLinkEntityResolver implements IEntityResolver {

	private static final String[] SCHEMES = { "http://", "https://" };

	/** Trailing characters always stripped from a candidate URL. */
	private static final String TRAILING_PUNCTUATION = ".,;:!?\"'`^|%\\{}[<> \u00A0\t\n\r";

	/** Closing brackets paired by index with their {@link #OPENERS}; stripped only when unbalanced. */
	private static final String CLOSERS = ")]";
	private static final String OPENERS = "([";

	@Override
	public String getName() {
		return "Web Link";
	}

	@Override
	public IResolvedEntity resolve(String text, boolean allowStripEdges) {
		String refined = refine(text, allowStripEdges);
		if (refined == null) {
			return null;
		}
		URL url;
		try {
			url = URI.create(refined).toURL();
		} catch (MalformedURLException | IllegalArgumentException e) {
			return null; // refined text isn't a usable URL — treat as no match
		}
		return () -> locate(url);
	}

	/**
	 * Extracts the refined URL from {@code text}, or {@code null} if it contains no supported URL.
	 * Package-private so tests can observe the result (the {@link IResolvedEntity} returned by
	 * {@link #resolve} only exposes {@link IResolvedEntity#locate()}).
	 */
	String refine(String text, boolean allowStripEdges) {
		if (text == null || text.isBlank()) {
			return null;
		}

		String lower = text.toLowerCase();
		int start = findSchemeStart(lower);
		if (start < 0) {
			return null;
		}

		// Without edge-stripping the URL must BE the text: scheme at the very start, no leading junk.
		if (!allowStripEdges && start != 0) {
			return null;
		}

		int schemeLen = schemeLengthAt(lower, start);
		String candidate = text.substring(start);

		// A URL never contains whitespace, so it ends at the first whitespace (the text may carry
		// trailing prose). When stripping is disallowed any whitespace means it is not an exact URL.
		int ws = indexOfWhitespace(candidate);
		if (ws >= 0) {
			if (!allowStripEdges) {
				return null;
			}
			candidate = candidate.substring(0, ws);
		}

		if (allowStripEdges) {
			candidate = stripTrailingJunk(candidate);
		}

		// Require something after "scheme://" — reject a bare scheme.
		return candidate.length() > schemeLen ? candidate : null;
	}

	private void locate(URL url) {
		UiHelper.asyncExec(() -> {
			try {
				PlatformUI.getWorkbench().getBrowserSupport().createBrowser(null).openURL(url);
			} catch (Exception | SWTError e) {
				// A failed browser launch (misconfigured external browser, or no native browser backend
				// for the internal one — the latter surfaces as an SWTError, not an Exception) is a dead
				// end for a user-initiated click, so both log it and show it.
				IStatus status = new Status(IStatus.ERROR, Constants.PLUGIN_ID, "Could not open web link: " + url, e);
				StatusManager.getManager().handle(status, StatusManager.SHOW | StatusManager.LOG);
			}
		});
	}

	/** Earliest (case-insensitive) index at which any supported scheme begins, or -1 if none. */
	private static int findSchemeStart(String lower) {
		int best = -1;
		for (String scheme : SCHEMES) {
			int idx = lower.indexOf(scheme);
			if (idx >= 0 && (best < 0 || idx < best)) {
				best = idx;
			}
		}
		return best;
	}

	/** Length of the supported scheme located at {@code idx} in {@code lower}. */
	private static int schemeLengthAt(String lower, int idx) {
		for (String scheme : SCHEMES) {
			if (lower.startsWith(scheme, idx)) {
				return scheme.length();
			}
		}
		return 0;
	}

	/**
	 * Drops trailing punctuation/quotes, and closing brackets that have no matching opener inside the
	 * candidate — so a balanced {@code (bar)} is preserved while a wrapping {@code )} is removed.
	 */
	private static String stripTrailingJunk(String s) {
		int end = s.length();
		while (end > 0) {
			char c = s.charAt(end - 1);
			if (TRAILING_PUNCTUATION.indexOf(c) >= 0) {
				end--;
				continue;
			}
			int closerIdx = CLOSERS.indexOf(c);
			if (closerIdx >= 0 && count(s, OPENERS.charAt(closerIdx), end) < count(s, c, end)) {
				end--;
				continue;
			}
			break;
		}
		return s.substring(0, end);
	}

	/** Number of occurrences of {@code c} within {@code s[0, end)}. */
	private static int count(String s, char c, int end) {
		int n = 0;
		for (int i = 0; i < end; i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	private static int indexOfWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}
}
