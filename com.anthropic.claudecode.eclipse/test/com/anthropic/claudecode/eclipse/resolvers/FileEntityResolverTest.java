package com.anthropic.claudecode.eclipse.resolvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.dialogs.SearchPattern;
import org.junit.jupiter.api.Test;

import com.anthropic.claudecode.eclipse.resolvers.IEntityResolver.IResolvedEntity;

/**
 * Tests for {@link FileEntityResolver#resolve(String, boolean)}. Lives in the production package so it
 * can subclass the resolver and override its protected hooks ({@code fileExists},
 * {@code browseWorkspaceFiles}) and open seams ({@code locate}, {@code open}, {@code openChooser}) to run
 * without a file system or a live Eclipse workspace. Like the identifier resolver tests, it asserts
 * routing (miss / absolute open / single workspace open / chooser) and the navigation line; the matched
 * files are irrelevant (the overridden open seams never dereference them), so the recorder uses
 * {@code null} placeholders and asserts the normalized suffix the search was given instead.
 *
 * <p>Inputs model a single space-delimited token cut from a Claude Code answer, so none contain
 * internal whitespace.
 */
class FileEntityResolverTest {

	/** Records which branch {@code resolve} chose and with what arguments. */
	private static final class Recorder extends FileEntityResolver {
		final Set<String> existing = new HashSet<>();
		List<IFile> workspaceMatches = new ArrayList<>();
		String searchedSuffix;

		boolean located;
		String locatedPath;
		int locatedLine = -1;

		boolean opened;
		int openedLine = -1;

		boolean chooserOpened;
		int chooserCount = -1;
		int chooserLine = -1;

		@Override
		boolean fileExists(String absolutePath) {
			return existing.contains(absolutePath);
		}

		@Override
		List<IFile> browseWorkspaceFiles(String pathSuffix) {
			searchedSuffix = pathSuffix;
			return workspaceMatches;
		}

		@Override
		void locate(String filePath, int lineNumber) {
			located = true;
			locatedPath = filePath;
			locatedLine = lineNumber;
		}

		@Override
		void open(IFile file, int lineNumber) {
			opened = true;
			openedLine = lineNumber;
		}

		@Override
		void openChooser(List<IFile> matches, int lineNumber) {
			chooserOpened = true;
			chooserCount = matches.size();
			chooserLine = lineNumber;
		}
	}

	private final Recorder resolver = new Recorder();

	/** A list of {@code count} null placeholder files — the open seams never dereference them. */
	private static List<IFile> nullMatches(int count) {
		return new ArrayList<>(Collections.nCopies(count, null));
	}

	/** Resolves and fires the resulting entity so the chosen branch is recorded; asserts a hit. */
	private void resolveAndFire(String text, boolean allowStripEdges) {
		IResolvedEntity entity = resolver.resolve(text, allowStripEdges);
		assertNotNull(entity, () -> "expected a match for: " + text);
		entity.locate();
	}

	// --- misses -------------------------------------------------------------------------------

	@Test
	void nullBlankAndWhitespaceDoNotMatch() {
		for (String input : new String[] { null, "", "   ", "\t" }) {
			assertNull(resolver.resolve(input, true), "blank/null must not match");
		}
	}

	@Test
	void webSchemesAreDeferred() {
		assertNull(resolver.resolve("http://example.com", false));
		assertNull(resolver.resolve("https://example.com/a.txt", false));
		assertNull(resolver.resolve("HTTPS://Example.com", false)); // case-insensitive
	}

	@Test
	void ftpIsNotDeferredButResolvesToNothingWhenUnknown() {
		// ftp:// is intentionally NOT short-circuited; with no fs/workspace hit it simply misses.
		assertNull(resolver.resolve("ftp://host/file.txt", false));
	}

	@Test
	void unknownRelativePathWithNoWorkspaceMatchMisses() {
		resolver.workspaceMatches = nullMatches(0);
		assertNull(resolver.resolve("src/Missing.java", false));
	}

	@Test
	void absoluteButNonexistentAndNoWorkspaceMatchMisses() {
		resolver.workspaceMatches = nullMatches(0);
		assertNull(resolver.resolve("/no/such/file.txt", false));
	}

	// --- absolute path branch -----------------------------------------------------------------

