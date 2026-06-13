package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.ui.dialogs.SearchPattern;
import org.junit.jupiter.api.Test;

import com.anthropic.claudecode.eclipse.resolvers.IEntityResolver.IResolvedEntity;
import com.anthropic.claudecode.eclipse.resolvers.JavaIdentifierEntityResolver.JavaReference;

/**
 * Tests for {@link JavaIdentifierEntityResolver#resolve(String, boolean)}: that it gates on the matcher
 * and routes to the right branch (miss / single open / chooser) and propagates the navigation line. Lives
 * in the production package so it can subclass the resolver and override the JDT-touching seams
 * ({@code findMatches}, {@code open}, {@code openChooser}) to run without a Java model or a workbench. The
 * matched elements are irrelevant here (the overridden open seams never dereference them), so the recorder
 * uses {@code null} placeholders.
 */
class JavaIdentifierEntityResolverTest {

	/** Records which branch {@code resolve} routed to, and the line it carried. */
	private static final class Recorder extends JavaIdentifierEntityResolver {
		List<IJavaElement> matches = new ArrayList<>();
		boolean findCalled;
		boolean opened;
		boolean chooserOpened;
		int openedLine = -1;
		int chooserCount = -1;
		int chooserLine = -1;

		@Override
		void findMatches(JavaReference ref, List<IJavaElement> out) {
			findCalled = true;
			out.addAll(matches);
		}

		@Override
		void open(IJavaElement element, int line) {
			opened = true;
			openedLine = line;
		}

		@Override
		void openChooser(List<IJavaElement> chooserMatches, int line) {
			chooserOpened = true;
			chooserCount = chooserMatches.size();
			chooserLine = line;
		}
	}

	private final Recorder resolver = new Recorder();

	/** A list of {@code count} null placeholder elements — the open seams never dereference them. */
	private static List<IJavaElement> nullMatches(int count) {
		return new ArrayList<>(Collections.nCopies(count, null));
	}

	@Test
	void nonReferenceMissesWithoutSearching() {
		assertNull(resolver.resolve("123abc", false), "a non-reference must not resolve");
		assertFalse(resolver.findCalled, "the workspace search must be skipped when the matcher misses");
	}

	@Test
	void nullAndEmptyMissWithoutSearching() {
		assertNull(resolver.resolve(null, true));
		assertNull(resolver.resolve("", true));
		assertFalse(resolver.findCalled);
	}

	@Test
	void recognizedButUnresolvedMisses() {
		resolver.matches = nullMatches(0); // looks like a type, but nothing in the workspace matches
		assertNull(resolver.resolve("java.util.List", false));
		assertTrue(resolver.findCalled, "a recognized reference must trigger the search");
	}

	@Test
	void singleMatchOpensDirectly() {
		resolver.matches = nullMatches(1);
		IResolvedEntity entity = resolver.resolve("java.util.List", false);
		assertNotNull(entity);
		entity.locate();
		assertTrue(resolver.opened);
		assertFalse(resolver.chooserOpened);
		assertEquals(0, resolver.openedLine, "a plain type reference carries no navigation line");
	}

	@Test
	void typeWithLineRoutesToSearchAndOpensAtLine() {
		resolver.matches = nullMatches(1);
		// "Type : line" is recognized by the matcher, so resolve must reach the search and open branch,
		// and the line from the reference (ref.lineNumber()) must reach open().
		IResolvedEntity entity = resolver.resolve("com.foo.Bar : 21", false);
		assertNotNull(entity);
		entity.locate();
		assertTrue(resolver.findCalled, "a Type : line reference must trigger the search");
		assertTrue(resolver.opened);
		assertEquals(21, resolver.openedLine, "the navigation line must reach open()");
	}

	@Test
	void multipleMatchesOpenTheChooser() {
		resolver.matches = nullMatches(2);
		IResolvedEntity entity = resolver.resolve("Foo", true);
		assertNotNull(entity);
		entity.locate();
		assertTrue(resolver.chooserOpened);
		assertFalse(resolver.opened);
		assertEquals(2, resolver.chooserCount);
		assertEquals(0, resolver.chooserLine, "a plain name reference carries no navigation line");
	}

	@Test
	void allowStripEdgesIsHonored() {
		resolver.matches = nullMatches(1);
		// Wrapped token: only matches when edges are stripped; strict mode rejects it before searching.
		assertNull(resolver.resolve("(java.util.List).", false), "strict mode must reject a wrapped token");
		assertFalse(resolver.findCalled);

		assertNotNull(resolver.resolve("(java.util.List).", true), "strip mode must recognize the wrapped token");
		assertTrue(resolver.findCalled);
	}

	// ---- dialog filtering (fqnMatches: substring over the fully qualified name) -----------------

	/** Whether the chooser's substring filter would keep an element whose FQN is {@code fqn} for {@code pattern}. */
	private static boolean filters(String pattern, String fqn) {
		SearchPattern matcher = new SearchPattern(
				SearchPattern.DEFAULT_MATCH_RULES | SearchPattern.RULE_SUBSTRING_MATCH);
		matcher.setPattern(pattern);
		return JavaIdentifierEntityResolver.fqnMatches(matcher, fqn);
	}

	@Test
	void substringMatchesAnyFragmentOfTheQualifiedName() {
		String fqn = "java.util.HashMap.put(Object, Object)";
		assertTrue(filters("HashMap", fqn), "a type fragment must match");
		assertTrue(filters("hashmap", fqn), "matching is case-insensitive");
		assertTrue(filters("*Map*", fqn), "an explicit *...* wildcard must match");
		assertTrue(filters("put", fqn), "a member fragment must match");
		assertTrue(filters("HashMap.put", fqn), "a dotted run must match");
		assertTrue(filters("util.Hash", fqn), "a package-anchored fragment must match");
		assertFalse(filters("xyz", fqn), "an absent fragment must not match");
	}
}
