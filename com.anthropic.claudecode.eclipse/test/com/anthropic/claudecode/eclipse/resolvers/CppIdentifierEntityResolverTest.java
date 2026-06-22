package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.ui.dialogs.SearchPattern;
import org.junit.jupiter.api.Test;

import com.anthropic.claudecode.eclipse.resolvers.CppIdentifierEntityResolver.CppReference;
import com.anthropic.claudecode.eclipse.resolvers.IEntityResolver.IResolvedEntity;

/**
 * Tests for {@link CppIdentifierEntityResolver#resolve(String, boolean)}: that it gates on the matcher and
 * routes to the right branch (miss / single open / chooser), and for the pure qualifier/filter helpers.
 * Lives in the production package so it can subclass the resolver and override the CDT-touching seams
 * ({@code findMatches}, {@code open}, {@code openChooser}) to run without a CDT index or a workbench. The
 * matched elements are irrelevant for the routing tests (the overridden open seams never dereference them),
 * so the recorder uses {@code null} placeholders.
 */
class CppIdentifierEntityResolverTest {

	/** Records which branch {@code resolve} routed to. */
	private static final class Recorder extends CppIdentifierEntityResolver {
		List<ICElement> matches = new ArrayList<>();
		boolean findCalled;
		boolean opened;
		boolean chooserOpened;
		int chooserCount = -1;

		@Override
		void findMatches(CppReference ref, List<ICElement> out) {
			findCalled = true;
			out.addAll(matches);
		}

		@Override
		void open(ICElement match) {
			opened = true;
		}

		@Override
		void openChooser(List<ICElement> chooserMatches) {
			chooserOpened = true;
			chooserCount = chooserMatches.size();
		}
	}

	private final Recorder resolver = new Recorder();

	/** A list of {@code count} null placeholder elements — the open seams never dereference them. */
	private static List<ICElement> nullMatches(int count) {
		return new ArrayList<>(Collections.nCopies(count, null));
	}

	@Test
	void nonIdentifierMissesWithoutSearching() {
		assertNull(resolver.resolve("123abc", false), "a non-identifier must not resolve");
		assertFalse(resolver.findCalled, "the index lookup must be skipped when the matcher misses");
	}

	@Test
	void nullAndEmptyMissWithoutSearching() {
		assertNull(resolver.resolve(null, true));
		assertNull(resolver.resolve("", true));
		assertFalse(resolver.findCalled);
	}

	@Test
	void recognizedButUnresolvedMisses() {
		resolver.matches = nullMatches(0); // looks like an identifier, but nothing in the index matches
		assertNull(resolver.resolve("ns::Foo::bar", false));
		assertTrue(resolver.findCalled, "a recognized identifier must trigger the lookup");
	}

	@Test
	void singleMatchOpensDirectly() {
		resolver.matches = nullMatches(1);
		IResolvedEntity entity = resolver.resolve("MyClass", false);
		assertNotNull(entity);
		entity.locate();
		assertTrue(resolver.opened);
		assertFalse(resolver.chooserOpened);
	}

	@Test
	void multipleMatchesOpenTheChooser() {
		resolver.matches = nullMatches(2);
		IResolvedEntity entity = resolver.resolve("bar", true);
		assertNotNull(entity);
		entity.locate();
		assertTrue(resolver.chooserOpened);
		assertFalse(resolver.opened);
		assertEquals(2, resolver.chooserCount);
	}

	@Test
	void allowStripEdgesIsHonored() {
		resolver.matches = nullMatches(1);
		// Wrapped token: only matches when edges are stripped; strict mode rejects it before searching.
		assertNull(resolver.resolve("(MyClass)", false), "strict mode must reject a wrapped token");
		assertFalse(resolver.findCalled);

		assertNotNull(resolver.resolve("(MyClass)", true), "strip mode must recognize the wrapped token");
		assertTrue(resolver.findCalled);
	}

	// ---- qualifier narrowing (qualifierMatches: segment-aligned suffix of the qualified name) ----

	@Test
	void bareNameKeepsEverySameNamedBinding() {
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(new String[] {"ns", "Foo", "bar"}, List.of("bar"), false));
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(new String[] {"bar"}, List.of("bar"), false));
	}

	@Test
	void qualifiedReferenceEnforcesItsSuffix() {
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(
				new String[] {"app", "ns", "Foo", "bar"}, List.of("Foo", "bar"), false), "a partial qualifier matches as a suffix");
		assertFalse(CppIdentifierEntityResolver.qualifierMatches(
				new String[] {"app", "Other", "bar"}, List.of("Foo", "bar"), false), "a different container must not match");
	}

	@Test
	void qualifierSuffixIsSegmentAligned() {
		// "Name" must not match a container segment "ClassName" — the boundary is a ::, not any character.
		assertFalse(CppIdentifierEntityResolver.qualifierMatches(
				new String[] {"ns", "ClassName", "update"}, List.of("Name", "update"), false));
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(
				new String[] {"ns", "ClassName", "update"}, List.of("ClassName", "update"), false));
	}

	@Test
	void aQualifierLongerThanTheBindingCannotMatch() {
		assertFalse(CppIdentifierEntityResolver.qualifierMatches(new String[] {"bar"}, List.of("Foo", "bar"), false));
	}

	@Test
	void globalScopeOnlyMatchesRootNamespaceBindings() {
		// ::Foo matches a global Foo (CDT: ["Foo"]) but not ns::Foo (CDT: ["ns","Foo"])
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(new String[] {"Foo"}, List.of("Foo"), true),
				"::Foo must match global Foo");
		assertFalse(CppIdentifierEntityResolver.qualifierMatches(new String[] {"ns", "Foo"}, List.of("Foo"), true),
				"::Foo must not match ns::Foo");
		// ::ns::Foo matches ns::Foo but not outer::ns::Foo
		assertTrue(CppIdentifierEntityResolver.qualifierMatches(new String[] {"ns", "Foo"}, List.of("ns", "Foo"), true),
				"::ns::Foo must match root-level ns::Foo");
		assertFalse(CppIdentifierEntityResolver.qualifierMatches(new String[] {"outer", "ns", "Foo"}, List.of("ns", "Foo"), true),
				"::ns::Foo must not match outer::ns::Foo");
	}

	// ---- dialog filtering (fqnMatches: substring over the fully qualified name) -----------------

	/** Whether the chooser's substring filter would keep an element whose FQN is {@code fqn} for {@code pattern}. */
	private static boolean filters(String pattern, String fqn) {
		SearchPattern matcher = new SearchPattern(
				SearchPattern.DEFAULT_MATCH_RULES | SearchPattern.RULE_SUBSTRING_MATCH);
		matcher.setPattern(pattern);
		return CppIdentifierEntityResolver.fqnMatches(matcher, fqn);
	}

	@Test
	void substringMatchesAnyFragmentOfTheQualifiedName() {
		String fqn = "ns::Foo::bar(int, char*)";
		assertTrue(filters("Foo", fqn), "a type fragment must match");
		assertTrue(filters("foo", fqn), "matching is case-insensitive");
		assertTrue(filters("*Foo*", fqn), "an explicit *...* wildcard must match");
		assertTrue(filters("bar", fqn), "a member fragment must match");
		assertTrue(filters("Foo::bar", fqn), "a qualified run must match");
		assertTrue(filters("ns::Foo", fqn), "a namespace-anchored fragment must match");
		assertFalse(filters("xyz", fqn), "an absent fragment must not match");
	}
}
