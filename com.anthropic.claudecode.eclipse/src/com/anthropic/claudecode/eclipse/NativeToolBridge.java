package com.anthropic.claudecode.eclipse;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Implements {@link NativeCore.ToolCallback} by delegating every tool call
 * to the existing Java {@link McpToolRegistry}.
 *
 * This is the only place where the Rust layer re-enters Eclipse APIs.
 * All tool implementations remain unchanged; Rust just drives the dispatch.
 */
public class NativeToolBridge implements NativeCore.ToolCallback {

    private final McpToolRegistry toolRegistry;

    public NativeToolBridge(McpToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Called from a Rust worker thread (via JNI) for every MCP tools/call request.
     *
     * @param toolName  name of the tool, e.g. "openFile"
     * @param argsJson  JSON object string of the arguments
     * @return          JSON string matching {@code McpToolResult.toJson()}
     */
    /**
     * Sentinel tool name Rust uses to fetch the live tool list for {@code tools/list},
     * instead of the DLL carrying its own hardcoded copy of it. Registering a tool in
     * {@link McpToolRegistry} is now all that is needed to advertise it — no native
     * rebuild. Kept as a sentinel through the existing callback rather than a new
     * interface method so the DLL and the plugin jar stay version-independent; the
     * leading {@code $} cannot collide with a real {@link McpTool#toolName()}.
     */
    public static final String LIST_TOOLS_SENTINEL = "$listTools";

    @Override
    public String executeEclipseTool(String toolName, String argsJson) {
        if (LIST_TOOLS_SENTINEL.equals(toolName)) {
            return McpToolResult.success(toolRegistry.getToolDefinitions().toString())
                    .toJson().toString();
        }

        McpTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return McpToolResult.error("Unknown tool: " + toolName).toJson().toString();
        }

        try {
            JsonObject args = argsJson != null && !argsJson.isBlank()
                    ? JsonParser.parseString(argsJson).getAsJsonObject()
                    : new JsonObject();
            McpToolResult result = tool.execute(args);
            // null when UiHelper.syncCall() skips execution because the display is
            // disposed (e.g. Eclipse is shutting down while a tool call is in flight).
            if (result == null) {
                return McpToolResult.error("Tool returned no result.").toJson().toString();
            }
            return result.toJson().toString();
        } catch (Exception e) {
            Activator.logError("NativeToolBridge: error executing " + toolName, e);
            return McpToolResult.error("Tool execution failed: " + e.getMessage()).toJson().toString();
        }
    }
}
