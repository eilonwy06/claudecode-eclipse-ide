package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GetWorkspaceFoldersTool implements McpTool {

    @Override
    public String toolName() {
        return "getWorkspaceFolders";
    }

    @Override
    public String description() {
        return "Get the list of workspace folders and open project paths in the Eclipse workspace.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            JsonArray folders = new JsonArray();

            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null) {
                    JsonObject folder = new JsonObject();
                    folder.addProperty("uri", project.getLocationURI().toString());
                    folder.addProperty("name", project.getName());
                    folder.addProperty("path", project.getLocation().toOSString());
                    folders.add(folder);
                }
            }

            JsonObject result = new JsonObject();
            result.add("folders", folders);
            return McpToolResult.success(result);
        } catch (Exception e) {
            return McpToolResult.error("Failed to get workspace folders: " + e.getMessage());
        }
    }
}
