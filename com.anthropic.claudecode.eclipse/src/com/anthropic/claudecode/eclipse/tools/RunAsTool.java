package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The tool form of right-click &gt; Run As on a project.
 *
 * <p>"Run As" is not a fixed list — it is whatever contributes to the
 * {@code org.eclipse.debug.ui.launchShortcuts} extension point, which is exactly what
 * populates that menu. So this reads the same registry the menu reads and invokes the
 * same {@link ILaunchShortcut}. "Eclipse Application" works because PDE contributes a
 * shortcut with that label, not because anything here knows what PDE is.
 *
 * <p>Consequences worth knowing:
 * <ul>
 * <li>No dependency on PDE, JDT, or any other launcher. {@code org.eclipse.debug.ui} is
 *     already a hard {@code Require-Bundle}, and the shortcut arrives through
 *     {@code createExecutableExtension} — never an import. Where PDE isn't installed,
 *     "Eclipse Application" is simply absent from the list and asking for it is a plain
 *     "no such option" rather than a {@code NoClassDefFoundError}.</li>
 * <li>Options are filtered by each shortcut's own {@code contextualLaunch} enablement
 *     expression — the same expression the menu uses to decide what to show. That is the
 *     project-type guard: PDE's "Eclipse Application" tests for
 *     {@code org.eclipse.pde.PluginNature}, so it is offered for a plugin project and
 *     refused for a Node or PHP one, while whatever those projects' own tooling
 *     contributes shows up in their place. Nothing here enumerates project types; the
 *     launchers describe themselves and this evaluates what they declare.</li>
 * <li>Asking for an option that doesn't apply is refused rather than attempted. Some
 *     launchers — PDE's included — will happily launch <em>something</em> from an
 *     unrelated selection rather than erroring, so "just try it and let the launcher
 *     complain" silently starts the wrong thing. {@code force} overrides if you want
 *     the launcher's own judgement instead.</li>
 * <li>Omitting {@code option} lists rather than launches — opening the menu, not
 *     clicking an item. Nothing starts without being named.</li>
 * <li>{@code option="auto"} is the exception, and the "just run it" path: it launches the
 *     only applicable choice, or asks the user to pick from an in-chat card when there are
 *     several. The card appears only when the answer is genuinely ambiguous — being shown a
 *     picker with one item on it is not a question, it is a speed bump. Note that on a
 *     plugin project whose only applicable option is "Eclipse Application", {@code auto}
 *     therefore starts a second IDE without confirming.</li>
 * </ul>
 */
public class RunAsTool implements McpTool {

    private static final String SHORTCUT_EXT_POINT = "org.eclipse.debug.ui.launchShortcuts";
    /** How long to watch for the ILaunch to appear after the shortcut returns. */
    private static final long LAUNCH_REGISTER_TIMEOUT_MS = 8000;
    /** Reserved {@code option} value meaning "work out which one, ask me only if unclear". */
    private static final String AUTO = "auto";

    /**
     * One thing the user could mean by "run it" — either a Run As shortcut or a saved launch
     * configuration attributed to the project. Exactly one of the two is non-null.
     */
    private record Choice(String label, String description,
            IConfigurationElement shortcut, ILaunchConfiguration config) {
    }

    @Override
    public String toolName() {
        return "runAs";
    }

