package com.anthropic.claudecode.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link SpinnerVerbs} to whatever {@code working.js} says.
 *
 * <p>The Claude Code view reads its gerunds straight out of {@code working.js}; the Terminal
 * can't — the CLI's spinner is fed through a settings file, so the same words have to exist
 * as Java constants. That duplication is only safe if drift is loud, which is this test's
 * whole job: the parsing of {@code working.js} lives here, where a mismatch fails the build,
 * rather than in production code where a reformat would break the Terminal silently.
 *
 * <p>It also asserts the two invariants the {@code VERB_SETS} comment claims: every
 * categorised word exists in the master list, and the categories are pairwise disjoint.
 *
 * <p>One thing it cannot check is the claim that {@code UNCLAIMED} equals the CLI's own
 * built-in list minus the deprecated six — that was verified against the installed binary
 * and would need one here to re-verify. If Anthropic changes its list, the replace branch of
 * {@link SpinnerVerbs#settingsJson} goes stale without anything failing.
 */
class SpinnerVerbsParityTest {

	@Test
	void unclaimedMatchesWorkingJs() throws IOException {
		String src = source();
		Set<String> categorised = new HashSet<>();
		for (String key : List.of("deprecated", "pack1", "pack2", "dank", "vibecoder")) {
			categorised.addAll(category(src, key));
		}
		List<String> expected = new ArrayList<>();
		for (String w : gerunds(src)) {
			if (!categorised.contains(w)) expected.add(w);
		}
		assertSameWords("UNCLAIMED", expected, SpinnerVerbs.UNCLAIMED);
	}

	@Test
	void categoriesMatchWorkingJs() throws IOException {
		String src = source();
		assertSameWords("deprecated", category(src, "deprecated"), SpinnerVerbs.DEPRECATED);
		assertSameWords("pack1", category(src, "pack1"), SpinnerVerbs.PACK_ONE);
		assertSameWords("pack2", category(src, "pack2"), SpinnerVerbs.PACK_TWO);
		assertSameWords("dank", category(src, "dank"), SpinnerVerbs.DANK);
		assertSameWords("vibecoder", category(src, "vibecoder"), SpinnerVerbs.VIBECODER);
	}

	/** A category naming a word the master list doesn't have would silently match nothing. */
	@Test
	void everyCategorisedWordIsInTheMasterList() throws IOException {
		String src = source();
		Set<String> master = new HashSet<>(gerunds(src));
		for (String key : List.of("deprecated", "pack1", "pack2", "dank", "vibecoder")) {
			for (String w : category(src, key)) {
				assertTrue(master.contains(w), key + " names '" + w + "', which is not in GERUNDS");
			}
		}
	}

	/** Disjointness is what makes a checkbox the sole owner of the words it names. */
	@Test
	void categoriesArePairwiseDisjoint() throws IOException {
		String src = source();
		Set<String> seen = new HashSet<>();
		for (String key : List.of("deprecated", "pack1", "pack2", "dank", "vibecoder")) {
			for (String w : category(src, key)) {
				assertTrue(seen.add(w), "'" + w + "' belongs to more than one category");
			}
		}
	}

	/** Order is free to differ; a duplicate is not, since it would double how often a word comes up. */
	private static void assertSameWords(String what, List<String> fromJs, List<String> fromJava) {
		assertEquals(new HashSet<>(fromJs), new HashSet<>(fromJava), what + " differs from working.js");
		assertEquals(fromJs.size(), fromJava.size(), what + " has a duplicated word");
		assertEquals(fromJs.size(), new LinkedHashSet<>(fromJs).size(), what + " is duplicated in working.js");
	}

	private static List<String> gerunds(String src) {
		return jsStrings(slice(src, "const GERUNDS = [", "];"));
	}

	private static List<String> category(String src, String key) {
		String sets = slice(src, "const VERB_SETS = {", "\n};");
		return jsStrings(slice(sets, key + ": [", "]"));
	}

	private static String slice(String src, String start, String end) {
		int s = src.indexOf(start);
		assertTrue(s >= 0, "working.js has no '" + start + "'");
		int e = src.indexOf(end, s + start.length());
		assertTrue(e > s, "working.js never closes '" + start + "'");
		return src.substring(s + start.length(), e);
	}

	/**
	 * Every single-quoted literal in {@code src}, in order. Deliberately dumb — it only has to
	 * cope with the word lists, whose sole escape is the apostrophe in Cappin'.
	 */
	private static List<String> jsStrings(String src) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while (i < src.length()) {
			if (src.charAt(i++) != '\'') continue;
			StringBuilder word = new StringBuilder();
			while (i < src.length()) {
				char c = src.charAt(i++);
				if (c == '\\' && i < src.length()) { word.append(src.charAt(i++)); continue; }
				if (c == '\'') break;
				word.append(c);
			}
			out.add(word.toString());
		}
		return out;
	}

	/** Line endings normalised so the "\n};" marker doesn't depend on how the file was checked out. */
	private static String source() throws IOException {
		return Files.readString(workingJs(), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/** Walks up from the working directory, so it resolves whether the runner starts in the bundle or the workspace. */
	private static Path workingJs() {
		Path start = Paths.get("").toAbsolutePath();
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			Path direct = dir.resolve("resources/claudegui/scripts/working.js");
			if (Files.isRegularFile(direct)) return direct;
			Path nested = dir.resolve("com.anthropic.claudecode.eclipse/resources/claudegui/scripts/working.js");
			if (Files.isRegularFile(nested)) return nested;
		}
		throw new IllegalStateException("working.js not found from " + start);
	}
}
