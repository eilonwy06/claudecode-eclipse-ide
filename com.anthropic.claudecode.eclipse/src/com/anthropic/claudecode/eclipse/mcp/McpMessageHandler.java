package com.anthropic.claudecode.eclipse.mcp;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.anthropic.claudecode.eclipse.server.SseClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class McpMessageHandler {

    private final McpToolRegistry toolRegistry;
    private final HttpSseServer server; // Changed: was WebSocketServer

    public McpMessageHandler(McpToolRegistry toolRegistry, HttpSseServer server) {
        this.toolRegistry = toolRegistry;
        this.server = server;
    }

    public void handleMessage(SseClient client, String rawMessage) { // Changed: was WebSocketClient
        try {
            JsonObject message = JsonParser.parseString(rawMessage).getAsJsonObject();
            String jsonrpc = message.has("jsonrpc") ? message.get("jsonrpc").getAsString() : null;

            if (!"2.0".equals(jsonrpc)) {
                Activator.log("Ignoring non-JSON-RPC 2.0 message");
                return;
            }

            if (message.has("method")) {
                handleRequest(client, message);
            } else if (message.has("result") || message.has("error")) {
                handleResponse(message);
            }
        } catch (Exception e) {
            Activator.logError("Error processing message: " + rawMessage, e);
        }
    }

    private void handleRequest(SseClient client, JsonObject message) {
        String method = message.get("method").getAsString();
        var id = message.get("id");
        Activator.log("MCP request: method=" + method + " id=" + id);

        switch (method) {
            case "initialize" -> handleInitialize(client, id);
            case "initialized" -> { /* notification, no response needed */ }
            case "tools/list" -> handleToolsList(client, id);
            case "tools/call" -> handleToolCall(client, message, id);
            case "shutdown" -> handleShutdown(client, id);
            default -> {
                if (id != null) {
                    sendError(client, id, -32601, "Method not found: " + method);
                }
            }
        }
    }

    private void handleInitialize(SseClient client, com.google.gson.JsonElement id) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", Constants.MCP_VERSION);

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "claude-code-eclipse");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);

        sendResult(client, id, result);
        Activator.log("MCP initialized for client " + client.getId());
    }

    private void handleToolsList(SseClient client, com.google.gson.JsonElement id) {
        JsonObject result = new JsonObject();
        var toolDefs = toolRegistry.getToolDefinitions();
        result.add("tools", toolDefs);
        Activator.log("tools/list: returning " + toolDefs.size() + " tools");
        String responseJson = buildResultJson(id, result);
        Activator.log("tools/list response: " + responseJson);
        client.sendText(responseJson);
    }

    private void handleToolCall(SseClient client, JsonObject message, com.google.gson.JsonElement id) {
        JsonObject params = message.has("params") ? message.getAsJsonObject("params") : new JsonObject();
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject toolArgs = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        Activator.log("tools/call: " + toolName + " args=" + toolArgs); // Log actual params from Claude

        McpTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            sendError(client, id, -32602, "Unknown tool: " + toolName);
            return;
        }

        try {
            McpToolResult result = tool.execute(toolArgs);
            sendResult(client, id, result.toJson());
        } catch (Exception e) {
            Activator.logError("Tool execution error: " + toolName, e);
            McpToolResult errorResult = McpToolResult.error("Tool execution failed: " + e.getMessage());
            sendResult(client, id, errorResult.toJson());
        }
    }

    private void handleShutdown(SseClient client, com.google.gson.JsonElement id) {
        sendResult(client, id, new JsonObject());
        client.close();
    }

    private void handleResponse(JsonObject message) {
        if (message.has("id")) {
            String responseId = message.get("id").getAsString();
            DeferredResponseManager.complete(responseId, message);
        }
    }

    private void sendResult(SseClient client, com.google.gson.JsonElement id, JsonObject result) {
        String json = buildResultJson(id, result);
        Activator.log("SSE send: " + json.substring(0, Math.min(200, json.length())) + (json.length() > 200 ? "..." : ""));
        client.sendText(json);
    }

    private String buildResultJson(com.google.gson.JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response.toString();
    }

    private void sendError(SseClient client, com.google.gson.JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        client.sendText(response.toString());
    }
}
