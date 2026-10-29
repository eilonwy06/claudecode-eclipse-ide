package com.anthropic.claudecode.eclipse.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;
import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;
import com.anthropic.claudecode.eclipse.ui.TerminalOnlyUi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The Claude Code view's part of {@link ClaudeCodeEclipseTool}: its conversation tabs — opening
 * and closing them, sending a prompt or a slash command into one, switching Remote Control on or
 * off for one — each tab's model, effort, thinking and permission mode, and the saved
 * conversations a tab can be opened on; and what the view offers on a conversation: reading
 * it, stopping its turn, forking, rewinding or deleting at a message, bookmarks, the archive,
 * a conversation's name, the usage figures and the conversation as text.
 *
 * <p>The tabs and their settings live in the view's page, so an action on them is carried out
 * there ({@code scripts/claudetool.js}) by the same code paths the composer's own controls
 * use; this class only checks the action, passes its parameters across and turns the page's
 * reply into a tool result. The saved conversations are on disk, and are listed from here.
 */
class ClaudeCodeViewModule implements ClaudeCodeEclipseTool.Module {

    private static final List<String> ACTIONS = List.of("listTabs", "newTab", "configureTab", "sendPrompt", "runCommand", "remoteControl", "closeTab", "listHistory", "openSession",
            "readTab", "stopTab", "forkMessage", "rewindCode", "deleteMessage", "listBookmarks", "setBookmark",
            "archiveSession", "renameSession", "usage", "exportConversation", "selectTab", "answerQuestion",
            "answerAdvisor", "dismissAdvisor", "closeFolder");

    /** The actions on a saved conversation, which is checked here before the page is asked. */
    private static final List<String> ON_A_SESSION = List.of("openSession", "archiveSession", "renameSession");

    /** What an action may send to the page; anything else in the call stays behind. */
    private static final List<String> PARAMS = List.of("tabId", "folder", "model", "effort", "thinking", "mode", "prompt", "command", "enabled", "remoteControl",
            "messageId", "replyId", "restoreCode", "preview", "archived", "limit", "answers", "advisor");

    /** How long an answer the page has to wait for is waited for here, and how often the page
     *  is asked whether it has come. The page gives up on the CLI first, and says so. */
    private static final long WAIT_MS = 120_000;
    private static final long POLL_MS = 200;

    /** The most of an exported conversation that goes into a result; the rest needs 'file'. */
    private static final int MAX_EXPORT_CHARS = 100_000;

    /** How many conversations 'listHistory' shows when no 'limit' says, and the most there are
     *  to show: the core lists a folder's 100 most recent. */
    private static final int LISTED = 30;
    private static final int MOST_LISTED = 100;

