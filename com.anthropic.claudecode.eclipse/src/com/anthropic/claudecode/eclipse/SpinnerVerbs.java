package com.anthropic.claudecode.eclipse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The working-indicator vocabulary, shared by both views.
 *
 * <p>The Claude Code (GUI) view draws its own spinner and owns the master list in
 * {@code resources/claudegui/scripts/working.js}. The Terminal view embeds the CLI's TUI,
 * which draws its own spinner from its own built-in list; the only way in is the
 * {@code spinnerVerbs} key of a settings file. Hence the mirror below: {@code ClaudeCliView}
 * writes it into the {@code --settings} file it already injects for the status line, so one
 * set of preference checkboxes governs the verbs in both views.
 *
 * <p>{@code SpinnerVerbsParityTest} parses {@code working.js} and fails the build if the
 * mirror drifts, so the duplication can't rot silently. Production code never parses that
 * file: a reformat there would otherwise break the Terminal at runtime with nothing to see.
 */
public final class SpinnerVerbs {

    /**
     * Every gerund in {@code working.js} belonging to no category — the words that rotate
     * whichever boxes are ticked.
     *
     * <p>Verified against the installed CLI (2.1.251): this is exactly its 186 built-in verbs
     * minus the six in {@link #DEPRECATED}. That equality is what lets the replace branch of
     * {@link #settingsJson} drop those six without this file having to carry (and then age
     * alongside) Anthropic's own list under its own name.
     */
    public static final List<String> UNCLAIMED = List.of(
            "Architecting", "Baking", "Beaming", "Beboppin'", "Befuddling", "Billowing",
            "Blanching", "Bloviating", "Boogieing", "Boondoggling", "Booping", "Bootstrapping",
            "Brewing", "Bunning", "Burrowing", "Calculating", "Canoodling", "Caramelizing",
            "Cascading", "Catapulting", "Cerebrating", "Channeling", "Channelling",
            "Choreographing", "Churning", "Clauding", "Coalescing", "Cogitating", "Combobulating",
            "Composing", "Computing", "Concocting", "Considering", "Contemplating", "Cooking",
            "Crafting", "Creating", "Crunching", "Crystallizing", "Cultivating", "Deciphering",
            "Deliberating", "Determining", "Dilly-dallying", "Discombobulating", "Doodling",
            "Drizzling", "Ebbing", "Elucidating", "Embellishing", "Enchanting", "Envisioning",
            "Fermenting", "Fiddle-faddling", "Finagling", "Flamb\u00e9ing", "Flibbertigibbeting",
            "Flowing", "Flummoxing", "Fluttering", "Forging", "Forming", "Frolicking", "Frosting",
            "Gallivanting", "Galloping", "Garnishing", "Generating", "Gesticulating", "Germinating",
            "Gitifying", "Grooving", "Gusting", "Harmonizing", "Hashing", "Hatching", "Herding",
            "Honking", "Hullaballooing", "Hyperspacing", "Ideating", "Imagining", "Improvising",
            "Incubating", "Inferring", "Infusing", "Ionizing", "Jitterbugging", "Julienning",
            "Kneading", "Leavening", "Levitating", "Lollygagging", "Manifesting", "Marinating",
            "Meandering", "Metamorphosing", "Misting", "Moonwalking", "Moseying", "Mulling",
            "Mustering", "Musing", "Nebulizing", "Nesting", "Newspapering", "Noodling",
            "Nucleating", "Orbiting", "Orchestrating", "Osmosing", "Perambulating", "Percolating",
            "Perusing", "Philosophising", "Photosynthesizing", "Pollinating", "Pondering",
            "Pontificating", "Pouncing", "Precipitating", "Prestidigitating", "Proofing",
            "Propagating", "Puttering", "Puzzling", "Quantumizing", "Razzle-dazzling",
            "Razzmatazzing", "Recombobulating", "Reticulating", "Roosting", "Ruminating",
            "Saut\u00e9ing", "Scampering", "Schlepping", "Scurrying", "Seasoning", "Shenaniganing",
            "Shimmying", "Simmering", "Skedaddling", "Sketching", "Slithering", "Smooshing",
            "Sock-hopping", "Spelunking", "Spinning", "Sprouting", "Stewing", "Sublimating",
            "Swirling", "Swooping", "Symbioting", "Synthesizing", "Tempering", "Thinking",
            "Thundering", "Tinkering", "Tomfoolering", "Topsy-turvying", "Transfiguring",
            "Transmuting", "Twisting", "Undulating", "Unfurling", "Unravelling", "Vibing",
            "Waddling", "Wandering", "Warping", "Whatchamacalliting", "Whirlpooling", "Whirring",
            "Whisking", "Wibbling", "Working", "Wrangling", "Zesting", "Zigzagging"
    );

