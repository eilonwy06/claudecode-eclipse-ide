package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GetDiagnosticsTool implements McpTool {

    @Override
    public String toolName() {
        return "getDiagnostics";
    }

    @Override
    public String description() {
        return "Get Eclipse workspace diagnostics (errors, warnings, info markers) for a specific file or all open projects.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Optional file path to filter diagnostics");
        props.add("file_path", filePath);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        try {
            String filePath = EditorUtils.getString(params, "file_path", "filePath");
            JsonArray diagnostics = new JsonArray();

            if (filePath != null) {
                collectFileMarkers(filePath, diagnostics);
            } else {
                collectAllMarkers(diagnostics);
            }

            JsonObject result = new JsonObject();
            result.add("diagnostics", diagnostics);
            return McpToolResult.success(result);
        } catch (Exception e) {
            return McpToolResult.error("Failed to get diagnostics: " + e.getMessage());
        }
    }

    private void collectAllMarkers(JsonArray diagnostics) throws Exception {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen()) continue;
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            addMarkers(markers, diagnostics);
        }
    }

    private void collectFileMarkers(String filePath, JsonArray diagnostics) throws Exception {
        var path = new org.eclipse.core.runtime.Path(filePath);
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
        if (resource != null && resource.exists()) {
            IMarker[] markers = resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
            addMarkers(markers, diagnostics);
        }
    }

    private void addMarkers(IMarker[] markers, JsonArray diagnostics) throws Exception {
        for (IMarker marker : markers) {
            JsonObject diag = new JsonObject();

            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            diag.addProperty("severity", switch (severity) {
                case IMarker.SEVERITY_ERROR -> "error";
                case IMarker.SEVERITY_WARNING -> "warning";
                default -> "information";
            });

            diag.addProperty("message", marker.getAttribute(IMarker.MESSAGE, ""));
            diag.addProperty("line", marker.getAttribute(IMarker.LINE_NUMBER, 0));
            diag.addProperty("charStart", marker.getAttribute(IMarker.CHAR_START, 0));
            diag.addProperty("charEnd", marker.getAttribute(IMarker.CHAR_END, 0));
            diag.addProperty("source", marker.getType());

            IResource res = marker.getResource();
            if (res != null && res.getLocation() != null) {
                diag.addProperty("filePath", res.getLocation().toOSString());
            }

            diagnostics.add(diag);
        }
    }
}