    /** What a conversation id may be. It becomes a file name. */
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    /** What Claude Code wraps around a message that is not the user's words: the editor's
     *  context, and the commands' own bookkeeping. The core takes most of these out of a
     *  title and the history panel the rest (history.js stripMeta); the same ones go here. */
    private static final Pattern IDE_BLOCK = Pattern.compile("<(ide_[a-z_]*)\\b[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IDE_EMPTY = Pattern.compile("<ide_[a-z_]*\\b[^>]*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_BLOCK = Pattern.compile("<(local-command-caveat|command-message|command-args"
            + "|local-command-stdout|command-stdout|command-contents|system-reminder)>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMAND_NAME = Pattern.compile("<command-name>(.*?)</command-name>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String UNTITLED = "(untitled)";

    /** The saved conversations of a folder. Asked without the view's page: scanning them can
     *  take a moment, and the page answers on the UI thread. */
    interface History {

        /** The folder meant when a call names none: the one in front in the view, or the
         *  workspace folder when the view is not open. */
        String frontFolder();

        /** A folder's conversations as the history panel lists them, newest first: a JSON
         *  array of {@code {sessionId, display, timestamp}}. */
        String list(String folder);

        /** Whether the folder has a conversation with this id, listed or too old to be. */
        boolean has(String folder, String sessionId);
    }

    private static final History VIEW_HISTORY = new History() {
        @Override
        public String frontFolder() {
            return ClaudeGuiView.frontFolder();
        }

        @Override
        public String list(String folder) {
            return ClaudeGuiView.sessionListOf(folder);
        }

        @Override
        public boolean has(String folder, String sessionId) {
            return ClaudeGuiView.hasSession(folder, sessionId);
        }
    };

    /** Asks the page: request JSON in, reply JSON out, null when there is no page to ask. */
    private final Function<String, String> page;

    /** Whether the view is switched off altogether ("Exclusively use terminal"). */
    private final BooleanSupplier viewOff;

    private final History history;

    ClaudeCodeViewModule() {
        this(ClaudeCodeViewModule::askView, TerminalOnlyUi::isOn, VIEW_HISTORY);
    }

    ClaudeCodeViewModule(Function<String, String> page) {
        this(page, () -> false);
    }

    ClaudeCodeViewModule(Function<String, String> page, BooleanSupplier viewOff) {
        this(page, viewOff, VIEW_HISTORY);
    }

    ClaudeCodeViewModule(Function<String, String> page, BooleanSupplier viewOff, History history) {
        this.page = page;
        this.viewOff = viewOff;
        this.history = history;
    }

    @Override
    public String name() {
        return "claudeCodeView";
    }

    @Override
    public String description() {
        return "the Claude Code chat view and its conversation tabs. action='listTabs' shows every "
                + "tab with its id, title, folder ('root'), model, effort, thinking, permission mode and Remote Control "
                + "state (with its claude.ai address once on), and the "
                + "models, efforts and modes a tab can be given, and the slash commands of the view. "
                + "action='newTab' opens a new "
                + "conversation tab and brings it to the front, on the defaults unless 'model', "
                + "'effort', 'thinking' or 'mode' say otherwise. It opens under the folder tab in front, "
                + "or under 'folder': a folder tab that is already open, or a folder the user has "
                + "trusted before, which gets a folder tab of its own. With 'prompt' it also sends that "
                + "as the tab's first message, and with 'remoteControl' true the tab comes up "
                + "reachable from the user's other devices. action='configureTab' changes "
                + "those settings on the tab with 'tabId', or on the tab in front when it is left "
                + "out; settings left out keep their value, and a running conversation takes the "
                + "change at once. action='sendPrompt' sends 'prompt' as the user's message in the "
                + "tab with 'tabId' and returns at once, without waiting for the reply; a tab "
                + "already working queues it, and 'listTabs' shows 'streaming' while a tab works. "
                + "action='runCommand' runs the slash command in 'command' in the tab with 'tabId'. "
                + "/compact, /clear, /context, /model, /remote-control and /help are carried out for "
                + "that tab and answered in the result; /clear replaces that tab's conversation with "
                + "a new one, which on the tab the call came from ends this conversation, and on a "
                + "tab under Remote Control cuts the user's other devices off from it. /advisor opens "
                + "the advisor's card in that tab, which has to be the tab in front with no card up, "
                + "and answers with its choices and the current one. /rewind, "
                + "/resume and /mcp open something in the view that waits for the user "
                + "there, and are refused. With anything after it, /context, /remote-control, "
                + "/help, /rewind, /resume or /mcp is passed to Claude as typed, as the view's "
                + "composer passes it. Any other command (Claude's own, a custom command, a "
                + "skill) is passed to Claude in that tab, and the call returns at once without "
                + "what it prints. "
                + "action='remoteControl' switches Remote Control on or off ('enabled') for the tab "
                + "with 'tabId'; it connects in the background, so 'listTabs' shows 'connecting' "
                + "and then 'on', and switching it off for the tab the user is talking through "
                + "from another device cuts them off from it. "
                + "action='closeTab' closes the tab with 'tabId', stopping whatever it is doing. "
                + "action='listHistory' lists the saved conversations of the folder tab in front, or "
                + "of 'folder', newest first, each with its id and title; 'query' keeps those whose "
                + "title contains it and 'limit' says how many to show. They are the ones on this "
                + "machine, not the ones on claude.ai. action='openSession' opens the conversation "
                + "with 'sessionId' in a new tab under that folder, on the settings it was last "
                + "used with, or brings its tab to the front when it is already open. "
                + "action='readTab' returns the conversation the tab with 'tabId' shows, or the tab "
                + "in front: the last 'limit' of the user's messages and Claude's replies, each "
                + "with the 'messageId' or 'replyId' other actions take, and whether Claude is "
                + "still working or the tab waits on a card ('waitingOnCard': question, approval or "
                + "advisor), with a question card's questions and options and the advisor card's "
                + "choices, and the requests of Claude's waiting behind that card for their turn "
                + "('cardsWaiting', in order: approval or question; 'listTabs' shows it too). Read it to "
                + "see the answer to a prompt that was sent. "
                + "action='stopTab' stops what Claude is doing in the tab with 'tabId', like the "
                + "Stop button, and leaves the tab open. "
                + "'sendPrompt' and 'newTab' with a 'prompt' also take 'files', which go with the "
                + "message as attached files. "
                + "action='forkMessage' opens the conversation as it was before the user's message "
                + "'messageId' in a new tab, and answers with that message's text; with "
                + "'restoreCode' true the files Claude edited are first put back as they were then. "
                + "action='rewindCode' puts those files back and leaves the conversation alone; "
                + "with 'preview' true it only lists what would change. Either overwrites what is "
                + "in those files now. action='deleteMessage' removes the user's message "
                + "'messageId' from the conversation's saved history for good, which cannot be "
                + "undone. These three work on a tab under the folder tab in front. "
                + "action='listBookmarks' lists the bookmarked replies of a tab, and "
                + "action='setBookmark' bookmarks the reply 'replyId' or, with 'enabled' false, "
                + "takes its bookmark away. "
                + "action='archiveSession' puts the saved conversation 'sessionId' into the archive "
                + "('archived' true), closing its tab when it has one, or takes it back out. "
                + "action='renameSession' gives the saved conversation 'sessionId' the name "
                + "'title'; it has to be one of the folder tab in front. "
                + "action='usage' returns the account's usage figures as Claude Code reports them. "
                + "action='exportConversation' returns a tab's whole conversation as plain text, or "
                + "writes it to 'file'. "
                + "action='selectTab' brings the tab with 'tabId' to the front of the view, and its "
                + "folder tab with it. action='answerQuestion' answers the question card the tab with "
                + "'tabId' waits on, as the user would: 'answers' has one entry per question, in "
                + "order, each an option's label or words of its own, and for a question that "
                + "takes several options their labels joined by ', '. 'readTab' shows the "
                + "questions and their options. action='answerAdvisor' picks 'advisor' on the "
                + "advisor's card the tab with 'tabId' waits on, as a click on that choice does: one "
                + "of the choices 'readTab' shows, by its label, or 'No advisor'. The choice is Claude "
                + "Code's own advisor setting, which applies to every conversation and workspace on "
                + "this machine and to the Claude Terminal. action='dismissAdvisor' closes that card "
                + "and changes nothing, as Esc does. Both need that tab in front. A request of "
                + "Claude's that came while the card was up shows its own card once it is gone. An "
                + "approval card (a tool asking to run or to edit) is the user's to answer, and is "
                + "refused. action='closeFolder' closes the folder tab "
                + "'folder' with its conversations; it is refused for the only folder open, and "
                + "while one of its conversations is working or waiting on a card. "
                + "A call cannot tell which tab it was made from, so take 'tabId' from 'listTabs' "
                + "or 'newTab'; closing the tab the call came from ends this conversation.";
    }

    @Override
    public void describeParams(JsonObject props) {
        props.add("tabId", param("string", "claudeCodeView configureTab/sendPrompt/runCommand/remoteControl/closeTab/"
                + "readTab/stopTab/forkMessage/rewindCode/deleteMessage/listBookmarks/setBookmark/usage/"
                + "exportConversation/selectTab/answerQuestion/answerAdvisor/dismissAdvisor: the tab, as shown by 'listTabs', e.g. 'tab2'. Left out, "
                + "'configureTab', 'readTab', 'listBookmarks', 'usage' and 'exportConversation' take the "
                + "tab in front; the others require it."));
        props.add("folder", param("string", "claudeCodeView newTab/listHistory/openSession/archiveSession/closeFolder: the full "
                + "path of the folder the conversation runs in, such as a 'root' from 'listTabs'. Left "
                + "out, the folder tab in front; 'closeFolder' requires it."));
        props.add("answers", ToolSupport.strings("claudeCodeView answerQuestion: one answer per question of "
                + "the card, in order: an option's label, words of its own, or for a question that takes "
                + "several options their labels joined by ', '."));
        props.add("advisor", param("string", "claudeCodeView answerAdvisor: the choice to pick on the "
                + "advisor's card, by its label as 'readTab' shows it, or 'No advisor'."));
        props.add("sessionId", param("string", "claudeCodeView openSession/archiveSession/renameSession: "
                + "the conversation, as 'listHistory' shows it."));
        props.add("query", param("string", "claudeCodeView listHistory: show only the conversations "
                + "whose title contains this."));
        props.add("limit", param("number", "claudeCodeView listHistory: how many to show, 1 to 100; "
                + "30 when left out. readTab: how many of the last messages and replies, 1 to 200; 20 "
                + "when left out."));
        props.add("files", ToolSupport.strings("claudeCodeView sendPrompt/newTab: full paths of files to "
                + "attach to the prompt."));
        props.add("messageId", param("string", "claudeCodeView forkMessage/rewindCode/deleteMessage: a "
                + "message of the user's, as 'readTab' shows it."));
        props.add("replyId", param("string", "claudeCodeView setBookmark: a reply of Claude's, as "
                + "'readTab' shows it."));
        props.add("restoreCode", param("boolean", "claudeCodeView forkMessage: true also puts the files "
                + "back as they were at that message."));
        props.add("preview", param("boolean", "claudeCodeView rewindCode: true only lists the files that "
                + "would change."));
        props.add("archived", param("boolean", "claudeCodeView archiveSession: true archives the "
                + "conversation, false takes it back out of the archive."));
        props.add("title", param("string", "claudeCodeView renameSession: the conversation's new name, "
                + "on one line."));
        props.add("file", param("string", "claudeCodeView exportConversation: the full path of a file "
                + "to write the conversation to; it must not exist yet. Left out, the text comes back "
                + "in the result."));
        props.add("prompt", param("string", "claudeCodeView sendPrompt/newTab: the message to send. "
                + "A slash command goes through 'runCommand' instead."));
        props.add("command", param("string", "claudeCodeView runCommand: the slash command, with "
                + "anything it takes after it, e.g. '/compact' or '/model opus'. 'listTabs' shows the "
                + "view's own."));
        props.add("model", param("string", "claudeCodeView newTab/configureTab: a model id from "
                + "'listTabs' (e.g. 'opus'), a full id such as 'claude-opus-5-5', or 'default'."));
        props.add("effort", param("string", "claudeCodeView newTab/configureTab: low, medium, high, "
                + "xhigh or max."));
        props.add("thinking", param("boolean", "claudeCodeView newTab/configureTab: extended thinking "
                + "on or off."));
        props.add("mode", param("string", "claudeCodeView newTab/configureTab: the permission mode — "
                + "default (ask before edits), acceptEdits, plan, auto or bypassPermissions; "
                + "'listTabs' shows which are available here."));
        props.add("enabled", param("boolean", "claudeCodeView remoteControl: true switches Remote "
                + "Control on for the tab, false switches it off. setBookmark: false takes the "
                + "bookmark away."));
        props.add("remoteControl", param("boolean", "claudeCodeView newTab: true opens the tab with "
                + "Remote Control on. A 'prompt' given with it is sent once it has connected."));
    }

    @Override
    public McpToolResult run(String action, JsonObject params) {
        if (!ACTIONS.contains(action)) {
            return McpToolResult.error("Unknown action: " + action + ". Use one of: "
                    + String.join(", ", ACTIONS) + ".");
        }
        if (viewOff.getAsBoolean()) {
            return McpToolResult.error("The Claude Code view is switched off in this Eclipse: the "
                    + "\"Exclusively use terminal\" preference is on, so there are no tabs to act on.");
        }
        if ("listHistory".equals(action)) return listHistory(params);

        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        for (String key : PARAMS) {
            if (params.has(key) && !params.get(key).isJsonNull()) request.add(key, params.get(key));
        }
        Path exportTo = null;
        try {
            if (ON_A_SESSION.contains(action)) {
                String refusal = describeSession(params, request);
                if (refusal != null) return McpToolResult.error(refusal);
            }
            if ("renameSession".equals(action)) nameRename(params, request);
            if ("answerQuestion".equals(action)) checkAnswers(params);
            if ("answerAdvisor".equals(action)) checkAdvisor(params);
            if (params.has("files") && !params.get("files").isJsonNull()) request.add("files", filesOf(params));
            if ("exportConversation".equals(action)) exportTo = exportFileOf(params);
        } catch (IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage());
        }

        try {
            JsonObject out = answer(request.toString());
            // An answer the page has to wait for itself comes as a token to ask again under.
            if (out != null && ok(out) && out.has("wait")) out = collect(out.get("wait").getAsString());
            if (out == null) {
                return McpToolResult.error("The Claude Code view is not open. Open it with eclipseShowView "
                        + "(viewId '" + ClaudeGuiView.VIEW_ID + "') and try again.");
            }
            if (!ok(out)) {
                return McpToolResult.error(out.has("error") ? out.get("error").getAsString()
                        : "The Claude Code view could not carry out '" + action + "'.");
            }
            out.remove("ok");
            if ("exportConversation".equals(action)) deliverExport(out, exportTo);
            return McpToolResult.success(out);
        } catch (IOException e) {
            return McpToolResult.error("The conversation could not be written to " + exportTo + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpToolResult.error("'" + action + "' was interrupted while waiting for Claude Code's answer.");
        } catch (RuntimeException e) {
            return McpToolResult.error("The Claude Code view gave an unreadable answer to '" + action
                    + "': " + e.getClass().getSimpleName());
        }
    }

    /** The page's answer to a request, null when there is no page to ask. */
    private JsonObject answer(String requestJson) {
        String reply = page.apply(requestJson);
        return reply == null ? null : JsonParser.parseString(reply).getAsJsonObject();
    }

    private static boolean ok(JsonObject out) {
        return out.has("ok") && out.get("ok").getAsBoolean();
    }

    /**
     * Asks the page for an answer it was still waiting for, until it has it. The page answers
     * on the UI thread and cannot wait there, so the waiting is done here, between asks.
     */
    private JsonObject collect(String token) throws InterruptedException {
        JsonObject ask = new JsonObject();
        ask.addProperty("action", "collect");
        ask.addProperty("token", token);
        long until = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            JsonObject out = answer(ask.toString());
            if (out == null || !ok(out)) return out;
            if (out.has("done") && out.get("done").getAsBoolean()) {
                out.remove("done");
                return out;
            }
            if (System.currentTimeMillis() > until) {
                JsonObject late = new JsonObject();
                late.addProperty("ok", false);
                late.addProperty("error", "Claude Code did not answer in time.");
                return late;
            }
            Thread.sleep(POLL_MS);
        }
    }

    /** A 'renameSession' call's new name, and the one folder it can be carried out in. */
    private void nameRename(JsonObject params, JsonObject request) {
        String title = ClaudeCodeEclipseTool.str(params, "title");
        if (title == null) throw new IllegalArgumentException("'title' is required: the conversation's new name.");
        // The view renames a conversation of the folder in front (ClaudeGuiView#renameSessionFile).
        if (!Paths.get(request.get("folder").getAsString()).equals(Paths.get(history.frontFolder()).normalize())) {
            throw new IllegalArgumentException("A conversation is renamed under the folder tab in front, and "
                    + "that one belongs to another folder. The user brings its folder tab to the front first.");
        }
        request.addProperty("newTitle", title);
    }

    /** An 'answerQuestion' call's answers: a list with a string for each question. Which
     *  question each answers, and whether it fits it, the page says. */
    private static void checkAnswers(JsonObject params) {
        JsonElement answers = params.get("answers");
        boolean strings = answers != null && answers.isJsonArray() && answers.getAsJsonArray().size() > 0;
        if (strings) {
            for (JsonElement e : answers.getAsJsonArray()) {
                strings &= e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
            }
        }
        if (!strings) {
            throw new IllegalArgumentException("'answers' is required: a list with one answer per question, "
                    + "in order. 'readTab' shows the questions.");
        }
    }

    /** An 'answerAdvisor' call's choice: a label in words. Whether the card offers it, the page says. */
    private static void checkAdvisor(JsonObject params) {
        JsonElement advisor = params.get("advisor");
        boolean words = advisor != null && advisor.isJsonPrimitive() && advisor.getAsJsonPrimitive().isString()
                && !advisor.getAsString().isBlank();
        if (!words) {
            throw new IllegalArgumentException("'advisor' is required: one of the choices on the advisor's card, "
                    + "by its label, or 'No advisor'. 'readTab' shows them.");
        }
    }

    /** The files a call attaches, each checked to be one, as full paths. */
    private static JsonArray filesOf(JsonObject params) {
        if (!params.get("files").isJsonArray()) {
            throw new IllegalArgumentException("'files' must be a list of file paths.");
        }
        JsonArray out = new JsonArray();
        for (JsonElement e : params.getAsJsonArray("files")) {
            String named = e.isJsonPrimitive() ? e.getAsString().trim() : "";
            Path path = null;
            try {
                path = Paths.get(named);
            } catch (InvalidPathException bad) {
                // refused below
            }
            if (path == null || !path.isAbsolute()) {
                throw new IllegalArgumentException("'files' takes full paths; '" + named + "' is not one.");
            }
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("There is no file at " + path + ".");
            out.add(path.normalize().toString());
        }
        return out;
    }

    /** Where an 'exportConversation' call wants the text written, null for in the result. */
    private static Path exportFileOf(JsonObject params) {
        String named = ClaudeCodeEclipseTool.str(params, "file");
        if (named == null) return null;
        Path path = null;
        try {
            path = Paths.get(named);
        } catch (InvalidPathException bad) {
            // refused below
        }
        if (path == null || !path.isAbsolute()) throw new IllegalArgumentException("'file' must be a full path.");
        path = path.normalize();
        if (Files.exists(path)) throw new IllegalArgumentException("There is already a file at " + path + ".");
        if (path.getParent() == null || !Files.isDirectory(path.getParent())) {
            throw new IllegalArgumentException("There is no folder " + path.getParent() + " to write the file in.");
        }
        return path;
    }

    /** Puts an exported conversation where the call wanted it: in the file, or in the result
     *  as far as a result should carry it. */
    private static void deliverExport(JsonObject out, Path file) throws IOException {
        String text = out.has("text") ? out.get("text").getAsString() : "";
        out.addProperty("characters", text.length());
        if (file != null) {
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            out.remove("text");
            out.addProperty("file", file.toString());
        } else if (text.length() > MAX_EXPORT_CHARS) {
            out.addProperty("text", text.substring(0, MAX_EXPORT_CHARS));
            out.addProperty("truncated", true);
            JsonArray notes = new JsonArray();
            notes.add("Only the first " + MAX_EXPORT_CHARS + " characters are here. Pass 'file' to have "
                    + "the whole conversation written to a file.");
            out.add("notes", notes);
        }
    }

    /** What the history panel lists, for the folder in front or the one named. Answered here,
     *  not by the page. */
    private McpToolResult listHistory(JsonObject params) {
        String folder;
        int limit;
        try {
            folder = folderOf(params);
            limit = limitOf(params);
        } catch (IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage());
        }
        String query = ClaudeCodeEclipseTool.str(params, "query");
        String wanted = query == null ? null : query.toLowerCase(Locale.ROOT);

        JsonArray all = sessions(folder);
        JsonArray shown = new JsonArray();
        int total = 0;
        for (JsonElement e : all) {
            JsonObject session = e.getAsJsonObject();
            String title = title(text(session, "display"));
            if (wanted != null && !title.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            total++;
            if (shown.size() >= limit) continue;
            JsonObject one = new JsonObject();
            one.addProperty("sessionId", text(session, "sessionId"));
            one.addProperty("title", title);
            one.addProperty("lastActive", text(session, "timestamp"));
            shown.add(one);
        }
        JsonObject out = new JsonObject();
        out.addProperty("folder", folder);
        out.addProperty("total", total);
        out.add("sessions", shown);
        if (all.size() >= MOST_LISTED) {
            JsonArray notes = new JsonArray();
            notes.add("Only a folder's " + MOST_LISTED + " most recent conversations are listed. An older "
                    + "one still opens by its id.");
            out.add("notes", notes);
        }
        return McpToolResult.success(out);
    }

    /**
     * Checks a call on a saved conversation and gives the page what it needs to carry it out:
     * the folder spelled out, and the conversation's title.
     *
     * @return why it cannot be opened, or null when it can
     */
    private String describeSession(JsonObject params, JsonObject request) {
        String id = ClaudeCodeEclipseTool.str(params, "sessionId");
        if (id == null) return "'sessionId' is required. Use action 'listHistory'.";
        if (!SESSION_ID.matcher(id).matches()) {
            return "'" + id + "' is not a conversation id. Use action 'listHistory'.";
        }
        String folder;
        try {
            folder = folderOf(params);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String title = null;
        for (JsonElement e : sessions(folder)) {
            JsonObject session = e.getAsJsonObject();
            if (id.equals(text(session, "sessionId"))) {
                title = title(text(session, "display"));
                break;
            }
        }
        // Being listed is proof enough. Its file is looked for only when it is not: the list
        // ends at a folder's most recent conversations, and an older one is still there.
        if (title == null && !history.has(folder, id)) {
            return "There is no saved conversation '" + id + "' in " + folder + ". Use action 'listHistory'.";
        }
        request.addProperty("sessionId", id);
        request.addProperty("folder", folder);
        // No title of its own leaves the tab its usual name rather than "(untitled)".
        request.addProperty("title", title == null || UNTITLED.equals(title) ? "" : title);
        return null;
    }

    /** A conversation's title as the history panel shows it. */
    static String title(String display) {
        String t = display == null ? "" : display;
        t = IDE_BLOCK.matcher(t).replaceAll("");
        t = IDE_EMPTY.matcher(t).replaceAll("");
        t = META_BLOCK.matcher(t).replaceAll("");
        t = COMMAND_NAME.matcher(t).replaceAll("$1");   // the command itself stays, e.g. /usage
        t = t.strip();
        return t.isEmpty() ? UNTITLED : t;
    }

    /** The folder a call is about: the one it names, as a full path, or the one in front. */
    private String folderOf(JsonObject params) {
        String named = ClaudeCodeEclipseTool.str(params, "folder");
        if (named == null) return history.frontFolder();
        try {
            Path path = Paths.get(named);
            // Anything else would be read against Eclipse's own working directory.
            if (path.isAbsolute()) return path.normalize().toString();
        } catch (InvalidPathException e) {
            // refused below
        }
        throw new IllegalArgumentException("'folder' must be the full path of a folder, such as a 'root' "
                + "that 'listTabs' shows.");
    }

    private static int limitOf(JsonObject params) {
        if (!params.has("limit") || params.get("limit").isJsonNull()) return LISTED;
        try {
            int limit = params.get("limit").getAsInt();
            if (limit >= 1 && limit <= MOST_LISTED) return limit;
        } catch (RuntimeException e) {
            // refused below
        }
        throw new IllegalArgumentException("'limit' must be a number from 1 to " + MOST_LISTED + ".");
    }

    /** A folder's conversations, as objects; none when the list cannot be read. */
    private JsonArray sessions(String folder) {
        JsonArray out = new JsonArray();
        try {
            for (JsonElement e : JsonParser.parseString(history.list(folder)).getAsJsonArray()) {
                if (e.isJsonObject()) out.add(e);
            }
        } catch (RuntimeException e) {
            // an unreadable list is an empty one
        }
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static String askView(String requestJson) {
        ClaudeCodeView.debug("[claudeCodeEclipse]claudeCodeView request " + requestJson);
        String reply = UiHelper.syncCall(() -> ClaudeGuiView.pageTool(requestJson));
        ClaudeCodeView.debug("[claudeCodeEclipse]claudeCodeView reply " + reply);
        return reply;
    }

    private static JsonObject param(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }
}