    /**
     * Still live in the CLI's built-in list despite the name, which makes these the only
     * words a checkbox can ask us to <em>remove</em> rather than add. Never sent: when they
     * are wanted, we leave the CLI's own list alone and say nothing.
     */
    public static final List<String> DEPRECATED = List.of(
            "Accomplishing", "Actioning", "Actualizing", "Doing", "Effecting", "Processing"
    );

    /** Expansion pack one. No CLI built-ins here, so ticking the box is pure addition. */
    public static final List<String> PACK_ONE = List.of(
            "Absquatulating", "Abstracting", "Brainstorming", "Chock-a-blocking",
            "Compartmentalizing", "Confabulating", "Coruscating", "Crocheting", "Eclipsing",
            "Effervescing", "Encapsulating", "Evaporating", "Felting", "Fishmongering",
            "Flabbergasting", "Fluxing", "Gerrymandering", "Gibbergaberring", "Gravitating",
            "Hypnotizing", "Hyperenthusiasticating", "Inheriting", "Intellectualizing",
            "Journaling", "Meowing", "Perplexing", "Polymorphing", "Ratiocinating",
            "Skitterscattering", "Skylarking", "Sous-viding", "Susurrating", "Transmogrifying",
            "Vulcanizing", "Woolgathering", "Xanthating", "Xeriscaping", "Xylographing", "Zooming"
    );

    /** Expansion pack two. No CLI built-ins. */
    public static final List<String> PACK_TWO = List.of(
            "Fathoming", "Triangulating", "Picturing", "Figuring", "Weighing", "Honing", "Sifting",
            "Untangling", "Reckoning", "Caramelizing onions", "Basking"
    );

    /** The dank set. No CLI built-ins. */
    public static final List<String> DANK = List.of(
            "Brainrotting", "Wewertsing", "Fanum taxing", "Gooning", "Malding", "Mewing", "Rizzing",
            "Rizzmastering", "Skibidirizzing", "Zambasdting", "Aura farming", "Looksmaxxing",
            "Sigmaing", "Glazing", "Bussing", "Cappin'", "Gyatting", "Edging", "Poggering"
    );

    /** The vibecoder set. Not a CLI built-in. */
    public static final List<String> VIBECODER = List.of(
            "Vibecoding"
    );

    /** The {@code spinnerVerbs} object as it stands in the user's own settings.json. */
    public static final class UserVerbs {
        /** {@code "append"}, {@code "replace"}, or {@code ""} when the user set neither. */
        public final String mode;
        /** The user's words, blanks dropped; empty when they configured none. */
        public final List<String> verbs;

        UserVerbs(String mode, List<String> verbs) {
            this.mode = mode;
            this.verbs = verbs;
        }
    }

    /** What {@link #readUserVerbs} returns when there is nothing to read. */
    private static final UserVerbs NONE = new UserVerbs("", List.of());

    private SpinnerVerbs() {
    }

    /** ~/.claude/settings.json — the user scope, the same file the advisor model lives in. */
    private static Path userSettingsPath() {
        return Paths.get(System.getProperty("user.home"), ".claude", "settings.json");
    }

