package com.anthropic.claudecode.eclipse.tools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.MUILabel;
import org.eclipse.e4.ui.model.application.ui.SideValue;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimBar;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimElement;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimmedWindow;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBar;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBarElement;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBarSeparator;
import org.eclipse.e4.ui.model.application.ui.menu.MToolControl;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.ICoolBarManager;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarContributionItem;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.Perspective;
import org.eclipse.ui.internal.WorkbenchPage;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.WorkbenchWindow;
import org.eclipse.ui.internal.dialogs.cpd.CustomizePerspectiveDialog;
import org.eclipse.ui.internal.registry.ActionSetRegistry;
import org.eclipse.ui.internal.registry.IActionSetDescriptor;
import org.eclipse.ui.views.IViewDescriptor;
import org.eclipse.ui.wizards.IWizardCategory;
import org.eclipse.ui.wizards.IWizardDescriptor;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What Window &gt; Perspective &gt; Customize Perspective does, for the perspective on screen:
 * its four tabs are Tool Bar Visibility and Menu Visibility (items hidden in this
 * perspective), Action Set Availability (command groups on or off), and Shortcuts (what the
 * New, Open Perspective and Show View submenus offer) — and, for the window's main toolbar,
 * what Window &gt; Appearance &gt; Hide Toolbar does and every part of that toolbar besides.
 *
 * <p>The dialog keeps none of this in a file of its own. It sits on the perspective, in the
 * workbench's model, and is saved with the workbench like the dialog's own changes:
 * shortcuts as tags ({@code persp.newWizSC:<id>}, {@code persp.perspSC:<id>},
 * {@code persp.viewSC:<id>}), hidden items as one string of {@code <prefix><id>,} entries
 * ({@code persp.hideMenuSC:}, {@code persp.hideToolbarSC:}, {@code persp.hideActionSetSC:}).
 * This tool changes them through the same workbench calls the dialog makes when OK is
 * pressed, and names a menu or toolbar item by the same id the workbench checks
 * ({@link CustomizePerspectiveDialog#getIDFromIContributionItem}).
 *
 * <p>The main toolbar goes further than the dialog's Tool Bar Visibility tab: groups the
 * coolbar manager builds (action sets, the workbench's own) are hidden through the
 * perspective's hidden items button by button, which that manager applies each time it
 * refills them ({@code CoolBarToTrimManager.fill} checks each button, never a group's own
 * id, so a group is hidden by hiding all its buttons); anything else in the toolbar (the launch bar, a model-contributed toolbar) is hidden
 * on the window model's element itself, for the whole window and saved with it (the launch
 * bar's installer only re-adds the element when it is missing); and the whole toolbar goes as
 * Window &gt; Appearance &gt; Hide Toolbar does ({@code WorkbenchWindow.toggleToolbarVisibility}).
 *
 * <p>Those calls are the workbench's internal API (present from Eclipse 2022-12, workbench
 * 3.127, to today); in an Eclipse where they are missing the tool says so instead of failing
 * on a linkage error. The active perspective is reached through the page's non-public
 * {@code getActivePerspective()}, which the dialog is handed by the page itself.
 */
@SuppressWarnings("restriction") // org.eclipse.ui.internal.* — what the Customize Perspective dialog calls (no public equivalent)
public class EclipseCustomizePerspectiveTool implements McpTool {

    static final String NEW_WIZARD_TAG = "persp.newWizSC:";
    static final String PERSPECTIVE_TAG = "persp.perspSC:";
    static final String VIEW_TAG = "persp.viewSC:";
    static final String HIDDEN_MENU = "persp.hideMenuSC:";
    static final String HIDDEN_TOOLBAR = "persp.hideToolbarSC:";
    static final String HIDDEN_ACTION_SET = "persp.hideActionSetSC:";

    static final List<String> KINDS = List.of("newWizard", "perspective", "view", "actionSet", "menu", "toolbar", "mainToolbar");

    /** A toolbar entry changed through the perspective's hidden items (a group the coolbar
     *  manager builds, or an item in one), or on the window's model element itself. */
    static final String SCOPE_PERSPECTIVE = "perspective";
    static final String SCOPE_WINDOW = "window";

    @Override
    public String toolName() {
        return "eclipseCustomizePerspective";
    }

    @Override
    public String description() {
        return "Customize the Eclipse perspective on screen, as Window > Perspective > Customize "
                + "Perspective does: which items its menus and main toolbar show, which action sets "
                + "(command groups) are on, and what the File > New, Open Perspective and Show View "
                + "submenus offer as shortcuts. Shortcuts, action sets, menu items and the main "
                + "toolbar's action-set groups and their buttons change for the active perspective "
                + "only and are kept with it; the whole main toolbar, and toolbar entries listed "
                + "with scope 'window' (such as the launch bar), change for the whole window. "
                + "action='get' (default) reports what is customized now. "
                + "action='list' with 'kind' lists what can be shown or hidden, with each entry's "
                + "id and current state; narrow it with 'filter'. action='show' or 'hide' with "
                + "'kind' and 'ids' changes them. action='open' opens the Customize Perspective "
                + "dialog for the user. Kinds: newWizard, perspective, view (the Shortcuts tab), "
                + "actionSet, menu, toolbar, mainToolbar. kind='toolbar' covers every part of the "
                + "main toolbar, whichever plug-in put it there: its toolbar groups, the controls "
                + "in it (such as the launch bar) and the buttons inside each group; each entry "
                + "says whether it is kept per perspective or for the whole window. Hiding or "
                + "showing a group hides or shows every button in it. "
                + "kind='mainToolbar' with action 'hide' or 'show' (no ids) hides or shows the "
                + "whole main toolbar and the perspective switcher for the window, as Window > "
                + "Appearance > Hide Toolbar does.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "get (default), list, show, hide, or open");
        JsonArray actions = new JsonArray();
        for (String a : List.of("get", "list", "show", "hide", "open")) actions.add(a);
        action.add("enum", actions);
        props.add("action", action);

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        kind.addProperty("description", "What to list, show or hide: newWizard, perspective or view "
                + "(shortcuts in File > New, Open Perspective and Show View), actionSet (a command "
                + "group), menu (an item of the menu bar), toolbar (a group, control or button of "
                + "the main toolbar) or mainToolbar (the whole main toolbar). Required for 'list', "
                + "'show' and 'hide'.");
        JsonArray kinds = new JsonArray();
        KINDS.forEach(kinds::add);
        kind.add("enum", kinds);
        props.add("kind", kind);

        JsonObject ids = new JsonObject();
        ids.addProperty("type", "array");
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        ids.add("items", item);
        ids.addProperty("description", "For 'show' and 'hide': the ids, as action='list' gives them.");
        props.add("ids", ids);

        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("description", "For 'list': only entries whose id or name contains this text.");
        props.add("filter", filter);

        schema.add("properties", props);
        return schema;   // no args means "get"
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String action = str(params, "action");
            String kind = str(params, "kind");
            ClaudeCodeView.debug("[eclipseCustomizePerspective] action=" + action + " kind=" + kind);

            List<String> ids = idsOf(params);
            String wrong = argumentError(action, kind, ids);
            if (wrong != null) return McpToolResult.error(wrong);

            if (action == null || "get".equalsIgnoreCase(action)) return onUiThread(this::get);
            if ("open".equalsIgnoreCase(action)) return onUiThread(this::openDialog);
            String k = kindOf(kind);
            if ("list".equalsIgnoreCase(action)) {
                String filter = str(params, "filter");
                return onUiThread(() -> list(k, filter));
            }
            boolean show = "show".equalsIgnoreCase(action);
            return onUiThread(() -> change(k, ids, show));
        } catch (LinkageError e) {
            return notAvailable(e);
        } catch (Exception e) {
            return failed(e);
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────

    private McpToolResult get() throws Exception {
        WorkbenchPage page = page();
        if (page == null) return McpToolResult.error("No workbench window is open.");
        IPerspectiveDescriptor persp = page.getPerspective();
        if (persp == null) return McpToolResult.error("No perspective is open.");

        JsonObject out = perspectiveOf(page);
        JsonObject shortcuts = new JsonObject();
        shortcuts.add("newWizard", named("newWizard", page.getNewWizardShortcuts()));
        shortcuts.add("perspective", named("perspective", page.getPerspectiveShortcuts()));
        shortcuts.add("view", named("view", page.getShowViewShortcuts()));
        out.add("shortcuts", shortcuts);

        JsonArray on = new JsonArray();
        for (IActionSetDescriptor set : page.getActionSets()) on.add(entry(set.getId(), set.getLabel()));
        out.add("actionSetsOn", on);

        String hidden = page.getHiddenItems();
        JsonObject hiddenOut = new JsonObject();
        hiddenOut.add("menu", strings(hiddenIds(hidden, HIDDEN_MENU)));
        hiddenOut.add("toolbar", strings(hiddenIds(hidden, HIDDEN_TOOLBAR)));
        hiddenOut.add("actionSet", strings(hiddenIds(hidden, HIDDEN_ACTION_SET)));
        // Parts of the main toolbar hidden for the whole window (on the element itself).
        List<String> inWindow = new ArrayList<>();
        for (JsonObject e : toolbarEntries(page)) {
            if (SCOPE_WINDOW.equals(text(e, "scope")) && e.get("hidden").getAsBoolean()) inWindow.add(text(e, "id"));
        }
        hiddenOut.add("toolbarInWindow", strings(inWindow));
        out.add("hidden", hiddenOut);
        out.addProperty("mainToolbarVisible", ((WorkbenchWindow) page.getWorkbenchWindow()).getCoolBarVisible());
        return McpToolResult.success(out);
    }

    private McpToolResult list(String kind, String filter) throws Exception {
        WorkbenchPage page = page();
        if (page == null) return McpToolResult.error("No workbench window is open.");
        if (page.getPerspective() == null) return McpToolResult.error("No perspective is open.");

        String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        JsonArray listed = new JsonArray();
        for (JsonObject e : candidates(page, kind)) {
            if (needle != null && !contains(text(e, "id"), needle) && !contains(text(e, "name"), needle)
                    && !contains(text(e, "path"), needle)) {
                continue;
            }
            listed.add(e);
        }
        JsonObject out = perspectiveOf(page);
        out.addProperty("kind", kind);
        out.addProperty("count", listed.size());
        out.add("entries", listed);
        if ("toolbar".equals(kind) && listed.isEmpty() && needle == null) {
            out.addProperty("note", "The main toolbar listed no items in this window.");
        }
        ClaudeCodeView.debug("[eclipseCustomizePerspective] list " + kind + " filter=" + filter + " → " + listed.size());
        return McpToolResult.success(out);
    }

    private McpToolResult change(String kind, List<String> ids, boolean show) throws Exception {
        WorkbenchPage page = page();
        if (page == null) return McpToolResult.error("No workbench window is open.");
        if ("mainToolbar".equals(kind)) return setMainToolbar(page, show);
        if (page.getPerspective() == null) return McpToolResult.error("No perspective is open.");
        Perspective persp = activePerspective(page);
        if (persp == null) return McpToolResult.error("No perspective is open.");

        // Every id must be one this Eclipse knows (or, to show a hidden item again, one that is
        // hidden): a mistyped id would otherwise be stored and never match anything.
        Set<String> known = new LinkedHashSet<>();
        for (JsonObject e : candidates(page, kind)) known.add(text(e, "id"));
        String hiddenPrefix = hiddenPrefix(kind);
        if (show && hiddenPrefix != null) known.addAll(hiddenIds(page.getHiddenItems(), hiddenPrefix));
        List<String> unknown = new ArrayList<>();
        for (String id : ids) if (!known.contains(id)) unknown.add(id);
        if (!unknown.isEmpty()) {
            return McpToolResult.error("Not a " + kind + " id in this perspective: " + String.join(", ", unknown)
                    + ". Use action='list' with kind='" + kind + "' to find the ids.");
        }

        List<String> changed = new ArrayList<>();
        List<String> inWindow = new ArrayList<>();
        switch (kind) {
            case "newWizard", "perspective", "view" -> {
                String tag = shortcutTag(kind);
                String[] current = currentShortcuts(page, kind);
                List<String> next = mergedShortcuts(current, ids, show);
                for (String id : ids) if (Arrays.asList(current).contains(id) != show) changed.add(id);
                if (!changed.isEmpty()) page.setNewShortcuts(next, tag);
            }
            case "actionSet" -> {
                ActionSetRegistry registry = WorkbenchPlugin.getDefault().getActionSetRegistry();
                Set<String> on = new LinkedHashSet<>();
                for (IActionSetDescriptor set : page.getActionSets()) on.add(set.getId());
                List<IActionSetDescriptor> sets = new ArrayList<>();
                for (String id : ids) {
                    if (on.contains(id) == show) continue;
                    IActionSetDescriptor set = registry.findActionSet(id);
                    if (set != null) { sets.add(set); changed.add(id); }
                }
                IActionSetDescriptor[] array = sets.toArray(new IActionSetDescriptor[0]);
                // As the dialog does: turned on or off, and remembered as hidden while off.
                if (show) persp.turnOnActionSets(array); else persp.turnOffActionSets(array);
                for (String id : changed) setHidden(page, HIDDEN_ACTION_SET + id, !show);
            }
            case "toolbar" -> {
                // The buttons of the groups the coolbar manager builds are checked against the
                // perspective's hidden items each time it refills them, one by one: a group is
                // hidden or shown through all its buttons. Anything else (the launch bar, a
                // model toolbar) is hidden on the window's own element, which the coolbar
                // manager leaves alone.
                WorkbenchWindow window = (WorkbenchWindow) page.getWorkbenchWindow();
                java.util.Map<String, String> scopes = new java.util.HashMap<>();
                java.util.Map<String, List<String>> buttonsByGroup = new java.util.HashMap<>();
                for (JsonObject e : toolbarEntries(page)) {
                    String id = text(e, "id");
                    scopes.put(id, text(e, "scope"));
                    String group = text(e, "group");
                    if (group != null) buttonsByGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(id);
                    else if ("group".equals(text(e, "type")) && SCOPE_PERSPECTIVE.equals(text(e, "scope"))) {
                        buttonsByGroup.putIfAbsent(id, new ArrayList<>());
                    }
                }
                for (String id : ids) {
                    if (SCOPE_WINDOW.equals(scopes.get(id))) {
                        MUIElement element = findToolbarElement(window, id);
                        if (element != null && element.isVisible() != show) {
                            element.setVisible(show);
                            changed.add(id);
                            inWindow.add(id);
                        }
                        continue;
                    }
                    // A group's own id among the hidden items hides nothing; drop one left there.
                    if (buttonsByGroup.containsKey(id)) setHidden(page, HIDDEN_TOOLBAR + id, false);
                    boolean any = false;
                    for (String item : itemsToStore(id, buttonsByGroup)) {
                        any |= setHidden(page, HIDDEN_TOOLBAR + item, !show);
                    }
                    if (any) changed.add(id);
                }
            }
            default -> {   // menu
                for (String id : ids) {
                    if (setHidden(page, hiddenPrefix + id, !show)) changed.add(id);
                }
            }
        }
        if (!changed.isEmpty()) persp.updateActionBars();

        JsonObject out = perspectiveOf(page);
        out.addProperty("kind", kind);
        out.addProperty(show ? "shown" : "hidden", true);
        out.add("changed", strings(changed));
        if (!inWindow.isEmpty()) out.add("changedForWholeWindow", strings(inWindow));
        List<String> already = new ArrayList<>(ids);
        already.removeAll(changed);
        if (!already.isEmpty()) out.add(show ? "alreadyShown" : "alreadyHidden", strings(already));
        ClaudeCodeView.debug("[eclipseCustomizePerspective] " + (show ? "show " : "hide ") + kind + " " + changed);
        return McpToolResult.success(out);
    }

    /** Window &gt; Appearance &gt; Hide Toolbar / Show Toolbar: the main toolbar and the
     *  perspective switcher, for the whole window, through the same workbench call. */
    private McpToolResult setMainToolbar(WorkbenchPage page, boolean show) {
        WorkbenchWindow window = (WorkbenchWindow) page.getWorkbenchWindow();
        boolean changed = window.getCoolBarVisible() != show;
        if (changed) window.toggleToolbarVisibility();
        JsonObject out = new JsonObject();
        out.addProperty("kind", "mainToolbar");
        out.addProperty("mainToolbarVisible", window.getCoolBarVisible());
        out.addProperty("changed", changed);
        out.addProperty("note", "For the whole window: the main toolbar and the perspective switcher, "
                + "as Window > Appearance > Hide Toolbar.");
        ClaudeCodeView.debug("[eclipseCustomizePerspective] mainToolbar show=" + show + " changed=" + changed);
        return McpToolResult.success(out);
    }

    private McpToolResult openDialog() {
        WorkbenchPage page = page();
        if (page == null) return McpToolResult.error("No workbench window is open.");
        if (page.getPerspective() == null) return McpToolResult.error("No perspective is open.");
        // After the reply: the dialog is modal, and this call would otherwise wait on the user.
        page.getWorkbenchWindow().getShell().getDisplay().asyncExec(() -> {
            try {
                page.editActionSets();
            } catch (RuntimeException | LinkageError e) {
                ClaudeCodeView.debug("[eclipseCustomizePerspective] open failed: " + e);
            }
        });
        JsonObject out = perspectiveOf(page);
        out.addProperty("opened", true);
        out.addProperty("note", "The Customize Perspective dialog is open for the user.");
        return McpToolResult.success(out);
    }

    // ── What each kind can hold ──────────────────────────────────────────────────────

    private List<JsonObject> candidates(WorkbenchPage page, String kind) {
        IWorkbench wb = PlatformUI.getWorkbench();
        List<JsonObject> out = new ArrayList<>();
        switch (kind) {
            case "newWizard" -> {
                Set<String> on = Set.of(page.getNewWizardShortcuts());
                addWizards(wb.getNewWizardRegistry().getRootCategory(), "", on, out);
            }
            case "perspective" -> {
                Set<String> on = Set.of(page.getPerspectiveShortcuts());
                for (IPerspectiveDescriptor p : wb.getPerspectiveRegistry().getPerspectives()) {
                    out.add(withState(entry(p.getId(), p.getLabel()), "shortcut", on.contains(p.getId())));
                }
            }
            case "view" -> {
                Set<String> on = Set.of(page.getShowViewShortcuts());
                for (IViewDescriptor v : wb.getViewRegistry().getViews()) {
                    JsonObject e = entry(v.getId(), v.getLabel());
                    String[] path = v.getCategoryPath();
                    if (path != null && path.length > 0) e.addProperty("path", categoryLabel(wb, path));
                    out.add(withState(e, "shortcut", on.contains(v.getId())));
                }
            }
            case "actionSet" -> {
                Set<String> on = new LinkedHashSet<>();
                for (IActionSetDescriptor set : page.getActionSets()) on.add(set.getId());
                for (IActionSetDescriptor set : WorkbenchPlugin.getDefault().getActionSetRegistry().getActionSets()) {
                    JsonObject e = entry(set.getId(), set.getLabel());
                    if (set.getDescription() != null && !set.getDescription().isBlank()) {
                        e.addProperty("description", set.getDescription());
                    }
                    out.add(withState(e, "on", on.contains(set.getId())));
                }
            }
            case "menu" -> {
                Set<String> hidden = new LinkedHashSet<>(hiddenIds(page.getHiddenItems(), HIDDEN_MENU));
                WorkbenchWindow window = (WorkbenchWindow) page.getWorkbenchWindow();
                addItems(window.getMenuManager(), "", hidden, out, new LinkedHashSet<>());
            }
            case "toolbar" -> out.addAll(toolbarEntries(page));
            case "mainToolbar" -> {
                JsonObject e = entry("mainToolbar", "Main toolbar and perspective switcher");
                out.add(withState(e, "hidden", !((WorkbenchWindow) page.getWorkbenchWindow()).getCoolBarVisible()));
            }
            default -> { }
        }
        return out;
    }

    /** Every part of the window's main toolbar, in the order it is drawn: each toolbar group
     *  and control, and the buttons inside each group, with whether it is hidden and where
     *  that is kept ({@link #SCOPE_PERSPECTIVE} or {@link #SCOPE_WINDOW}). */
    private static List<JsonObject> toolbarEntries(WorkbenchPage page) {
        List<JsonObject> out = new ArrayList<>();
        WorkbenchWindow window = (WorkbenchWindow) page.getWorkbenchWindow();
        Set<String> hidden = new LinkedHashSet<>(hiddenIds(page.getHiddenItems(), HIDDEN_TOOLBAR));

        // The groups the coolbar manager builds (action sets and the workbench's own).
        java.util.Map<String, ToolBarContributionItem> groups = new java.util.LinkedHashMap<>();
        ICoolBarManager cool = window.getCoolBarManager2();
        if (cool != null) {
            for (IContributionItem item : cool.getItems()) {
                if (item instanceof ToolBarContributionItem tb && tb.getId() != null) groups.put(tb.getId(), tb);
            }
        }
        ActionSetRegistry sets = WorkbenchPlugin.getDefault().getActionSetRegistry();
        Set<String> seen = new LinkedHashSet<>();
        MTrimBar top = mainToolbarBar(window);
        List<MTrimElement> elements = top == null ? List.of() : top.getChildren();
        for (MTrimElement element : elements) {
            String id = element.getElementId();
            if (id == null || id.isBlank() || element instanceof MToolBarSeparator) continue;
            if (element.getTags().contains("toolbarSeparator") || element.getTags().contains("TrimStack")) continue;
            if (!seen.add(id)) continue;
            ToolBarContributionItem group = groups.get(id);
            String name;
            if (group != null) {
                IActionSetDescriptor set = sets.findActionSet(id);
                name = set != null ? set.getLabel() : labelOfElement(element);
            } else {
                name = labelOfElement(element);
            }
            JsonObject e = entry(id, name);
            e.addProperty("type", element instanceof MToolControl ? "control" : "group");
            e.addProperty("scope", group != null ? SCOPE_PERSPECTIVE : SCOPE_WINDOW);
            out.add(withState(e, "hidden", !element.isVisible()));
            String path = name != null ? cleanLabel(name) : id;

            if (group != null) {
                // The workbench hides a group's buttons one by one, never the group: it is
                // hidden when they all are.
                List<String> buttons = new ArrayList<>();
                int from = out.size();
                addItems(group.getToolBarManager(), path, hidden, out, seen);
                for (int i = from; i < out.size(); i++) {
                    out.get(i).addProperty("type", "button");
                    out.get(i).addProperty("scope", SCOPE_PERSPECTIVE);
                    out.get(i).addProperty("group", id);
                    buttons.add(text(out.get(i), "id"));
                }
                e.addProperty("hidden", groupHidden(id, buttons, hidden));
            } else if (element instanceof MToolBar toolbar) {
                for (MToolBarElement child : toolbar.getChildren()) {
                    String cid = child.getElementId();
                    if (cid == null || cid.isBlank() || child instanceof MToolBarSeparator || !seen.add(cid)) continue;
                    JsonObject c = entry(cid, labelOfElement(child));
                    c.addProperty("path", path);
                    c.addProperty("type", "button");
                    c.addProperty("scope", SCOPE_WINDOW);
                    out.add(withState(c, "hidden", !child.isVisible()));
                }
            }
        }
        return out;
    }

    /** The window's main toolbar: its top trim bar. */
    private static MTrimBar mainToolbarBar(WorkbenchWindow window) {
        MWindow model = window.getModel();
        if (!(model instanceof MTrimmedWindow trimmed)) return null;
        for (MTrimBar bar : trimmed.getTrimBars()) {
            if (bar.getSide() == SideValue.TOP) return bar;
        }
        return null;
    }

    /** The main toolbar's element with this id, or a button directly in one of its toolbars. */
    private static MUIElement findToolbarElement(WorkbenchWindow window, String id) {
        MTrimBar top = mainToolbarBar(window);
        if (top == null) return null;
        for (MTrimElement element : top.getChildren()) {
            if (id.equals(element.getElementId())) return element;
            if (element instanceof MToolBar toolbar) {
                for (MToolBarElement child : toolbar.getChildren()) {
                    if (id.equals(child.getElementId())) return child;
                }
            }
        }
        return null;
    }

    /** What a model element says on screen, where it says anything. */
    private static String labelOfElement(MUIElement element) {
        if (element instanceof MUILabel label) {
            for (String s : new String[] {label.getLocalizedLabel(), label.getLabel(),
                                          label.getLocalizedTooltip(), label.getTooltip()}) {
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private static void addWizards(IWizardCategory category, String path, Set<String> on, List<JsonObject> out) {
        if (category == null) return;
        for (IWizardDescriptor w : category.getWizards()) {
            JsonObject e = entry(w.getId(), w.getLabel());
            if (!path.isEmpty()) e.addProperty("path", path);
            out.add(withState(e, "shortcut", on.contains(w.getId())));
        }
        for (IWizardCategory sub : category.getCategories()) {
            addWizards(sub, path.isEmpty() ? sub.getLabel() : path + " > " + sub.getLabel(), on, out);
        }
    }

    /** Every item of a menu bar or toolbar under `manager`, each once, with the menus it is in. */
    private static void addItems(IContributionManager manager, String path, Set<String> hidden,
                                 List<JsonObject> out, Set<String> seen) {
        if (manager == null) return;
        for (IContributionItem item : manager.getItems()) {
            if (item == null || item.isSeparator() || item.isGroupMarker()) continue;
            String id = CustomizePerspectiveDialog.getIDFromIContributionItem(item);
            String label = cleanLabel(labelOf(item));
            if (id != null && !id.isBlank() && seen.add(id)) {
                JsonObject e = entry(id, label);
                if (!path.isEmpty()) e.addProperty("path", path);
                if (item instanceof IMenuManager) e.addProperty("submenu", true);
                out.add(withState(e, "hidden", hidden.contains(id)));
            }
            if (item instanceof IMenuManager sub) {
                String name = label != null ? label : id;
                addItems(sub, path.isEmpty() ? String.valueOf(name) : path + " > " + name, hidden, out, seen);
            } else if (item instanceof IToolBarManager tb) {
                addItems(tb, path, hidden, out, seen);
            }
        }
    }

    /** What an item says on screen, where the item can tell. */
    private static String labelOf(IContributionItem item) {
        if (item instanceof MenuManager m) return m.getMenuText();
        if (item instanceof ActionContributionItem a && a.getAction() != null) {
            String t = a.getAction().getText();
            return t != null && !t.isBlank() ? t : a.getAction().getToolTipText();
        }
        // The workbench's model-backed items (commands, handled items) keep their label on
        // the model element behind them; read it without naming the e4 model types.
        try {
            Method getModel = item.getClass().getMethod("getModel");
            Object model = getModel.invoke(item);
            if (model != null) {
                for (String getter : new String[] {"getLocalizedLabel", "getLabel", "getLocalizedTooltip", "getTooltip"}) {
                    try {
                        Object v = model.getClass().getMethod(getter).invoke(model);
                        if (v instanceof String s && !s.isBlank()) return s;
                    } catch (ReflectiveOperationException ignored) {
                        // not this one
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // an item without a model
        }
        return null;
    }

    // ── Pure helpers (tested) ────────────────────────────────────────────────────────

    /** The ids hidden under `prefix` in the page's hidden-items string ({@code <prefix><id>,…}). */
    static List<String> hiddenIds(String hiddenItems, String prefix) {
        List<String> out = new ArrayList<>();
        if (hiddenItems == null || hiddenItems.isEmpty()) return out;
        for (String entry : hiddenItems.split(",")) {
            if (entry.startsWith(prefix) && entry.length() > prefix.length()) {
                String id = entry.substring(prefix.length());
                if (!out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    /** The ids to store in the hidden items so the toolbar entry `id` is hidden or shown: a
     *  group's buttons (the workbench checks each button, never the group), a button itself. */
    static List<String> itemsToStore(String id, java.util.Map<String, List<String>> buttonsByGroup) {
        List<String> buttons = buttonsByGroup.get(id);
        return buttons != null ? buttons : List.of(id);
    }

    /** Whether the toolbar group is hidden on screen: when every button in it is (one with no
     *  buttons shows nothing). Its own id among the hidden items hides nothing. */
    static boolean groupHidden(String group, List<String> buttons, Set<String> hidden) {
        return hidden.containsAll(buttons);
    }

    /** The shortcut list after showing or hiding `ids`, in the order it had, new ones last. */
    static List<String> mergedShortcuts(String[] current, List<String> ids, boolean show) {
        LinkedHashSet<String> next = new LinkedHashSet<>(Arrays.asList(current == null ? new String[0] : current));
        if (show) next.addAll(ids); else next.removeAll(ids);
        return new ArrayList<>(next);
    }

    /** A menu text without its mnemonic marker and accelerator ("&amp;Open File...\tCtrl+O" → "Open File..."). */
    static String cleanLabel(String label) {
        if (label == null) return null;
        String s = label;
        int tab = s.indexOf('\t');
        if (tab >= 0) s = s.substring(0, tab);
        s = s.replace("&&", "\u0000").replace("&", "").replace("\u0000", "&").trim();
        return s.isEmpty() ? null : s;
    }

    /** What is wrong with the arguments, before anything is asked of the workbench; null when
     *  nothing is. */
    static String argumentError(String action, String kind, List<String> ids) {
        if (action == null || "get".equalsIgnoreCase(action) || "open".equalsIgnoreCase(action)) return null;
        String a = action.toLowerCase(Locale.ROOT);
        if (!a.equals("list") && !a.equals("show") && !a.equals("hide")) {
            return "Unknown action: " + action + ". Use get, list, show, hide or open.";
        }
        String k = kindOf(kind);
        if (k == null) {
            return "'kind' is required for action='" + a + "': one of " + String.join(", ", KINDS) + ".";
        }
        // The whole main toolbar is one thing: it takes no ids.
        if (!a.equals("list") && !k.equals("mainToolbar") && ids.isEmpty()) {
            return "'ids' is required for action='" + a + "'. Use action='list' with kind='" + k + "' to find them.";
        }
        return null;
    }

    /** The kind as given, matched without regard to case; null when it is none of them. */
    static String kindOf(String kind) {
        if (kind == null) return null;
        for (String k : KINDS) if (k.equalsIgnoreCase(kind.trim())) return k;
        return null;
    }

    /** `ids` as an array of strings, or a single string. */
    static List<String> idsOf(JsonObject params) {
        List<String> out = new ArrayList<>();
        JsonElement el = params.get("ids");
        if (el == null || el.isJsonNull()) return out;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e != null && e.isJsonPrimitive()) addId(out, e.getAsString());
            }
        } else if (el.isJsonPrimitive()) {
            addId(out, el.getAsString());
        }
        return out;
    }

    private static void addId(List<String> out, String id) {
        String t = id == null ? "" : id.trim();
        if (!t.isEmpty() && !out.contains(t)) out.add(t);
    }

    static String shortcutTag(String kind) {
        return switch (kind) {
            case "newWizard" -> NEW_WIZARD_TAG;
            case "perspective" -> PERSPECTIVE_TAG;
            case "view" -> VIEW_TAG;
            default -> null;
        };
    }

    static String hiddenPrefix(String kind) {
        return switch (kind) {
            case "menu" -> HIDDEN_MENU;
            case "toolbar" -> HIDDEN_TOOLBAR;
            case "actionSet" -> HIDDEN_ACTION_SET;
            default -> null;
        };
    }

    // ── Workbench helpers ────────────────────────────────────────────────────────────

    private static WorkbenchPage page() {
        IWorkbenchPage page = UiHelper.getActivePage();
        return page instanceof WorkbenchPage wp ? wp : null;
    }

    /** The page's own perspective object, the one the dialog is given: the page has no
     *  public getter for it. */
    private static Perspective activePerspective(WorkbenchPage page) throws ReflectiveOperationException {
        Method m = WorkbenchPage.class.getDeclaredMethod("getActivePerspective");
        m.setAccessible(true);
        return (Perspective) m.invoke(page);
    }

    private static String[] currentShortcuts(WorkbenchPage page, String kind) {
        return switch (kind) {
            case "newWizard" -> page.getNewWizardShortcuts();
            case "perspective" -> page.getPerspectiveShortcuts();
            default -> page.getShowViewShortcuts();
        };
    }

    /** Adds or removes one hidden item; whether anything changed. */
    private static boolean setHidden(WorkbenchPage page, String item, boolean hide) {
        boolean isHidden = page.getHiddenItems().contains(item + ",");
        if (isHidden == hide) return false;
        if (hide) page.addHiddenItems(item); else page.removeHiddenItems(item);
        return true;
    }

    private static JsonArray named(String kind, String[] ids) {
        IWorkbench wb = PlatformUI.getWorkbench();
        JsonArray out = new JsonArray();
        for (String id : ids) {
            String name = switch (kind) {
                case "newWizard" -> {
                    IWizardDescriptor w = wb.getNewWizardRegistry().findWizard(id);
                    yield w == null ? null : w.getLabel();
                }
                case "perspective" -> {
                    IPerspectiveDescriptor p = wb.getPerspectiveRegistry().findPerspectiveWithId(id);
                    yield p == null ? null : p.getLabel();
                }
                default -> {
                    IViewDescriptor v = wb.getViewRegistry().find(id);
                    yield v == null ? null : v.getLabel();
                }
            };
            out.add(entry(id, name));
        }
        return out;
    }

    /** The name of a view's category, as Show View groups it; its id when it has no name. */
    private static String categoryLabel(IWorkbench wb, String[] path) {
        String id = path[path.length - 1];
        for (org.eclipse.ui.views.IViewCategory c : wb.getViewRegistry().getCategories()) {
            if (c.getId().equals(id)) return c.getLabel();
        }
        return String.join(" > ", path);
    }

    private static JsonObject perspectiveOf(WorkbenchPage page) {
        JsonObject out = new JsonObject();
        IPerspectiveDescriptor p = page.getPerspective();
        if (p != null) {
            out.addProperty("perspective", p.getId());
            out.addProperty("perspectiveName", p.getLabel());
        }
        return out;
    }

    private static JsonObject entry(String id, String name) {
        JsonObject e = new JsonObject();
        e.addProperty("id", id);
        if (name != null) e.addProperty("name", cleanLabel(name));
        return e;
    }

    private static JsonObject withState(JsonObject e, String key, boolean value) {
        e.addProperty(key, value);
        return e;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static String text(JsonObject e, String key) {
        return e.has(key) && !e.get(key).isJsonNull() ? e.get(key).getAsString() : null;
    }

    private interface UiAction {
        McpToolResult run() throws Exception;
    }

    private static McpToolResult onUiThread(UiAction action) {
        McpToolResult result = UiHelper.syncCall(() -> {
            try {
                return action.run();
            } catch (LinkageError e) {
                return notAvailable(e);
            } catch (Exception e) {
                return failed(e);
            }
        });
        return result != null ? result : McpToolResult.error("The workbench is not available.");
    }

    private static McpToolResult notAvailable(Throwable e) {
        ClaudeCodeView.debug("[eclipseCustomizePerspective] not available: " + e);
        return McpToolResult.error("Customizing perspectives is not available in this Eclipse ("
                + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
    }

    private static McpToolResult failed(Exception e) {
        ClaudeCodeView.debug("[eclipseCustomizePerspective] failed: " + e);
        return McpToolResult.error("eclipseCustomizePerspective failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return null;
        String v = params.get(key).getAsString().trim();
        return v.isEmpty() ? null : v;
    }
}
