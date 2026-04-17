package com.anthropic.claudecode.eclipse.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.anthropic.claudecode.eclipse.tools.AcceptDiffTool;
import com.anthropic.claudecode.eclipse.tools.CheckDocumentDirtyTool;
import com.anthropic.claudecode.eclipse.tools.CloseAllDiffTabsTool;
import com.anthropic.claudecode.eclipse.tools.GetCurrentSelectionTool;
import com.anthropic.claudecode.eclipse.tools.GetDiagnosticsTool;
import com.anthropic.claudecode.eclipse.tools.GetLatestSelectionTool;
import com.anthropic.claudecode.eclipse.tools.GetOpenEditorsTool;
import com.anthropic.claudecode.eclipse.tools.GetWorkspaceFoldersTool;
import com.anthropic.claudecode.eclipse.tools.OpenDiffTool;
import com.anthropic.claudecode.eclipse.tools.OpenFileTool;
import com.anthropic.claudecode.eclipse.tools.RejectDiffTool;
import com.anthropic.claudecode.eclipse.tools.SaveDocumentTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry() {
        register(new OpenFileTool());
        register(new OpenDiffTool());
        register(new AcceptDiffTool());
        register(new RejectDiffTool());
        register(new GetCurrentSelectionTool());
        register(new GetLatestSelectionTool());
        register(new GetOpenEditorsTool());
        register(new GetWorkspaceFoldersTool());
        register(new CheckDocumentDirtyTool());
        register(new SaveDocumentTool());
        register(new GetDiagnosticsTool());
        register(new CloseAllDiffTabsTool());
    }

    private void register(McpTool tool) {
        tools.put(tool.toolName(), tool);
    }

    public McpTool getTool(String name) {
        return tools.get(name);
    }

    public JsonArray getToolDefinitions() {
        JsonArray defs = new JsonArray();
        for (McpTool tool : tools.values()) {
            JsonObject def = new JsonObject();
            def.addProperty("name", tool.toolName());
            def.addProperty("description", tool.description());
            def.add("inputSchema", tool.inputSchema());
            defs.add(def);
        }
        return defs;
    }

    public int getToolCount() {
        return tools.size();
    }
}