    /**
     * Reads the user's own {@code spinnerVerbs}.
     *
     * <p>User scope only, deliberately. The CLI takes the highest-precedence
     * {@code spinnerVerbs} object wholesale rather than unioning the scopes, so "fold theirs
     * in" means picking one file, not merging three; and spinner verbs are a personal
     * setting nobody commits to a project. Reading only the user scope also keeps this free
     * of any dependency on the launch directory.
     *
     * <p>Anything unreadable or malformed yields {@link #NONE} rather than a partial read —
     * the same instinct as the advisor-model writer, which never touches a file it couldn't
     * parse.
     */
    public static UserVerbs readUserVerbs() {
        try {
            Path p = userSettingsPath();
            if (!Files.exists(p)) return NONE;
            JsonElement root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return NONE;
            JsonElement sv = root.getAsJsonObject().get("spinnerVerbs");
            if (sv == null || !sv.isJsonObject()) return NONE;
            JsonObject o = sv.getAsJsonObject();
            String mode = (o.has("mode") && o.get("mode").isJsonPrimitive())
                    ? o.get("mode").getAsString() : "";
            List<String> verbs = new ArrayList<>();
            JsonElement arr = o.get("verbs");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement e : arr.getAsJsonArray()) {
                    if (e == null || !e.isJsonPrimitive()) continue;
                    String w = e.getAsString().trim();
                    if (!w.isEmpty()) verbs.add(w);
                }
            }
            return new UserVerbs(mode, verbs);
        } catch (Exception e) {
            return NONE;
        }
    }

    /**
     * The user's own verbs for the Claude Code view's rotation, or empty when "Use custom
     * spinner verbs" is off.
     *
     * <p>This view is the only place that checkbox can act. The Terminal's spinner is the
     * CLI's, and the CLI unions {@code verbs} across settings scopes, so the user's words are
     * in that rotation whatever we write — see {@link #settingsJson}.
     *
     * <p>Their {@code mode} is ignored here on purpose: {@code replace} means "instead of the
     * CLI's built-ins", a statement about a list this view doesn't use. In the GUI the custom
     * words are always additive.
     */
    public static List<String> customVerbs(IPreferenceStore prefs) {
        if (prefs == null || !prefs.getBoolean(Constants.PREF_SPINNER_CUSTOM)) return List.of();
        return readUserVerbs().verbs;
    }

    /** Appends the words of every enabled optional category. */
    private static void addEnabledPacks(Collection<String> out, IPreferenceStore prefs) {
        if (prefs.getBoolean(Constants.PREF_SPINNER_PACK_ONE)) out.addAll(PACK_ONE);
        if (prefs.getBoolean(Constants.PREF_SPINNER_PACK_TWO)) out.addAll(PACK_TWO);
        if (prefs.getBoolean(Constants.PREF_SPINNER_DANK)) out.addAll(DANK);
        if (prefs.getBoolean(Constants.PREF_SPINNER_VIBECODER)) out.addAll(VIBECODER);
    }

    /**
     * Builds the {@code spinnerVerbs} object for the injected {@code --settings} file, in the
     * CLI's own schema: {@code {"mode":"append"|"replace","verbs":[...]}}.
     *
     * <p>Three branches, in precedence order:
     *
     * <ol>
     * <li><b>The user's own mode is {@code replace}</b> — they asked for their list
     *     <em>instead of</em> the built-ins. Say {@code replace} too, and add only our enabled
     *     packs: their words reach the CLI through the merge either way (see below), so the
     *     result is their list plus our packs and none of the built-ins they excluded.
     *     Answering with {@code append} would silently turn "only my verbs" into "everything,
     *     plus mine", since a scalar from our file outranks theirs.</li>
     * <li><b>Deprecated verbs are wanted (the default)</b> — append, which leaves the CLI
     *     owning its own list, so verbs Anthropic adds in a later release appear without a
     *     plugin update. Append can only add, which is why the third branch exists.</li>
     * <li><b>Deprecated verbs are not wanted</b> — the one subtractive case. Those six are CLI
     *     built-ins, so the only way to take them out of rotation is to replace the list
     *     wholesale with {@link #UNCLAIMED} (the built-ins minus exactly those six) plus
     *     whatever else is enabled. The cost is that this branch pins the built-in half of
     *     the vocabulary to whatever {@code working.js} last captured.</li>
     * </ol>
     *
     * <p><b>We never send the user's own verbs, and we cannot withhold them.</b> The
     * settings cascade does not overwrite an array from a lower scope — its merge customizer
     * concatenates them:
     *
     * <pre>if (Array.isArray(e) &amp;&amp; Array.isArray(t)) { if (r === "fallbackModel") return t;
     *                                             return te([...e, ...t]); }</pre>
     *
     * So {@code verbs} arrives at the CLI as the union of every scope's array while
     * {@code mode}, a scalar, is ours. Listing the user's words here would add nothing (they
     * are already in the union) and risk listing them twice; leaving them out cannot take
     * them away either. {@link Constants#PREF_SPINNER_CUSTOM} therefore governs the Claude
     * Code view alone — see {@link #customVerbs} — and the Terminal always shows whatever
     * the user configured, exactly as it would outside the IDE.
     *
     * <p>Their {@code mode} is read whatever that checkbox says, for the same reason: their
     * verbs are coming regardless, so overriding a {@code replace} they asked for would only
     * hand them the built-ins back on top.
     *
     * <p>Always returns an object, never {@code null} — the categories always have something
     * to say, even if it is an empty append.
     *
     * <p>A {@link LinkedHashSet} carries the words: a category could otherwise contribute the
     * same word twice and rotate it twice as often.
     */
    public static JsonObject settingsJson(IPreferenceStore prefs) {
        UserVerbs user = readUserVerbs();
        boolean userReplaces = "replace".equals(user.mode) && !user.verbs.isEmpty();

        LinkedHashSet<String> verbs = new LinkedHashSet<>();
        String mode;
        if (userReplaces) {
            mode = "replace";
            addEnabledPacks(verbs, prefs);
        } else if (prefs.getBoolean(Constants.PREF_SPINNER_DEPRECATED)) {
            mode = "append";
            addEnabledPacks(verbs, prefs);
        } else {
            mode = "replace";
            verbs.addAll(UNCLAIMED);
            addEnabledPacks(verbs, prefs);
        }

        JsonObject out = new JsonObject();
        out.addProperty("mode", mode);
        JsonArray arr = new JsonArray();
        for (String w : verbs) arr.add(w);
        out.add("verbs", arr);
        return out;
    }
}
