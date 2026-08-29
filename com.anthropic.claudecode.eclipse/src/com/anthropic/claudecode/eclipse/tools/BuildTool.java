package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Clean and/or rebuild the workspace, or just the named projects — the tool form of
 * Project &gt; Clean… Uses only {@code org.eclipse.core.resources}, which is already a
 * hard {@code Require-Bundle}, so it needs no availability guard.
 *
 * <p>Always returns the resulting problem markers. A rebuild whose answer is "3 errors,
 * here they are" is the point of the tool; making the caller follow up with
 * {@code getDiagnostics} would just cost a round trip.
 *
 * <p>Auto-build is reported rather than inherited silently: Eclipse's own Clean dialog
 * rebuilds afterwards only when auto-build happens to be on, which makes a bare "clean"
 * mean two different things in two workspaces. Here the mode says what runs, and the
 * result says what the workspace setting was.
 *
 * <p>Refreshes from the filesystem first ({@code refresh}, default true) for the same
 * reason — a build is only as current as what Eclipse has seen. {@link RefreshTool} does
 * that step alone, and only that, for when a refresh is all you want.
 */
public class BuildTool implements McpTool {

    /** Cap on markers returned, so a badly broken workspace can't flood the response. */
    private static final int MAX_MARKERS = 200;

    @Override
    public String toolName() {
        return "build";
    }