    @Override
    public String description() {
        return "Run a project the way right-click > Run As does — including 'Eclipse Application', "
                + "'Java Application', 'JUnit Plug-in Test', or any other launcher installed in this "
                + "IDE, plus any saved launch configuration by name. "
                + "When the user says 'run it' without naming how, pass option='auto': it runs the "
                + "only applicable choice, or shows the user a picker card in the chat when there "
                + "are several — never ask them in prose which option they want. "
                + "Call without 'option' to list what is available without launching anything. "
                + "Note that an Eclipse Application launch starts a second, long-running IDE.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject project = new JsonObject();
        project.addProperty("type", "string");
        project.addProperty("description", "Project name, e.g. com.anthropic.claudecode.eclipse");
        props.add("project", project);

        JsonObject option = new JsonObject();
        option.addProperty("type", "string");
        option.addProperty("description",
                "Run As option label or id, e.g. 'Eclipse Application'. Use 'auto' to let the tool "
                        + "choose — it runs the only applicable option, or asks the user to pick "
                        + "via an in-chat card when there is more than one. Omit entirely to list "
                        + "the options instead of launching.");
        props.add("option", option);

        JsonObject config = new JsonObject();
        config.addProperty("type", "string");
        config.addProperty("description",
                "Name of an existing launch configuration to run instead of a Run As option. "
                        + "Takes precedence over 'option'.");
        props.add("config", config);

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        mode.addProperty("description", "run (default) or debug");
        JsonArray modes = new JsonArray();
        modes.add("run");
        modes.add("debug");
        mode.add("enum", modes);
        props.add("mode", mode);

        JsonObject force = new JsonObject();
        force.addProperty("type", "boolean");
        force.addProperty("description",
                "Run the option even when it does not apply to this project type (default false). "
                        + "Only for overriding a launcher whose enablement expression is wrong. "
                        + "Applies only when naming an option explicitly — it is ignored with "
                        + "option='auto', which never offers an inapplicable choice in the first "
                        + "place.");
        props.add("force", force);

        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description",
                "Seconds to wait for the launched process to exit before returning (default 0 — "
                        + "return as soon as it starts). Leave at 0 for an Eclipse Application, "
                        + "which is not meant to terminate.");
        props.add("waitSeconds", wait);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("project");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String projectName = str(params, "project");
            if (projectName == null) return McpToolResult.error("Missing required parameter: project");

            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return McpToolResult.error("No such project: " + projectName);
            }
            if (!project.isAccessible()) {
                return McpToolResult.error("Project is closed: " + projectName);
            }

            String modeStr = str(params, "mode");
            String launchMode = "debug".equalsIgnoreCase(modeStr)
                    ? ILaunchManager.DEBUG_MODE : ILaunchManager.RUN_MODE;

            String configName = str(params, "config");
            String option = str(params, "option");
            int waitSeconds = params.has("waitSeconds") && !params.get("waitSeconds").isJsonNull()
                    ? params.get("waitSeconds").getAsInt() : 0;
            boolean force = params.has("force") && !params.get("force").isJsonNull()
                    && params.get("force").getAsBoolean();

            if (configName == null && option == null) {
                return McpToolResult.success(listOptions(project, launchMode));
            }
            if (configName != null) {
                return launchNamedConfig(configName, launchMode, waitSeconds);
            }
            // Checked before the label match, or "auto" would be reported as a missing option.
            if (AUTO.equalsIgnoreCase(option)) {
                return resolveAndLaunch(project, launchMode, waitSeconds);
            }
            return launchShortcut(project, option, launchMode, force, waitSeconds);
        } catch (Exception e) {
            return McpToolResult.error("runAs failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ── Listing ─────────────────────────────────────────────────────────────────────

    private JsonObject listOptions(IProject project, String launchMode) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("project", project.getName());
        out.addProperty("mode", launchMode);

        JsonArray applicable = new JsonArray();
        JsonArray notApplicable = new JsonArray();
        for (IConfigurationElement el : shortcutsFor(launchMode)) {
            JsonObject j = new JsonObject();
            j.addProperty("option", label(el));
            j.addProperty("id", el.getAttribute("id"));
            if (applies(el, project)) applicable.add(j); else notApplicable.add(j);
        }
        // What the Run As menu would show for this project…
        out.add("runAsOptions", applicable);
        // …and what exists but declined this project, so a missing option is explainable
        // rather than just absent.
        out.add("notApplicable", notApplicable);

        JsonArray configs = new JsonArray();
        int attributed = 0;
        for (ILaunchConfiguration c : DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurations()) {
            JsonObject j = new JsonObject();
            j.addProperty("config", c.getName());
            j.addProperty("type", c.getType().getName());
            boolean mine = belongsTo(c, project);
            j.addProperty("forThisProject", mine);
            if (mine && c.supportsMode(launchMode)) attributed++;
            configs.add(j);
        }
        out.add("savedConfigurations", configs);

        // Steer the caller to the card instead of a prose question. Without this the list is
        // just text, and text is answered with text — which is exactly the "which one would
        // you like?" message this replaces.
        //
        // Counted from the two loops above rather than by calling choicesFor: that would run
        // every enablement expression a second time, and applies() sets allowPluginActivation,
        // so on a cold workbench the duplicate pass is real bundle-starting work.
        int choices = applicable.size() + attributed;
        if (choices > 1) {
            out.addProperty("next", "Do not ask the user in prose which option they want. If they "
                    + "asked to run this, call runAs again with option='auto' — that shows them a "
                    + "picker card and launches what they choose.");
        } else if (choices == 1) {
            out.addProperty("next", "Only one applicable choice. If they asked to run this, call "
                    + "runAs again with option='auto' to run it.");
        }
        return out;
    }

    // ── Choosing ────────────────────────────────────────────────────────────────────

    /**
     * Everything "run it" could reasonably mean for this project: the shortcuts the Run As
     * menu would show, plus the saved launch configurations belonging to it. Labels are made
     * unique here, because the card sends back the user's pick as a label string and two
     * identically-named entries would be indistinguishable on the way home.
     */
    private List<Choice> choicesFor(IProject project, String launchMode) throws Exception {
        List<Choice> out = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (IConfigurationElement el : shortcutsFor(launchMode)) {
            if (!applies(el, project)) continue;
            String lbl = unique(label(el), used);
            out.add(new Choice(lbl, "Run As option", el, null));
        }
        for (ILaunchConfiguration c : DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurations()) {
            if (!belongsTo(c, project) || !c.supportsMode(launchMode)) continue;
            String lbl = unique(c.getName(), used);
            out.add(new Choice(lbl, "Saved launch configuration (" + c.getType().getName() + ")",
                    null, c));
        }
        return out;
    }

    private static String unique(String label, List<String> used) {
        String candidate = label;
        int n = 2;
        while (used.contains(candidate)) candidate = label + " (" + n++ + ")";
        used.add(candidate);
        return candidate;
    }

    /**
     * Whether a saved configuration belongs to this project.
     *
     * <p>{@code getMappedResources} is the launcher-agnostic association — every launcher
     * type can set it, so a PDE, Gradle or Node configuration is attributed as readily as a
     * Java one. JDT's {@code PROJECT_ATTR} is the fallback for configurations old enough, or
     * hand-written enough, not to carry mapped resources.
     *
     * <p>A configuration that answers neither is left out of the choice set rather than
     * guessed at — it would otherwise be offered for every project in the workspace.
     */
    private static boolean belongsTo(ILaunchConfiguration c, IProject project) {
        try {
            IResource[] mapped = c.getMappedResources();
            if (mapped != null && mapped.length > 0) {
                // Mapped resources are authoritative when present: a configuration that says
                // which project it belongs to is not also asking to be second-guessed by a
                // stale PROJECT_ATTR, which would attribute it to a project it was moved away
                // from. Only an absent mapping falls through.
                for (IResource r : mapped) {
                    if (r != null && project.equals(r.getProject())) return true;
                }
                return false;
            }
        } catch (Exception ignored) {
            // Fall through to the attribute.
        }
        try {
            return project.getName()
                    .equals(c.getAttribute("org.eclipse.jdt.launching.PROJECT_ATTR", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The {@code option="auto"} path: run the single obvious choice, or ask.
     *
     * <p>The card is raised only at two or more choices. One choice is not a question, and
     * zero is an error rather than an empty card.
     */
    private McpToolResult resolveAndLaunch(IProject project, String launchMode, int waitSeconds)
            throws Exception {
        List<Choice> choices = choicesFor(project, launchMode);

        if (choices.isEmpty()) {
            List<String> declined = new ArrayList<>();
            for (IConfigurationElement el : shortcutsFor(launchMode)) declined.add(label(el));
            return McpToolResult.error("Nothing applicable to run for " + project.getName()
                    + " in mode " + launchMode + ". Every installed launcher declined this "
                    + "project type"
                    + (declined.isEmpty() ? "" : " (" + String.join(", ", declined) + ")")
                    + ", and it has no saved launch configuration. Name one explicitly with "
                    + "force=true to override.");
        }
        if (choices.size() == 1) {
            return launchChoice(choices.get(0), project, launchMode, waitSeconds);
        }

        // No picker reachable — a Terminal-view caller, or the GUI page not loaded. Falling
        // back to the list is right; claiming the user cancelled would not be, since no card
        // was ever shown. requestQuestion answers "[]" for both, hence the check up front.
        if (Display.getCurrent() != null || !ClaudeGuiView.canAskQuestion()) {
            JsonObject out = listOptions(project, launchMode);
            out.addProperty("note", choices.size() + " applicable choices and no in-chat picker "
                    + "available here. Ask the user which one, then call again with 'option' or "
                    + "'config' naming it.");
            return McpToolResult.success(out);
        }

        String answer = ask(project, launchMode, choices);
        if (answer == null || answer.isBlank()) {
            JsonObject out = new JsonObject();
            out.addProperty("launched", false);
            out.addProperty("project", project.getName());
            out.addProperty("cancelled", true);
            out.addProperty("note", "The user dismissed the picker without choosing. Do not "
                    + "launch anything and do not re-ask; wait for them.");
            return McpToolResult.success(out);
        }

        for (Choice c : choices) {
            if (c.label().equalsIgnoreCase(answer)) {
                return launchChoice(c, project, launchMode, waitSeconds);
            }
        }
        // Free text via the card's "Other" box: fall back to the ordinary named lookups, which
        // already report unknown names with the list of what does exist.
        for (ILaunchConfiguration c : DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurations()) {
            if (c.getName().equalsIgnoreCase(answer)) {
                return launchNamedConfig(answer, launchMode, waitSeconds);
            }
        }
        return launchShortcut(project, answer, launchMode, false, waitSeconds);
    }

    /** Raises the in-chat card and blocks until the user picks or dismisses it. */
    private String ask(IProject project, String launchMode, List<Choice> choices) {
        JsonArray options = new JsonArray();
        for (Choice c : choices) {
            JsonObject o = new JsonObject();
            o.addProperty("label", c.label());
            o.addProperty("description", c.description());
            options.add(o);
        }
        JsonObject question = new JsonObject();
        // "header" is the card's tab label — a short noun, not the sentence.
        question.addProperty("header", "Run As");
        question.addProperty("question", "How should " + project.getName() + " be "
                + (ILaunchManager.DEBUG_MODE.equals(launchMode) ? "debugged" : "run") + "?");
        question.addProperty("multiSelect", false);
        question.add("options", options);

        JsonArray questions = new JsonArray();
        questions.add(question);

        // Blocks on the user, as the card is the whole point. Matches askUserQuestion's wait
        // deliberately: a shorter timeout here would free this thread but leave a live-looking
        // card on screen whose clicks go nowhere, which is worse than waiting.
        String answersJson = ClaudeGuiView.requestQuestion(questions.toString());
        try {
            JsonArray arr = JsonParser.parseString(answersJson).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("answer")) return o.get("answer").getAsString().trim();
            }
        } catch (Exception ignored) {
            // Dismissed, or an answer shape we don't recognise — both mean "don't launch".
        }
        return null;
    }

    private McpToolResult launchChoice(Choice choice, IProject project, String launchMode,
            int waitSeconds) throws Exception {
        if (choice.config() != null) {
            return launchNamedConfig(choice.config().getName(), launchMode, waitSeconds);
        }
        // Already filtered by applies(), so no second enablement check here.
        return launchElement(choice.shortcut(), project, launchMode, waitSeconds);
    }

    private List<IConfigurationElement> shortcutsFor(String launchMode) {
        List<IConfigurationElement> out = new ArrayList<>();
        for (IConfigurationElement el : Platform.getExtensionRegistry()
                .getConfigurationElementsFor(SHORTCUT_EXT_POINT)) {
            // Declared as e.g. modes="run, debug" — spaces and all, hence contains().
            String modes = el.getAttribute("modes");
            if (modes == null || !modes.contains(launchMode)) continue;
            out.add(el);
        }
        return out;
    }

    /**
     * Whether this shortcut offers itself for this project — by evaluating the very
     * {@code contextualLaunch/enablement} expression the Run As menu evaluates. PDE's
     * Eclipse Application shortcut, for instance, declares
     * {@code <test property="org.eclipse.debug.ui.projectNature"
     * value="org.eclipse.pde.PluginNature"/>}, which is what keeps it off the menu for a
     * project that isn't a plugin.
     *
     * <p>A shortcut with no {@code contextualLaunch} block declares no opinion and is
     * treated as applicable, matching how the menu handles it. An expression that fails to
     * parse or evaluate also counts as applicable: a broken guard should not make a
     * working launcher unreachable.
     */
    private boolean applies(IConfigurationElement el, IProject project) {
        IConfigurationElement[] contextual = el.getChildren("contextualLaunch");
        if (contextual.length == 0) return true;
        IConfigurationElement[] enablement = contextual[0].getChildren("enablement");
        if (enablement.length == 0) return true;
        try {
            Expression expr = ExpressionConverter.getDefault().perform(enablement[0]);
            if (expr == null) return true;
            List<IProject> selection = List.of(project);
            EvaluationContext context = new EvaluationContext(null, selection);
            context.addVariable("selection", selection);
            // The property testers these expressions rely on (projectNature above) live in
            // bundles that may not have started yet; without this they evaluate to NOT_LOADED
            // and every option would look inapplicable on a cold workbench.
            context.setAllowPluginActivation(true);
            return expr.evaluate(context) == EvaluationResult.TRUE;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * The label as the menu shows it. {@code getAttribute} already resolves the {@code %key}
     * indirection against the contributing bundle's plugin.properties — PDE's shortcut is
     * declared as {@code label="%launcher.shortcut.label"}, which resolves to "Eclipse
     * Application". Mnemonics are stripped and the value trimmed so a caller can match what
     * they read on screen.
     */
    private static String label(IConfigurationElement el) {
        String l = el.getAttribute("label");
        return l == null ? "" : l.replace("&", "").trim();
    }

    // ── Launching ───────────────────────────────────────────────────────────────────

    private McpToolResult launchShortcut(IProject project, String option, String launchMode,
            boolean force, int waitSeconds) throws Exception {
        IConfigurationElement match = null;
        List<String> applicable = new ArrayList<>();
        for (IConfigurationElement el : shortcutsFor(launchMode)) {
            String lbl = label(el);
            if (applies(el, project)) applicable.add(lbl);
            if (lbl.equalsIgnoreCase(option) || option.equals(el.getAttribute("id"))) {
                match = el;
            }
        }
        if (match == null) {
            return McpToolResult.error("No Run As option '" + option + "' for mode " + launchMode
                    + ". Applicable for " + project.getName() + ": " + String.join(", ", applicable));
        }
        // The guard. Without it, a launcher handed a project it doesn't understand may launch
        // something anyway rather than refusing — PDE's will fall back to a default Eclipse
        // Application — which is worse than an error, because it looks like it worked.
        if (!force && !applies(match, project)) {
            return McpToolResult.error("'" + label(match) + "' does not apply to project "
                    + project.getName() + " — its launcher declines this project type. "
                    + "Applicable: " + (applicable.isEmpty() ? "(none)" : String.join(", ", applicable))
                    + ". Pass force=true to run it anyway.");
        }

        return launchElement(match, project, launchMode, waitSeconds);
    }

    /**
     * Invokes an already-resolved shortcut. Split out so the {@code auto} path can launch the
     * element it already holds instead of round-tripping through a label match — the labels
     * shown on the card are deduplicated, so re-matching by label there would be looking up a
     * string that no longer corresponds to any single shortcut.
     *
     * <p>Enablement is the caller's business: both paths check {@link #applies} before
     * getting here, one via {@code force}, the other by construction.
     */
    private McpToolResult launchElement(IConfigurationElement match, IProject project,
            String launchMode, int waitSeconds) throws Exception {
        Object ext = match.createExecutableExtension("class");
        if (!(ext instanceof ILaunchShortcut shortcut)) {
            return McpToolResult.error("Run As option '" + label(match)
                    + "' does not provide a launch shortcut.");
        }

        LaunchCollector collector = new LaunchCollector();
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        manager.addLaunchListener(collector);
        try {
            // The shortcut must run on the UI thread. Note what this does NOT protect
            // against: if the launcher opens a modal dialog — a type chooser when a project
            // has several main types and no saved config yet, "workspace in use", a missing
            // target platform — then launch() does not return until it is dismissed, so this
            // syncExec parks the calling (JNI worker) thread for exactly that long. The
            // await below only begins once launch() has returned. The IDE stays usable
            // throughout (a modal dialog runs its own event loop) but the tool call is
            // blocked, and report() says so when nothing registers.
            // UiHelper rather than Display directly, for its disposed-display guard during
            // shutdown — same as the rest of the tools.
            final Exception[] failure = new Exception[1];
            UiHelper.syncExec(() -> {
                try {
                    shortcut.launch(new StructuredSelection(project), launchMode);
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
            if (failure[0] != null) {
                return McpToolResult.error("Launcher '" + label(match) + "' failed: "
                        + failure[0].getMessage());
            }

            ILaunch launch = collector.await(LAUNCH_REGISTER_TIMEOUT_MS);
            return report(launch, label(match), project.getName(), launchMode, waitSeconds);
        } finally {
            manager.removeLaunchListener(collector);
        }
    }

    private McpToolResult launchNamedConfig(String configName, String launchMode, int waitSeconds)
            throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfiguration target = null;
        List<String> names = new ArrayList<>();
        for (ILaunchConfiguration c : manager.getLaunchConfigurations()) {
            names.add(c.getName());
            if (c.getName().equalsIgnoreCase(configName)) { target = c; break; }
        }
        if (target == null) {
            return McpToolResult.error("No launch configuration named '" + configName
                    + "'. Available: " + String.join(", ", names));
        }
        if (!target.supportsMode(launchMode)) {
            return McpToolResult.error("Configuration '" + target.getName()
                    + "' does not support mode " + launchMode);
        }
        ILaunch launch = target.launch(launchMode, new org.eclipse.core.runtime.NullProgressMonitor());
        return report(launch, target.getName(), null, launchMode, waitSeconds);
    }

    // ── Reporting ───────────────────────────────────────────────────────────────────

    private McpToolResult report(ILaunch launch, String what, String projectName, String launchMode,
            int waitSeconds) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("launched", launch != null);
        out.addProperty("option", what);
        if (projectName != null) out.addProperty("project", projectName);
        out.addProperty("mode", launchMode);

        if (launch == null) {
            // The shortcut returned without error but nothing registered inside the window.
            // Don't report this as a bare timeout: by far the most common cause is that the
            // launcher put a dialog up and it was cancelled, which is a decision, not a
            // failure. Name the possibilities in the order they actually occur so the result
            // points at what to look at instead of reading as "something went wrong".
            out.addProperty("note", "No launch registered within "
                    + (LAUNCH_REGISTER_TIMEOUT_MS / 1000) + "s. Most likely the launcher asked "
                    + "for input in the IDE and the dialog was cancelled — check Eclipse for a "
                    + "prompt still open, since this call blocks while one is up. Otherwise the "
                    + "launcher may hand off to a background job and still be starting.");
            return McpToolResult.success(out);
        }

        if (launch.getLaunchConfiguration() != null) {
            out.addProperty("configuration", launch.getLaunchConfiguration().getName());
        }

        if (waitSeconds > 0) {
            long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
            while (!launch.isTerminated() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        }
        out.addProperty("terminated", launch.isTerminated());

        JsonArray processes = new JsonArray();
        for (IProcess p : launch.getProcesses()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("label", p.getLabel());
            pj.addProperty("terminated", p.isTerminated());
            if (p.isTerminated()) {
                try { pj.addProperty("exitValue", p.getExitValue()); } catch (Exception ignored) {}
            }
            if (p.getStreamsProxy() != null) {
                String stdout = p.getStreamsProxy().getOutputStreamMonitor() == null ? ""
                        : p.getStreamsProxy().getOutputStreamMonitor().getContents();
                String stderr = p.getStreamsProxy().getErrorStreamMonitor() == null ? ""
                        : p.getStreamsProxy().getErrorStreamMonitor().getContents();
                // Whatever has accumulated so far; for a still-running IDE this is the startup
                // output, which is where a bad target platform shows up.
                if (!stdout.isBlank()) pj.addProperty("stdout", tail(stdout));
                if (!stderr.isBlank()) pj.addProperty("stderr", tail(stderr));
            }
            processes.add(pj);
        }
        out.add("processes", processes);
        return McpToolResult.success(out);
    }

    private static final int MAX_OUTPUT_CHARS = 8000;

    private static String tail(String s) {
        return s.length() <= MAX_OUTPUT_CHARS ? s
                : "…(truncated)…\n" + s.substring(s.length() - MAX_OUTPUT_CHARS);
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return null;
        String v = params.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }

    /** Catches the ILaunch the shortcut creates, since {@code launch()} returns void. */
    private static final class LaunchCollector implements ILaunchListener {
        private final List<ILaunch> launches = new CopyOnWriteArrayList<>();

        @Override public void launchAdded(ILaunch launch) { launches.add(launch); }
        @Override public void launchRemoved(ILaunch launch) { }
        @Override public void launchChanged(ILaunch launch) { }

        ILaunch await(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (launches.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            return launches.isEmpty() ? null : launches.get(launches.size() - 1);
        }
    }
}
