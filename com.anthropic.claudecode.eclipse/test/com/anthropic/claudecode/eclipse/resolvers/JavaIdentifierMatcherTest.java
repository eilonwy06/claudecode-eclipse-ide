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

import com.anthropic.claudecode.eclipse.resolvers.JavaIdentifierEntityResolver.JavaIdentifierMatcher;
import com.anthropic.claudecode.eclipse.resolvers.JavaIdentifierEntityResolver.JavaReference;
import com.anthropic.claudecode.eclipse.resolvers.JavaIdentifierEntityResolver.Kind;

/**
 * Tests for {@link JavaIdentifierMatcher#classify(String, boolean)} — the pure, JDT-free recognition.
 * Lives in the production package to reach the package-private {@code classify} and {@link JavaReference}.
 */
class JavaIdentifierMatcherTest {

	private static JavaReference ref(Kind kind, String pkg, String type, String member, int params) {
		return ref(kind, pkg, type, member, params, 0);
	}

	private static JavaReference ref(Kind kind, String pkg, String type, String member, int params, int line) {
		return new JavaReference(kind, pkg, type, member, params, line);
	}

	// ---- clean tokens: match identically in strict and strip mode ------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("cleanMatches")
	void cleanTokenMatchesInBothModes(String input, JavaReference expected) {
		assertEquals(expected, JavaIdentifierMatcher.classify(input, false), () -> "strict: " + input);
		assertEquals(expected, JavaIdentifierMatcher.classify(input, true), () -> "strip: " + input);
	}

	static Stream<Arguments> cleanMatches() {
		return Stream.of(
				// types — simple and qualified
				arguments("List", ref(Kind.TYPE, null, "List", null, -1)),
				arguments("java.util.List", ref(Kind.TYPE, "java.util", "List", null, -1)),
				arguments("com.foo.Bar", ref(Kind.TYPE, "com.foo", "Bar", null, -1)),
				// a token that *looks* like a type (the workspace search, not the matcher, rejects it)
				arguments("Bar.java", ref(Kind.TYPE, "Bar", "java", null, -1)),
				// members — javadoc-style "#", no argument list
				arguments("Foo#bar", ref(Kind.MEMBER, null, "Foo", "bar", -1)),
				arguments("com.foo.Bar#baz", ref(Kind.MEMBER, "com.foo", "Bar", "baz", -1)),
				// methods — javadoc "#" with args, and dotted call form
				arguments("Foo#bar()", ref(Kind.METHOD, null, "Foo", "bar", 0)),
				arguments("com.foo.Bar#baz(int, String)", ref(Kind.METHOD, "com.foo", "Bar", "baz", 2)),
				arguments("com.foo.Bar.method(x)", ref(Kind.METHOD, "com.foo", "Bar", "method", 1)),
				arguments("Bar.baz(x)", ref(Kind.METHOD, null, "Bar", "baz", 1)),
				// the "(...)" args-elided placeholder classifies exactly like an empty "()" (paramCount 0)
				arguments("com.foo.Bar.method()", ref(Kind.METHOD, "com.foo", "Bar", "method", 0)),
				arguments("com.foo.Bar.method(...)", ref(Kind.METHOD, "com.foo", "Bar", "method", 0)),
				arguments("Foo#bar(...)", ref(Kind.METHOD, null, "Foo", "bar", 0)),
				// generics/arrays in the argument list don't inflate the arity
				arguments("Foo#bar(Map<K, V>, int)", ref(Kind.METHOD, null, "Foo", "bar", 2)),
				arguments("Foo#bar(int[], long)", ref(Kind.METHOD, null, "Foo", "bar", 2)),
				// type + line: the line rides along on a TYPE reference (spaced or not)
				arguments("Foo : 5", ref(Kind.TYPE, null, "Foo", null, -1, 5)),
				arguments("com.foo.Bar : 21", ref(Kind.TYPE, "com.foo", "Bar", null, -1, 21)),
				arguments("com.foo.Bar:21", ref(Kind.TYPE, "com.foo", "Bar", null, -1, 21)));
	}

	// ---- junk-wrapped tokens: match only with edge stripping ------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("wrappedMatches")
	void wrappedTokenMatchesOnlyWhenStripping(String input, JavaReference expected) {
		assertEquals(expected, JavaIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(JavaIdentifierMatcher.classify(input, false), () -> "strict must reject wrapped: " + input);
	}

	static Stream<Arguments> wrappedMatches() {
		return Stream.of(
				arguments("(java.util.List).", ref(Kind.TYPE, "java.util", "List", null, -1)),
				arguments("\"Foo\"", ref(Kind.TYPE, null, "Foo", null, -1)),
				arguments("`com.foo.Bar`", ref(Kind.TYPE, "com.foo", "Bar", null, -1)),
				arguments("<Foo>", ref(Kind.TYPE, null, "Foo", null, -1)),
				arguments("@Override", ref(Kind.TYPE, null, "Override", null, -1)),
				// outer wrapping parens stripped while the method's own (balanced) parens survive
				arguments("(Bar.baz(x))", ref(Kind.METHOD, null, "Bar", "baz", 1)),
				arguments("com.foo.Bar,", ref(Kind.TYPE, "com.foo", "Bar", null, -1)));
	}

	// ---- never a Java reference, in either mode ------------------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = {
			"123abc", // identifiers can't start with a digit
			"a..b", // empty segment
			"std::string", // C++ scope operator
			"http://x.com", // URL
			"https://x.com",
			"method(x)", // a call with no type to resolve against
			"foo()",
			"#bar", // no type before '#'
			"Foo : abc", // line number isn't numeric
			": 21", // no type before the line number
			"com.foo.Bar:21-30", // line ranges aren't supported (unlike FileEntityResolver)
			"see java.util.List here", // a sentence, not a single token
			"   ",
			"" })
	void nonReferencesNeverMatch(String input) {
		assertNull(JavaIdentifierMatcher.classify(input, true), () -> "strip: " + input);
		assertNull(JavaIdentifierMatcher.classify(input, false), () -> "strict: " + input);
	}

	@Test
	void nullNeverMatches() {
		assertNull(JavaIdentifierMatcher.classify(null, true));
		assertNull(JavaIdentifierMatcher.classify(null, false));
	}

	// ---- strict mode keeps every character ----------------------------------------------------

	@ParameterizedTest(name = "[{index}] strict \"{0}\" -> no match")
	@ValueSource(strings = {
			" java.util.List", // leading whitespace
			"java.util.List ", // trailing whitespace
			"(java.util.List)", // wrapping parens
			"@Override", // leading annotation marker
			"java.util.List." }) // trailing dot
	void strictModeRejectsAnythingButTheExactReference(String input) {
		assertNull(JavaIdentifierMatcher.classify(input, false), () -> "strict must reject: " + input);
	}
}
