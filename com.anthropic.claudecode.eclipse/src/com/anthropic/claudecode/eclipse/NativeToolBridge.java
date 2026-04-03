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
    @Override
    public String executeEclipseTool(String toolName, String argsJson) {
        McpTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return McpToolResult.error("Unknown tool: " + toolName).toJson().toString();
        }

        try {
            JsonObject args = argsJson != null && !argsJson.isBlank()
                    ? JsonParser.parseString(argsJson).getAsJsonObject()
                    : new JsonObject();
            McpToolResult result = tool.execute(args);
            return result.toJson().toString();
        } catch (Exception e) {
            Activator.logError("NativeToolBridge: error executing " + toolName, e);
            return McpToolResult.error("Tool execution failed: " + e.getMessage()).toJson().toString();
        }
    }
}