	@Test
	void existingAbsolutePathLocatesIt() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("/abs/a.txt", false);
		assertTrue(resolver.located);
		assertEquals("/abs/a.txt", resolver.locatedPath);
		assertEquals(0, resolver.locatedLine);
	}

	@Test
	void fileSchemePrefixIsStrippedBeforeLookup() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("file:///abs/a.txt", false);
		assertEquals("/abs/a.txt", resolver.locatedPath);
		assertEquals(0, resolver.locatedLine);
	}

	// --- trailing line reference --------------------------------------------------------------

	@Test
	void singleLineNumberIsParsedAndStripped() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("/abs/a.txt:42", false);
		assertEquals("/abs/a.txt", resolver.locatedPath);
		assertEquals(42, resolver.locatedLine);
	}

	@Test
	void lineRangeKeepsOnlyTheFirstNumber() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("/abs/a.txt:10-15", false);
		assertEquals("/abs/a.txt", resolver.locatedPath);
		assertEquals(10, resolver.locatedLine);
	}

	@Test
	void lineRangeWithUnicodeDelimiter() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("/abs/a.txt:10\u201315", false);
		assertEquals("/abs/a.txt", resolver.locatedPath);
		assertEquals(10, resolver.locatedLine);
	}

	@Test
	void twoHyphensIsNotAValidLineReference() {
		// ":10-15-20" must NOT parse as a line ref, so the suffix stays part of the path.
		resolver.existing.add("/abs/a.txt:10-15-20");
		resolveAndFire("/abs/a.txt:10-15-20", false);
		assertEquals("/abs/a.txt:10-15-20", resolver.locatedPath);
		assertEquals(0, resolver.locatedLine);
	}

	@Test
	void nonNumericAfterColonIsNotALineReference() {
		resolver.existing.add("/abs/a.txt:abc");
		resolveAndFire("/abs/a.txt:abc", false);
		assertEquals("/abs/a.txt:abc", resolver.locatedPath);
		assertEquals(0, resolver.locatedLine);
	}

	// --- workspace branch ---------------------------------------------------------------------

	@Test
	void singleWorkspaceMatchOpensItWithParsedLine() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("src/Foo.java:7", false);
		assertTrue(resolver.opened);
		assertFalse(resolver.chooserOpened);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
		assertEquals(7, resolver.openedLine);
	}

	@Test
	void ftpUrlCanAlsoBeMatchedIfItIsAPath() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("ftp://host/file.txt", false);
		assertTrue(resolver.opened);
		assertFalse(resolver.chooserOpened);
		assertEquals(0, resolver.openedLine);
	}

	@Test
	void multipleWorkspaceMatchesOpenTheChooserWithStrippedLine() {
		resolver.workspaceMatches = nullMatches(2);
		resolveAndFire("src/Foo.java:7", false);
		assertTrue(resolver.chooserOpened);
		assertFalse(resolver.opened);
		assertEquals(2, resolver.chooserCount);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
		assertEquals(7, resolver.chooserLine);
	}

	// --- edge stripping -----------------------------------------------------------------------

	@Test
	void edgesAreStrippedWhenAllowed() {
		resolver.existing.add("/abs/a.txt");
		resolveAndFire("(/abs/a.txt).", true);
		assertEquals("/abs/a.txt", resolver.locatedPath);
	}

	@Test
	void quotedTokenIsStrippedWhenAllowed() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("\"src/Foo.java\"", true);
		assertTrue(resolver.opened);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void edgesAreKeptWhenNotAllowed() {
		// With stripping off, the wrapping punctuation stays, so the suffix lookup uses it verbatim.
		resolver.existing.add("/abs/a.txt");
		assertNull(resolver.resolve("(/abs/a.txt)", false),
				"unstripped token should not match the clean absolute path");
	}

	@Test
	void leadingDotInDotfileIsKeptWhenStripping() {
		// Ctrl-click on a dotfile: the leading '.' must survive so the suffix matches on a '/' boundary.
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire(".gitignore", true);
		assertTrue(resolver.opened);
		assertEquals(".gitignore", resolver.searchedSuffix);
	}

	@Test
	void leadingDotSlashRelativePathIsKeptWhenStripping() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("./src/Foo.java", true);
		assertTrue(resolver.opened);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void leadingParentRelativePathSurvivesStrippingIntoTheChooserSearch() {
		// Leading ".." is kept while the wrapping '(' and trailing ').' are still trimmed.
		resolver.workspaceMatches = nullMatches(2);
		resolveAndFire("(../src/Foo.java).", true);
		assertTrue(resolver.chooserOpened);
		assertEquals("../src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void trailingPeriodIsStillTrimmedForADotfileWhenStripping() {
		// Leading '.' kept, but a sentence-ending trailing '.' is still removed.
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire(".env.", true);
		assertTrue(resolver.opened);
		assertEquals(".env", resolver.searchedSuffix);
	}

	// --- path normalization ------------------------------------------------------------------

	@Test
	void normalizeSepCollapsesDotDuplicateSeparatorsAndInternalParent() {
		assertEquals("src/Foo.java", FileEntityResolver.normalizeSep("./src/Foo.java"));
		assertEquals("src/Foo.java", FileEntityResolver.normalizeSep("src//Foo.java"));
		assertEquals("src/Foo.java", FileEntityResolver.normalizeSep("src/./Foo.java"));
		assertEquals("src/Foo.java", FileEntityResolver.normalizeSep("src/x/../Foo.java"));
		assertEquals("src/Foo.java", FileEntityResolver.normalizeSep("src\\Foo.java"));
		assertEquals("/abs/a.txt", FileEntityResolver.normalizeSep("/abs/a.txt"));
	}

	@Test
	void normalizeSepPreservesLeadingParent() {
		assertEquals("../src/Foo.java", FileEntityResolver.normalizeSep("../src/Foo.java"));
		assertEquals("../b", FileEntityResolver.normalizeSep("a/../../b"));
	}

	@Test
	void normalizeSepReturnsEmptyForEmptyDotOrInvalidPath() {
		assertEquals("", FileEntityResolver.normalizeSep(""));
		assertEquals("", FileEntityResolver.normalizeSep("."));
		// A NUL byte is rejected by Path.of on Linux (and ':' outside a drive on Windows).
		assertEquals("", FileEntityResolver.normalizeSep("a" + '\0' + "b"));
	}

	@Test
	void leadingDotSlashIsNormalizedBeforeWorkspaceLookup() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("./src/Foo.java", false);
		assertTrue(resolver.opened);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void redundantSegmentsAreNormalizedBeforeWorkspaceLookup() {
		for (String token : new String[] { "src//Foo.java", "src/./Foo.java", "src/x/../Foo.java" }) {
			Recorder r = new Recorder();
			r.workspaceMatches = nullMatches(2);
			IResolvedEntity entity = r.resolve(token, false);
			assertNotNull(entity, () -> "expected a match for: " + token);
			entity.locate();
			assertTrue(r.chooserOpened);
			assertEquals("src/Foo.java", r.searchedSuffix, () -> "normalized suffix for: " + token);
		}
	}

	@Test
	void leadingParentIsKeptInTheWorkspaceLookup() {
		resolver.workspaceMatches = nullMatches(2);
		resolveAndFire("../src/Foo.java", false);
		assertTrue(resolver.chooserOpened);
		assertEquals("../src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void backslashTokenStillResolves() {
		resolver.workspaceMatches = nullMatches(1);
		resolveAndFire("src\\Foo.java", false);
		assertTrue(resolver.opened);
		assertEquals("src/Foo.java", resolver.searchedSuffix);
	}

	@Test
	void bareDotDoesNotMatch() {
		assertNull(resolver.resolve(".", false), "\".\" normalizes to empty and must not match");
	}

	// --- suffix matching helper ---------------------------------------------------------------

	@Test
	void matchesSuffixRespectsSegmentBoundaries() {
		assertTrue(FileEntityResolver.matchesSuffix("/proj/ui/Foo.java", "ui/Foo.java"));
		assertTrue(FileEntityResolver.matchesSuffix("/proj/ui/Foo.java", "/proj/ui/Foo.java"));
		assertFalse(FileEntityResolver.matchesSuffix("/proj/gui/Foo.java", "ui/Foo.java"));
	}

	// --- chooser filtering (pathMatches: substring over the workspace path) --------------------

	/** Whether the chooser's substring filter would keep a file whose path is {@code path} for {@code pattern}. */
	private static boolean filters(String pattern, String path) {
		SearchPattern matcher = new SearchPattern(
				SearchPattern.DEFAULT_MATCH_RULES | SearchPattern.RULE_SUBSTRING_MATCH);
		matcher.setPattern(pattern);
		return FileEntityResolver.pathMatches(matcher, path);
	}

	@Test
	void substringMatchesAnyFragmentOfTheWorkspacePath() {
		String path = "/proj/src/Foo.java";
		assertTrue(filters("Foo", path), "a file-name fragment must match");
		assertTrue(filters("foo", path), "matching is case-insensitive");
		assertTrue(filters("*Foo*", path), "an explicit *...* wildcard must match");
		assertTrue(filters("Foo.java", path), "the full file name must match");
		assertTrue(filters("src", path), "a folder fragment must match");
		assertTrue(filters("src/Foo", path), "a folder-anchored path fragment must match");
		assertFalse(filters("xyz", path), "an absent fragment must not match");
	}
}
