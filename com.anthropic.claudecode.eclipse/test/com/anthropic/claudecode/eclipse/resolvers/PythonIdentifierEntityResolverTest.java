package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.ui.dialogs.SearchPattern;
import org.junit.jupiter.api.Test;

import org.python.pydev.core.IInfo;
import org.python.pydev.core.IPythonNature;

import com.anthropic.claudecode.eclipse.resolvers.IEntityResolver.IResolvedEntity;
import com.python.pydev.analysis.additionalinfo.AdditionalInfoAndIInfo;

/**
 * Tests for {@link PythonIdentifierEntityResolver#resolve(String, boolean)}: that it gates on the matcher
 * and routes to the right branch (miss / single open / chooser). Lives in the production package so it can
 * subclass the resolver and override the PyDev-touching seams ({@code findMatches}, {@code open},
 * {@code openChooser}) to run without a PyDev model or a workbench. The matched elements are irrelevant here
 * (the overridden open seams never dereference them), so the recorder uses {@code null} placeholders.
 */
class PythonIdentifierEntityResolverTest {

	/** Records which branch {@code resolve} routed to. */
	private static final class Recorder extends PythonIdentifierEntityResolver {
		List<AdditionalInfoAndIInfo> matches = new ArrayList<>();
		boolean findCalled;
		boolean opened;
		boolean chooserOpened;
		int chooserCount = -1;

		@Override
		void findMatches(PythonReference ref, List<AdditionalInfoAndIInfo> out) {
			findCalled = true;
			out.addAll(matches);
		}

		@Override
		void open(AdditionalInfoAndIInfo match) {
			opened = true;
		}

		@Override
		void openChooser(List<AdditionalInfoAndIInfo> chooserMatches) {
			chooserOpened = true;
			chooserCount = chooserMatches.size();
		}
	}

	private final Recorder resolver = new Recorder();

	/** A list of {@code count} null placeholder matches — the open seams never dereference them. */
	private static List<AdditionalInfoAndIInfo> nullMatches(int count) {
		return new ArrayList<>(Collections.nCopies(count, null));
	}

	@Test
	void nonIdentifierMissesWithoutSearching() {
		assertNull(resolver.resolve("123abc", false), "a non-identifier must not resolve");
		assertFalse(resolver.findCalled, "the PyDev lookup must be skipped when the matcher misses");
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
		assertNull(resolver.resolve("os.path.join", false));
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
		IResolvedEntity entity = resolver.resolve("join", true);
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
		assertNull(resolver.resolve("(MyClass).", false), "strict mode must reject a wrapped token");
		assertFalse(resolver.findCalled);

		assertNotNull(resolver.resolve("(MyClass).", true), "strip mode must recognize the wrapped token");
		assertTrue(resolver.findCalled);
	}

	// ---- qualifier narrowing (filterByQualifier / containerMatchesQualifier) -------------------

	/** A minimal {@link IInfo}: only the declaring module and in-module path carry real values. */
	private static AdditionalInfoAndIInfo hit(String declaringModuleName, String path) {
		return new AdditionalInfoAndIInfo(null, infoOf(declaringModuleName, path, "name"));
	}