    @Override
    public String description() {
        return "Clean and/or rebuild Eclipse projects and report the resulting compile errors. "
                + "Refreshes from the filesystem first by default, so files written outside the "
                + "IDE are picked up instead of silently building stale content. "
                + "Scope: the named projects, or the whole workspace when none are given. "
                + "Modes: clean-rebuild (default), clean, rebuild (full build), incremental. "
                + "Returns per-project error/warning counts plus the problem markers themselves.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject projects = new JsonObject();
        projects.addProperty("type", "array");
        projects.addProperty("description",
                "Project names to build. Omit or leave empty to build the whole workspace.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        projects.add("items", items);
        props.add("projects", projects);

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        mode.addProperty("description",
                "clean-rebuild (default) | clean | rebuild | incremental");
        JsonArray modes = new JsonArray();
        modes.add("clean-rebuild");
        modes.add("clean");
        modes.add("rebuild");
        modes.add("incremental");
        mode.add("enum", modes);
        props.add("mode", mode);

        JsonObject refresh = new JsonObject();
        refresh.addProperty("type", "boolean");
        refresh.addProperty("description",
                "Refresh from the filesystem before building (default true). Set false only when "
                        + "you know nothing has changed outside the IDE and want to skip the scan.");
        props.add("refresh", refresh);

        schema.add("properties", props);
        return schema;   // nothing is required — no args means "clean and rebuild everything"
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String mode = params.has("mode") && !params.get("mode").isJsonNull()
                    ? params.get("mode").getAsString().trim().toLowerCase()
                    : "clean-rebuild";
            if (!List.of("clean-rebuild", "clean", "rebuild", "incremental").contains(mode)) {
                return McpToolResult.error("Unknown mode: " + mode
                        + ". Expected clean-rebuild, clean, rebuild, or incremental.");
            }

            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            List<IProject> targets = new ArrayList<>();
            JsonArray requested = params.has("projects") && params.get("projects").isJsonArray()
                    ? params.getAsJsonArray("projects") : new JsonArray();

            boolean wholeWorkspace = requested.isEmpty();
            if (wholeWorkspace) {
                for (IProject p : workspace.getRoot().getProjects()) {
                    if (p.isAccessible()) targets.add(p);
                }
            } else {
                List<String> missing = new ArrayList<>();
                for (int i = 0; i < requested.size(); i++) {
                    String name = requested.get(i).getAsString();
                    IProject p = workspace.getRoot().getProject(name);
                    if (p == null || !p.exists()) { missing.add(name + " (no such project)"); continue; }
                    if (!p.isAccessible()) { missing.add(name + " (closed)"); continue; }
                    targets.add(p);
                }
                if (!missing.isEmpty()) {
                    return McpToolResult.error("Cannot build: " + String.join(", ", missing));
                }
            }
            if (targets.isEmpty()) {
                return McpToolResult.error("No accessible projects to build.");
            }

            boolean doRefresh = !params.has("refresh") || params.get("refresh").isJsonNull()
                    || params.get("refresh").getAsBoolean();

            long started = System.currentTimeMillis();

            // Refresh BEFORE cleaning, not after: Eclipse builds what it last saw, so a file
            // written from outside the IDE — or a whole new source file — is simply absent
            // from the build otherwise. That fails as a change that "didn't take" rather
            // than as an error, which is the expensive way to find out.
            if (doRefresh) {
                if (wholeWorkspace) {
                    workspace.getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                } else {
                    for (IProject p : targets) {
                        p.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                    }
                }
            }

            boolean doClean = mode.equals("clean") || mode.equals("clean-rebuild");
            boolean doBuild = !mode.equals("clean");
            int buildKind = mode.equals("incremental")
                    ? IncrementalProjectBuilder.INCREMENTAL_BUILD
                    : IncrementalProjectBuilder.FULL_BUILD;

            // Clean every target first, THEN build. Interleaving per project would let a
            // project compile against a sibling's stale output and then have it cleaned
            // out from under it — the classic "fix didn't take" symptom.
            if (doClean) {
                if (wholeWorkspace) {
                    workspace.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
                } else {
                    for (IProject p : targets) {
                        p.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
                    }
                }
            }
            if (doBuild) {
                if (wholeWorkspace) {
                    workspace.build(buildKind, new NullProgressMonitor());
                } else {
                    for (IProject p : targets) {
                        p.build(buildKind, new NullProgressMonitor());
                    }
                }
            }
            long elapsedMs = System.currentTimeMillis() - started;

            JsonObject result = new JsonObject();
            result.addProperty("mode", mode);
            result.addProperty("scope", wholeWorkspace ? "workspace" : "projects");
            result.addProperty("elapsedMs", elapsedMs);

            IWorkspaceDescription desc = workspace.getDescription();
            result.addProperty("autoBuildEnabled", desc != null && desc.isAutoBuilding());
            // Said out loud because a "clean" in the Eclipse UI silently rebuilds when
            // auto-build is on; here the mode alone decides, whatever the setting is.
            result.addProperty("refreshed", doRefresh);
            result.addProperty("cleaned", doClean);
            result.addProperty("built", doBuild);

            JsonArray perProject = new JsonArray();
            JsonArray problems = new JsonArray();
            int totalErrors = 0, totalWarnings = 0, emitted = 0;

            for (IProject p : targets) {
                IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                int errors = 0, warnings = 0;
                for (IMarker m : markers) {
                    int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    if (severity == IMarker.SEVERITY_ERROR) errors++;
                    else if (severity == IMarker.SEVERITY_WARNING) warnings++;

                    // Errors first and warnings only if there's room: a rebuild's answer is
                    // what broke, and warnings shouldn't crowd them out of the cap.
                    if (severity == IMarker.SEVERITY_ERROR && emitted < MAX_MARKERS) {
                        problems.add(toJson(m, p));
                        emitted++;
                    }
                }
                JsonObject pj = new JsonObject();
                pj.addProperty("project", p.getName());
                pj.addProperty("errors", errors);
                pj.addProperty("warnings", warnings);
                perProject.add(pj);
                totalErrors += errors;
                totalWarnings += warnings;
            }

            result.addProperty("errorCount", totalErrors);
            result.addProperty("warningCount", totalWarnings);
            result.add("projects", perProject);
            result.add("errors", problems);
            if (totalErrors > emitted) {
                result.addProperty("truncated", true);
                result.addProperty("note", (totalErrors - emitted) + " further errors not listed");
            }

            return McpToolResult.success(result);
        } catch (Exception e) {
            return McpToolResult.error("Build failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static JsonObject toJson(IMarker marker, IProject project) {
        JsonObject j = new JsonObject();
        j.addProperty("project", project.getName());
        IResource r = marker.getResource();
        if (r != null && r.getFullPath() != null) {
            j.addProperty("path", r.getFullPath().toString());
            if (r.getLocation() != null) j.addProperty("file", r.getLocation().toOSString());
        }
        j.addProperty("severity", "error");
        j.addProperty("message", marker.getAttribute(IMarker.MESSAGE, ""));
        j.addProperty("line", marker.getAttribute(IMarker.LINE_NUMBER, 0));
        return j;
    }
}
