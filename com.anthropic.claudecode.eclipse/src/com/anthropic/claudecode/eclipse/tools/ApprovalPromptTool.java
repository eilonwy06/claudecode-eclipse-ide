package com.anthropic.claudecode.eclipse.tools;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Claude's {@code --permission-prompt-tool}. When the chat runs in "Ask before
 * edits" mode, claude calls this for every tool that needs approval. We surface
 * an in-chat decision card in the Claude GUI and block until the user chooses,
 * then return the documented permission contract:
 *   allow -> {"behavior":"allow","updatedInput":<input>}
 *   deny  -> {"behavior":"deny","message":"..."}
 * (as a JSON string in the tool result text).
 */
public class ApprovalPromptTool implements McpTool {

    @Override
    public String toolName() {
        return "approvalPrompt";
    }

    @Override
    public String description() {
        return "Permission prompt: ask the user to approve a tool call. Returns "
             + "{\"behavior\":\"allow\",\"updatedInput\":<input>} or "
             + "{\"behavior\":\"deny\",\"message\":\"...\"} as a JSON string.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject tn = new JsonObject(); tn.addProperty("type", "string");
        props.add("tool_name", tn);
        JsonObject in = new JsonObject(); in.addProperty("type", "object");
        props.add("input", in);
        JsonObject tid = new JsonObject(); tid.addProperty("type", "string");
        props.add("tool_use_id", tid);
        schema.add("properties", props);
        var required = new com.google.gson.JsonArray();
        required.add("tool_name");
        required.add("input");
        schema.add("required", required);
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String toolName = (params.has("tool_name") && params.get("tool_name").isJsonPrimitive())
                ? params.get("tool_name").getAsString() : "tool";
        JsonElement input = params.has("input") ? params.get("input") : new JsonObject();

        // For edits, compute the would-be file content so the GUI can open a diff
        // preview while the decision card is shown.
        String[] proposal = proposedContentFor(toolName, input); // {filePath, content} or null
        String filePath = proposal != null ? proposal[0] : null;
        String content  = proposal != null ? proposal[1] : null;

        String decision = ClaudeGuiView.requestApproval(toolName, detailOf(input), filePath, content);

        JsonObject resp = new JsonObject();
        if (decision != null && decision.startsWith("allow")) {
            resp.addProperty("behavior", "allow");
            resp.add("updatedInput", input);
        } else {
            resp.addProperty("behavior", "deny");
            // A "deny" may carry the user's "do this instead" text appended after it.
            String message = "The user declined this action in Eclipse.";
            if (decision != null && decision.startsWith("deny") && decision.length() > 4) {
                message = decision.substring(4);
            }
            resp.addProperty("message", message);
        }
        return McpToolResult.success(resp.toString());
    }

    /** Pull a human-friendly detail (usually the target file or command) from the tool input. */
    private static String detailOf(JsonElement input) {
        if (input != null && input.isJsonObject()) {
            JsonObject o = input.getAsJsonObject();
            for (String k : new String[]{"file_path", "old_file_path", "path", "filePath", "command", "url"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
            }
        }
        return "";
    }

    /**
     * Computes the proposed full file content for an edit-style tool so a diff
     * preview can be shown. Returns {@code {filePath, content}} or {@code null}
     * when the tool isn't a file edit (Read, Bash, search, …).
     */
    private static String[] proposedContentFor(String toolName, JsonElement inputEl) {
        if (inputEl == null || !inputEl.isJsonObject()) return null;
        JsonObject in = inputEl.getAsJsonObject();
        String fp = str(in, "file_path");
        if (fp == null) return null;
        String tn = toolName == null ? "" : toolName.toLowerCase();
        try {
            if (tn.contains("write")) {
                String content = str(in, "content");
                if (content != null) return new String[]{fp, content};
            }
            java.nio.file.Path p = java.nio.file.Path.of(fp);
            String orig = java.nio.file.Files.exists(p) ? java.nio.file.Files.readString(p) : "";
            if (in.has("edits") && in.get("edits").isJsonArray()) {
                String cur = orig;
                for (JsonElement ee : in.getAsJsonArray("edits")) {
                    if (!ee.isJsonObject()) continue;
                    JsonObject e = ee.getAsJsonObject();
                    cur = applyEdit(cur, str(e, "old_string"), str(e, "new_string"), bool(e, "replace_all"));
                }
                return new String[]{fp, cur};
            }
            String oldS = str(in, "old_string"), newS = str(in, "new_string");
            if (oldS != null && newS != null) {
                return new String[]{fp, applyEdit(orig, oldS, newS, bool(in, "replace_all"))};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String applyEdit(String content, String oldS, String newS, boolean all) {
        if (oldS == null || newS == null || oldS.isEmpty()) return content;
        if (all) return content.replace(oldS, newS);
        int i = content.indexOf(oldS);
        return i < 0 ? content : content.substring(0, i) + newS + content.substring(i + oldS.length());
    }

    private static String str(JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsString() : null;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive()
                && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }
}
