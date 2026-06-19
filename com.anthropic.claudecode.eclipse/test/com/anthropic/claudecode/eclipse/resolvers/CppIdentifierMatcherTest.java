package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.anthropic.claudecode.eclipse.resolvers.CppIdentifierEntityResolver.CppIdentifierMatcher;
import com.anthropic.claudecode.eclipse.resolvers.CppIdentifierEntityResolver.CppReference;

/**
 * Tests for {@link CppIdentifierMatcher#classify(String, boolean)} — the recognition step. Lives in the
 * production package to reach the package-private {@code classify} and {@link CppReference}. Recognition is
 * a self-contained regex with no CDT/Eclipse runtime dependency, so these run as plain JUnit tests.
 */
class CppIdentifierMatcherTest {

	private static CppReference ref(int paramCount, String... segments) {
		return new CppReference(List.of(segments), paramCount, false);
	}

	private static CppReference refGlobal(int paramCount, String... segments) {
		return new CppReference(List.of(segments), paramCount, true);
	}

	// ---- clean tokens: match identically in strict and strip mode ------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("cleanMatches")
	void cleanTokenMatchesInBothModes(String input, CppReference expected) {
		assertEquals(expected, CppIdentifierMatcher.classify(input, false), () -> "strict: " + input);
		assertEquals(expected, CppIdentifierMatcher.classify(input, true), () -> "strip: " + input);
	}

	static Stream<Arguments> cleanMatches() {
		return Stream.of(
				// simple identifiers
				arguments("Foo", ref(-1, "Foo")),
				arguments("my_func", ref(-1, "my_func")),
				arguments("_private", ref(-1, "_private")),
				// :: qualified identifiers — searched by the final segment
				arguments("ns::Foo::bar", ref(-1, "ns", "Foo", "bar")),
				arguments("std::string", ref(-1, "std", "string")),
				// a trailing call is dropped, keeping the arity
				arguments("freeFunc()", ref(0, "freeFunc")),
				arguments("foo(int)", ref(1, "foo")),
				arguments("ns::Foo::bar(int, char*)", ref(2, "ns", "Foo", "bar")),
				// leading :: marks global scope — preserved as globalScope=true, not discarded
				arguments("::Foo", refGlobal(-1, "Foo")),
				arguments("::ns::Foo", refGlobal(-1, "ns", "Foo")),
				arguments("::freeFunc()", refGlobal(0, "freeFunc")));
	}

	// ---- junk-wrapped tokens: match only with edge stripping ------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("wrappedMatches")
	void wrappedTokenMatchesOnlyWhenStripping(String input, CppReference expected) {
		assertEquals(expected, CppIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(CppIdentifierMatcher.classify(input, false), () -> "strict must reject wrapped: " + input);
	}

	static Stream<Arguments> wrappedMatches() {
		return Stream.of(
				arguments("(Foo)", ref(-1, "Foo")),
				arguments("\"Foo\"", ref(-1, "Foo")),
				arguments("`ns::Foo::bar`", ref(-1, "ns", "Foo", "bar")),
				// a leading reference/address-of or pointer/deref marker is trimmed
				arguments("&foo", ref(-1, "foo")),
				arguments("*ptr", ref(-1, "ptr")),
				// outer wrapping parens stripped while the call's own (balanced) parens survive
				arguments("(freeFunc())", ref(0, "freeFunc")),
				arguments("ns::Foo::bar,", ref(-1, "ns", "Foo", "bar")),
				// a trailing pointer/reference suffix is trimmed
				arguments("Foo*", ref(-1, "Foo")),
				arguments("Foo&", ref(-1, "Foo")));
	}

	// ---- never a C/C++ identifier, in either mode ---------------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = {
			"123abc", // identifiers can't start with a digit
			"a-b", // hyphen is not an identifier char
			"a.b", // a dot is member access, not the :: scope operator
			"file.cpp", // a file name, not an identifier
			"class Foo", // a leading keyword is NOT stripped — a space-bearing token is rejected
			"a::::b", // empty middle segment
			"http://x.com", // URL
			"https://x.com",
			"see foo here", // a sentence, not a single token
			"::",
			"   ",
			"" })
	void nonIdentifiersNeverMatch(String input) {
		assertNull(CppIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(CppIdentifierMatcher.classify(input, false), () -> "strict: " + input);
	}

	@Test
	void nullNeverMatches() {
		assertNull(CppIdentifierMatcher.classify(null, true));
		assertNull(CppIdentifierMatcher.classify(null, false));
	}

	// ---- strict mode keeps every character ----------------------------------------------------

	@ParameterizedTest(name = "[{index}] strict \"{0}\" -> no match")
	@ValueSource(strings = {
			" Foo", // leading whitespace
			"Foo ", // trailing whitespace
			"(Foo)", // wrapping parens
			"&Foo", // leading address-of marker
			"`Foo`", // wrapping backticks
			"Foo*", // trailing pointer suffix
			"Foo." }) // trailing dot
	void strictModeRejectsAnythingButTheExactReference(String input) {
		assertNull(CppIdentifierMatcher.classify(input, false), () -> "strict must reject: " + input);
	}
}
