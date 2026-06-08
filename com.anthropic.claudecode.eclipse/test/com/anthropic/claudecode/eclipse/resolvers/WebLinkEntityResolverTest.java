package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link WebLinkEntityResolver}. Lives in the production package so it can call the
 * package-private {@link WebLinkEntityResolver#refine(String, boolean)} helper, which exposes the
 * refined URL that {@link WebLinkEntityResolver#resolve} otherwise hides behind an opaque
 * {@link IEntityResolver.IResolvedEntity}.
 */
class WebLinkEntityResolverTest {

	private final WebLinkEntityResolver resolver = new WebLinkEntityResolver();

	@Test
	void nullAndBlankNeverMatch() {
		for (String input : new String[] { null, "", "   ", "\t" }) {
			assertNull(resolver.refine(input, true), () -> "blank/null must not match (strip)");
			assertNull(resolver.refine(input, false), () -> "blank/null must not match (strict)");
		}
		assertNull(resolver.resolve(null, true), "resolve must return null for null");
	}

	// ---- resolve(): URL construction / validation ---------------------------------------------

	@Test
	void resolveReturnsEntityForValidUrl() {
		assertNotNull(resolver.resolve("https://anthropic.com", false),
				"a valid URL must resolve to an openable entity");
	}

	@Test
	void resolveReturnsNullWhenRefinedTextIsNotAConstructibleUrl() {
		// refine() accepts this (valid scheme, no whitespace) ...
		assertNotNull(resolver.refine("https://x.com/a^b", false));
		// ... but '^' is illegal in a URI, so resolve() rejects it instead of returning a dead entity.
		assertNull(resolver.resolve("https://x.com/a^b", false),
				"a refined string that is not a constructible URL must not resolve");
	}

	// ---- allowStripEdges = true ---------------------------------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
	@MethodSource("urls")
	void stripModeDetectsAndRefinesUrl(String input, String expected) {
		assertEquals(expected, resolver.refine(input, true), () -> "expected a URL match for: " + input);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\" -> no match")
	@MethodSource("nonUrls")
	void stripModeRejectsNonUrl(String input) {
		assertNull(resolver.refine(input, true), () -> "expected no URL match for: " + input);
	}

	static Stream<Arguments> urls() {
		return Stream.of(
				// plain, both supported schemes
				arguments("https://anthropic.com", "https://anthropic.com"),
				arguments("http://example.com", "http://example.com"),
				arguments("http://localhost:8080/path", "http://localhost:8080/path"), // no-dot host
				arguments("https://a", "https://a"), // minimal: >=1 char after "://"
				// surrounding prose with spaces — URL bounded by whitespace
				arguments("see this https://x.com here", "https://x.com"),
				arguments("https://x.com here", "https://x.com"),
				// wrapping brackets / quotes stripped
				arguments("(https://x.com)", "https://x.com"),
				arguments("(https://x.com).", "https://x.com"),
				arguments("[https://x.com]", "https://x.com"),
				arguments("{https://x.com}", "https://x.com"),
				arguments("<https://x.com>", "https://x.com"),
				arguments("\"https://x.com\"", "https://x.com"),
				arguments("'https://x.com'", "https://x.com"),
				arguments("`https://x.com`", "https://x.com"),
				arguments("[Anthropic](https://anthropic.com)", "https://anthropic.com"),
				// Unbalanced brackets / quotes stripped
				arguments("https://x.com)", "https://x.com"),
				arguments("https://x.com])}", "https://x.com"),
				arguments("\"'https://x.com", "https://x.com"),
				arguments("https://x.com)aaa", "https://x.com)aaa"),
				// Unbalanced openers
				arguments("https://x.com(", "https://x.com("),
				arguments("https://x.com[(", "https://x.com[("),
				arguments("https://x.com[aaa", "https://x.com[aaa"),
				// trailing sentence punctuation stripped
				arguments("https://x.com.", "https://x.com"),
				arguments("https://x.com,", "https://x.com"),
				arguments("https://x.com!", "https://x.com"),
				arguments("https://x.com?", "https://x.com"),
				arguments("https://x.com;", "https://x.com"),
				arguments("https://x.com:", "https://x.com"),
				// balanced brackets inside the URL are preserved
				arguments("https://en.wikipedia.org/wiki/Foo_(bar)", "https://en.wikipedia.org/wiki/Foo_(bar)"),
				arguments("(https://en.wikipedia.org/wiki/Foo_(bar))", "https://en.wikipedia.org/wiki/Foo_(bar)"),
				arguments("https://x.com/path[0]", "https://x.com/path[0]"),
				// query / fragment / trailing slash preserved
				arguments("https://x.com/p?q=1&r=2#frag", "https://x.com/p?q=1&r=2#frag"),
				arguments("https://x.com/path/", "https://x.com/path/"),
				// scheme case-insensitive, original case preserved
				arguments("HTTP://X.COM", "HTTP://X.COM"),
				arguments("HtTpS://Example.COM/Path", "HtTpS://Example.COM/Path"),
				// arbitrary leading prefix dropped (earliest scheme wins)
				arguments("see:https://x.com", "https://x.com"),
				arguments("xhttp://y.com", "http://y.com"),
				arguments("https://a.com/redirect?u=http://b.com", "https://a.com/redirect?u=http://b.com"),
				// combinations
				arguments("|https://x.com|", "https://x.com"),
				arguments("https://x.com^", "https://x.com"),
				arguments("((https://x.com)).", "https://x.com"));
	}

	static Stream<String> nonUrls() {
		return Stream.of(
				"notaurl",
				"httpsomething",
				"www.example.com", // no www / bare-domain support
				"ftp://x.com", // unsupported scheme
				"mailto:user@example.com", // unsupported scheme
				"ws://host", // unsupported scheme
				"file://", // unsupported scheme (handled by FileEntityResolver)
				"file:///home/user/a.txt", // unsupported scheme (handled by FileEntityResolver)
				"https:/x.com", // single slash, no "://"
				"https:x", // no "://"
				"http://", // bare scheme
				"https://", // bare scheme
				"https://...", // scheme then only punctuation -> trimmed to bare scheme
				"(https://).", // ditto, wrapped
				"https://)", // ditto
				"someIdentifier", // Java identifier
				"std::string", // C++ identifier
				"/just/file/name", // file name
				"" // empty string
		);
	}

	// ---- allowStripEdges = false (strict, nothing trimmed) ------------------------------------

	@ParameterizedTest(name = "[{index}] strict \"{0}\" -> unchanged")
	@ValueSource(strings = {
			"https://anthropic.com",
			"http://localhost:8080/path",
			"HTTP://X.COM", // case preserved
			"https://x.com.", // nothing trimmed — trailing dot kept
			"https://x.com)", // nothing trimmed — bracket kept
	})
	void strictModeReturnsExactUrlUnchanged(String input) {
		assertEquals(input, resolver.refine(input, false), () -> "strict mode must return text unchanged: " + input);
	}

	@ParameterizedTest(name = "[{index}] strict \"{0}\" -> no match")
	@ValueSource(strings = {
			"(https://x.com)", // scheme not at index 0
			"see:https://x.com", // scheme not at index 0
			" https://x.com", // leading whitespace
			"https://x.com here", // internal whitespace
			"http://", // bare scheme
			"https://", // bare scheme
			"ftp://x.com", // unsupported scheme
			"file:///home/user/a.txt", // unsupported scheme
			"notaurl",
	})
	void strictModeRejectsNonExactUrl(String input) {
		assertNull(resolver.refine(input, false), () -> "strict mode must reject: " + input);
	}
}
