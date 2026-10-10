package com.anthropic.claudecode.eclipse.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the list the Rewind dialog offers: the messages the user typed, and which of
 * them come from before the conversation's last compaction.
 */
class RewindServiceTest {

    private static final String BOUNDARY =
            "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"uuid\":\"b\",\"parentUuid\":null,"
            + "\"compactMetadata\":{\"trigger\":\"manual\",\"preTokens\":900000,\"postTokens\":13000}}";
    private static final String SUMMARY =
            "{\"type\":\"user\",\"uuid\":\"s\",\"isCompactSummary\":true,\"isVisibleInTranscriptOnly\":true,"
            + "\"message\":{\"role\":\"user\",\"content\":\"This session is being continued from a previous conversation.\"}}";

    private static String typed(String id, String text) {
        return "{\"type\":\"user\",\"uuid\":\"" + id + "\",\"message\":{\"role\":\"user\",\"content\":\"" + text
                + "\"},\"timestamp\":\"2026-10-07T01:00:00.000Z\"}";
    }

    private static String reply(String id) {
        return "{\"type\":\"assistant\",\"uuid\":\"" + id + "\",\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"an answer\"}]}}";
    }

    private static List<JsonObject> lines(String... json) {
        List<JsonObject> out = new ArrayList<>();
        for (String line : json) out.add(JsonParser.parseString(line).getAsJsonObject());
        return out;
    }

    /** The list as "id" or "id*", a star on each message marked as from before the compaction. */
    private static List<String> listed(String... json) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : RewindService.listOf(lines(json))) {
            JsonObject m = e.getAsJsonObject();
            boolean before = m.has("beforeCompaction") && m.get("beforeCompaction").getAsBoolean();
            out.add(m.get("id").getAsString() + (before ? "*" : ""));
        }
        return out;
    }

    @Test
    void messagesFromBeforeTheCompactionAreMarkedAndTheOnesAfterItAreNot() {
        assertEquals(List.of("u1*", "u2*", "u3", "u4"),
                listed(typed("u1", "first"), reply("a1"), typed("u2", "second"), reply("a2"),
                        BOUNDARY, SUMMARY, typed("u3", "/compact"), typed("u4", "third"), reply("a4")));
    }

    @Test
    void compactedTwiceEverythingBeforeTheLastCompactionIsMarked() {
        assertEquals(List.of("u1*", "u2*", "u3*", "u4"),
                listed(typed("u1", "first"), BOUNDARY, SUMMARY, typed("u2", "/compact"), typed("u3", "second"),
                        BOUNDARY, SUMMARY, typed("u4", "/compact")));
    }

    @Test
    void aConversationNeverCompactedIsListedExactlyAsBefore() {
        JsonArray list = RewindService.listOf(lines(typed("u1", "first"), reply("a1"), typed("u2", "second")));

        assertEquals("[{\"id\":\"u1\",\"text\":\"first\",\"ts\":\"2026-10-07T01:00:00.000Z\"},"
                + "{\"id\":\"u2\",\"text\":\"second\",\"ts\":\"2026-10-07T01:00:00.000Z\"}]", list.toString());
    }

    @Test
    void aCompactionWithNothingTypedBeforeItMarksNothing() {
        assertEquals(List.of("u1", "u2"), listed(BOUNDARY, SUMMARY, typed("u1", "/compact"), typed("u2", "first")));
    }

    @Test
    void aSubagentsCompactionIsNotTheConversations() {
        String theirs = BOUNDARY.replace("\"type\":\"system\"", "\"type\":\"system\",\"isSidechain\":true");

        assertEquals(List.of("u1", "u2"), listed(typed("u1", "first"), theirs, typed("u2", "second")));
    }

    @Test
    void theSummaryACompactionLeavesIsNotAMessageToRewindTo() {
        JsonArray list = RewindService.listOf(lines(typed("u1", "first"), BOUNDARY, SUMMARY, typed("u2", "second")));

        assertEquals(2, list.size());
        for (JsonElement e : list) assertFalse("s".equals(e.getAsJsonObject().get("id").getAsString()));
    }

    // ── A message sent while Claude was working ──────────────────────────────

    /** The attachment the CLI writes when it hands such a message over. */
    private static String queued(String lineId, Object prompt, String mode) {
        String p = prompt instanceof String ? "\"" + prompt + "\"" : prompt.toString();
        return "{\"type\":\"attachment\",\"uuid\":\"" + lineId + "\",\"timestamp\":\"2026-10-10T07:10:48.000Z\","
                + "\"attachment\":{\"type\":\"queued_command\",\"prompt\":" + p + ",\"source_uuid\":\"src\","
                + "\"commandMode\":\"" + mode + "\",\"timestamp\":\"2026-10-10T07:10:38.563Z\"}}";
    }

    private static JsonObject line(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aQueuedMessageIsTheAttachmentLineAndItsIdIsThatLinesOwn() {
        JsonObject m = RewindService.queuedUserMessage(line(queued("att-1", "wait", "prompt")));

        assertNotNull(m);
        assertEquals("att-1", m.get("id").getAsString());   // not the attachment's source_uuid
        assertEquals("wait", m.get("text").getAsString());
        assertEquals("2026-10-10T07:10:38.563Z", m.get("ts").getAsString());   // when it was sent
    }

    @Test
    void aQueuedMessageSentWithTheEditorsContextKeepsItsTextBlocks() {
        String blocks = "[{\"type\":\"text\",\"text\":\"<ide_opened_file>a.java</ide_opened_file>\"},"
                + "{\"type\":\"text\",\"text\":\"the words\"}]";
        JsonObject m = RewindService.queuedUserMessage(
                line(queued("att-1", JsonParser.parseString(blocks).getAsJsonArray(), "prompt")));

        assertNotNull(m);
        assertTrue(m.get("text").getAsString().endsWith("the words"));
    }

    @Test
    void aBackgroundTasksNoticeIsNotAMessageAndNeitherIsAnythingThatIsNotAQueuedCommand() {
        assertNull(RewindService.queuedUserMessage(line(queued("att-1", "<task-notification/>", "task-notification"))));
        assertNull(RewindService.queuedUserMessage(line(typed("u1", "hello"))));
        assertNull(RewindService.queuedUserMessage(line(reply("a1"))));
    }

    @Test
    void aForkCutsAtTheQueuedMessagesAttachmentAndOnlyThere() {
        assertTrue(RewindService.isCutPoint(line(queued("att-1", "wait", "prompt")), "att-1"));
        assertFalse(RewindService.isCutPoint(line(queued("att-1", "wait", "prompt")), "att-2"));
        // Another kind of attachment with the same uuid is not a message to cut at.
        assertFalse(RewindService.isCutPoint(line(queued("att-1", "<task-notification/>", "task-notification")), "att-1"));
    }

    @Test
    void aQueuedMessageIsNotInTheRewindList() {
        // The list names user lines, which a message that also has one is already among.
        assertEquals(List.of("u1", "u2"),
                listed(typed("u1", "first"), queued("att-1", "wait", "prompt"), typed("u2", "second")));
    }
}