	/** A minimal {@link IInfo} with the given declaring module, in-module path, and simple name. */
	private static IInfo infoOf(String declaringModuleName, String path, String name) {
		return new IInfo() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getDeclaringModuleName() {
				return declaringModuleName;
			}

			@Override
			public String getPath() {
				return path;
			}

			@Override
			public String getFile() {
				return null;
			}

			@Override
			public int getLine() {
				return 0;
			}

			@Override
			public int getCol() {
				return 0;
			}

			@Override
			public int getType() {
				return 0;
			}

			@Override
			public IPythonNature getNature() {
				return null;
			}

			@Override
			public int compareTo(IInfo o) {
				return 0;
			}
		};
	}

	@Test
	void qualifierNarrowsToTheMatchingContainer() {
		AdditionalInfoAndIInfo inClass = hit("app.models", "ClassName");
		AdditionalInfoAndIInfo inOther = hit("app.models", "Other");
		List<AdditionalInfoAndIInfo> kept = PythonIdentifierEntityResolver.filterByQualifier(
				List.of(inClass, inOther), "ClassName");
		assertEquals(List.of(inClass), kept, "only the method in ClassName must survive");
	}

	@Test
	void qualifierMatchesAModuleQualifiedTopLevelToken() {
		AdditionalInfoAndIInfo inModule = hit("mymodule", null);
		AdditionalInfoAndIInfo inOther = hit("othermodule", null);
		List<AdditionalInfoAndIInfo> kept = PythonIdentifierEntityResolver.filterByQualifier(
				List.of(inModule, inOther), "mymodule");
		assertEquals(List.of(inModule), kept);
	}

	@Test
	void qualifierSuffixIsSegmentAligned() {
		// "Name" must not match a container ending in "ClassName" — the boundary is a dot, not any char.
		assertFalse(PythonIdentifierEntityResolver.containerMatchesQualifier(hit("app", "ClassName").info, "Name"));
		assertTrue(PythonIdentifierEntityResolver.containerMatchesQualifier(hit("app", "ClassName").info, "ClassName"));
		// A multi-segment suffix of the container also matches.
		assertTrue(PythonIdentifierEntityResolver.containerMatchesQualifier(hit("app.models", "ClassName").info,
				"models.ClassName"));
	}

	@Test
	void nullQualifierKeepsEveryHit() {
		List<AdditionalInfoAndIInfo> all = List.of(hit("a", null), hit("b", "C"));
		assertEquals(all, PythonIdentifierEntityResolver.filterByQualifier(all, null),
				"a bare simple name (no qualifier) must not filter anything");
	}

	@Test
	void unmatchedQualifierYieldsNoHits() {
		// os.path.join: join is declared in posixpath, so the "os.path" qualifier matches nothing. A written
		// qualifier is enforced strictly → no hit survives (a miss), rather than degrading to every same-named token.
		List<AdditionalInfoAndIInfo> all = List.of(hit("posixpath", null), hit("ntpath", null));
		assertTrue(PythonIdentifierEntityResolver.filterByQualifier(all, "os.path").isEmpty(),
				"a written qualifier that matches no container must yield no hit, not every same-named token");
	}

	// ---- dialog filtering (fqnMatches: substring over the full qualified name) ------------------

	/** Whether the dialog's substring filter would keep {@code info} when the box contains {@code pattern}. */
	private static boolean filters(String pattern, IInfo info) {
		SearchPattern matcher = new SearchPattern(
				SearchPattern.DEFAULT_MATCH_RULES | SearchPattern.RULE_SUBSTRING_MATCH);
		matcher.setPattern(pattern);
		return PythonIdentifierEntityResolver.fqnMatches(matcher, info);
	}

	@Test
	void substringMatchesAnyFragmentOfTheQualifiedName() {
		// update_cache - main.LRUCacheManager (the regression from 3_bad_filter.png).
		IInfo info = infoOf("main", "LRUCacheManager", "update_cache");
		assertTrue(filters("LRU", info), "a class fragment must match (was the bug)");
		assertTrue(filters("lru", info), "matching is case-insensitive");
		assertTrue(filters("*LRU*", info), "an explicit *...* wildcard must match");
		assertTrue(filters("update", info), "a name fragment must match");
		assertTrue(filters("cache", info), "a fragment shared by name and class must match");
		assertTrue(filters("LRUCacheManager.update_cache", info), "a dotted run must match");
		assertTrue(filters("main.LRU", info), "a module-anchored fragment must match");
		assertFalse(filters("xyz", info), "an absent fragment must not match");
	}

	@Test
	void substringMatchesTopLevelTokens() {
		// A module-level function func in module pkg → fqn "pkg.func" (no class path).
		IInfo info = infoOf("pkg", null, "func");
		assertTrue(filters("pkg", info), "the module fragment must match");
		assertTrue(filters("func", info), "the name fragment must match");
		assertFalse(filters("LRU", info), "an absent fragment must not match");
	}
}
