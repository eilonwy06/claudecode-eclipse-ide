package com.anthropic.claudecode.eclipse.resolvers;

/**
 * Language-independent helpers shared by the per-language identifier matchers
 * ({@code JavaIdentifierEntityResolver.JavaIdentifierMatcher},
 * {@code PythonIdentifierEntityResolver.PythonIdentifierMatcher},
 * {@code CppIdentifierEntityResolver.CppIdentifierMatcher}). Recognizing and trimming a token taken
 * from a Claude Code answer is language-independent, so the trimming algorithm and the argument-list
 * arity count live here once. This class holds only algorithms — the language-specific character sets
 * (which leading/trailing characters count as junk, which brackets pair up) stay declared in each
 * matcher and are passed in.
 */
final class IdentifierUtils {

	private IdentifierUtils() {}

	/**
	 * Trims wrapping junk: leading characters in {@code leadingJunk}, then trailing characters in
	 * {@code trailingPlain} plus closing brackets that have no matching opener in the remaining span
	 * (so a balanced {@code (x)} survives while a wrapping {@code )} is removed). {@code openers} and
	 * {@code closers} pair an opening bracket with its closer by index. All four character sets are
	 * language-specific, so the caller passes them in.
	 */
	static String stripEdges(String s, String leadingJunk, String trailingPlain, String openers, String closers) {
		int start = 0;
		int end = s.length();
		while (start < end && leadingJunk.indexOf(s.charAt(start)) >= 0) {
			start++;
		}
		while (end > start) {
			char c = s.charAt(end - 1);
			if (trailingPlain.indexOf(c) >= 0) {
				end--;
				continue;
			}
			int closerIdx = closers.indexOf(c);
			if (closerIdx >= 0 && count(s, openers.charAt(closerIdx), start, end) < count(s, c, start, end)) {
				end--;
				continue;
			}
			break;
		}
		return s.substring(start, end);
	}

	/**
	 * Number of top-level parameters in an argument list (the text inside the outer parentheses):
	 * {@code 0} when empty or the bare ellipsis placeholder {@code ...} (so {@code method(...)} resolves
	 * exactly like {@code method()}), otherwise one more than the count of commas not nested inside
	 * {@code () <> []} — so generics/templates ({@code Map<K,V>}, {@code vector<int>}) and arrays don't
	 * inflate the arity.
	 */
	static int countParams(String args) {
		String inner = args.trim();
		if (inner.isEmpty() || inner.equals("...")) {
			return 0;
		}
		int depth = 0;
		int count = 1;
		for (int i = 0; i < inner.length(); i++) {
			switch (inner.charAt(i)) {
				case '(', '<', '[' -> depth++;
				case ')', '>', ']' -> depth--;
				case ',' -> {
					if (depth == 0) {
						count++;
					}
				}
				default -> { /* not a delimiter */ }
			}
		}
		return count;
	}

	/** Number of occurrences of {@code c} within {@code s[from, to)}. */
	private static int count(String s, char c, int from, int to) {
		int n = 0;
		for (int i = from; i < to; i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}
}
