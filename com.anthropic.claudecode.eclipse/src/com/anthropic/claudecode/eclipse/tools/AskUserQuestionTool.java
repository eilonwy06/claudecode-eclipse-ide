package com.anthropic.claudecode.eclipse.tools;

import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Replacement for claude's built-in AskUserQuestion (which auto-dismisses in
 * headless {@code -p} mode). Renders the in-chat multiple-choice card in the
 * Claude GUI and blocks until the user submits, then returns their answers as
 * text for claude to read.
 */
public class AskUserQuestionTool implements McpTool {

    @Override
    public String toolName() {
        return "askUserQuestion";
    }

    @Override
    public String description() {
        return "Ask the user one or more multiple-choice questions and wait for their "
             + "selection. Use this instead of the built-in AskUserQuestion. Returns the "
             + "user's chosen answers as text.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject questions = new JsonObject();
        questions.addProperty("type", "array");
        questions.addProperty("description", "The questions to ask");
        props.add("questions", questions);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("questions");
        schema.add("required", required);
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        JsonElement q = params.has("questions") ? params.get("questions") : new JsonArray();
        String questionsJson = q.toString();

        String answersJson = ClaudeGuiView.requestQuestion(questionsJson);

        try {
            JsonArray arr = JsonParser.parseString(answersJson).getAsJsonArray();
            if (arr.size() == 0) {
                return McpToolResult.success("The user dismissed the question without answering.");
            }
            StringBuilder sb = new StringBuilder("The user answered:\n");
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String question = o.has("question") ? o.get("question").getAsString() : "";
                String answer = o.has("answer") ? o.get("answer").getAsString() : "";
                if (!question.isEmpty()) sb.append("Q: ").append(question).append('\n');
                sb.append("A: ").append(answer).append('\n');
            }
            return McpToolResult.success(sb.toString().trim());
        } catch (Exception ex) {
            return McpToolResult.success("The user dismissed the question without answering.");
        }
    }
}
