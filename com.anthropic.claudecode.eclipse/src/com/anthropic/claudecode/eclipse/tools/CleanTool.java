package com.anthropic.claudecode.eclipse.tools;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

/**
 * Clean projects, or the whole workspace, without rebuilding — the tool form of
 * Project &gt; Clean… with "build immediately" unticked.
 *
 * <p>Same scope rule as {@link BuildTool} and {@link RefreshTool}: name projects to clean
 * those, omit {@code projects} to clean everything open in the workspace.
 *
 * <p>Deliberately a thin front for {@code build} with {@code mode=clean} rather than a
 * second copy of the scope, refresh and marker logic — one implementation means "clean
 * project X" and "build project X" can never disagree about what X resolves to. It exists
 * as its own tool because clean is a thing you ask for by name, and having to know it is
 * spelled as a mode of something called "build" is a bad way to find it.
 *
 * <p>Note that a clean is not always the end of the story: when workspace auto-build is on,
 * Eclipse's builder will start rebuilding what was just cleaned on its own. The result
 * reports {@code autoBuildEnabled} so that isn't a surprise.
 */
public class CleanTool implements McpTool {

    private final BuildTool delegate = new BuildTool();

    @Override
    public String toolName() {
        return "clean";
    }

    @Override
    public String description() {
        return "Clean Eclipse projects — discard build output and problem markers — without "
                + "rebuilding afterwards. Scope: the named projects, or the whole workspace when "
                + "none are given. Use 'build' instead when you want a clean followed by a rebuild. "
                + "If workspace auto-build is enabled, Eclipse may start rebuilding on its own "
                + "right after; the result reports whether it is.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject projects = new JsonObject();
        projects.addProperty("type", "array");
        projects.addProperty("description",
                "Project names to clean. Omit or leave empty to clean the whole workspace.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        projects.add("items", items);
        props.add("projects", projects);

        JsonObject refresh = new JsonObject();
        refresh.addProperty("type", "boolean");
        refresh.addProperty("description",
                "Refresh from the filesystem first (default true). Matters mainly when auto-build "
                        + "is on, since the rebuild it triggers should see current files.");
        props.add("refresh", refresh);

        schema.add("properties", props);
        return schema;   // nothing is required — no args means "clean everything"
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        JsonObject forwarded = new JsonObject();
        forwarded.addProperty("mode", "clean");
        if (params != null) {
            if (params.has("projects") && params.get("projects").isJsonArray()) {
                forwarded.add("projects", params.getAsJsonArray("projects"));
            }
            if (params.has("refresh") && !params.get("refresh").isJsonNull()) {
                forwarded.add("refresh", params.get("refresh"));
            }
        }
        // Reject a stray mode rather than silently ignoring it: "clean with mode=rebuild"
        // is a caller that thinks it asked for something it did not get.
        if (params != null && params.has("mode") && !params.get("mode").isJsonNull()) {
            String mode = params.get("mode").getAsString().trim().toLowerCase();
            if (!mode.equals("clean")) {
                return McpToolResult.error("The clean tool only cleans; it takes no mode. "
                        + "Use the build tool with mode=" + mode + " instead.");
            }
        }
        return delegate.execute(forwarded);
    }
}
