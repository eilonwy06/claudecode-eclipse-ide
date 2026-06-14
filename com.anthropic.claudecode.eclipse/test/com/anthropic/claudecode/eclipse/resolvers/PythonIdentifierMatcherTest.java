package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.anthropic.claudecode.eclipse.resolvers.PythonIdentifierEntityResolver.PythonIdentifierMatcher;
import com.anthropic.claudecode.eclipse.resolvers.PythonIdentifierEntityResolver.PythonReference;

/**
 * Tests for {@link PythonIdentifierMatcher#classify(String, boolean)} — the recognition step. Lives in the
 * production package to reach the package-private {@code classify} and {@link PythonReference}. Recognition
 * is a self-contained regex with no PyDev/Eclipse runtime dependency, so these run as plain JUnit tests.
 */
class PythonIdentifierMatcherTest {

	private static PythonReference ref(String qualifiedName, String name) {
		return new PythonReference(qualifiedName, name);
	}

	// ---- clean tokens: match identically in strict and strip mode ------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("cleanMatches")
	void cleanTokenMatchesInBothModes(String input, PythonReference expected) {
		assertEquals(expected, PythonIdentifierMatcher.classify(input, false), () -> "strict: " + input);
		assertEquals(expected, PythonIdentifierMatcher.classify(input, true), () -> "strip: " + input);
	}

	static Stream<Arguments> cleanMatches() {
		return Stream.of(
				// simple identifiers
				arguments("Foo", ref("Foo", "Foo")),
				arguments("my_func", ref("my_func", "my_func")),
				arguments("_private", ref("_private", "_private")),
				// dotted identifiers — searched by the final segment
				arguments("os.path.join", ref("os.path.join", "join")),
				arguments("module.Class", ref("module.Class", "Class")),
				// a trailing call is dropped (PyDev is searched by name only)
				arguments("join(a, b)", ref("join", "join")),
				arguments("os.path.join(a, b)", ref("os.path.join", "join")),
				arguments("foo()", ref("foo", "foo")));
	}

	// ---- junk-wrapped tokens: match only with edge stripping ------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("wrappedMatches")
	void wrappedTokenMatchesOnlyWhenStripping(String input, PythonReference expected) {
		assertEquals(expected, PythonIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(PythonIdentifierMatcher.classify(input, false), () -> "strict must reject wrapped: " + input);
	}

	static Stream<Arguments> wrappedMatches() {
		return Stream.of(
				arguments("(join)", ref("join", "join")),
				arguments("\"Foo\"", ref("Foo", "Foo")),
				arguments("`os.path.join`", ref("os.path.join", "join")),
				// a leading decorator marker / unpacking star is trimmed
				arguments("@app.route", ref("app.route", "route")),
				arguments("*args", ref("args", "args")),
				// outer wrapping parens stripped while the call's own (balanced) parens survive
				arguments("(join(a, b))", ref("join", "join")),
				arguments("os.path.join,", ref("os.path.join", "join")));
	}

	// ---- never a Python identifier, in either mode --------------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = {
			"123abc", // identifiers can't start with a digit
			"a..b", // empty segment
			"os..path", // empty middle segment
			"std::string", // C++ scope operator
			"a-b", // hyphen is not an identifier char
			"http://x.com", // URL
			"https://x.com",
			"see foo here", // a sentence, not a single token
			"...",
			"   ",
			"" })
	void nonIdentifiersNeverMatch(String input) {
		assertNull(PythonIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(PythonIdentifierMatcher.classify(input, false), () -> "strict: " + input);
	}

	@Test
	void nullNeverMatches() {
		assertNull(PythonIdentifierMatcher.classify(null, true));
		assertNull(PythonIdentifierMatcher.classify(null, false));
	}

	// ---- strict mode keeps every character ----------------------------------------------------

	@ParameterizedTest(name = "[{index}] strict \"{0}\" -> no match")
	@ValueSource(strings = {
			" Foo", // leading whitespace
			"Foo ", // trailing whitespace
			"(Foo)", // wrapping parens
			"@Foo", // leading decorator marker
			"`Foo`", // wrapping backticks
			"Foo." }) // trailing dot
	void strictModeRejectsAnythingButTheExactReference(String input) {
		assertNull(PythonIdentifierMatcher.classify(input, false), () -> "strict must reject: " + input);
	}
}
