package com.anthropic.claudecode.eclipse.tools;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Refresh projects, or the whole workspace, from the filesystem — the tool form of F5.
 *
 * <p>Eclipse caches resource state and does not watch the filesystem exhaustively, so
 * files written from outside the IDE are invisible to it until a refresh. That gap is not
 * cosmetic: a build run over an unrefreshed workspace compiles what Eclipse last saw, and
 * new source files can be missing from it entirely, which surfaces later as a change that
 * "didn't take" rather than as an error.
 *
 * <p>Uses only {@code org.eclipse.core.resources}, already a hard {@code Require-Bundle},
 * so no availability guard is needed.
 */
public class RefreshTool implements McpTool {

    @Override
    public String toolName() {
        return "refresh";
    }

    @Override
    public String description() {
        return "Refresh Eclipse projects from the filesystem (the equivalent of F5), so files "
                + "created or changed outside the IDE become visible to it. Scope: the named "
                + "projects, or the whole workspace when none are given. Run this before 'build' "
                + "after writing files outside Eclipse, or the build compiles what Eclipse last saw.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject projects = new JsonObject();
        projects.addProperty("type", "array");
        projects.addProperty("description",
                "Project names to refresh. Omit or leave empty to refresh the whole workspace.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        projects.add("items", items);
        props.add("projects", projects);

        schema.add("properties", props);
        return schema;   // no args means "refresh everything"
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            JsonArray requested = params.has("projects") && params.get("projects").isJsonArray()
                    ? params.getAsJsonArray("projects") : new JsonArray();
            boolean wholeWorkspace = requested.isEmpty();

            long started = System.currentTimeMillis();
            JsonArray refreshed = new JsonArray();

            if (wholeWorkspace) {
                // Refreshing the root covers every open project in one pass, including any
                // project created on disk since the last refresh.
                workspace.getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                for (IProject p : workspace.getRoot().getProjects()) {
                    if (p.isAccessible()) refreshed.add(p.getName());
                }
            } else {
                List<String> missing = new ArrayList<>();
                List<IProject> targets = new ArrayList<>();
                for (int i = 0; i < requested.size(); i++) {
                    String name = requested.get(i).getAsString();
                    IProject p = workspace.getRoot().getProject(name);
                    if (p == null || !p.exists()) { missing.add(name + " (no such project)"); continue; }
                    if (!p.isAccessible()) { missing.add(name + " (closed)"); continue; }
                    targets.add(p);
                }
                if (!missing.isEmpty()) {
                    return McpToolResult.error("Cannot refresh: " + String.join(", ", missing));
                }
                for (IProject p : targets) {
                    p.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                    refreshed.add(p.getName());
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("scope", wholeWorkspace ? "workspace" : "projects");
            result.addProperty("refreshedCount", refreshed.size());
            result.add("refreshed", refreshed);
            result.addProperty("elapsedMs", System.currentTimeMillis() - started);
            return McpToolResult.success(result);
        } catch (Exception e) {
            return McpToolResult.error("Refresh failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }
}
